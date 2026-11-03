package com.physicalchange.service.content;

import com.physicalchange.domain.*;
import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.exception.BadRequestException;
import com.physicalchange.repository.*;
import com.physicalchange.service.social.SocialNetwork;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Analíticas del generador (docs/api-contenido.md §8, ADR-014 §7). Solo datos reales:
 * - recuentos propios (programadas, publicadas, a tiempo) salen de nuestra base y pueden ser 0;
 * - las métricas salen de content_post_metric (SYNC o MANUAL). Un dato desconocido es null ("Sin datos"), nunca 0.
 * Para cada publicación y red se usa su última medición hasta el final del periodo; el mismo día, SYNC gana a MANUAL.
 */
@Service
public class ContentAnalyticsService {
    static final Duration ON_TIME = Duration.ofMinutes(15);
    static final int MAX_DAYS = 366;

    private final ContentPostRepository posts;
    private final ContentPostTargetRepository targets;
    private final ContentPostMetricRepository metrics;
    private final ContentSupport support;

    public ContentAnalyticsService(ContentPostRepository p, ContentPostTargetRepository t, ContentPostMetricRepository m, ContentSupport s) {
        posts = p; targets = t; metrics = m; support = s;
    }

    @Transactional(readOnly = true)
    public AnalyticsSummaryDto summary(LocalDate from, LocalDate to, String network, String format) {
        Long owner = support.workspace();
        LocalDate end = to == null ? LocalDate.now(ContentSupport.ZONE) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (end.isBefore(start)) throw new BadRequestException("to no puede ser anterior a from");
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) throw new BadRequestException("El periodo máximo es de " + MAX_DAYS + " días");
        String net = ContentSupport.blankToNull(network), fmt = ContentSupport.blankToNull(format);
        if (net != null) SocialNetwork.fromPath(net);
        LocalDate prevTo = start.minusDays(1), prevFrom = start.minusDays(days);

        Period cur = period(owner, start, end, net, fmt);
        Period prev = period(owner, prevFrom, prevTo, net, fmt);

        AnalyticsKpisDto kpis = new AnalyticsKpisDto(
                kpi(BigDecimal.valueOf(cur.scheduled), BigDecimal.valueOf(prev.scheduled), null),
                kpi(BigDecimal.valueOf(cur.published), BigDecimal.valueOf(prev.published), null),
                kpi(cur.onTimeRate, prev.onTimeRate, null),
                kpi(dec(cur.views.value), dec(prev.views.value), cur.views.source),
                kpi(dec(cur.reach.value), dec(prev.reach.value), cur.reach.source),
                kpi(dec(cur.interactions.value), dec(prev.interactions.value), cur.interactions.source),
                kpi(cur.engagementRate, prev.engagementRate, cur.engagementSource),
                new KpiDto(null, null, null, null));   // seguidores: solo con un conector que lo dé (T194)

