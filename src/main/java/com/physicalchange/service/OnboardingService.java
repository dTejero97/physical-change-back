package com.physicalchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Asistente de bienvenida del cliente (docs/api-onboarding.md, docs/fase6-onboarding.md).
 * Guardado parcial por paso: solo cambian los campos presentes en el JSON. Los datos básicos van a la ficha
 * (client_profile) y el peso, además, al registro de progreso de hoy. Los datos de salud solo se guardan
 * con consentimiento y retirarlo los borra.
 */
@Service
public class OnboardingService {
    static final String INITIAL_NOTE = "Cuestionario inicial";
    private static final String REF = "CLIENT";

    private static final List<String> GENDERS = List.of("MALE", "FEMALE", "OTHER");
    private static final List<String> GOALS = List.of("FAT_LOSS", "MUSCLE_GAIN", "PERFORMANCE", "HEALTH");
    private static final List<String> EXPERIENCE = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final List<String> COMMITMENT = List.of("MODERATE", "CONSISTENT", "ALL_IN");
    private static final List<String> TRAINING_STYLES = List.of("HYPERTROPHY", "STRENGTH", "FAT_LOSS", "PERFORMANCE", "GENERAL_HEALTH");
    private static final List<String> CARDIO = List.of("MINIMAL", "MODERATE", "AS_NEEDED");
    private static final List<String> DIET_STYLES = List.of("FLEXIBLE", "STRICT", "CHAOTIC");
    private static final List<String> LIKED_EXERCISES = List.of("COMPOUND", "MACHINES", "FREE_WEIGHTS", "FUNCTIONAL", "UNSURE");
    private static final List<String> AVOID = List.of("CARDIO", "LEGS", "CORE", "NOTHING");
    private static final List<String> INJURIES = List.of("KNEE", "LOWER_BACK", "SHOULDER", "ELBOW_WRIST", "NECK", "HIP", "NONE");
    private static final List<String> CONDITIONS = List.of("HYPOTHYROIDISM", "PCOS_INSULIN", "CELIAC", "DIABETES",
            "HIGH_CHOLESTEROL", "HIGH_BLOOD_PRESSURE", "REFLUX");
    private static final List<String> SPECIAL_DIETS = List.of("NONE", "VEGETARIAN", "VEGAN");
    private static final List<String> COOKS = List.of("MOSTLY", "RARELY");

    private final ClientOnboardingRepository repo;
    private final ClientProfileRepository profiles;
    private final ClientProfileService profileService;
    private final ProgressService progress;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AccessService access;

    public OnboardingService(ClientOnboardingRepository r, ClientProfileRepository p, ClientProfileService ps, ProgressService pr,
                             UserRepository u, NotificationService n, AccessService a) {
        repo = r; profiles = p; profileService = ps; progress = pr; users = u; notifications = n; access = a;
    }

    // ---------- Cliente ----------

    @Transactional(readOnly = true)
    public OnboardingDto mine() {
        return dto(currentClient().getId());
    }

