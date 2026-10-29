package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.util.TrainingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Logros por cliente (ADR-007 y ADR-012 §5). Cuatro pasos separados:
 * 1) estadísticas recalculadas desde el histórico (client_stats), 2) progreso de cada logro (al leer, no se guarda),
 * 3) desbloqueo solo si la condición se cumple y no existía (achievement_unlock, único por cliente y logro),
 * 4) aviso ACHIEVEMENT_UNLOCKED en la misma transacción que el desbloqueo.
 * Se evalúa tras cada ClientActivityEvent y, como respaldo, al consultar los logros.
 */
@Service
public class AchievementProgressService {
    private static final Logger log = LoggerFactory.getLogger(AchievementProgressService.class);
    static final String UNLOCKED = "UNLOCKED", LOCKED = "LOCKED", NO_DATA = "NO_DATA";
    static final int XP_PER_LEVEL = 100;
    /** Rangos del nivel (etiquetas vistas en TotalGains, totalgains/logros.md §5). */
    static final List<String> LEVEL_NAMES = List.of("Novato", "Iniciado", "Constante", "Dedicado", "Guerrero", "Veterano",
            "Disciplina de Acero", "Fuerza Bruta");

    private final UserRepository users;
    private final ClientProfileRepository profiles;
    private final AchievementCategoryRepository categories;
    private final AchievementRepository achievements;
    private final AchievementCategoryPrefRepository prefs;
    private final AchievementHiddenRepository hidden;
    private final AchievementUnlockRepository unlocks;
    private final ClientStatsRepository stats;
    private final ExerciseLogRepository logs;
    private final WorkoutAssignmentRepository assignments;
    private final AgendaBookingRepository bookings;
    private final AgendaEventRepository events;
    private final ProgressEntryRepository progress;
    private final NotificationService notifications;
    private final FileService files;
    private final AccessService access;
    private final TransactionTemplate newTx;

