package com.physicalchange.service.content;

import com.physicalchange.domain.*;
import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.service.NotificationService;
import com.physicalchange.service.social.*;
import com.physicalchange.service.social.SocialConnector.ConnectorException;
import com.physicalchange.service.social.SocialConnector.TokenSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Cuentas sociales y OAuth genérico (ADR-014 §3-§5, docs/api-contenido.md §1).
 * - state: 32 bytes aleatorios; solo se guarda su SHA-256; un uso, 10 minutos, atado al usuario y a la red.
 * - PKCE S256 cuando el conector lo declara; el code_verifier va cifrado y se borra al consumir el state.
 * - Redirect URI fija desde configuración (PUBLIC_APP_URL), nunca de la petición.
 * - Las llamadas a la red van fuera de transacción. Los tokens nunca se escriben en logs, DTOs ni last_error.
 */
@Service
public class SocialAccountService {
    private static final Logger log = LoggerFactory.getLogger(SocialAccountService.class);
    static final Duration STATE_TTL = Duration.ofMinutes(10);
    static final Duration SYNC_STALE = Duration.ofMinutes(30);
    static final int ERRORS_TO_ERROR = 3;
    static final String INVALID_STATE = "La conexión ha caducado o no es válida; vuelve a intentarlo";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SocialAccountRepository accounts;
    private final SocialOauthStateRepository states;
    private final ContentPostTargetRepository targets;
    private final ContentPostMetricRepository metrics;
    private final SocialConnectorRegistry registry;
    private final TokenCipher cipher;
    private final NotificationService notifications;
    private final ContentSupport support;
    private final TransactionTemplate tx;
    private final String redirectUri;

    public SocialAccountService(SocialAccountRepository a, SocialOauthStateRepository s, ContentPostTargetRepository t,
                                ContentPostMetricRepository m, SocialConnectorRegistry r, TokenCipher c, NotificationService n,
                                ContentSupport cs, PlatformTransactionManager tm, @Value("${app.public-url}") String publicUrl) {
        accounts = a; states = s; targets = t; metrics = m; registry = r; cipher = c; notifications = n; support = cs;
        tx = new TransactionTemplate(tm);
        redirectUri = publicUrl.replaceAll("/+$", "") + "/marketing/contenido/cuentas/oauth";
    }

    // ---------- Lectura ----------

    @Transactional(readOnly = true)
    public List<SocialAccountDto> list() {
        Map<String, SocialAccount> mine = byNetwork(support.workspace());
        return Arrays.stream(SocialNetwork.values()).map(n -> dto(n, mine.get(n.name()))).toList();
    }

    /** Estado efectivo de las 8 redes de un espacio (NOT_CONFIGURED prevalece sobre lo guardado). */
    @Transactional(readOnly = true)
    public Map<SocialNetwork, String> statuses(Long owner) {
        Map<String, SocialAccount> mine = byNetwork(owner);
        Map<SocialNetwork, String> out = new EnumMap<>(SocialNetwork.class);
        for (SocialNetwork n : SocialNetwork.values()) out.put(n, status(n, mine.get(n.name())));
        return out;
    }

    public String status(SocialNetwork n, SocialAccount a) {
        if (!registry.configured(n)) return "NOT_CONFIGURED";
        return a == null ? "DISCONNECTED" : a.getStatus();
    }

    // ---------- OAuth ----------

