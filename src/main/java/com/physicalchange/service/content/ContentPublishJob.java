package com.physicalchange.service.content;

import com.physicalchange.domain.*;
import com.physicalchange.repository.*;
import com.physicalchange.service.NotificationService;
import com.physicalchange.service.social.SocialConnector;
import com.physicalchange.service.social.SocialConnector.ConnectorException;
import com.physicalchange.service.social.SocialConnectorRegistry;
import com.physicalchange.service.social.SocialNetwork;
import com.physicalchange.service.social.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Publicación programada (ADR-014 §6). Cada minuto (con CONTENT_SCHEDULER_ENABLED) y al consultar Inicio, calendario o el
 * contador de avisos (como mucho una vez cada 30 s por instancia, porque Render gratuito se duerme):
 * 1. publicaciones SCHEDULED con la hora pasada → claim con UPDATE condicional (SCHEDULED → DUE) en su propia transacción;
 * 2. en esa misma transacción, las redes sin publicación automática pasan a MANUAL_DUE y se crea UN aviso CONTENT_POST_DUE:
 *    solo el ganador del claim llega aquí, así que el aviso no se repite aunque haya varias instancias o reintentos;
 * 3. las redes automáticas (Fase 8.2) se toman con otro claim (PENDING → PUBLISHING), se llama al conector fuera de transacción
 *    y se guarda PUBLISHED o FAILED. Como mucho una vez: nunca se reintenta solo; PUBLISHING > 15 min → FAILED "estado desconocido".
 */
@Component
public class ContentPublishJob {
    private static final Logger log = LoggerFactory.getLogger(ContentPublishJob.class);
    static final int BATCH = 50;
    static final Duration STALE_PUBLISHING = Duration.ofMinutes(15);
    static final String UNKNOWN = "Estado desconocido: comprueba en la red si se publicó";

    private final ContentPostRepository posts;
    private final ContentPostTargetRepository targets;
    private final SocialAccountRepository accountRepo;
    private final SocialAccountService accounts;
    private final SocialConnectorRegistry registry;
    private final ContentPostService postService;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final boolean enabled, processOnRead;
    private final AtomicLong lastRun = new AtomicLong();
    private final Object running = new Object();

    public ContentPublishJob(ContentPostRepository p, ContentPostTargetRepository t, SocialAccountRepository ar, SocialAccountService a,
                             SocialConnectorRegistry r, ContentPostService ps, NotificationService n, PlatformTransactionManager tm,
                             @Value("${app.content.scheduler-enabled:true}") boolean enabled,
                             @Value("${app.content.process-on-read:true}") boolean processOnRead) {
        posts = p; targets = t; accountRepo = ar; accounts = a; registry = r; postService = ps; notifications = n;
        tx = new TransactionTemplate(tm);
        this.enabled = enabled; this.processOnRead = processOnRead;
    }

    @Scheduled(fixedDelayString = "${app.content.publish-interval-ms:60000}", initialDelayString = "${app.content.publish-initial-delay-ms:30000}")
    public void scheduled() { if (enabled) runSafely(); }

    /** Métricas de las cuentas conectadas cada hora (en la Fase 8.1 ningún conector las da). */
    @Scheduled(fixedDelayString = "${app.content.sync-interval-ms:3600000}", initialDelayString = "${app.content.sync-initial-delay-ms:300000}")
    public void hourlySync() {
        if (!enabled) return;
        for (SocialAccount a : accountRepo.findByStatus("CONNECTED")) {
            SocialNetwork n = SocialNetwork.valueOf(a.getNetwork());
            if (!registry.capabilities(n).metrics()) continue;
            try { accounts.syncAccount(a.getId()); }
            catch (RuntimeException e) { log.warn("Metrics sync failed for account {} ({})", a.getId(), e.getClass().getSimpleName()); }
        }
    }

    /** Al consultar: como mucho una vez cada 30 s por instancia. */
    public void touch() {
        if (!processOnRead) return;
        long now = System.currentTimeMillis(), last = lastRun.get();
        if (now - last < 30_000 || !lastRun.compareAndSet(last, now)) return;
        runSafely();
    }

    private void runSafely() {
        try { runOnce(Instant.now()); }
        catch (RuntimeException e) { log.error("Content publish job failed ({})", e.getClass().getSimpleName(), e); }
    }

    /** Una pasada completa. Devuelve cuántas publicaciones ha tomado esta llamada. */
    public int runOnce(Instant now) {
        synchronized (running) {
            failStalePublishing(now);
            int claimed = 0;
            for (Long id : posts.dueIds(now, PageRequest.of(0, BATCH))) {
                try { if (process(id, now)) claimed++; }
                catch (RuntimeException e) { log.warn("Content post {} could not be processed ({})", id, e.getClass().getSimpleName()); }
            }
            return claimed;
        }
    }

