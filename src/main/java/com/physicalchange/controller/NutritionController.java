package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.NutritionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/nutrition") @Tag(name = "Nutrición")
public class NutritionController {
    private final NutritionService svc;
    public NutritionController(NutritionService s) { svc = s; }

    /** El entrenador activa o desactiva el servicio de nutrición. */
    @PutMapping("/service") @PreAuthorize("hasRole('TRAINER')")
    public NutritionServiceRequest service(@Valid @RequestBody NutritionServiceRequest r) { return svc.setService(r.enabled()); }

    /** Clientes de los que soy responsable de nutrición (ADR-013). */
    @GetMapping("/clients") @PreAuthorize("hasRole('TRAINER')")
    public List<UserDto> clients() { return svc.myNutritionClients(); }
    /** Profesionales asignables como responsables de nutrición. */
    @GetMapping("/professionals") @PreAuthorize("hasRole('ADMIN')")
    public List<ProfessionalDto> professionals() { return svc.nutritionProfessionals(); }

    @GetMapping("/foods") public Page<FoodDto> foods(@RequestParam(required = false) String q, Pageable p) { return svc.searchFoods(q, p); }
    @PostMapping("/foods") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public FoodDto createFood(@Valid @RequestBody FoodRequest r) { return svc.createFood(r); }

    @PostMapping("/plans") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public PlanDto createPlan(@Valid @RequestBody PlanRequest r) { return svc.createPlan(r); }
    @GetMapping("/plans/client/{clientId}") public List<PlanDto> plans(@PathVariable Long clientId) { return svc.plansForClient(clientId); }
    /** Solo el responsable de nutrición del cliente (o ADMIN) cambia objetivos y datos; el cliente pide cambios con /api/change-requests/nutrition. */
    @PutMapping("/plans/{planId}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public PlanDto updatePlan(@PathVariable Long planId, @Valid @RequestBody PlanUpdateRequest r) { return svc.updatePlan(planId, r); }
    @PostMapping("/plans/{planId}/meals") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public PlanDto addMeal(@PathVariable Long planId, @Valid @RequestBody MealRequest r) { return svc.addMeal(planId, r); }
    @DeleteMapping("/plans/{planId}/meals/{mealId}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public PlanDto removeMeal(@PathVariable Long planId, @PathVariable Long mealId) { return svc.removeMeal(planId, mealId); }
}