    @Transactional
    public ConnectResponse connect(String network) {
        SocialNetwork n = SocialNetwork.fromPath(network);
        Long me = support.me(), owner = support.workspace();
        if (!registry.configured(n)) throw new ConflictException("Esta red todavía no está disponible");
        if (accounts.existsByOwnerIdAndNetwork(owner, n.name())) throw new ConflictException("Ya hay una cuenta vinculada: desconéctala antes");
        Instant now = Instant.now();
        states.deleteExpired(now);
        SocialConnector c = registry.get(n);
        String state = random();
        String hash = sha256Hex(state);
        String verifier = c.capabilities().pkce() ? random() : null;
        String challenge = verifier == null ? null : b64(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        Instant expires = now.plus(STATE_TTL);
        states.save(new SocialOauthState(hash, me, owner, n.name(), cipher.encrypt(verifier, TokenCipher.stateAad(hash)), expires));
        return new ConnectResponse(c.authorizationUrl(state, challenge, redirectUri), ContentSupport.odt(expires));
    }

    private record Consumed(Long ownerId, String hash, String verifierEnc) {}

    /** El state se consume siempre que exista (también si caducó, es de otro o llega un error); cualquier fallo responde lo mismo. */
    public SocialAccountDto callback(OAuthCallbackRequest r) {
        SocialNetwork n = SocialNetwork.fromPath(r.network());
        Long me = support.me();
        support.workspace();
        String hash = sha256Hex(r.state());
        Instant now = Instant.now();
        Consumed c = tx.execute(st -> {
            SocialOauthState s = states.findByStateHash(hash).orElse(null);
            if (s == null) return null;
            boolean fresh = s.getUsedAt() == null && s.getExpiresAt().isAfter(now);
            boolean mine = s.getUserId().equals(me) && s.getNetwork().equals(n.name());
            String verifierEnc = s.getCodeVerifierEnc();
            Long owner = s.getOwnerId();
            int won = states.consume(s.getId(), now);
            return won == 1 && fresh && mine ? new Consumed(owner, hash, verifierEnc) : null;
        });
        if (c == null) throw new BadRequestException(INVALID_STATE);
        if (r.error() != null && !r.error().isBlank()) throw new BadRequestException("Conexión cancelada");
        if (r.code() == null || r.code().isBlank()) throw new BadRequestException(INVALID_STATE);
        if (!registry.configured(n)) throw new ConflictException("Esta red todavía no está disponible");
        String verifier;
        try { verifier = cipher.decrypt(c.verifierEnc(), TokenCipher.stateAad(c.hash())); }
        catch (TokenCipher.DecryptException e) { throw new BadRequestException(INVALID_STATE); }

        TokenSet tokens;
        try {
            tokens = registry.get(n).exchangeCode(r.code(), verifier, redirectUri);
        } catch (RuntimeException e) {
            log.warn("OAuth code exchange rejected by {} ({})", n, e.getClass().getSimpleName());   // sin code ni tokens
            throw new BadGatewayException("La red no ha aceptado la conexión");
        }
        if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank())
            throw new BadGatewayException("La red no ha aceptado la conexión");
        return tx.execute(st -> {
            if (accounts.existsByOwnerIdAndNetwork(c.ownerId(), n.name())) throw new ConflictException("Ya hay una cuenta vinculada: desconéctala antes");
            SocialAccount a = new SocialAccount(c.ownerId(), n.name(), me);
            store(a, tokens);
            return dto(n, accounts.save(a));
        });
    }

    // ---------- Gestión ----------

    @Transactional
    public SocialAccountDto disable(String network) {
        SocialNetwork n = SocialNetwork.fromPath(network);
        SocialAccount a = owned(n);
        String s = status(n, a);
        if (!Set.of("CONNECTED", "ERROR", "EXPIRED").contains(s)) throw new ConflictException("Solo se puede desactivar una cuenta vinculada");
        a.setStatus("DISABLED");
        return dto(n, a);
    }

    public SocialAccountDto enable(String network) {
        SocialNetwork n = SocialNetwork.fromPath(network);
        Long owner = support.workspace();
        SocialAccount a = tx.execute(st -> owned(n));
        if (!"DISABLED".equals(status(n, a))) throw new ConflictException("La cuenta no está desactivada");
        boolean expired = a.getTokenExpiresAt() != null && !a.getTokenExpiresAt().isAfter(Instant.now());
        TokenSet refreshed = null;
        boolean ok = !expired;
        if (expired && a.getRefreshTokenEnc() != null) {
            try { refreshed = registry.get(n).refresh(tokens(a)); ok = refreshed != null; }
            catch (RuntimeException e) { ok = false; }
        }
        boolean valid = ok;
        TokenSet fresh = refreshed;
        return tx.execute(st -> {
            SocialAccount x = accounts.findByOwnerIdAndNetwork(owner, n.name()).orElseThrow(() -> new NotFoundException("No hay ninguna cuenta vinculada"));
            if (!"DISABLED".equals(x.getStatus())) throw new ConflictException("La cuenta no está desactivada");
            if (valid) {
                if (fresh != null) store(x, fresh);
                x.setStatus("CONNECTED");
                x.setErrorCount(0);
                x.setLastError(null);
            } else {
                attention(x, "EXPIRED", "La conexión ha caducado: vuelve a conectar la cuenta");
            }
            return dto(n, x);
        });
    }

    /** Sincroniza las métricas de la cuenta (ADR-014 §6). En la Fase 8.1 ningún conector da métricas: 409. */
    public SocialAccountDto sync(String network) {
        SocialNetwork n = SocialNetwork.fromPath(network);
        Long owner = support.workspace();
        SocialAccount a = tx.execute(st -> owned(n));
        String s = status(n, a);
        if (!"CONNECTED".equals(s) && !"ERROR".equals(s)) throw new ConflictException("Solo se puede sincronizar una cuenta vinculada");
        if (!registry.capabilities(n).metrics()) throw new ConflictException("Esta red todavía no ofrece métricas");
        if (!syncAccount(a.getId())) throw new ConflictException("La cuenta ya se está sincronizando");
        return tx.execute(st -> dto(n, accounts.findByOwnerIdAndNetwork(owner, n.name()).orElseThrow()));
    }