    /** PUT parcial: valida todo lo presente y lo aplica en una sola transacción (un 400 no deja nada a medias). */
    @Transactional
    public OnboardingDto update(JsonNode body) {
        if (body == null || !body.isObject()) throw new BadRequestException("Petición no válida");
        Long clientId = currentClient().getId();
        ClientOnboarding o = repo.lockById(clientId).orElseGet(() -> new ClientOnboarding(clientId));

        // Paso 1 · datos básicos, en la ficha del cliente.
        BigDecimal newWeight = null;
        if (has(body, "birthDate", "weightKg", "heightCm", "gender")) {
            ClientProfile p = profileService.ensure(clientId);
            if (body.has("birthDate")) p.setBirthDate(birthDate(body.get("birthDate")));
            if (body.has("weightKg")) { newWeight = weight(body.get("weightKg")); p.setWeightKg(newWeight); }
            if (body.has("heightCm")) p.setHeightCm(integer(body.get("heightCm"), "heightCm", 50, 260));
            if (body.has("gender")) {
                String g = one(body.get("gender"), "gender", GENDERS);
                p.setGender(g == null ? "NONE" : g);
            }
        }
        if (body.has("goals")) o.setGoals(many(body.get("goals"), "goals", GOALS, null));

        // Paso 2
        if (body.has("experience")) o.setExperience(one(body.get("experience"), "experience", EXPERIENCE));
        if (body.has("commitment")) o.setCommitment(one(body.get("commitment"), "commitment", COMMITMENT));
        if (body.has("techniqueLevel")) o.setTechniqueLevel(integer(body.get("techniqueLevel"), "techniqueLevel", 1, 5));

        // Paso 3
        if (body.has("trainingStyle")) o.setTrainingStyle(one(body.get("trainingStyle"), "trainingStyle", TRAINING_STYLES));
        if (body.has("cardio")) o.setCardio(one(body.get("cardio"), "cardio", CARDIO));
        if (body.has("dietStyle")) o.setDietStyle(one(body.get("dietStyle"), "dietStyle", DIET_STYLES));
        if (body.has("mealsPerDay")) o.setMealsPerDay(integer(body.get("mealsPerDay"), "mealsPerDay", 1, 8));
        if (body.has("likedExercises")) o.setLikedExercises(many(body.get("likedExercises"), "likedExercises", LIKED_EXERCISES, "UNSURE"));
        if (body.has("avoid")) o.setAvoid(many(body.get("avoid"), "avoid", AVOID, "NOTHING"));

        // Paso 4 · consentimiento primero: decide si los datos de salud de esta misma petición se guardan.
        if (body.has("healthConsent")) {
            Boolean consent = bool(body.get("healthConsent"), "healthConsent");
            if (Boolean.TRUE.equals(consent)) {
                if (!Boolean.TRUE.equals(o.getHealthConsent())) o.setHealthConsentAt(now());
            } else {
                o.clearHealthData();                 // retirado (o nunca dado): no queda ningún dato de salud
                o.setHealthConsentAt(null);
            }
            o.setHealthConsent(consent);
        }
        // Sin consentimiento vigente, los datos de salud que lleguen se ignoran.
        if (Boolean.TRUE.equals(o.getHealthConsent())) {
            if (body.has("injuries")) o.setInjuries(many(body.get("injuries"), "injuries", INJURIES, "NONE"));
            if (body.has("injuriesOther")) o.setInjuriesOther(text(body.get("injuriesOther"), "injuriesOther", 200));
            if (body.has("medicalNotes")) o.setMedicalNotes(text(body.get("medicalNotes"), "medicalNotes", 1000));
            if (body.has("conditions")) o.setConditions(many(body.get("conditions"), "conditions", CONDITIONS, null));
            if (body.has("foodAllergy")) o.setFoodAllergy(bool(body.get("foodAllergy"), "foodAllergy"));
            if (body.has("allergyDetails")) o.setAllergyDetails(text(body.get("allergyDetails"), "allergyDetails", 300));
        }

        // Paso 4 · hábitos
        if (body.has("specialDiet")) o.setSpecialDiet(one(body.get("specialDiet"), "specialDiet", SPECIAL_DIETS));
        if (body.has("mustKeep")) o.setMustKeep(text(body.get("mustKeep"), "mustKeep", 500));
        if (body.has("dislikedFoods")) o.setDislikedFoods(text(body.get("dislikedFoods"), "dislikedFoods", 500));
        if (body.has("cooks")) o.setCooks(one(body.get("cooks"), "cooks", COOKS));

        o.touch();
        repo.save(o);
        // El peso del cuestionario es el punto de partida de Evolución: un registro por día, sin duplicar al repetir.
        if (newWeight != null) progress.recordTodayWeight(clientId, newWeight, INITIAL_NOTE);
        return dto(clientId);
    }

    /** Marca el asistente como terminado. Solo la primera vez avisa a sus responsables (uno por persona). */
    @Transactional
    public OnboardingDto complete() {
        AppUser client = currentClient();
        ClientOnboarding o = repo.lockById(client.getId()).orElseGet(() -> repo.saveAndFlush(new ClientOnboarding(client.getId())));
        if (o.getCompletedAt() == null) {
            o.setCompletedAt(now());
            o.touch();
            Set<Long> responsibles = new LinkedHashSet<>();
            Long training = access.trainingResponsibleOf(client), nutrition = access.nutritionResponsibleOf(client);
            if (training != null) responsibles.add(training);
            if (nutrition != null) responsibles.add(nutrition);
            for (Long pro : responsibles)
                notifications.notify(pro, NotificationService.ONBOARDING_COMPLETED, "Cuestionario inicial completado",
                        client.getFullName() + " ha completado su cuestionario inicial", REF, client.getId());
        }
        return dto(client.getId());
    }

    // ---------- Profesional / ADMIN (solo lectura) ----------

    /** ADMIN, su responsable de entrenamiento o su responsable de nutrición (ADR-013). */
    @Transactional(readOnly = true)
    public OnboardingDto forClient(Long clientId) {
        access.assertCanReadNutrition(clientId);
        users.findById(clientId).filter(u -> u.getRole() == Role.CLIENT).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        return dto(clientId);
    }

    // ---------- internos ----------

