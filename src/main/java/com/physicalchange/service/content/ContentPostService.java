package com.physicalchange.service.content;

import com.physicalchange.domain.*;
import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.service.FileService;
import com.physicalchange.service.NotificationService;
import com.physicalchange.service.social.SocialConnectorRegistry;
import com.physicalchange.service.social.SocialNetwork;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Publicaciones, calendario, Inicio y métricas manuales (docs/api-contenido.md §6-§8, ADR-014 §6-§7).
 * Estados: DRAFT → SCHEDULED → DUE → PUBLISHED | FAILED; CANCELLED desde cualquiera salvo PUBLISHED. Fechas en Europe/Madrid.
 */
@Service
public class ContentPostService {
    public static final Set<String> CALENDAR_STATUSES = Set.of("SCHEDULED", "DUE", "PUBLISHED", "FAILED");
    private static final Set<String> PENDING_TARGET = Set.of("PENDING", "PUBLISHING", "MANUAL_DUE");
    private static final Pattern HTTPS = Pattern.compile("^https://[^\\s<>\"]+$");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z"), MAX = Instant.parse("9999-12-31T00:00:00Z");
    private static final Set<String> SORTABLE = Set.of("scheduledAt", "createdAt", "updatedAt", "title", "status");

    private final ContentPostRepository posts;
    private final ContentPostMetricRepository metrics;
    private final ContentIdeaRepository ideas;
    private final ContentSnippetRepository snippets;
    private final StoredFileRepository files;
    private final UserRepository users;
    private final FileService fileService;
    private final SocialAccountService accounts;
    private final SocialConnectorRegistry registry;
    private final ContentLibraryService library;
    private final ContentAnalyticsService analytics;
    private final NotificationService notifications;
    private final ContentSupport support;

    public ContentPostService(ContentPostRepository p, ContentPostMetricRepository m, ContentIdeaRepository i, ContentSnippetRepository s,
                              StoredFileRepository f, UserRepository u, FileService fs, SocialAccountService a, SocialConnectorRegistry r,
                              ContentLibraryService l, ContentAnalyticsService an, NotificationService n, ContentSupport cs) {
        posts = p; metrics = m; ideas = i; snippets = s; files = f; users = u; fileService = fs; accounts = a; registry = r; library = l;
        analytics = an; notifications = n; support = cs;
    }

    // ---------- Lectura ----------

