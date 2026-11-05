package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.mail.MailService;
import com.physicalchange.repository.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.security.AuthUser;
import com.physicalchange.service.audit.AuditAction;
import com.physicalchange.service.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Programas de la librería asignados a clientes (docs/api-entrenamiento-automatico.md, T248/T250): asignación manual,
 * asignación automática al empezar una suscripción cuyo plan tiene programa, avisos al profesional (campana y email)
 * y la lista "pagados sin entrenamiento" del Inicio.
 *
 * Solo los elementos WORKOUT generan asignaciones (fecha = inicio + (semana-1)·7 + (día-1)); el resto cuenta como skipped.
 */
@Service
public class ProgramAssignmentService {
    private static final Logger log = LoggerFactory.getLogger(ProgramAssignmentService.class);
    static final String AUTO = "AUTO", MANUAL = "MANUAL";
    static final String NO_PROGRAM = "NO_PROGRAM", PROGRAM_FINISHED = "PROGRAM_FINISHED", ASSIGN_FAILED = "ASSIGN_FAILED";
    private static final List<String> CURRENT = List.of("ACTIVE", "PAST_DUE");
    private static final int MAX_PAID_WITHOUT_TRAINING = 50;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LibraryProgramRepository programs;
    private final LibraryProgramItemRepository items;
    private final WorkoutRepository workouts;
    private final WorkoutAssignmentRepository assignments;
    private final ProgramAssignmentRepository records;
    private final ClientSubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final UserRepository users;
    private final ClientDirectory directory;
    private final AccessService access;
    private final NotificationService notifications;
    private final MailService mail;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ProgramAssignmentService(LibraryProgramRepository p, LibraryProgramItemRepository i, WorkoutRepository w,
                                    WorkoutAssignmentRepository a, ProgramAssignmentRepository r, ClientSubscriptionRepository s,
                                    SubscriptionPlanRepository pl, UserRepository u, ClientDirectory d, AccessService ac,
                                    NotificationService n, MailService m, AuditService au, Clock c, PlatformTransactionManager tm) {
        programs = p; items = i; workouts = w; assignments = a; records = r; subscriptions = s; plans = pl; users = u;
        directory = d; access = ac; notifications = n; mail = m; audit = au; clock = c;
        // REQUIRES_NEW: en AFTER_COMMIT la transacción del alta sigue ligada al hilo; con REQUIRED se uniría a ella (ya
        // confirmada) y lo escrito aquí no se guardaría nunca.
        tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- Manual ----------

    @Transactional
    public ProgramAssignmentDto assign(Long clientId, ProgramAssignmentRequest r) {
        access.assertOffersTraining();
        AuthUser me = access.current();
        if (me.role() == Role.COLLABORATOR) access.assertPermission(access.collaborator().isCanAssignRoutines(), "asignar rutinas");
        AppUser client = client(clientId);
        access.assertCanAccessClient(client.getId());
        LibraryProgram program = programs.findById(r.programId()).filter(this::canUse)
                .orElseThrow(() -> new NotFoundException("Programa no encontrado"));
        if (r.startDate().isBefore(LocalDate.now(clock))) throw new BadRequestException("La fecha de inicio no puede ser anterior a hoy");
        ProgramAssignmentDto dto = create(client.getId(), program, r.startDate(), null, MANUAL, me.id());
        audit.record(AuditAction.ROUTINE_ASSIGNED, "PROGRAM_ASSIGNMENT", dto.id(), client.getId(), summary(dto));
        return dto;
    }

    @Transactional(readOnly = true)
    public List<ProgramAssignmentDto> list(Long clientId) {
        AppUser client = client(clientId);
        access.assertCanAccessClient(client.getId());
        return records.findByClientIdOrderByCreatedAtDesc(client.getId()).stream()
                .map(x -> dto(x, (int) assignments.countByProgramAssignmentId(x.getId()), null)).toList();
    }

    // ---------- Automática al empezar una suscripción ----------

    private enum Outcome { ASSIGNED, NO_PROGRAM, FAILED, ALREADY_DONE }
    private record AutoResult(Outcome outcome, ProgramAssignmentDto assignment, String programName) {}

    /**
     * Tras el commit del alta (el pago y la suscripción ya están guardados): la asignación va en su propia transacción y,
     * si falla, se deshace solo ella; el aviso va en otra, para que llegue también cuando la asignación falla.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionStarted(SubscriptionStartedEvent e) {
        AutoResult result;
        try {
            result = tx.execute(st -> autoAssign(e.subscriptionId()));
        } catch (RuntimeException ex) {
            log.warn("No se ha podido asignar el programa de la suscripción {}: {}", e.subscriptionId(), ex.toString());
            result = new AutoResult(Outcome.FAILED, null, programNameOf(e.subscriptionId()));
        }
        if (result == null || result.outcome() == Outcome.ALREADY_DONE) return;
        AutoResult r = result;
        try {
            tx.executeWithoutResult(st -> announce(e.subscriptionId(), r));
        } catch (RuntimeException ex) {
            log.warn("No se ha podido avisar del alta de la suscripción {}: {}", e.subscriptionId(), ex.toString());
        }
    }

    private AutoResult autoAssign(Long subscriptionId) {
        ClientSubscription s = subscriptions.findById(subscriptionId).orElse(null);
        if (s == null || records.existsBySubscriptionId(subscriptionId)) return new AutoResult(Outcome.ALREADY_DONE, null, null);
        SubscriptionPlan plan = plans.findById(s.getPlanId()).orElse(null);
        if (plan == null || plan.getProgramId() == null) return new AutoResult(Outcome.NO_PROGRAM, null, null);
        LibraryProgram program = programs.findById(plan.getProgramId()).filter(p -> p.getTrainerId().equals(plan.getTrainerId())).orElse(null);
        if (program == null) {
            log.warn("Suscripción {}: el programa {} del plan {} ya no es del profesional del plan; no se asigna", s.getId(), plan.getProgramId(), plan.getId());
            return new AutoResult(Outcome.FAILED, null, null);
        }
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        LocalDate start = s.getStartDate() != null && s.getStartDate().isAfter(tomorrow) ? s.getStartDate() : tomorrow;
        try {
            ProgramAssignmentDto dto = create(s.getClientId(), program, start, s.getId(), AUTO, null);
            audit.recordAs(null, null, AuditAction.ROUTINE_ASSIGNED, "PROGRAM_ASSIGNMENT", dto.id(), s.getClientId(), "Automática · " + summary(dto));
            return new AutoResult(Outcome.ASSIGNED, dto, program.getName());
        } catch (ConflictException ex) {   // programa sin workouts: nada creado
            log.warn("Suscripción {}: {}", s.getId(), ex.getMessage());
            return new AutoResult(Outcome.FAILED, null, program.getName());
        }
    }

    private void announce(Long subscriptionId, AutoResult r) {
        ClientSubscription s = subscriptions.findById(subscriptionId).orElse(null);
        if (s == null) return;
        AppUser client = users.findById(s.getClientId()).orElse(null);
        SubscriptionPlan plan = plans.findById(s.getPlanId()).orElse(null);
        if (client == null || plan == null) return;
        Long to = access.trainingResponsibleOf(client);
        if (to == null) to = plan.getTrainerId();
        AppUser pro = users.findById(to).orElse(null);
        if (pro == null) return;
        String title = "Nuevo cliente: " + client.getFullName();
        String body = "Ha contratado " + plan.getName() + ". " + switch (r.outcome()) {
            case ASSIGNED -> "Programa " + r.programName() + " asignado desde el " + r.assignment().startDate().format(DAY) + ".";
            case NO_PROGRAM -> "Aún no tiene entrenamiento: asígnale un programa.";
            default -> "No se ha podido asignar el programa" + (r.programName() == null ? "" : " " + r.programName()) + ": asígnalo a mano.";
        };
        notifications.notify(pro.getId(), NotificationService.SUBSCRIPTION_STARTED, title, body, "CLIENT", client.getId());
        if (pro.getEmail() != null) mail.newSubscriber(pro.getEmail(), pro.getFullName(), title, body);
    }

    // ---------- Pagados sin entrenamiento ----------

    @Transactional(readOnly = true)
    public List<PaidWithoutTrainingDto> paidWithoutTraining() {
        AuthUser me = access.current();
        Collection<Long> mine = switch (me.role()) {
            case ADMIN, TRAINER -> directory.clientsOf(me.id()).stream().map(AppUser::getId).toList();
            case COLLABORATOR -> access.assignedClients();
            default -> throw new ForbiddenException("Solo para profesionales");
        };
        if (mine.isEmpty()) return List.of();
        List<ClientSubscription> subs = subscriptions.findByClientIdInAndStatusInOrderByCreatedAtAsc(mine, CURRENT);
        if (subs.isEmpty()) return List.of();
        Map<Long, ClientSubscription> firstByClient = new LinkedHashMap<>();
        for (ClientSubscription s : subs) firstByClient.putIfAbsent(s.getClientId(), s);
        LocalDate today = LocalDate.now(clock);
        Set<Long> withTraining = new HashSet<>(assignments.clientsWithTrainingFrom(firstByClient.keySet(), today));
        List<Long> pending = firstByClient.keySet().stream().filter(id -> !withTraining.contains(id)).limit(MAX_PAID_WITHOUT_TRAINING).toList();
        if (pending.isEmpty()) return List.of();

        Map<Long, LocalDate> last = new HashMap<>();
        for (Object[] row : assignments.lastScheduledDates(pending)) last.put((Long) row[0], (LocalDate) row[1]);
        List<ClientSubscription> chosen = pending.stream().map(firstByClient::get).toList();
        Set<Long> assignedSubs = records.findBySubscriptionIdIn(chosen.stream().map(ClientSubscription::getId).toList()).stream()
                .map(ProgramAssignment::getSubscriptionId).collect(Collectors.toSet());
        Map<Long, SubscriptionPlan> planById = plans.findAllById(chosen.stream().map(ClientSubscription::getPlanId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SubscriptionPlan::getId, Function.identity()));
        Map<Long, AppUser> userById = users.findAllById(pending).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        return chosen.stream().map(s -> {
            SubscriptionPlan plan = planById.get(s.getPlanId());
            AppUser c = userById.get(s.getClientId());
            String reason = plan != null && plan.getProgramId() != null && !assignedSubs.contains(s.getId()) ? ASSIGN_FAILED
                    : last.containsKey(s.getClientId()) ? PROGRAM_FINISHED : NO_PROGRAM;
            return new PaidWithoutTrainingDto(s.getClientId(), c == null ? "—" : c.getFullName(), plan == null ? "—" : plan.getName(),
                    s.getId(), s.getStatus(), s.getStartDate(), last.get(s.getClientId()), reason);
        }).toList();
    }

    // ---------- Comunes ----------

    /** Crea la asignación de programa y una asignación por cada workout. Programa sin workouts → 409 sin crear nada. */
    private ProgramAssignmentDto create(Long clientId, LibraryProgram program, LocalDate start, Long subscriptionId, String origin, Long by) {
        List<LibraryProgramItem> all = items.findByProgramIdOrderByWeekAscDayAscPositionAsc(program.getId());
        Set<Long> refs = all.stream().filter(i -> "WORKOUT".equals(i.getKind()) && i.getRefId() != null)
                .map(LibraryProgramItem::getRefId).collect(Collectors.toSet());
        Set<Long> usable = workouts.findAllById(refs).stream().filter(w -> w.getTrainerId().equals(program.getTrainerId()))
                .map(Workout::getId).collect(Collectors.toSet());
        List<LibraryProgramItem> toAssign = all.stream()
                .filter(i -> "WORKOUT".equals(i.getKind()) && i.getRefId() != null && usable.contains(i.getRefId())).toList();
        if (toAssign.isEmpty()) throw new ConflictException("El programa no tiene workouts que asignar");

        ProgramAssignment pa = new ProgramAssignment();
        pa.setClientId(clientId);
        pa.setProgramId(program.getId());
        pa.setProgramName(program.getName());
        pa.setStartDate(start);
        pa.setEndDate(start.plusDays(Math.max(1, program.getWeeks()) * 7L - 1));
        pa.setSubscriptionId(subscriptionId);
        pa.setOrigin(origin);
        pa.setCreatedBy(by);
        pa = records.save(pa);
        for (LibraryProgramItem i : toAssign) {
            WorkoutAssignment a = new WorkoutAssignment();
            a.setWorkoutId(i.getRefId());
            a.setClientId(clientId);
            a.setScheduledDate(start.plusDays((i.getWeek() - 1) * 7L + (i.getDay() - 1)));
            a.setProgramAssignmentId(pa.getId());
            assignments.save(a);
        }
        return dto(pa, toAssign.size(), all.size() - toAssign.size());
    }

    /** Programa propio del espacio de trabajo (o cualquiera para el ADMIN). Lo ajeno se trata como inexistente (404). */
    private boolean canUse(LibraryProgram p) {
        try {
            access.assertOwnerOrAdmin(p.getTrainerId());
            return true;
        } catch (ForbiddenException e) {
            return false;
        }
    }

    private AppUser client(Long clientId) {
        return users.findById(clientId).filter(u -> u.getRole() == Role.CLIENT).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
    }

    private String programNameOf(Long subscriptionId) {
        try {
            return tx.execute(st -> subscriptions.findById(subscriptionId).flatMap(s -> plans.findById(s.getPlanId()))
                    .map(SubscriptionPlan::getProgramId).flatMap(programs::findById).map(LibraryProgram::getName).orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String summary(ProgramAssignmentDto d) {
        return "Programa " + d.programName() + " · " + d.workouts() + " workouts desde " + d.startDate();
    }

    private static ProgramAssignmentDto dto(ProgramAssignment x, int workoutCount, Integer skipped) {
        return new ProgramAssignmentDto(x.getId(), x.getProgramId(), x.getProgramName(), x.getClientId(), x.getStartDate(), x.getEndDate(),
                workoutCount, skipped, x.getOrigin(), x.getSubscriptionId(), x.getCreatedAt());
    }
}