    private AppUser currentClient() {
        AuthUser me = access.current();
        if (me.role() != Role.CLIENT) throw new ForbiddenException("Solo para clientes");
        return users.findById(me.id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    private OnboardingDto dto(Long clientId) {
        ClientOnboarding stored = repo.findById(clientId).orElse(null);
        ClientOnboarding o = stored != null ? stored : new ClientOnboarding(clientId);   // sin guardar nada: todo a null
        ClientProfile p = profiles.findById(clientId).orElse(null);
        String gender = p == null || "NONE".equals(p.getGender()) ? null : p.getGender();
        boolean consent = Boolean.TRUE.equals(o.getHealthConsent());
        return new OnboardingDto(clientId,
                p == null ? null : p.getBirthDate(), p == null ? null : p.getWeightKg(), p == null ? null : p.getHeightCm(), gender,
                split(o.getGoals()), o.getExperience(), o.getCommitment(), o.getTechniqueLevel(),
                o.getTrainingStyle(), o.getCardio(), o.getDietStyle(), o.getMealsPerDay(),
                split(o.getLikedExercises()), split(o.getAvoid()), o.getHealthConsent(),
                consent ? o.getHealthConsentAt() : null,
                consent ? split(o.getInjuries()) : List.of(), consent ? o.getInjuriesOther() : null,
                consent ? o.getMedicalNotes() : null, consent ? split(o.getConditions()) : List.of(),
                consent ? o.getFoodAllergy() : null, consent ? o.getAllergyDetails() : null,
                o.getSpecialDiet(), o.getMustKeep(), o.getDislikedFoods(), o.getCooks(),
                o.getCompletedAt(), stored == null ? null : o.getUpdatedAt());
    }

    private static boolean has(JsonNode body, String... fields) {
        for (String f : fields) if (body.has(f)) return true;
        return false;
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    /** Una opción de la lista, o null. */
    private static String one(JsonNode n, String field, List<String> allowed) {
        if (n.isNull()) return null;
        if (!n.isTextual() || !allowed.contains(n.asText())) throw invalid(field);
        return n.asText();
    }

    /** Varias opciones: sin repetir, todas de la lista y el excluyente (si lo hay) solo. Lista vacía = null. */
    private static String many(JsonNode n, String field, List<String> allowed, String exclusive) {
        if (n.isNull()) return null;
        if (!n.isArray()) throw invalid(field);
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode v : n) {
            if (!v.isTextual() || !allowed.contains(v.asText())) throw invalid(field);
            if (!values.add(v.asText())) throw new BadRequestException(field + ": valor repetido");
        }
        if (exclusive != null && values.contains(exclusive) && values.size() > 1)
            throw new BadRequestException(field + ": " + exclusive + " no se combina con otras opciones");
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static Integer integer(JsonNode n, String field, int min, int max) {
        if (n.isNull()) return null;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < min || n.intValue() > max)
            throw new BadRequestException(field + ": debe ser un entero entre " + min + " y " + max);
        return n.intValue();
    }

    private static Boolean bool(JsonNode n, String field) {
        if (n.isNull()) return null;
        if (!n.isBoolean()) throw invalid(field);
        return n.booleanValue();
    }

    /** Texto recortado; vacío = null. */
    private static String text(JsonNode n, String field, int max) {
        if (n.isNull()) return null;
        if (!n.isTextual()) throw invalid(field);
        String s = n.asText().trim();
        if (s.length() > max) throw new BadRequestException(field + ": máximo " + max + " caracteres");
        return s.isEmpty() ? null : s;
    }

    /** Peso con un decimal, como en la ficha (NUMERIC(5,1)), entre 20 y 500 kg. */
    private static BigDecimal weight(JsonNode n) {
        if (n.isNull()) return null;
        if (!n.isNumber()) throw invalid("weightKg");
        BigDecimal w = n.decimalValue().setScale(1, RoundingMode.HALF_UP);
        if (w.compareTo(BigDecimal.valueOf(20)) < 0 || w.compareTo(BigDecimal.valueOf(500)) > 0)
            throw new BadRequestException("weightKg: debe estar entre 20 y 500");
        return w;
    }

    /** Fecha ISO (AAAA-MM-DD) con una edad de 14 a 100 años. */
    private static LocalDate birthDate(JsonNode n) {
        if (n.isNull()) return null;
        if (!n.isTextual()) throw invalid("birthDate");
        LocalDate d;
        try { d = LocalDate.parse(n.asText()); } catch (DateTimeParseException e) { throw invalid("birthDate"); }
        LocalDate today = LocalDate.now();
        int age = d.isAfter(today) ? -1 : Period.between(d, today).getYears();
        if (age < 14 || age > 100) throw new BadRequestException("birthDate: la edad debe estar entre 14 y 100 años");
        return d;
    }

    /** Al segundo: lo que devuelve la respuesta coincide con lo que se lee después de la BD. */
    private static LocalDateTime now() { return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS); }

    private static BadRequestException invalid(String field) { return new BadRequestException(field + ": valor no válido"); }
}