    /** Procesa una publicación si gana el claim. false si otra instancia (o el usuario) ya la tomó. */
    public boolean process(Long postId, Instant now) {
        List<Long> automatic = tx.execute(st -> {
            if (posts.claimDue(postId, now) == 0) return null;
            ContentPost p = posts.findById(postId).orElseThrow();
            Map<String, SocialAccount> acc = accountRepo.findByOwnerId(p.getOwnerId()).stream()
                    .collect(Collectors.toMap(SocialAccount::getNetwork, a -> a));
            List<String> manual = new ArrayList<>();
            List<Long> auto = new ArrayList<>();
            for (ContentPostTarget t : p.getTargets()) {
                if (!"PENDING".equals(t.getStatus())) continue;
                SocialNetwork n = SocialNetwork.valueOf(t.getNetwork());
                SocialAccount a = acc.get(n.name());
                boolean canPublish = registry.publishes(n, p.getFormat()) && a != null && "CONNECTED".equals(a.getStatus());
                if (canPublish) auto.add(t.getId());
                else { t.setStatus("MANUAL_DUE"); manual.add(n.label()); }
            }
            if (!manual.isEmpty()) notifyDue(p, manual);
            postService.recompute(p);
            return auto;
        });
        if (automatic == null) return false;
        for (Long targetId : automatic) publishTarget(postId, targetId, now);
        return true;
    }

    private void notifyDue(ContentPost p, List<String> networks) {
        String nets = networks.size() == 1 ? networks.get(0)
                : String.join(", ", networks.subList(0, networks.size() - 1)) + " y " + networks.get(networks.size() - 1);
        notifications.notify(p.getOwnerId(), NotificationService.CONTENT_POST_DUE, "Toca publicar: «" + p.getTitle() + "» en " + nets,
                "Publícalo y márcalo como publicado en el calendario.", "CONTENT_POST", p.getId());
    }

    /** Fase 8.2: claim de la red, llamada al conector fuera de transacción y resultado en otra transacción. */
    private void publishTarget(Long postId, Long targetId, Instant now) {
        Object[] input = tx.execute(st -> {
            if (targets.claimPublishing(targetId, now) == 0) return null;
            ContentPostTarget t = targets.findById(targetId).orElseThrow();
            ContentPost p = t.getPost();
            SocialAccount a = accountRepo.findByOwnerIdAndNetwork(p.getOwnerId(), t.getNetwork()).orElse(null);
            SocialConnector.PublishRequest req = new SocialConnector.PublishRequest(t.getId(), p.getTitle(), p.getBody(),
                    ContentSupport.split(p.getHashtags()), p.getFormat(), p.getMediaFileId(), p.getMediaUrl(), null);
            return new Object[]{a, req};
        });
        if (input == null) return;
        SocialAccount account = (SocialAccount) input[0];
        SocialConnector.PublishRequest req = (SocialConnector.PublishRequest) input[1];
        SocialConnector.PublishResult result = null;
        ConnectorException failure = null;
        boolean lostKey = false;
        try {
            if (account == null) throw new ConnectorException(ConnectorException.Kind.EXPIRED, "Sin cuenta");
            result = registry.get(SocialNetwork.valueOf(account.getNetwork())).publish(accounts.tokens(account), req);
        } catch (ConnectorException e) {
            failure = e;
        } catch (TokenCipher.DecryptException e) {
            lostKey = true;
        } catch (RuntimeException e) {
            failure = new ConnectorException(ConnectorException.Kind.TRANSIENT, "Error inesperado");
        }
        SocialConnector.PublishResult ok = result;
        ConnectorException ko = failure;
        boolean expiredKey = lostKey;
        tx.executeWithoutResult(st -> {
            ContentPostTarget t = targets.findById(targetId).orElseThrow();
            if (!"PUBLISHING".equals(t.getStatus())) return;   // cancelada mientras tanto
            ContentPost p = t.getPost();
            SocialAccount a = account == null ? null : accountRepo.findById(account.getId()).orElse(null);
            if (ok != null) {
                t.setStatus("PUBLISHED");
                t.setPublishedAt(Instant.now());
                t.setExternalId(ContentSupport.cut(ok.externalId(), 200));
                String url = ok.externalUrl();
                t.setExternalUrl(url != null && url.startsWith("https://") ? ContentSupport.cut(url, 500) : null);
                if (a != null) { a.setErrorCount(0); a.setLastError(null); }
            } else if (expiredKey || (ko != null && ko.kind() == ConnectorException.Kind.EXPIRED)) {
                if (a != null) accounts.attention(a, "EXPIRED", "La conexión ha caducado: vuelve a conectar la cuenta");
                t.setStatus("MANUAL_DUE");
                notifyDue(p, List.of(SocialNetwork.valueOf(t.getNetwork()).label()));
            } else {
                t.setStatus("FAILED");
                t.setError(ko.kind() == ConnectorException.Kind.REJECTED ? "La red ha rechazado la publicación" : "La red no ha respondido; inténtalo a mano");
                if (a != null) accounts.failure(a, ko);
            }
            postService.recompute(p);
        });
    }

    private void failStalePublishing(Instant now) {
        for (Long id : targets.stalePublishing(now.minus(STALE_PUBLISHING))) {
            tx.executeWithoutResult(st -> {
                if (targets.failStale(id, UNKNOWN) == 0) return;
                targets.findById(id).ifPresent(t -> postService.recompute(t.getPost()));
            });
        }
    }
}