    @Transactional(readOnly = true)
    public Page<ContentPostDto> list(String status, String network, String format, LocalDate from, LocalDate to, String q, Pageable page) {
        Long owner = support.workspace();
        if (q != null && q.length() > 100) throw new BadRequestException("q: como mucho 100 caracteres");
        if (from != null && to != null && to.isBefore(from)) throw new BadRequestException("to no puede ser anterior a from");
        boolean dated = from != null || to != null;
        Instant fromI = from == null ? MIN : from.atStartOfDay(ContentSupport.ZONE).toInstant();
        Instant toI = to == null ? MAX : to.plusDays(1).atStartOfDay(ContentSupport.ZONE).toInstant();
        List<Sort.Order> orders = page.getSort().stream().filter(o -> SORTABLE.contains(o.getProperty())).toList();
        Sort sort = (orders.isEmpty() ? Sort.by(Sort.Order.asc("scheduledAt")) : Sort.by(orders)).and(Sort.by("id"));
        Page<ContentPost> found = posts.search(owner, blank(status), blank(format), blank(network), dated, fromI, toI, ContentSupport.like(q),
                PageRequest.of(page.getPageNumber(), Math.min(page.getPageSize(), 100), sort));
        List<ContentPostDto> dtos = dtos(owner, found.getContent());
        return new PageImpl<>(dtos, found.getPageable(), found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ContentPostDto get(Long id) { Long owner = support.workspace(); return dto(owner, owned(id, owner)); }

    // ---------- Crear y editar ----------

    @Transactional
    public ContentPostDto create(PostRequest r) {
        Long owner = support.workspace(), me = support.me();
        ContentPost p = new ContentPost();
        p.setOwnerId(owner);
        p.setCreatedBy(me);
        p.changedBy(me);
        apply(p, r, owner);
        syncTargets(p, r.networks() == null ? List.of() : r.networks());
        return dto(owner, posts.saveAndFlush(p));
    }

    /** Ideas → publicación: borrador con título, gancho + guion como texto, formato e idea. Una idea del banco se copia antes. */
    @Transactional
    public ContentPostDto fromIdea(Long ideaId, ToPostRequest r) {
        Long owner = support.workspace(), me = support.me();
        ContentIdea idea = library.ownIdeaFor(ideaId);
        ContentPost p = new ContentPost();
        p.setOwnerId(owner);
        p.setCreatedBy(me);
        p.changedBy(me);
        p.setTitle(idea.getTitle());
        String body = String.join("\n\n", Stream.of(idea.getHook(), idea.getScript()).filter(s -> s != null && !s.isBlank()).toList());
        p.setBody(body.isBlank() ? null : ContentSupport.cut(body, 5000));
        p.setFormat(idea.getFormat());
        p.setIdeaId(idea.getId());
        syncTargets(p, r == null || r.networks() == null ? List.of() : r.networks());
        idea.setUsed(true);
        return dto(owner, posts.saveAndFlush(p));
    }

    @Transactional
    public ContentPostDto update(Long id, PostRequest r) {
        Long owner = support.workspace();
        if (r.version() == null) throw new BadRequestException("version: obligatoria");
        ContentPost p = owned(id, owner);
        if (Set.of("PUBLISHED", "CANCELLED").contains(p.getStatus())) throw new ConflictException("Una publicación publicada o cancelada no se edita");
        if (r.version() != p.getVersion()) throw new ConflictException("Otra persona ha cambiado esta publicación; recarga y vuelve a intentarlo");
        apply(p, r, owner);
        if (r.networks() != null) syncTargets(p, r.networks());
        if ("SCHEDULED".equals(p.getStatus()) && p.getTargets().isEmpty()) throw new BadRequestException("networks: elige al menos una red");
        p.changedBy(support.me());
        recompute(p);
        return dto(owner, posts.saveAndFlush(p));
    }

    @Transactional
    public void delete(Long id) {
        ContentPost p = owned(id, support.workspace());
        if (!Set.of("DRAFT", "CANCELLED").contains(p.getStatus())) throw new ConflictException("Solo se borran borradores o publicaciones canceladas");
        metrics.deleteByPost(p.getId());
        posts.delete(p);
    }

    @Transactional
    public ContentPostDto duplicate(Long id) {
        Long owner = support.workspace(), me = support.me();
        ContentPost src = owned(id, owner);
        ContentPost p = new ContentPost();
        p.setOwnerId(owner); p.setCreatedBy(me); p.changedBy(me);
        p.setTitle(src.getTitle()); p.setBody(src.getBody()); p.setHashtags(src.getHashtags()); p.setFormat(src.getFormat());
        p.setMediaFileId(src.getMediaFileId()); p.setMediaUrl(src.getMediaUrl()); p.setIdeaId(src.getIdeaId());
        src.getTargets().forEach(t -> p.addTarget(t.getNetwork(), "PENDING"));
        return dto(owner, posts.saveAndFlush(p));
    }

    // ---------- Programación ----------

    /** POST: desde DRAFT o FAILED. PATCH (mover en el calendario): también desde SCHEDULED. */
    @Transactional
    public ContentPostDto schedule(Long id, ScheduleRequest r, boolean move) {
        Long owner = support.workspace();
        ContentPost p = owned(id, owner);
        // D-05: al arrastrar en el calendario se envía la versión vista; si otro (o el job) la cambió, 409.
        if (r.version() != null && r.version() != p.getVersion())
            throw new ConflictException("Otra persona ha cambiado esta publicación; recarga y vuelve a intentarlo");
        Set<String> allowed = move ? Set.of("SCHEDULED", "DRAFT", "FAILED") : Set.of("DRAFT", "FAILED");
        if (!allowed.contains(p.getStatus())) throw new ConflictException("No se puede programar una publicación en estado " + p.getStatus());
        Instant at = instant(r);
        if (r.networks() != null) syncTargets(p, r.networks());
        if (p.getTargets().isEmpty()) throw new BadRequestException("networks: elige al menos una red");
        if (p.getTargets().stream().allMatch(t -> "PUBLISHED".equals(t.getStatus()))) throw new ConflictException("Ya está publicada en todas sus redes");
        p.getTargets().stream().filter(t -> !"PUBLISHED".equals(t.getStatus())).forEach(ContentPostTarget::reset);
        p.setStatus("SCHEDULED");
        p.setScheduledAt(at);
        p.setTimeZone(ContentSupport.ZONE.getId());
        p.setDueAt(null);
        p.changedBy(support.me());
        return dto(owner, posts.saveAndFlush(p));
    }

    @Transactional
    public ContentPostDto unschedule(Long id) {
        Long owner = support.workspace();
        ContentPost p = owned(id, owner);
        if (!"SCHEDULED".equals(p.getStatus())) throw new ConflictException("Solo se desprograma una publicación programada");
        p.setStatus("DRAFT");
        p.setScheduledAt(null);
        p.changedBy(support.me());
        return dto(owner, posts.saveAndFlush(p));
    }

    @Transactional
    public ContentPostDto cancel(Long id) {
        Long owner = support.workspace();
        ContentPost p = owned(id, owner);
        if (Set.of("PUBLISHED", "CANCELLED").contains(p.getStatus())) throw new ConflictException("No se puede cancelar una publicación publicada o ya cancelada");
        p.getTargets().stream().filter(t -> !"PUBLISHED".equals(t.getStatus())).forEach(t -> t.setStatus("CANCELLED"));
        p.setStatus("CANCELLED");
        p.changedBy(support.me());
        return dto(owner, posts.saveAndFlush(p));
    }

    @Transactional
    public ContentPostDto restore(Long id) {
        Long owner = support.workspace();
        ContentPost p = owned(id, owner);
        if (!"CANCELLED".equals(p.getStatus())) throw new ConflictException("Solo se restaura una publicación cancelada");
        p.getTargets().stream().filter(t -> "CANCELLED".equals(t.getStatus())).forEach(ContentPostTarget::reset);
        p.setStatus("DRAFT");
        p.setScheduledAt(null);
        p.setDueAt(null);
        p.changedBy(support.me());
        return dto(owner, posts.saveAndFlush(p));
    }

    /** Marcar a mano una red como publicada (desde MANUAL_DUE o FAILED; también PENDING de una publicación DUE). */
    @Transactional
    public ContentPostDto markPublished(Long id, String network, MarkPublishedRequest r) {
        Long owner = support.workspace();
        ContentPost p = owned(id, owner);
        SocialNetwork n = SocialNetwork.fromPath(network);
        ContentPostTarget t = p.target(n.name()).orElseThrow(() -> new NotFoundException("Esa red no está en la publicación"));
        boolean ok = Set.of("DUE", "FAILED").contains(p.getStatus())
                && (Set.of("MANUAL_DUE", "FAILED").contains(t.getStatus()) || ("PENDING".equals(t.getStatus()) && "DUE".equals(p.getStatus())));
        if (!ok) throw new ConflictException("Esa red no está pendiente de publicar");
        String url = r == null ? null : blank(r.externalUrl());
        if (url != null && !HTTPS.matcher(url).matches()) throw new BadRequestException("externalUrl: tiene que empezar por https://");
        Instant now = Instant.now();
        Instant at = r == null || r.publishedAt() == null ? now : r.publishedAt().toInstant();
        if (at.isAfter(now.plusSeconds(60))) throw new BadRequestException("publishedAt: no puede ser una fecha futura");
        t.setStatus("PUBLISHED");
        t.setPublishedAt(at);
        t.setExternalUrl(url);
        t.setError(null);
        p.changedBy(support.me());
        recompute(p);
        return dto(owner, posts.saveAndFlush(p));
    }

    /**
     * Estado global de una publicación DUE o FAILED (ADR-014 §6, paso 5): todas publicadas → PUBLISHED; alguna FAILED y ninguna
     * pendiente → FAILED (aviso CONTENT_POST_FAILED al pasar de DUE a FAILED); si no, DUE.
     */
    public void recompute(ContentPost p) {
        if (!Set.of("DUE", "FAILED").contains(p.getStatus())) return;
        List<ContentPostTarget> live = p.getTargets().stream().filter(t -> !"CANCELLED".equals(t.getStatus())).toList();
        String next;
        if (!live.isEmpty() && live.stream().allMatch(t -> "PUBLISHED".equals(t.getStatus()))) next = "PUBLISHED";
        else if (live.stream().anyMatch(t -> "FAILED".equals(t.getStatus())) && live.stream().noneMatch(t -> PENDING_TARGET.contains(t.getStatus()))) next = "FAILED";
        else next = "DUE";
        if ("FAILED".equals(next) && "DUE".equals(p.getStatus())) {
            String nets = live.stream().filter(t -> "FAILED".equals(t.getStatus())).map(t -> SocialNetwork.valueOf(t.getNetwork()).label())
                    .collect(Collectors.joining(", "));
            notifications.notify(p.getOwnerId(), NotificationService.CONTENT_POST_FAILED, "No se pudo publicar «" + p.getTitle() + "»",
                    "Ha fallado en " + nets + ". Revisa la publicación en el calendario.", "CONTENT_POST", p.getId());
        }
        p.setStatus(next);
    }

    // ---------- Calendario e Inicio ----------

    @Transactional(readOnly = true)
    public CalendarDto calendar(String view, LocalDate date, String network, String format) {
        Long owner = support.workspace();
        return calendar(owner, view == null ? "WEEK" : view, date == null ? LocalDate.now(ContentSupport.ZONE) : date, blank(network), blank(format));
    }

    private CalendarDto calendar(Long owner, String view, LocalDate date, String network, String format) {
        LocalDate from, to;
        if ("WEEK".equals(view)) {
            from = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            to = from.plusDays(6);
        } else if ("MONTH".equals(view)) {
            from = date.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            to = date.with(TemporalAdjusters.lastDayOfMonth()).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        } else throw new BadRequestException("view: WEEK o MONTH");
        List<ContentPost> found = posts.inRange(owner, CALENDAR_STATUSES, from.atStartOfDay(ContentSupport.ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ContentSupport.ZONE).toInstant()).stream()
                .filter(p -> network == null || p.target(network).isPresent())
                .filter(p -> format == null || format.equals(p.getFormat())).toList();
        Map<Long, StoredFile> media = media(found);
        Map<LocalDate, List<CalendarPostDto>> byDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d, new ArrayList<>());
        for (ContentPost p : found) {
            ZonedDateTime local = p.getScheduledAt().atZone(ContentSupport.ZONE);
            StoredFile f = p.getMediaFileId() == null ? null : media.get(p.getMediaFileId());
            String thumb = f != null && f.getContentType() != null && f.getContentType().startsWith("image/") ? fileService.url(f.getId()) : null;
            byDay.get(local.toLocalDate()).add(new CalendarPostDto(p.getId(), p.getTitle(), p.getFormat(), p.getStatus(), local.format(HHMM),
                    p.getTargets().stream().map(ContentPostTarget::getNetwork).toList(),
                    p.getTargets().stream().map(t -> new CalendarTargetDto(t.getNetwork(), t.getStatus())).toList(), thumb, p.getVersion()));
        }
        return new CalendarDto(view, from, to, ContentSupport.ZONE.getId(),
                byDay.entrySet().stream().map(e -> new CalendarDayDto(e.getKey(), e.getValue())).toList());
    }

    @Transactional(readOnly = true)
    public ContentHomeDto home(LocalDate date) {
        Long owner = support.workspace();
        CalendarDto week = calendar(owner, "WEEK", date == null ? LocalDate.now(ContentSupport.ZONE) : date, null, null);
        List<ContentIdea> ownIdeas = ideas.unusedOwn(owner, PageRequest.of(0, 5));
        List<ContentIdea> homeIdeas = ownIdeas.isEmpty() ? ideas.bank(PageRequest.of(0, 5)) : ownIdeas;
        List<ContentSnippet> ownHooks = snippets.favorites(owner, ContentLibraryService.HOOK, PageRequest.of(0, 5));
        List<ContentSnippet> homeHooks = ownHooks.isEmpty() ? snippets.bank(ContentLibraryService.HOOK, PageRequest.of(0, 5)) : ownHooks;
        HomeKpisDto kpis = analytics.homeKpis(owner, week.from(), week.to());
        List<AccountStatusDto> acc = accounts.statuses(owner).entrySet().stream()
                .map(e -> new AccountStatusDto(e.getKey().name(), e.getValue())).toList();
        return new ContentHomeDto(week, homeIdeas.stream().map(ContentSupport::idea).toList(),
                homeHooks.stream().map(ContentSupport::snippet).toList(), kpis, acc, posts.countManualDue(owner));
    }

    /** D-06: publicaciones con alguna red en "toca publicar" (MANUAL_DUE), para la barra lateral. */
    @Transactional(readOnly = true)
    public long manualDueCount() { return posts.countManualDue(support.workspace()); }

    // ---------- Métricas manuales ----------

    @Transactional(readOnly = true)
    public List<PostMetricDto> metrics(Long postId) {
        ContentPost p = owned(postId, support.workspace());
        return metrics.findByPostIdOrderByMeasuredOnDescIdDesc(p.getId()).stream().map(ContentPostService::metric).toList();
    }

    /** Crea o sustituye la medición manual de ese día. Los valores son totales acumulados; null = sin dato (nunca 0). */
    @Transactional
    public PostMetricDto putMetric(Long postId, String network, LocalDate on, MetricRequest r) {
        ContentPost p = owned(postId, support.workspace());
        SocialNetwork n = SocialNetwork.fromPath(network);
        if (r.views() == null && r.reach() == null && r.likes() == null && r.comments() == null && r.shares() == null && r.saves() == null)
            throw new BadRequestException("Indica al menos una métrica");
        ContentPostTarget t = p.target(n.name()).filter(x -> "PUBLISHED".equals(x.getStatus()))
                .orElseThrow(() -> new ConflictException("Esa red no está publicada en esta publicación"));
        if (on.isAfter(LocalDate.now(ContentSupport.ZONE))) throw new ConflictException("La fecha de medición no puede ser futura");
        if (on.isBefore(t.getPublishedAt().atZone(ContentSupport.ZONE).toLocalDate()))
            throw new ConflictException("La fecha de medición es anterior a la publicación");
        ContentPostMetric m = metrics.findByPostIdAndNetworkAndMeasuredOnAndSource(p.getId(), n.name(), on, "MANUAL")
                .orElseGet(() -> new ContentPostMetric(p.getId(), n.name(), on, "MANUAL", support.me()));
        m.setValues(r.views(), r.reach(), r.likes(), r.comments(), r.shares(), r.saves());
        return metric(metrics.save(m));
    }

    @Transactional
    public void deleteMetric(Long postId, String network, LocalDate on) {
        ContentPost p = owned(postId, support.workspace());
        SocialNetwork n = SocialNetwork.fromPath(network);
        Optional<ContentPostMetric> manual = metrics.findByPostIdAndNetworkAndMeasuredOnAndSource(p.getId(), n.name(), on, "MANUAL");
        if (manual.isPresent()) { metrics.delete(manual.get()); return; }
        if (metrics.existsByPostIdAndNetworkAndMeasuredOn(p.getId(), n.name(), on)) throw new ConflictException("Las métricas sincronizadas no se borran");
        throw new NotFoundException("Medición no encontrada");
    }

    // ---------- Apoyo ----------

    public ContentPost owned(Long id, Long owner) {
        return posts.findById(id).filter(p -> p.getOwnerId().equals(owner)).orElseThrow(() -> new NotFoundException("Publicación no encontrada"));
    }

    private void apply(ContentPost p, PostRequest r, Long owner) {
        p.setTitle(r.title().strip());
        p.setBody(blank(r.body()));
        p.setHashtags(ContentSupport.joinHashtags(r.hashtags()));
        p.setFormat(r.format());
        String url = blank(r.mediaUrl());
        if (r.mediaFileId() != null && url != null) throw new BadRequestException("Elige un archivo de la Librería o una URL, no los dos");
        if (r.mediaFileId() != null) {
            StoredFile f = files.findById(r.mediaFileId()).filter(x -> owner.equals(x.getOwnerId()) && "READY".equals(x.getStatus()))
                    .orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
            p.setMediaFileId(f.getId());
        } else p.setMediaFileId(null);
        if (url != null && !HTTPS.matcher(url).matches()) throw new BadRequestException("mediaUrl: tiene que empezar por https://");
        p.setMediaUrl(url);
        p.setIdeaId(r.ideaId() == null ? null : library.ownIdeaFor(r.ideaId()).getId());
    }

    /** Ajusta las redes destino: añade las nuevas y quita las que sobran (nunca una ya publicada o publicándose). */
    private void syncTargets(ContentPost p, List<String> networks) {
        if (new HashSet<>(networks).size() != networks.size()) throw new BadRequestException("networks: hay redes repetidas");
        Set<String> wanted = new LinkedHashSet<>(networks);
        for (ContentPostTarget t : List.copyOf(p.getTargets())) {
            if (wanted.contains(t.getNetwork())) continue;
            if (Set.of("PUBLISHED", "PUBLISHING").contains(t.getStatus()))
                throw new ConflictException("No puedes quitar una red en la que ya se ha publicado");
            p.getTargets().remove(t);
        }
        // Una red añadida cuando ya ha pasado la hora toca publicarla a mano.
        String initial = Set.of("DUE", "FAILED").contains(p.getStatus()) ? "MANUAL_DUE" : "PENDING";
        for (String n : wanted) if (p.target(n).isEmpty()) p.addTarget(n, initial);
    }

    /** Fecha y hora locales → instante. Solo Europe/Madrid en v1; hora inexistente (marzo) → 400; repetida (octubre) → la primera. */
    private static Instant instant(ScheduleRequest r) {
        if (!ContentSupport.ZONE.getId().equals(r.timeZone())) throw new BadRequestException("timeZone: solo se admite Europe/Madrid");
        LocalDateTime ldt = r.date().atTime(LocalTime.parse(r.time()));
        if (ContentSupport.ZONE.getRules().getValidOffsets(ldt).isEmpty())
            throw new BadRequestException("Esa hora no existe por el cambio de hora; elige otra");
        Instant at = ZonedDateTime.ofLocal(ldt, ContentSupport.ZONE, null).toInstant();
        if (at.isBefore(Instant.now().plusSeconds(60))) throw new BadRequestException("La hora tiene que ser al menos 1 minuto en el futuro");
        return at;
    }

    private Map<Long, StoredFile> media(List<ContentPost> list) {
        Set<Long> ids = list.stream().map(ContentPost::getMediaFileId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, StoredFile> m = new HashMap<>();
        if (!ids.isEmpty()) files.findAllById(ids).forEach(f -> m.put(f.getId(), f));
        return m;
    }

    private ContentPostDto dto(Long owner, ContentPost p) { return dtos(owner, List.of(p)).get(0); }

    private List<ContentPostDto> dtos(Long owner, List<ContentPost> list) {
        if (list.isEmpty()) return List.of();
        Map<SocialNetwork, String> statuses = accounts.statuses(owner);
        Map<Long, StoredFile> media = media(list);
        Set<Long> userIds = new HashSet<>();
        list.forEach(p -> { if (p.getCreatedBy() != null) userIds.add(p.getCreatedBy()); if (p.getUpdatedBy() != null) userIds.add(p.getUpdatedBy()); });
        Map<Long, String> names = new HashMap<>();
        users.findAllById(userIds).forEach(u -> names.put(u.getId(), u.getFullName()));
        Set<Long> withMetrics = new HashSet<>(metrics.postsWithMetrics(list.stream().map(ContentPost::getId).toList()));
        return list.stream().map(p -> {
            MediaDto m = null;
            if (p.getMediaFileId() != null) {
                StoredFile f = media.get(p.getMediaFileId());
                m = f == null ? null : new MediaDto(f.getId(), fileService.url(f.getId()), f.getName(), f.getContentType());
            } else if (p.getMediaUrl() != null) m = new MediaDto(null, p.getMediaUrl(), null, null);
            List<PostTargetDto> targets = p.getTargets().stream().map(t -> {
                SocialNetwork n = SocialNetwork.valueOf(t.getNetwork());
                String acc = statuses.get(n);
                boolean automatic = "CONNECTED".equals(acc) && registry.publishes(n, p.getFormat());
                return new PostTargetDto(t.getNetwork(), t.getStatus(), automatic, acc, t.getExternalUrl(), ContentSupport.odt(t.getPublishedAt()),
                        t.getError());
            }).toList();
            ZonedDateTime local = p.getScheduledAt() == null ? null : p.getScheduledAt().atZone(ContentSupport.ZONE);
            return new ContentPostDto(p.getId(), p.getTitle(), p.getBody(), ContentSupport.split(p.getHashtags()), p.getFormat(), m, p.getIdeaId(),
                    p.getStatus(), local == null ? null : local.toOffsetDateTime(), local == null ? null : local.format(LOCAL), p.getTimeZone(),
                    targets, withMetrics.contains(p.getId()), ref(p.getCreatedBy(), names), ref(p.getUpdatedBy(), names), ContentSupport.odt(p.getCreatedAt()),
                    ContentSupport.odt(p.getUpdatedAt()), p.getVersion());
        }).toList();
    }

    private static UserRefDto ref(Long id, Map<Long, String> names) { return id == null ? null : new UserRefDto(id, names.get(id)); }

    static PostMetricDto metric(ContentPostMetric m) {
        return new PostMetricDto(m.getId(), m.getNetwork(), m.getMeasuredOn(), m.getViews(), m.getReach(), m.getLikes(), m.getComments(),
                m.getShares(), m.getSaves(), m.getSource(), m.getCreatedBy(), ContentSupport.odt(m.getCreatedAt()));
    }

    private static String blank(String s) { return ContentSupport.blankToNull(s); }
}
