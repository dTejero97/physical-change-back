package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class NutritionService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final UserRepository users; private final FoodRepository foods;
    private final NutritionPlanRepository plans; private final NutritionMealRepository meals; private final AccessService access;
    private final NotificationService notifications;

    public NutritionService(UserRepository u, FoodRepository f, NutritionPlanRepository p, NutritionMealRepository m, AccessService a,
                            NotificationService n) {
        users = u; foods = f; plans = p; meals = m; access = a; notifications = n;
    }

    // ---- Servicio opcional del entrenador ----
    @Transactional
    public NutritionServiceRequest setService(boolean enabled) {
        AppUser me = users.findById(access.current().id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        if (me.getRole() != Role.TRAINER) throw new ForbiddenException("Solo un entrenador puede activar el servicio");
        if (!enabled && !me.isTrainingEnabled()) throw new BadRequestException("Ofrece al menos entrenamiento o nutrición");
        me.setNutritionEnabled(enabled);
        return new NutritionServiceRequest(enabled);
    }

    /** Un TRAINER solo gestiona nutrición si ha activado el servicio. ADMIN siempre. */
    private void assertTrainerOffersNutrition() {
        AuthUser me = access.current();
        if (me.role() == Role.ADMIN) return;
        AppUser t = users.findById(me.id()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        if (!t.isNutritionEnabled()) throw new ForbiddenException("Activa el servicio de nutrición para usarlo");
    }

    // ---- Alimentos ----
    private static FoodDto dto(Food f) { return new FoodDto(f.getId(), f.getName(), f.getKcal100g(), f.getProteinG(), f.getCarbsG(), f.getFatG()); }

    @Transactional(readOnly = true)
    public Page<FoodDto> searchFoods(String q, Pageable p) {
        return foods.search(q == null || q.isBlank() ? null : q, p).map(NutritionService::dto);
    }

    @Transactional
    public FoodDto createFood(FoodRequest r) {
        assertTrainerOffersNutrition();
        Food f = new Food();
        f.setName(r.name()); f.setKcal100g(r.kcal100g()); f.setProteinG(r.proteinG()); f.setCarbsG(r.carbsG()); f.setFatG(r.fatG());
        return dto(foods.save(f));
    }

    // ---- Clientes y profesionales de nutrición (ADR-013) ----

    /** Clientes de los que soy el responsable de nutrición: los asignados y los míos sin nutricionista propio. */
    @Transactional(readOnly = true)
    public List<UserDto> myNutritionClients() {
        Long me = access.current().id();
        return users.findNutritionCandidates(me).stream()
                .filter(c -> me.equals(access.nutritionResponsibleOf(c))).map(AuthService::toDto).toList();
    }

    /** Profesionales activos y sus servicios (para el ADMIN): solo los que ofrecen nutrición se pueden asignar como nutricionistas. */
    @Transactional(readOnly = true)
    public List<ProfessionalDto> professionals() {
        return users.findByRole(Role.TRAINER).stream().filter(AppUser::isActive).sorted(Comparator.comparing(AppUser::getFullName))
                .map(u -> new ProfessionalDto(u.getId(), u.getFullName(), u.isTrainingEnabled(), u.isNutritionEnabled())).toList();
    }

    // ---- Planes ----
    /** Solo el responsable de nutrición vigente del cliente (o ADMIN) crea y cambia sus planes (ADR-013 §4). */
    @Transactional
    public PlanDto createPlan(PlanRequest r) {
        access.assertNutritionResponsible(r.clientId());
        NutritionPlan p = new NutritionPlan();
        p.setTrainerId(access.current().id()); p.setClientId(r.clientId());
        p.setName(r.name()); p.setDailyKcal(r.dailyKcal()); p.setNotes(r.notes());
        p.setTargetProteinG(r.targetProteinG()); p.setTargetCarbsG(r.targetCarbsG()); p.setTargetFatG(r.targetFatG());
        return build(plans.save(p), true);
    }

    /**
     * Cambio del plan por su profesional (o ADMIN): sube la versión y avisa al cliente (ADR-012 §2).
     * El cliente no llega aquí (403 en el controlador y en assertNutritionResponsible).
     */
    @Transactional
    public PlanDto updatePlan(Long planId, PlanUpdateRequest r) {
        NutritionPlan p = plans.findById(planId).orElseThrow(() -> new NotFoundException("Plan no encontrado"));
        access.assertNutritionResponsible(p.getClientId());
        p.setName(r.name()); p.setDailyKcal(r.dailyKcal()); p.setNotes(r.notes());
        p.setTargetProteinG(r.targetProteinG()); p.setTargetCarbsG(r.targetCarbsG()); p.setTargetFatG(r.targetFatG());
        p.touch();
        notifications.notify(p.getClientId(), NotificationService.PLAN_UPDATED, "Plan nutricional actualizado",
                "Tu profesional ha actualizado el plan " + p.getName() + ".", "NUTRITION_PLAN", p.getId());
        return build(p, isCurrent(p));
    }

    /** El primero es el vigente (el más reciente); el cliente lo ve marcado como tal. */
    @Transactional(readOnly = true)
    public List<PlanDto> plansForClient(Long clientId) {
        access.assertCanReadNutrition(clientId);
        List<NutritionPlan> list = plans.findByClientIdOrderByIdDesc(clientId);
        List<PlanDto> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(build(list.get(i), i == 0));
        return out;
    }

    private boolean isCurrent(NutritionPlan p) {
        return plans.findByClientIdOrderByIdDesc(p.getClientId()).stream().findFirst().map(x -> x.getId().equals(p.getId())).orElse(false);
    }

    @Transactional
    public PlanDto addMeal(Long planId, MealRequest r) {
        NutritionPlan p = plans.findById(planId).orElseThrow(() -> new NotFoundException("Plan no encontrado"));
        access.assertNutritionResponsible(p.getClientId());
        foods.findById(r.foodId()).orElseThrow(() -> new NotFoundException("Alimento no encontrado"));
        NutritionMeal m = new NutritionMeal();
        m.setPlanId(planId); m.setMealName(r.mealName()); m.setFoodId(r.foodId()); m.setGrams(r.grams());
        m.setPosition(meals.findByPlanIdOrderByMealNameAscPositionAsc(planId).size() + 1);
        meals.save(m);
        p.touch();
        return build(p, isCurrent(p));
    }

    @Transactional
    public PlanDto removeMeal(Long planId, Long mealId) {
        NutritionPlan p = plans.findById(planId).orElseThrow(() -> new NotFoundException("Plan no encontrado"));
        access.assertNutritionResponsible(p.getClientId());
        NutritionMeal m = meals.findById(mealId).orElseThrow(() -> new NotFoundException("Comida no encontrada"));
        if (!m.getPlanId().equals(planId)) throw new NotFoundException("Comida no encontrada");
        meals.delete(m);
        p.touch();
        return build(p, isCurrent(p));
    }

    // ---- Cálculo de macros ----
    private static BigDecimal part(BigDecimal per100, BigDecimal grams) {
        return per100 == null ? BigDecimal.ZERO : per100.multiply(grams).divide(HUNDRED, 1, RoundingMode.HALF_UP);
    }

    private PlanDto build(NutritionPlan p, boolean current) {
        List<MealDto> list = new ArrayList<>();
        BigDecimal k = BigDecimal.ZERO, pr = BigDecimal.ZERO, c = BigDecimal.ZERO, f = BigDecimal.ZERO;
        for (NutritionMeal m : meals.findByPlanIdOrderByMealNameAscPositionAsc(p.getId())) {
            Food food = foods.findById(m.getFoodId()).orElseThrow();
            BigDecimal mk = part(food.getKcal100g(), m.getGrams()), mp = part(food.getProteinG(), m.getGrams()),
                       mc = part(food.getCarbsG(), m.getGrams()), mf = part(food.getFatG(), m.getGrams());
            list.add(new MealDto(m.getId(), m.getMealName(), food.getId(), food.getName(), m.getGrams(), mk, mp, mc, mf));
            k = k.add(mk); pr = pr.add(mp); c = c.add(mc); f = f.add(mf);
        }
        return new PlanDto(p.getId(), p.getClientId(), p.getName(), p.getDailyKcal(), p.getNotes(), list, new Totals(k, pr, c, f),
                p.getTargetProteinG(), p.getTargetCarbsG(), p.getTargetFatG(), p.getVersion(), p.getUpdatedAt(), current);
    }
}