    /**
     * Sincroniza una cuenta con el patrón de claim sobre sync_started_at (también lo usa el job cada hora).
     * false si otra instancia la tiene tomada.
     */
    public boolean syncAccount(Long accountId) {
        Instant now = Instant.now();
        Integer won = tx.execute(st -> accounts.claimSync(accountId, now, now.minus(SYNC_STALE)));
        if (won == null || won == 0) return false;
        SocialAccount a = accounts.findById(accountId).orElseThrow();
        SocialNetwork n = SocialNetwork.valueOf(a.getNetwork());
        List<ContentPostTarget> published = targets.publishedWithExternalId(a.getOwnerId(), a.getNetwork());
        Map<String, Long> postByExternal = new HashMap<>();
        published.forEach(t -> postByExternal.put(t.getExternalId(), t.getPost().getId()));
        List<SocialConnector.MetricSnapshot> got;
        try {
            got = postByExternal.isEmpty() ? List.of() : registry.get(n).fetchMetrics(tokens(a), new ArrayList<>(postByExternal.keySet()));
        } catch (ConnectorException e) {
            tx.executeWithoutResult(st -> accounts.findById(accountId).ifPresent(x -> { failure(x, e); x.setSyncStartedAt(null); }));
            return true;
        } catch (TokenCipher.DecryptException e) {
            tx.executeWithoutResult(st -> accounts.findById(accountId).ifPresent(x -> {
                attention(x, "EXPIRED", "No se pueden leer los datos de conexión: vuelve a conectar la cuenta");
                x.setSyncStartedAt(null);
            }));
            return true;
        }
        tx.executeWithoutResult(st -> {
            for (SocialConnector.MetricSnapshot m : got) {
                Long postId = postByExternal.get(m.externalId());
                if (postId == null || (m.views() == null && m.reach() == null && m.likes() == null && m.comments() == null
                        && m.shares() == null && m.saves() == null)) continue;
                LocalDate on = m.measuredOn() == null ? LocalDate.now(ContentSupport.ZONE) : m.measuredOn();
                ContentPostMetric row = metrics.findByPostIdAndNetworkAndMeasuredOnAndSource(postId, n.name(), on, "SYNC")
                        .orElseGet(() -> new ContentPostMetric(postId, n.name(), on, "SYNC", null));
                row.setValues(m.views(), m.reach(), m.likes(), m.comments(), m.shares(), m.saves());
                metrics.save(row);
            }
            accounts.findById(accountId).ifPresent(x -> {
                x.setLastSyncAt(Instant.now());
                x.setSyncStartedAt(null);
                x.setErrorCount(0);
                x.setLastError(null);
                if ("ERROR".equals(x.getStatus())) x.setStatus("CONNECTED");
            });
        });
        return true;
    }

    /** Revoca en la red si se puede y borra la cuenta con sus tokens. Publicaciones y métricas se conservan. */
    public void disconnect(String network) {
        SocialNetwork n = SocialNetwork.fromPath(network);
        Long owner = support.workspace();
        SocialAccount a = tx.execute(st -> owned(n));
        if (registry.configured(n) && registry.capabilities(n).revoke()) {
            try { registry.get(n).revoke(tokens(a)); }
            catch (RuntimeException e) { log.info("Token revocation not confirmed by {} ({})", n, e.getClass().getSimpleName()); }
        }
        tx.executeWithoutResult(st -> accounts.findByOwnerIdAndNetwork(owner, n.name()).ifPresent(accounts::delete));
    }

    // ---------- Uso interno (job de publicación) ----------

    /** Tokens en claro de una cuenta (solo en memoria). Si la clave cambió (rotación), los vuelve a cifrar con la actual. */
    public TokenSet tokens(SocialAccount a) {
        String aadA = TokenCipher.accountAad(a.getOwnerId(), a.getNetwork(), "access_token");
        String aadR = TokenCipher.accountAad(a.getOwnerId(), a.getNetwork(), "refresh_token");
        TokenSet t = new TokenSet(cipher.decrypt(a.getAccessTokenEnc(), aadA), cipher.decrypt(a.getRefreshTokenEnc(), aadR),
                a.getTokenExpiresAt(), a.getScopes(), a.getExternalId(), a.getExternalUsername());
        if (cipher.needsReencrypt(a.getAccessTokenEnc()) || cipher.needsReencrypt(a.getRefreshTokenEnc())) {
            tx.executeWithoutResult(st -> accounts.findById(a.getId()).ifPresent(x -> {
                x.setAccessTokenEnc(cipher.encrypt(t.accessToken(), aadA));
                x.setRefreshTokenEnc(cipher.encrypt(t.refreshToken(), aadR));
            }));
        }
        return t;
    }

