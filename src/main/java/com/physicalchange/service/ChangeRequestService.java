package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Solicitudes de cambio del cliente sobre su plan (ADR-012 §1–§3, docs/api-solicitudes-logros.md).
 * Crear una solicitud nunca toca el plan. Solo accept(), del profesional responsable o del ADMIN, lo modifica,
 * en la misma transacción que el cambio de estado, el historial y el aviso al cliente.
 */
@Service
public class ChangeRequestService {
    static final String TRAINING = "TRAINING", NUTRITION = "NUTRITION";
    static final String PENDING = "PENDING", ACCEPTED = "ACCEPTED", REJECTED = "REJECTED";
    private static final String REF = "CHANGE_REQUEST";

    private final ChangeRequestRepository requests;
    private final ChangeRequestEventRepository events;
    private final WorkoutAssignmentRepository assignments;
    private final WorkoutRepository workouts;
    private final WorkoutItemRepository items;
    private final ExerciseRepository exercises;
    private final ExerciseLogRepository logs;
    private final NutritionPlanRepository plans;
    private final NutritionMealRepository meals;
    private final FoodRepository foods;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AccessService access;

    public ChangeRequestService(ChangeRequestRepository rq, ChangeRequestEventRepository ev, WorkoutAssignmentRepository a,
                                WorkoutRepository w, WorkoutItemRepository i, ExerciseRepository e, ExerciseLogRepository l,
                                NutritionPlanRepository p, NutritionMealRepository m, FoodRepository f, UserRepository u,
                                NotificationService n, AccessService ac) {
        requests = rq; events = ev; assignments = a; workouts = w; items = i; exercises = e; logs = l;
        plans = p; meals = m; foods = f; users = u; notifications = n; access = ac;
    }

    // ---------- Crear (solo el cliente, sobre lo suyo) ----------

    @Transactional
    public ChangeRequestDto createTraining(TrainingChangeRequest r) {
        AppUser client = currentClient();
        WorkoutAssignment a = assignments.findById(r.assignmentId()).orElseThrow(() -> new NotFoundException("Sesión no encontrada"));
        if (!a.getClientId().equals(client.getId())) throw new ForbiddenException("La sesión no es tuya");
        if (a.getCompletedDate() != null) throw new ConflictException("La sesión ya está completada");
        Workout w = workouts.findById(a.getWorkoutId()).orElseThrow(() -> new NotFoundException("Rutina no encontrada"));

        ChangeRequest c = new ChangeRequest();
        c.setKind(TRAINING);
        c.setAction(r.action());
        c.setAssignmentId(a.getId());
        c.setWorkoutId(w.getId());
        if ("ADD_EXERCISE".equals(r.action())) {
            if (r.exerciseId() == null || r.sets() == null || r.reps() == null || r.weightKg() == null)
                throw new BadRequestException("Indica el ejercicio, las series, las repeticiones y el peso");
            c.setExerciseId(exerciseFor(w, r.exerciseId()).getId());
            c.setSets(r.sets()); c.setReps(r.reps()); c.setWeightKg(r.weightKg()); c.setRestSeconds(r.restSeconds());
            c.setPendingKey("T:" + w.getId() + ":ADD:" + c.getExerciseId());
        } else {
            WorkoutItem item = itemOf(w, r.workoutItemId());
            c.setWorkoutItemId(item.getId());
            c.setTargetPosition(item.getPosition());
            c.setTargetExerciseId(item.getExerciseId());
            switch (r.action()) {
                case "REPLACE_EXERCISE" -> {
                    if (r.exerciseId() == null) throw new BadRequestException("Indica el ejercicio que propones");
                    if (r.exerciseId().equals(item.getExerciseId())) throw new BadRequestException("Propón un ejercicio distinto");
                    c.setExerciseId(exerciseFor(w, r.exerciseId()).getId());
                }
                case "MODIFY_EXERCISE" -> {
                    if (r.sets() == null && r.reps() == null && r.weightKg() == null && r.restSeconds() == null)
                        throw new BadRequestException("Indica qué quieres cambiar: series, repeticiones, peso o descanso");
                    c.setSets(r.sets()); c.setReps(r.reps()); c.setWeightKg(r.weightKg()); c.setRestSeconds(r.restSeconds());
                }
                default -> { /* REMOVE_EXERCISE: basta con el ejercicio afectado */ }
            }
            // Una sola solicitud pendiente por ejercicio de la rutina.
            c.setPendingKey("T:" + w.getId() + ":ITEM:" + item.getPosition());
        }
        return create(c, client, professionalOf(client), r.reason(), "en " + w.getName());
    }

