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
    public NutritionServiceRequest service(@RequestBody NutritionServiceRequest r) { return svc.setService(r.enabled()); }

    @GetMapping("/foods") public Page<FoodDto> foods(@RequestParam(required = false) String q, Pageable p) { return svc.searchFoods(q, p); }
    @PostMapping("/foods") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public FoodDto createFood(@Valid @RequestBody FoodRequest r) { return svc.createFood(r); }

    @PostMapping("/plans") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public PlanDto createPlan(@Valid @RequestBody PlanRequest r) { return svc.createPlan(r); }
    @GetMapping("/plans/client/{clientId}") public List<PlanDto> plans(@PathVariable Long clientId) { return svc.plansForClient(clientId); }
    @PostMapping("/plans/{planId}/meals") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public PlanDto addMeal(@PathVariable Long planId, @Valid @RequestBody MealRequest r) { return svc.addMeal(planId, r); }
    @DeleteMapping("/plans/{planId}/meals/{mealId}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public PlanDto removeMeal(@PathVariable Long planId, @PathVariable Long mealId) { return svc.removeMeal(planId, mealId); }
}