        // Visualizaciones por día de publicación.
        List<DayValueDto> byDay = new ArrayList<>();
        Map<LocalDate, List<Measured>> perDay = cur.measured.stream().collect(Collectors.groupingBy(m -> localDate(m.target.getPublishedAt())));
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Agg a = agg(perDay.getOrDefault(d, List.of()), m -> m.metric == null ? null : m.metric.getViews());
            byDay.add(new DayValueDto(d, a.value, a.source));
        }

        // Destinos de las publicaciones con fecha en el periodo, por red y estado.
        List<NetworkCountsDto> byNetwork = new ArrayList<>();
        for (SocialNetwork n : SocialNetwork.values()) {
            if (net != null && !net.equals(n.name())) continue;
            long pub = 0, sch = 0, fail = 0;
            for (ContentPost p : cur.scheduledPosts) for (ContentPostTarget t : p.getTargets()) {
                if (!t.getNetwork().equals(n.name())) continue;
                switch (t.getStatus()) {
                    case "PUBLISHED" -> pub++;
                    case "FAILED" -> fail++;
                    case "CANCELLED" -> { }
                    default -> sch++;
                }
            }
            byNetwork.add(new NetworkCountsDto(n.name(), pub, sch, fail));
        }

        Map<String, Set<Long>> postsByFormat = new LinkedHashMap<>();
        for (Measured m : cur.measured) postsByFormat.computeIfAbsent(m.target.getPost().getFormat(), k -> new HashSet<>()).add(m.target.getPost().getId());
        List<FormatCountDto> formats = ContentSupport.FORMATS.keySet().stream().filter(postsByFormat::containsKey)
                .map(f -> new FormatCountDto(f, postsByFormat.get(f).size())).toList();

        Agg likes = agg(cur.measured, m -> m.metric == null ? null : m.metric.getLikes());
        Agg comments = agg(cur.measured, m -> m.metric == null ? null : m.metric.getComments());
        Agg shares = agg(cur.measured, m -> m.metric == null ? null : m.metric.getShares());
        Agg saves = agg(cur.measured, m -> m.metric == null ? null : m.metric.getSaves());
        InteractionsByTypeDto byType = new InteractionsByTypeDto(likes.value, comments.value, shares.value, saves.value,
                source(Arrays.asList(likes.source, comments.source, shares.source, saves.source)));   // admite nulos (sin datos)

        // Mapa de calor: día × hora de publicación (Europe/Madrid).
        Map<String, List<Measured>> cells = new TreeMap<>();
        for (Measured m : cur.measured) {
            ZonedDateTime z = m.target.getPublishedAt().atZone(ContentSupport.ZONE);
            cells.computeIfAbsent(String.format("%d-%02d", z.getDayOfWeek().getValue(), z.getHour()), k -> new ArrayList<>()).add(m);
        }
        List<HeatCellDto> heat = cells.entrySet().stream().map(e -> {
            String[] k = e.getKey().split("-");
            List<BigDecimal> rates = e.getValue().stream().map(Measured::rate).filter(Objects::nonNull).toList();
            BigDecimal avg = rates.isEmpty() ? null
                    : rates.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(rates.size()), 2, RoundingMode.HALF_UP);
            return new HeatCellDto(Integer.parseInt(k[0]), Integer.parseInt(k[1]), e.getValue().size(), avg);
        }).toList();

        List<TopPostDto> top = cur.measured.stream().filter(m -> m.metric != null && m.metric.getViews() != null)
                .sorted(Comparator.comparing((Measured m) -> m.metric.getViews()).reversed().thenComparing(m -> m.target.getId()))
                .limit(10)
                .map(m -> new TopPostDto(m.target.getPost().getId(), m.target.getPost().getTitle(), m.target.getNetwork(),
                        m.target.getPost().getFormat(), ContentSupport.odt(m.target.getPublishedAt()), m.metric.getViews(), m.interactions(),
                        m.rate(), m.metric.getSource()))
                .toList();

        return new AnalyticsSummaryDto(start, end, prevFrom, prevTo, kpis, byDay, byNetwork, formats, byType, heat, top);
    }

    /** KPIs de la semana del Inicio. */
    @Transactional(readOnly = true)
    public HomeKpisDto homeKpis(Long owner, LocalDate from, LocalDate to) {
        Period p = period(owner, from, to, null, null);
        return new HomeKpisDto(p.scheduled, p.published, p.onTimeRate, p.views.value, p.views.source);
    }

    // ---------- Cálculo de un periodo ----------

    private record Agg(Long value, String source) {}

    /** Un destino publicado y su medición elegida (null si no tiene ninguna). */
    private record Measured(ContentPostTarget target, ContentPostMetric metric) {
        /** Likes + comentarios + compartidos + guardados; los nulos no suman; todos nulos → null. */
        Long interactions() {
            if (metric == null) return null;
            Long sum = null;
            for (Long v : new Long[]{metric.getLikes(), metric.getComments(), metric.getShares(), metric.getSaves()})
                if (v != null) sum = (sum == null ? 0 : sum) + v;
            return sum;
        }
        /** Tasa de interacción en %; solo con visualizaciones > 0. */
        BigDecimal rate() {
            Long i = interactions();
            if (metric == null || metric.getViews() == null || metric.getViews() <= 0 || i == null) return null;
            return BigDecimal.valueOf(i * 100).divide(BigDecimal.valueOf(metric.getViews()), 2, RoundingMode.HALF_UP);
        }
    }

    private static final class Period {
        List<ContentPost> scheduledPosts;
        long scheduled, published;
        BigDecimal onTimeRate, engagementRate;
        String engagementSource;
        Agg views, reach, interactions;
        List<Measured> measured;
    }

    private Period period(Long owner, LocalDate from, LocalDate to, String network, String format) {
        Instant start = from.atStartOfDay(ContentSupport.ZONE).toInstant(), end = to.plusDays(1).atStartOfDay(ContentSupport.ZONE).toInstant();
        Period p = new Period();
        p.scheduledPosts = posts.inRange(owner, ContentPostService.CALENDAR_STATUSES, start, end).stream()
                .filter(x -> format == null || format.equals(x.getFormat()))
                .filter(x -> network == null || x.target(network).isPresent()).toList();
        p.scheduled = p.scheduledPosts.size();

        List<ContentPostTarget> published = targets.publishedBetween(owner, start, end).stream()
                .filter(t -> network == null || network.equals(t.getNetwork()))
                .filter(t -> format == null || format.equals(t.getPost().getFormat())).toList();
        p.published = published.stream().map(t -> t.getPost().getId()).distinct().count();
        List<ContentPostTarget> timed = published.stream().filter(t -> t.getPost().getScheduledAt() != null).toList();
        p.onTimeRate = timed.isEmpty() ? null : BigDecimal.valueOf(timed.stream()
                        .filter(t -> !t.getPublishedAt().isAfter(t.getPost().getScheduledAt().plus(ON_TIME))).count() * 100)
                .divide(BigDecimal.valueOf(timed.size()), 1, RoundingMode.HALF_UP);

        // Última medición de cada publicación y red hasta el final del periodo (el mismo día, SYNC gana).
        Map<String, ContentPostMetric> latest = new HashMap<>();
        Set<Long> postIds = published.stream().map(t -> t.getPost().getId()).collect(Collectors.toSet());
        if (!postIds.isEmpty()) for (ContentPostMetric m : metrics.forPosts(postIds, to)) {
            String key = m.getPostId() + ":" + m.getNetwork();
            ContentPostMetric cur = latest.get(key);
            if (cur == null || m.getMeasuredOn().isAfter(cur.getMeasuredOn())
                    || (m.getMeasuredOn().equals(cur.getMeasuredOn()) && "SYNC".equals(m.getSource()))) latest.put(key, m);
        }
        p.measured = published.stream().map(t -> new Measured(t, latest.get(t.getPost().getId() + ":" + t.getNetwork()))).toList();
        p.views = agg(p.measured, m -> m.metric == null ? null : m.metric.getViews());
        p.reach = agg(p.measured, m -> m.metric == null ? null : m.metric.getReach());
        p.interactions = agg(p.measured, Measured::interactions);
        long sumI = 0, sumV = 0;
        Set<String> src = new HashSet<>();
        for (Measured m : p.measured) {
            Long i = m.interactions();
            if (m.metric == null || m.metric.getViews() == null || m.metric.getViews() <= 0 || i == null) continue;
            sumI += i; sumV += m.metric.getViews(); src.add(m.metric.getSource());
        }
        p.engagementRate = sumV == 0 ? null : BigDecimal.valueOf(sumI * 100).divide(BigDecimal.valueOf(sumV), 2, RoundingMode.HALF_UP);
        p.engagementSource = src.isEmpty() ? null : src.size() > 1 ? "MIXED" : src.iterator().next();
        return p;
    }

    /** Suma de los valores no nulos; si no hay ninguno, null (no 0). source: SYNC, MANUAL, MIXED o null. */
    private static Agg agg(List<Measured> list, Function<Measured, Long> f) {
        Long sum = null;
        Set<String> src = new HashSet<>();
        for (Measured m : list) {
            Long v = f.apply(m);
            if (v == null) continue;
            sum = (sum == null ? 0 : sum) + v;
            src.add(m.metric.getSource());
        }
        return new Agg(sum, src.isEmpty() ? null : src.size() > 1 ? "MIXED" : src.iterator().next());
    }

    private static String source(List<String> sources) {
        Set<String> s = sources.stream().filter(Objects::nonNull).flatMap(x -> "MIXED".equals(x) ? java.util.stream.Stream.of("SYNC", "MANUAL")
                : java.util.stream.Stream.of(x)).collect(Collectors.toSet());
        return s.isEmpty() ? null : s.size() > 1 ? "MIXED" : s.iterator().next();
    }

    private static KpiDto kpi(BigDecimal value, BigDecimal previous, String source) {
        BigDecimal change = value == null || previous == null || previous.signum() == 0 ? null
                : value.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP);
        return new KpiDto(value, previous, change, source);
    }

    private static BigDecimal dec(Long v) { return v == null ? null : BigDecimal.valueOf(v); }
    private static LocalDate localDate(Instant i) { return i.atZone(ContentSupport.ZONE).toLocalDate(); }
}