    public AchievementProgressService(UserRepository u, ClientProfileRepository cp, AchievementCategoryRepository c, AchievementRepository a,
                                      AchievementCategoryPrefRepository p, AchievementHiddenRepository h, AchievementUnlockRepository un,
                                      ClientStatsRepository s, ExerciseLogRepository l, WorkoutAssignmentRepository wa,
                                      AgendaBookingRepository b, AgendaEventRepository e, ProgressEntryRepository pr,
                                      NotificationService n, FileService f, AccessService ac, PlatformTransactionManager tm) {
        users = u; profiles = cp; categories = c; achievements = a; prefs = p; hidden = h; unlocks = un; stats = s; logs = l;
        assignments = wa; bookings = b; events = e; progress = pr; notifications = n; files = f; access = ac;
        newTx = new TransactionTemplate(tm);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- Entradas ----------

    /** Tras confirmar la actividad. Si falla, la actividad ya está guardada y la siguiente consulta lo recupera. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onActivity(ClientActivityEvent e) {
        try {
            evaluate(e.clientId());
        } catch (RuntimeException ex) {
            log.warn("No se pudieron evaluar los logros del cliente {}: {}", e.clientId(), ex.getMessage());
        }
    }

    /** GET /api/my/achievements y /api/clients/{id}/achievements: evalúa (idempotente) y devuelve el resumen. */
    @Transactional
    public ClientAchievementsDto summary(Long clientId) {
        access.assertCanAccessClient(clientId);
        Evaluation ev = evaluate(clientId);
        return toDto(ev);
    }

    // ---------- Evaluación ----------

    record Result(String status, LocalDate unlockedOn, BigDecimal progress, BigDecimal target) {
        static Result noData() { return new Result(NO_DATA, null, null, null); }
        static Result locked(Number progress, Number target) { return new Result(LOCKED, null, dec(progress), dec(target)); }
        static Result unlocked(LocalDate on, Number progress, Number target) { return new Result(UNLOCKED, on, dec(progress), dec(target)); }
    }
    record Evaluation(ClientStats stats, List<AchievementCategory> categories, Map<Long, List<Achievement>> byCategory,
                      Map<Long, Result> results, Map<Long, AchievementUnlock> unlocked) {}

    /** Datos de actividad de un cliente, leídos una vez por evaluación. */
    record Activity(List<ExerciseLog> logs, List<WorkoutAssignment> assignments, List<LocalDateTime> attended,
                    List<ProgressEntry> weights, ClientProfile profile, LocalDate today) {
        TreeSet<LocalDate> activeDays(LocalDate from, LocalDate to) {
            TreeSet<LocalDate> days = new TreeSet<>();
            logs.forEach(l -> days.add(l.getLogDate()));
            assignments.stream().map(WorkoutAssignment::getCompletedDate).filter(Objects::nonNull).forEach(days::add);
            attended.forEach(t -> days.add(t.toLocalDate()));
            return new TreeSet<>(days.subSet(from, true, to, true));
        }
        List<ExerciseLog> logsIn(LocalDate from, LocalDate to) {
            return logs.stream().filter(l -> !l.getLogDate().isBefore(from) && !l.getLogDate().isAfter(to)).toList();
        }
    }

    /** Pasos 1–4. Las evaluaciones del mismo cliente se serializan bloqueando su fila de estadísticas. */
    Evaluation evaluate(Long clientId) {
        ensureStatsRow(clientId);
        ClientStats st = stats.lockById(clientId).orElseThrow();
        AppUser client = users.findById(clientId).orElseThrow();
        Activity act = load(clientId);

        // 1) Estadísticas desde el histórico.
        updateStats(st, act);

        // 2) Progreso de cada logro disponible para el cliente.
        Long pro = professionalOf(client);
        List<AchievementCategory> cats = availableCategories(pro, act.profile());
        Set<Long> hiddenIds = hidden.findByTrainerId(pro).stream().map(AchievementHidden::getAchievementId).collect(Collectors.toSet());
        Map<Long, List<Achievement>> byCat = achievements.findByCategoryIdInOrderByPositionAscIdAsc(cats.stream().map(AchievementCategory::getId).toList())
                .stream().filter(a -> (a.isSystem() || pro.equals(a.getTrainerId())) && !hiddenIds.contains(a.getId()))
                .collect(Collectors.groupingBy(Achievement::getCategoryId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Result> results = new HashMap<>();
        for (List<Achievement> list : byCat.values()) for (Achievement a : list) results.put(a.getId(), evaluate(a, act));

        // 3) y 4) Desbloqueos nuevos (una sola vez) y su aviso.
        Map<Long, AchievementUnlock> done = unlocks.findByClientId(clientId).stream()
                .collect(Collectors.toMap(AchievementUnlock::getAchievementId, x -> x));
        for (List<Achievement> list : byCat.values()) for (Achievement a : list) {
            Result r = results.get(a.getId());
            if (!UNLOCKED.equals(r.status()) || done.containsKey(a.getId())) continue;
            AchievementUnlock u = unlocks.save(new AchievementUnlock(clientId, a.getId(), r.unlockedOn(), a.getPoints()));
            done.put(a.getId(), u);
            notifications.notify(clientId, NotificationService.ACHIEVEMENT_UNLOCKED, "¡Logro conseguido!",
                    "Has conseguido «" + a.getName() + "» (+" + a.getPoints() + " puntos).", "ACHIEVEMENT", a.getId());
        }
        return new Evaluation(st, cats, byCat, results, done);
    }

    /** Crea la fila de estadísticas si no existe, en su propia transacción: si otra petición la crea a la vez, no pasa nada. */
    private void ensureStatsRow(Long clientId) {
        if (stats.existsById(clientId)) return;
        try {
            newTx.executeWithoutResult(s -> stats.saveAndFlush(new ClientStats(clientId)));
        } catch (DataIntegrityViolationException ignored) {
            // Ya la creó otra evaluación concurrente.
        }
    }

    private Activity load(Long clientId) {
        List<LocalDateTime> attended = new ArrayList<>();
        List<AgendaBooking> b = bookings.findByClientIdAndStatus(clientId, "ATTENDED");
        LocalDateTime now = LocalDateTime.now();
        if (!b.isEmpty()) events.findAllById(b.stream().map(AgendaBooking::getEventId).toList()).stream()
                .filter(e -> "SESSION".equals(e.getKind()) && !e.getStartAt().isAfter(now))
                .forEach(e -> attended.add(e.getStartAt()));
        List<ProgressEntry> weights = progress.findByClientIdOrderByEntryDateAsc(clientId).stream().filter(p -> p.getWeightKg() != null).toList();
        return new Activity(logs.findByClientIdOrderByLogDateAscSetNumberAsc(clientId), assignments.findByClientId(clientId), attended,
                weights, profiles.findById(clientId).orElse(null), LocalDate.now());
    }

    private static void updateStats(ClientStats st, Activity act) {
        TreeSet<LocalDate> days = act.activeDays(LocalDate.MIN, act.today());
        int best = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : days) {
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        // Racha actual: la que llega a hoy o a ayer (hoy aún puede entrenar).
        int current = prev != null && !prev.isBefore(act.today().minusDays(1)) ? run : 0;
        long reps = act.logs().stream().mapToLong(ExerciseLog::getReps).sum();
        BigDecimal volume = act.logs().stream().map(l -> TrainingMath.volume(l.getWeightKg(), l.getReps())).reduce(BigDecimal.ZERO, BigDecimal::add);
        int workouts = (int) act.assignments().stream().filter(a -> a.getCompletedDate() != null).count();
        st.update(workouts, days.size(), current, best, act.logs().size(), reps, volume, days.isEmpty() ? null : days.last());
    }

    /** Profesional cuyos logros ve el cliente: su entrenador o, si no tiene, el ADMIN (ADR-007 §4). */
    private Long professionalOf(AppUser client) {
        if (client.getTrainerId() != null) return client.getTrainerId();
        return users.findByRole(Role.ADMIN).stream().map(AppUser::getId).min(Comparator.naturalOrder()).orElse(-1L);
    }

    private List<AchievementCategory> availableCategories(Long pro, ClientProfile profile) {
        Map<Long, String> prefByCat = prefs.findByTrainerId(pro).stream()
                .collect(Collectors.toMap(AchievementCategoryPref::getCategoryId, AchievementCategoryPref::getVisibility));
        Set<Long> clientTags = profile == null ? Set.of() : profile.getTagIds();
        return categories.findVisibleTo(pro).stream().filter(c -> {
            if (c.isSystem()) return !"HIDDEN".equals(prefByCat.getOrDefault(c.getId(), c.getVisibility()));
            if ("ALL".equals(c.getVisibility())) return true;
            return "TAGS".equals(c.getVisibility()) && c.getTagIds().stream().anyMatch(clientTags::contains);
        }).toList();
    }

    // ---------- Condiciones ----------

    /** Ventana de datos que cuentan según la duración del logro (ADR-007 §5). */
    private static LocalDate[] window(Achievement a, Activity act) {
        LocalDate today = act.today();
        return switch (a.getDurationType()) {
            case "DATE_RANGE" -> new LocalDate[]{a.getStartDate(), a.getEndDate().isBefore(today) ? a.getEndDate() : today};
            case "AFTER_ASSIGNMENT" -> {
                LocalDate start = a.getCreatedAt().toLocalDate();
                if (act.profile() != null && act.profile().getActivatedAt().isAfter(start)) start = act.profile().getActivatedAt();
                LocalDate end = start.plusDays(a.getDaysAfter() == null ? 0 : a.getDaysAfter());
                yield new LocalDate[]{start, end.isBefore(today) ? end : today};
            }
            default -> new LocalDate[]{LocalDate.MIN, today};
        };
    }

    Result evaluate(Achievement a, Activity act) {
        LocalDate[] w = window(a, act);
        LocalDate from = w[0], to = w[1];
        if (to.isBefore(from)) return Result.locked(0, null);
        String metric = a.isSystem() ? a.getMetric() : a.getConditionType();
        int n = a.getThreshold() == null ? 0 : a.getThreshold().intValue();
        if (metric == null) return Result.noData();
        return switch (metric) {
            case "FIRST_ACTIVITY" -> first(act.activeDays(from, to), d -> true);
            case "NEW_YEAR" -> first(act.activeDays(from, to), d -> d.getMonthValue() == 1 && d.getDayOfMonth() <= 7);
            case "VALENTINE" -> first(act.activeDays(from, to), d -> d.getMonthValue() == 2 && d.getDayOfMonth() == 14);
            case "BIRTHDAY" -> birthday(act, from, to);
            case "WORKOUTS" -> milestone(act.assignments().stream().map(WorkoutAssignment::getCompletedDate)
                    .filter(d -> d != null && !d.isBefore(from) && !d.isAfter(to)).sorted().toList(), n);
            case "SESSIONS" -> milestone(act.attended().stream().map(LocalDateTime::toLocalDate)
                    .filter(d -> !d.isBefore(from) && !d.isAfter(to)).sorted().toList(), n);
            case "STREAK_DAYS" -> streak(act.activeDays(from, to), n);
            case "STREAK" -> streak(act.activeDays(from, to), a.getDays() == null ? 0 : a.getDays());
            case "PERFECT_DAYS" -> milestone(perfectDays(act, from, to), n);
            case "EARLY_DAYS" -> milestone(act.attended().stream().filter(t -> t.toLocalTime().isBefore(LocalTime.of(8, 0)))
                    .map(LocalDateTime::toLocalDate).filter(d -> !d.isBefore(from) && !d.isAfter(to)).distinct().sorted().toList(), n);
            case "SESSION_VOLUME" -> sessionVolume(act.logsIn(from, to), a.getThreshold());
            case "TOTAL_VOLUME" -> cumulative(act.logsIn(from, to), l -> TrainingMath.volume(l.getWeightKg(), l.getReps()), a.getThreshold());
            case "TOTAL_REPS_ALL" -> cumulative(act.logsIn(from, to), l -> BigDecimal.valueOf(l.getReps()), a.getThreshold());
            case "WEEK_SESSIONS" -> weekSessions(act.activeDays(from, to), n);
            case "WEIGHT_EXERCISE" -> a.getExerciseId() == null || a.getWeightKg() == null ? Result.noData()
                    : weightOn(act.logsIn(from, to).stream().filter(l -> a.getExerciseId().equals(l.getExerciseId())).toList(), a.getWeightKg());
            case "TOTAL_REPS" -> a.getExerciseId() == null || a.getReps() == null ? Result.noData()
                    : cumulative(act.logsIn(from, to).stream().filter(l -> a.getExerciseId().equals(l.getExerciseId())).toList(),
                                 l -> BigDecimal.valueOf(l.getReps()), BigDecimal.valueOf(a.getReps()));
            case "BODY_WEIGHT" -> bodyWeight(act, a.getTargetKg(), from, to);
            case "TIME_WITH_YOU" -> timeWithYou(act, a.getMonths(), from, to);
            // CARDIO, NUTRITION, VIDEOS: no hay datos que permitan comprobarlos (nunca bloqueado ni conseguido sin más).
            default -> Result.noData();
        };
    }

    private static Result first(SortedSet<LocalDate> days, java.util.function.Predicate<LocalDate> match) {
        return days.stream().filter(match).findFirst().map(d -> Result.unlocked(d, 1, 1)).orElse(Result.locked(0, 1));
    }

    /** El N-ésimo de una lista ordenada de fechas. */
    private static Result milestone(List<LocalDate> sorted, int n) {
        if (n <= 0) return Result.noData();
        return sorted.size() >= n ? Result.unlocked(sorted.get(n - 1), n, n) : Result.locked(sorted.size(), n);
    }

    private static Result streak(SortedSet<LocalDate> days, int n) {
        if (n <= 0) return Result.noData();
        int run = 0, best = 0;
        LocalDate prev = null;
        for (LocalDate d : days) {
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            if (run >= n) return Result.unlocked(d, n, n);
            prev = d;
        }
        return Result.locked(best, n);
    }

    /** Días con algo planificado en los que todo se completó ese mismo día (cuenta de menos, nunca de más). */
    private static List<LocalDate> perfectDays(Activity act, LocalDate from, LocalDate to) {
        Map<LocalDate, List<WorkoutAssignment>> byDay = act.assignments().stream()
                .filter(a -> !a.getScheduledDate().isBefore(from) && !a.getScheduledDate().isAfter(to))
                .collect(Collectors.groupingBy(WorkoutAssignment::getScheduledDate, TreeMap::new, Collectors.toList()));
        return byDay.entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(a -> e.getKey().equals(a.getCompletedDate())))
                .map(Map.Entry::getKey).toList();
    }

    private static Result sessionVolume(List<ExerciseLog> logs, BigDecimal target) {
        if (target == null) return Result.noData();
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        for (ExerciseLog l : logs) perDay.merge(l.getLogDate(), TrainingMath.volume(l.getWeightKg(), l.getReps()), BigDecimal::add);
        BigDecimal best = BigDecimal.ZERO;
        for (var e : perDay.entrySet()) {
            if (e.getValue().compareTo(target) >= 0) return Result.unlocked(e.getKey(), target, target);
            best = best.max(e.getValue());
        }
        return Result.locked(best, target);
    }

    private static Result cumulative(List<ExerciseLog> logs, java.util.function.Function<ExerciseLog, BigDecimal> value, BigDecimal target) {
        if (target == null) return Result.noData();
        BigDecimal sum = BigDecimal.ZERO;
        for (ExerciseLog l : logs.stream().sorted(Comparator.comparing(ExerciseLog::getLogDate)).toList()) {
            sum = sum.add(value.apply(l));
            if (sum.compareTo(target) >= 0) return Result.unlocked(l.getLogDate(), target, target);
        }
        return Result.locked(sum, target);
    }

    private static Result weekSessions(SortedSet<LocalDate> days, int n) {
        if (n <= 0) return Result.noData();
        Map<LocalDate, List<LocalDate>> byWeek = new TreeMap<>();
        for (LocalDate d : days) byWeek.computeIfAbsent(d.with(DayOfWeek.MONDAY), k -> new ArrayList<>()).add(d);
        int best = 0;
        for (List<LocalDate> week : byWeek.values()) {
            if (week.size() >= n) return Result.unlocked(week.get(n - 1), n, n);
            best = Math.max(best, week.size());
        }
        return Result.locked(best, n);
    }

    private static Result weightOn(List<ExerciseLog> logs, BigDecimal target) {
        BigDecimal best = BigDecimal.ZERO;
        for (ExerciseLog l : logs.stream().sorted(Comparator.comparing(ExerciseLog::getLogDate)).toList()) {
            if (l.getWeightKg().compareTo(target) >= 0) return Result.unlocked(l.getLogDate(), target, target);
            best = best.max(l.getWeightKg());
        }
        return Result.locked(best, target);
    }

    /** Referencia: peso de la ficha o, si no hay, el primer registro de Progreso (ADR-007). */
    private static Result bodyWeight(Activity act, BigDecimal target, LocalDate from, LocalDate to) {
        if (target == null) return Result.noData();
        BigDecimal ref = act.profile() != null && act.profile().getWeightKg() != null ? act.profile().getWeightKg()
                : act.weights().isEmpty() ? null : act.weights().get(0).getWeightKg();
        List<ProgressEntry> in = act.weights().stream().filter(p -> !p.getEntryDate().isBefore(from) && !p.getEntryDate().isAfter(to)).toList();
        if (ref == null || in.isEmpty()) return Result.noData();
        boolean losing = target.compareTo(ref) < 0;
        for (ProgressEntry p : in) {
            int cmp = p.getWeightKg().compareTo(target);
            if (losing ? cmp <= 0 : cmp >= 0) return Result.unlocked(p.getEntryDate(), target, target);
        }
        // Progreso: cuánto del camino entre la referencia y el objetivo se ha recorrido.
        BigDecimal latest = in.get(in.size() - 1).getWeightKg();
        BigDecimal span = target.subtract(ref).abs();
        BigDecimal done = losing ? ref.subtract(latest) : latest.subtract(ref);
        return Result.locked(done.max(BigDecimal.ZERO).min(span), span);
    }

    private static Result timeWithYou(Activity act, Integer months, LocalDate from, LocalDate to) {
        if (months == null || act.profile() == null) return Result.noData();
        LocalDate start = act.profile().getActivatedAt();
        LocalDate on = start.plusMonths(months);
        if (!on.isAfter(to) && !on.isBefore(from)) return Result.unlocked(on, months, months);
        long elapsed = Math.max(0, Period.between(start, act.today()).toTotalMonths());
        return Result.locked(Math.min(elapsed, months), months);
    }

    private static Result birthday(Activity act, LocalDate from, LocalDate to) {
        ClientProfile p = act.profile();
        if (p == null || p.getBirthDate() == null) return Result.noData();
        LocalDate start = p.getActivatedAt().isAfter(from) ? p.getActivatedAt() : from;
        for (int y = start.getYear(); y <= to.getYear(); y++) {
            // 29 de febrero → 28 en años no bisiestos.
            LocalDate b = p.getBirthDate().withYear(y);
            if (!b.isBefore(start) && !b.isAfter(to)) return Result.unlocked(b, 1, 1);
        }
        return Result.locked(0, 1);
    }

    // ---------- Respuesta ----------

    private ClientAchievementsDto toDto(Evaluation ev) {
        ClientStats s = ev.stats();
        int points = ev.unlocked().values().stream().mapToInt(AchievementUnlock::getPoints).sum();
        int level = 1 + points / XP_PER_LEVEL;
        List<ClientAchievementCategoryDto> cats = new ArrayList<>();
        List<ClientAchievementDto> all = new ArrayList<>();
        for (AchievementCategory c : ev.categories()) {
            List<ClientAchievementDto> list = ev.byCategory().getOrDefault(c.getId(), List.of()).stream()
                    .map(a -> dto(a, ev.results().get(a.getId()), ev.unlocked().get(a.getId()))).toList();
            if (list.isEmpty()) continue;
            all.addAll(list);
            cats.add(new ClientAchievementCategoryDto(c.getId(), c.getName(), c.getIcon(), list));
        }
        List<ClientAchievementDto> almost = all.stream()
                .filter(a -> LOCKED.equals(a.status()) && a.percent() != null && a.percent() > 0)
                .sorted(Comparator.comparing(ClientAchievementDto::percent).reversed()).limit(3).toList();
        int unlockedCount = (int) all.stream().filter(a -> UNLOCKED.equals(a.status())).count();
        return new ClientAchievementsDto(new ClientStatsDto(s.getWorkoutsCompleted(), s.getActiveDays(), s.getCurrentStreak(),
                s.getBestStreak(), s.getTotalSets(), s.getTotalReps(), s.getTotalVolumeKg(), s.getLastActivity()),
                points, level, LEVEL_NAMES.get(Math.min(level, LEVEL_NAMES.size()) - 1), points % XP_PER_LEVEL, XP_PER_LEVEL,
                unlockedCount, all.size(), almost, cats);
    }

    /** Un desbloqueo guardado manda sobre el cálculo de hoy: un logro conseguido no se pierde. */
    private ClientAchievementDto dto(Achievement a, Result r, AchievementUnlock u) {
        String status = u != null ? UNLOCKED : r.status();
        Integer percent = u != null ? Integer.valueOf(100) : percent(r);
        return new ClientAchievementDto(a.getId(), a.getName(), a.getDescription(), a.getGroupLabel(), a.getBadge(),
                files.url(a.getBadgeFileId()), a.getTier(), a.getPoints(), status, u != null ? u.getUnlockedOn() : null,
                u != null && r.progress() == null ? null : r.progress(), r.target(), percent);
    }

    private static Integer percent(Result r) {
        if (r.progress() == null || r.target() == null || r.target().signum() == 0) return null;
        return Math.min(100, r.progress().multiply(BigDecimal.valueOf(100)).divide(r.target(), 0, RoundingMode.DOWN).intValue());
    }

    private static BigDecimal dec(Number n) {
        if (n == null) return null;
        return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    }
}