    @Transactional
    public ChangeRequestDto createNutrition(NutritionChangeRequest r) {
        AppUser client = currentClient();
        NutritionPlan p = plans.findById(r.planId()).orElseThrow(() -> new NotFoundException("Plan no encontrado"));
        if (!p.getClientId().equals(client.getId())) throw new ForbiddenException("El plan no es tuyo");
        if (!isCurrent(p)) throw new ConflictException("Solo puedes pedir cambios sobre tu plan vigente");

        ChangeRequest c = new ChangeRequest();
        c.setKind(NUTRITION);
        c.setAction(r.action());
        c.setPlanId(p.getId());
        c.setPlanVersion(p.getVersion());
        if ("CHANGE_TARGETS".equals(r.action())) {
            if (r.kcal() == null && r.proteinG() == null && r.carbsG() == null && r.fatG() == null)
                throw new BadRequestException("Indica al menos un objetivo: calorías, proteínas, hidratos o grasas");
            c.setKcal(r.kcal()); c.setProteinG(r.proteinG()); c.setCarbsG(r.carbsG()); c.setFatG(r.fatG());
            c.setPendingKey("N:" + p.getId() + ":TARGETS");
        } else {
            if (r.mealId() == null || r.foodId() == null || r.grams() == null) throw new BadRequestException("Indica la comida, el alimento y los gramos");
            NutritionMeal m = meals.findById(r.mealId()).filter(x -> x.getPlanId().equals(p.getId()))
                    .orElseThrow(() -> new BadRequestException("La comida no es de este plan"));
            foods.findById(r.foodId()).orElseThrow(() -> new BadRequestException("Alimento no válido"));
            c.setMealId(m.getId()); c.setFoodId(r.foodId()); c.setGrams(r.grams());
            c.setPendingKey("N:" + p.getId() + ":MEAL:" + m.getId());
        }
        AppUser pro = users.findById(p.getTrainerId()).orElseThrow(() -> new NotFoundException("Profesional no encontrado"));
        return create(c, client, pro, r.reason(), "en el plan " + p.getName());
    }

    private ChangeRequestDto create(ChangeRequest c, AppUser client, AppUser pro, String reason, String where) {
        if (requests.existsByClientIdAndPendingKey(client.getId(), c.getPendingKey()))
            throw new ConflictException("Ya tienes una solicitud pendiente igual");
        c.setClientId(client.getId());
        c.setProfessionalId(pro.getId());
        c.setReason(blankToNull(reason));
        try {
            c = requests.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            // Dos envíos simultáneos: la restricción única (cliente, clave) deja pasar solo uno.
            throw new ConflictException("Ya tienes una solicitud pendiente igual");
        }
        events.save(new ChangeRequestEvent(c.getId(), client.getId(), "CREATED", c.getReason()));
        notifications.notify(pro.getId(), NotificationService.CHANGE_REQUEST_CREATED, "Nueva solicitud de cambio",
                client.getFullName() + " pide " + describe(c) + " " + where + ".", REF, c.getId());
        return dtos(List.of(c)).get(0);
    }

    // ---------- Consultar ----------

    @Transactional(readOnly = true)
    public List<ChangeRequestDto> mine(String status) {
        return dtos(requests.findByClient(currentClient().getId(), blankToNull(status)));
    }