    /** Fallo del conector al publicar o sincronizar (dentro de una transacción con la cuenta gestionada). */
    public void failure(SocialAccount a, ConnectorException e) {
        if (e.kind() == ConnectorException.Kind.EXPIRED) { attention(a, "EXPIRED", "La red ha rechazado el acceso: vuelve a conectar la cuenta"); return; }
        a.setErrorCount(a.getErrorCount() + 1);
        a.setLastError(switch (e.kind()) {
            case RATE_LIMITED -> "La red ha limitado las peticiones; se reintentará más tarde";
            case REJECTED -> "La red ha rechazado la operación";
            default -> "La red no responde ahora mismo";
        });
        if (a.getErrorCount() >= ERRORS_TO_ERROR) attention(a, "ERROR", a.getLastError());
    }

    /** Pasa a EXPIRED o ERROR y avisa al profesional una sola vez por cambio de estado. */
    public void attention(SocialAccount a, String status, String reason) {
        boolean changed = !status.equals(a.getStatus());
        a.setStatus(status);
        a.setLastError(ContentSupport.cut(reason, 500));
        if (changed) {
            String label = SocialNetwork.valueOf(a.getNetwork()).label();
            notifications.notify(a.getOwnerId(), NotificationService.SOCIAL_ACCOUNT_ATTENTION, "Revisa tu cuenta de " + label,
                    reason, "SOCIAL_ACCOUNT", a.getId());
        }
    }

    // ---------- Apoyo ----------

    private SocialAccount owned(SocialNetwork n) {
        return accounts.findByOwnerIdAndNetwork(support.workspace(), n.name()).orElseThrow(() -> new NotFoundException("No hay ninguna cuenta vinculada"));
    }

    private Map<String, SocialAccount> byNetwork(Long owner) {
        Map<String, SocialAccount> m = new HashMap<>();
        accounts.findByOwnerId(owner).forEach(a -> m.put(a.getNetwork(), a));
        return m;
    }

    private void store(SocialAccount a, TokenSet t) {
        a.setAccessTokenEnc(cipher.encrypt(t.accessToken(), TokenCipher.accountAad(a.getOwnerId(), a.getNetwork(), "access_token")));
        a.setRefreshTokenEnc(cipher.encrypt(t.refreshToken(), TokenCipher.accountAad(a.getOwnerId(), a.getNetwork(), "refresh_token")));
        a.setTokenExpiresAt(t.expiresAt());
        a.setScopes(ContentSupport.cut(t.scopes(), 500));
        a.setExternalId(ContentSupport.cut(t.externalId(), 100));
        a.setExternalUsername(ContentSupport.cut(t.username(), 120));
    }

    SocialAccountDto dto(SocialNetwork n, SocialAccount a) {
        String status = status(n, a);
        SocialConnector.Capabilities caps = registry.capabilities(n);
        CapabilitiesDto capsDto = new CapabilitiesDto(caps.publish(), caps.publishFormats().stream().sorted().toList(), caps.metrics());
        boolean linked = a != null;
        String lastError = linked && ("ERROR".equals(a.getStatus()) || "EXPIRED".equals(a.getStatus())) ? a.getLastError() : null;
        return new SocialAccountDto(n.name(), n.label(), status, registry.configured(n), reason(status), capsDto,
                linked ? a.getExternalUsername() : null, linked ? ContentSupport.odt(a.getConnectedAt()) : null,
                linked ? ContentSupport.odt(a.getLastSyncAt()) : null, linked ? ContentSupport.odt(a.getTokenExpiresAt()) : null, lastError);
    }

    static String reason(String status) {
        return switch (status) {
            case "NOT_CONFIGURED" -> "No disponible todavía: lo activa el administrador de la plataforma";
            case "DISCONNECTED" -> "Disponible: conecta tu cuenta";
            case "CONNECTED" -> "Vinculada";
            case "DISABLED" -> "Pausada: no publica ni sincroniza";
            case "EXPIRED" -> "La conexión ha caducado: vuelve a conectar";
            case "ERROR" -> "Fallos repetidos al sincronizar o publicar";
            default -> null;
        };
    }

    private static String random() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return b64(b); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha256Hex(String s) { return HexFormat.of().formatHex(sha256(s.getBytes(StandardCharsets.UTF_8))); }
}