    /** Bandeja del profesional: las que le corresponden. ADMIN: todas. */
    @Transactional(readOnly = true)
    public List<ChangeRequestDto> inbox(String status) {
        AuthUser me = access.current();
        return dtos(me.role() == Role.ADMIN ? requests.findAllByStatus(blankToNull(status))
                                            : requests.findByProfessional(me.id(), blankToNull(status)));
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestDto> forClient(Long clientId, String status) {
        access.assertCanAccessClient(clientId);
        return dtos(requests.findByClient(clientId, blankToNull(status)));
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestEventDto> history(Long id) {
        ChangeRequest c = requests.findById(id).orElseThrow(() -> new NotFoundException("Solicitud no encontrada"));
        AuthUser me = access.current();
        if (!me.id().equals(c.getClientId()) && !me.id().equals(c.getProfessionalId())) access.assertCanAccessClient(c.getClientId());
        List<ChangeRequestEvent> list = events.findByRequestIdOrderByAtAscIdAsc(id);
        Map<Long, String> names = names(list.stream().map(ChangeRequestEvent::getActorId).toList());
        return list.stream().map(e -> new ChangeRequestEventDto(e.getAt(), e.getActorId(), names.get(e.getActorId()), e.getAction(), e.getNote())).toList();
    }

    // ---------- Decidir (profesional responsable o ADMIN) ----------

    @Transactional
    public ChangeRequestDto accept(Long id, DecisionRequest d) {
        ChangeRequest c = decidable(id);
        String detail;
        if (TRAINING.equals(c.getKind())) {
            detail = applyTraining(c);
        } else {
            detail = applyNutrition(c);
        }
        Long me = access.current().id();
        String note = blankToNull(d == null ? null : d.note());
        c.decide(ACCEPTED, me, note);
        events.save(new ChangeRequestEvent(c.getId(), me, "ACCEPTED", note == null ? detail : detail + " · " + note));
        notifications.notify(c.getClientId(), NotificationService.CHANGE_REQUEST_ACCEPTED, "Solicitud aceptada",
                "Tu profesional ha aceptado " + describe(c) + ". Tu plan ya está actualizado." + (note == null ? "" : " Nota: " + note), REF, c.getId());
        return dtos(List.of(c)).get(0);
    }

    /** Rechazar nunca toca el plan. */
    @Transactional
    public ChangeRequestDto reject(Long id, DecisionRequest d) {
        ChangeRequest c = decidable(id);
        Long me = access.current().id();
        String note = blankToNull(d == null ? null : d.note());
        c.decide(REJECTED, me, note);
        events.save(new ChangeRequestEvent(c.getId(), me, "REJECTED", note));
        notifications.notify(c.getClientId(), NotificationService.CHANGE_REQUEST_REJECTED, "Solicitud rechazada",
                "Tu profesional ha rechazado " + describe(c) + ". Tu plan sigue igual." + (note == null ? "" : " Nota: " + note), REF, c.getId());
        return dtos(List.of(c)).get(0);
    }

    /** Bloquea la solicitud y comprueba quién decide y que siga pendiente. */
    private ChangeRequest decidable(Long id) {
        ChangeRequest c = requests.lockById(id).orElseThrow(() -> new NotFoundException("Solicitud no encontrada"));
        AuthUser me = access.current();
        if (me.id().equals(c.getClientId())) throw new ForbiddenException("No puedes decidir sobre tus propias solicitudes");
        if (me.role() != Role.ADMIN) {
            if (me.role() != Role.TRAINER || !me.id().equals(c.getProfessionalId()))
                throw new ForbiddenException("Solo el profesional responsable puede decidir esta solicitud");
            access.assertCanAccessClient(c.getClientId());
        }
        if (!c.isPending()) throw new ConflictException("La solicitud ya está " + (ACCEPTED.equals(c.getStatus()) ? "aceptada" : "rechazada"));
        return c;
    }

    // ---------- Aplicar un cambio aceptado ----------

    /**
     * Aplica el cambio a la rutina de las sesiones pendientes del cliente. Si la rutina la usan otros clientes
     * (o sesiones ya completadas), se crea una copia personal para no alterar sus planes ni el historial.
     */
    private String applyTraining(ChangeRequest c) {
        WorkoutAssignment a = c.getAssignmentId() == null ? null : assignments.findById(c.getAssignmentId()).orElse(null);
        if (a == null) throw new ConflictException("La sesión de la solicitud ya no existe; recházala");
        Workout w = workouts.findById(a.getWorkoutId()).orElseThrow(() -> new ConflictException("La rutina ya no existe; recházala"));
        List<WorkoutAssignment> pending = assignments.findByClientIdAndWorkoutIdAndCompletedDateIsNull(c.getClientId(), w.getId());
        if (pending.isEmpty()) throw new ConflictException("El cliente ya no tiene sesiones pendientes con esta rutina; recházala");

        // El ejercicio afectado se busca en la versión vigente por la posición y el ejercicio que tenía al pedirlo.
        WorkoutItem target = null;
        if (!"ADD_EXERCISE".equals(c.getAction())) {
            target = items.findByWorkoutIdOrderByPositionAsc(w.getId()).stream()
                    .filter(i -> i.getPosition().equals(c.getTargetPosition()) && i.getExerciseId().equals(c.getTargetExerciseId()))
                    .findFirst().orElseThrow(() -> new ConflictException("El ejercicio ya no está en la rutina vigente; rechaza la solicitud"));
        }
        if (c.getExerciseId() == null && ("ADD_EXERCISE".equals(c.getAction()) || "REPLACE_EXERCISE".equals(c.getAction())))
            throw new ConflictException("El ejercicio propuesto ya no existe; rechaza la solicitud");

        boolean shared = assignments.countByWorkoutId(w.getId()) > pending.size();
        if (shared) {
            Map<Long, WorkoutItem> copies = new HashMap<>();
            w = copyFor(w, c.getClientId(), copies);
            Set<Long> pendingIds = pending.stream().map(WorkoutAssignment::getId).collect(Collectors.toSet());
            for (WorkoutAssignment p : pending) p.setWorkoutId(w.getId());
            // Las series ya anotadas en esas sesiones pasan a apuntar a los ejercicios de la copia.
            for (Long aid : pendingIds) for (ExerciseLog l : logs.findByAssignmentId(aid)) {
                WorkoutItem copy = l.getWorkoutItemId() == null ? null : copies.get(l.getWorkoutItemId());
                if (copy != null) l.setWorkoutItemId(copy.getId());
            }
            if (target != null) target = copies.get(target.getId());
        }

        switch (c.getAction()) {
            case "ADD_EXERCISE" -> {
                WorkoutItem i = new WorkoutItem();
                i.setWorkoutId(w.getId());
                i.setPosition((int) items.countByWorkoutId(w.getId()) + 1);
                i.setExerciseId(c.getExerciseId());
                i.setSets(c.getSets()); i.setReps(c.getReps()); i.setWeightKg(c.getWeightKg()); i.setRestSeconds(c.getRestSeconds());
                items.save(i);
            }
            case "REPLACE_EXERCISE" -> target.setExerciseId(c.getExerciseId());
            case "MODIFY_EXERCISE" -> {
                if (c.getSets() != null) target.setSets(c.getSets());
                if (c.getReps() != null) target.setReps(c.getReps());
                if (c.getWeightKg() != null) target.setWeightKg(c.getWeightKg());
                if (c.getRestSeconds() != null) target.setRestSeconds(c.getRestSeconds());
            }
            case "REMOVE_EXERCISE" -> {
                items.delete(target);
                items.flush();
                int pos = 1;
                for (WorkoutItem i : items.findByWorkoutIdOrderByPositionAsc(w.getId())) i.setPosition(pos++);
            }
            default -> throw new BadRequestException("Tipo de solicitud no válido");
        }
        c.setAppliedWorkoutId(w.getId());
        c.setCopiedWorkout(shared);
        return "Aplicado en la rutina \"" + w.getName() + "\"" + (shared ? " (copia personal del cliente)" : "");
    }

    /** Copia la rutina y sus ejercicios para un cliente; devuelve la copia y el mapa original → copia. */
    private Workout copyFor(Workout original, Long clientId, Map<Long, WorkoutItem> copies) {
        String clientName = users.findById(clientId).map(AppUser::getFullName).orElse("cliente");
        String name = original.getName() + " · " + clientName;
        Workout w = new Workout();
        w.setTrainerId(original.getTrainerId());
        w.setName(name.length() > 120 ? name.substring(0, 120) : name);
        w.setNotes(original.getNotes());
        w = workouts.save(w);
        for (WorkoutItem i : items.findByWorkoutIdOrderByPositionAsc(original.getId())) {
            WorkoutItem n = new WorkoutItem();
            n.setWorkoutId(w.getId());
            n.setExerciseId(i.getExerciseId()); n.setPosition(i.getPosition()); n.setSets(i.getSets()); n.setReps(i.getReps());
            n.setWeightKg(i.getWeightKg()); n.setRestSeconds(i.getRestSeconds()); n.setNotes(i.getNotes());
            copies.put(i.getId(), items.save(n));
        }
        return w;
    }

    private String applyNutrition(ChangeRequest c) {
        NutritionPlan p = c.getPlanId() == null ? null : plans.findById(c.getPlanId()).orElse(null);
        if (p == null) throw new ConflictException("El plan ya no existe; rechaza la solicitud");
        if ("CHANGE_TARGETS".equals(c.getAction())) {
            if (c.getKcal() != null) p.setDailyKcal(c.getKcal());
            if (c.getProteinG() != null) p.setTargetProteinG(c.getProteinG());
            if (c.getCarbsG() != null) p.setTargetCarbsG(c.getCarbsG());
            if (c.getFatG() != null) p.setTargetFatG(c.getFatG());
        } else {
            NutritionMeal m = c.getMealId() == null ? null : meals.findById(c.getMealId()).filter(x -> x.getPlanId().equals(p.getId())).orElse(null);
            if (m == null) throw new ConflictException("La comida ya no está en el plan; rechaza la solicitud");
            if (c.getFoodId() == null || foods.findById(c.getFoodId()).isEmpty()) throw new ConflictException("El alimento propuesto ya no existe; rechaza la solicitud");
            m.setFoodId(c.getFoodId());
            m.setGrams(c.getGrams());
        }
        p.touch();
        return "Aplicado en el plan \"" + p.getName() + "\" (versión " + p.getVersion() + ")";
    }

    // ---------- Ayudas ----------

    private AppUser currentClient() {
        AuthUser me = access.current();
        if (me.role() != Role.CLIENT) throw new ForbiddenException("Solo un cliente puede pedir cambios en su plan");
        return users.findById(me.id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    /** Su entrenador; si no tiene (alta del admin, ADR-007 §4), el administrador más antiguo. */
    private AppUser professionalOf(AppUser client) {
        if (client.getTrainerId() != null) return users.findById(client.getTrainerId()).orElseThrow(() -> new NotFoundException("Entrenador no encontrado"));
        return users.findByRole(Role.ADMIN).stream().min(Comparator.comparing(AppUser::getId))
                .orElseThrow(() -> new ConflictException("No hay ningún profesional que pueda revisar la solicitud"));
    }

    private WorkoutItem itemOf(Workout w, Long itemId) {
        if (itemId == null) throw new BadRequestException("Indica el ejercicio de la rutina");
        return items.findById(itemId).filter(i -> i.getWorkoutId().equals(w.getId()))
                .orElseThrow(() -> new BadRequestException("El ejercicio no está en esta rutina"));
    }

    /** El ejercicio propuesto tiene que poder usarlo el dueño de la rutina: global o suyo. */
    private Exercise exerciseFor(Workout w, Long exerciseId) {
        Exercise e = exercises.findById(exerciseId).orElseThrow(() -> new BadRequestException("Ejercicio no válido"));
        if (e.getOwnerId() != null && !e.getOwnerId().equals(w.getTrainerId())) throw new BadRequestException("Ejercicio no válido");
        return e;
    }

    private boolean isCurrent(NutritionPlan p) {
        return plans.findByClientIdOrderByIdDesc(p.getClientId()).stream().findFirst().map(x -> x.getId().equals(p.getId())).orElse(false);
    }

    static String describe(ChangeRequest c) {
        return switch (c.getAction()) {
            case "ADD_EXERCISE" -> "añadir un ejercicio";
            case "REPLACE_EXERCISE" -> "sustituir un ejercicio";
            case "MODIFY_EXERCISE" -> "modificar un ejercicio";
            case "REMOVE_EXERCISE" -> "quitar un ejercicio";
            case "CHANGE_TARGETS" -> "cambiar los objetivos nutricionales";
            case "REPLACE_FOOD" -> "cambiar un alimento";
            default -> "un cambio";
        };
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private Map<Long, String> names(Collection<Long> ids) {
        Set<Long> set = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return set.isEmpty() ? new HashMap<>() : users.findAllById(set).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }

    private List<ChangeRequestDto> dtos(List<ChangeRequest> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, String> people = names(list.stream().flatMap(c -> java.util.stream.Stream.of(c.getClientId(), c.getProfessionalId())).toList());
        Map<Long, String> workoutNames = byId(list, ChangeRequest::getWorkoutId, ids -> workouts.findAllById(ids).stream()
                .collect(Collectors.toMap(Workout::getId, Workout::getName)));
        Map<Long, String> exerciseNames = byId(list, null, ids -> exercises.findAllById(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, Exercise::getName)));
        Map<Long, NutritionPlan> planById = byId(list, ChangeRequest::getPlanId, ids -> plans.findAllById(ids).stream()
                .collect(Collectors.toMap(NutritionPlan::getId, Function.identity())));
        Map<Long, String> foodNames = byId(list, ChangeRequest::getFoodId, ids -> foods.findAllById(ids).stream()
                .collect(Collectors.toMap(Food::getId, Food::getName)));
        return list.stream().map(c -> {
            NutritionPlan p = c.getPlanId() == null ? null : planById.get(c.getPlanId());
            boolean outdated = p != null && c.isPending() && c.getPlanVersion() != null && p.getVersion() != c.getPlanVersion();
            return new ChangeRequestDto(c.getId(), c.getKind(), c.getAction(), c.getStatus(), c.getClientId(), people.get(c.getClientId()),
                    c.getProfessionalId(), people.get(c.getProfessionalId()), c.getAssignmentId(), c.getWorkoutId(),
                    workoutNames.get(c.getWorkoutId()), c.getWorkoutItemId(), exerciseNames.get(c.getTargetExerciseId()),
                    c.getExerciseId(), exerciseNames.get(c.getExerciseId()), c.getSets(), c.getReps(), c.getWeightKg(),
                    c.getRestSeconds(), c.getPlanId(), p == null ? null : p.getName(), c.getPlanVersion(), outdated,
                    c.getMealId(), c.getFoodId(), foodNames.get(c.getFoodId()), c.getGrams(), c.getKcal(), c.getProteinG(),
                    c.getCarbsG(), c.getFatG(), c.getReason(), c.getDecisionNote(), c.getCreatedAt(), c.getDecidedAt(),
                    c.getDecidedBy(), c.getAppliedWorkoutId(), c.isCopiedWorkout());
        }).toList();
    }

    /** Carga en bloque lo referenciado por las solicitudes. key null = ejercicios (propuesto y afectado). */
    private <T> Map<Long, T> byId(List<ChangeRequest> list, Function<ChangeRequest, Long> key, Function<Set<Long>, Map<Long, T>> load) {
        Set<Long> ids = new HashSet<>();
        for (ChangeRequest c : list) {
            if (key == null) { ids.add(c.getExerciseId()); ids.add(c.getTargetExerciseId()); }
            else ids.add(key.apply(c));
        }
        ids.remove(null);
        // HashMap: get(null) devuelve null (Map.of() lanzaría NullPointerException con las referencias vacías).
        return ids.isEmpty() ? new HashMap<>() : new HashMap<>(load.apply(ids));
    }
}
