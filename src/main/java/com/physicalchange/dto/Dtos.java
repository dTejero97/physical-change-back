package com.physicalchange.dto;

import com.physicalchange.domain.Role;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;

/** DTOs de la API. Las entidades JPA nunca salen del backend. */
public final class Dtos {
    private Dtos() {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record LoginResponse(String token, UserDto user) {}
    /** nutritionEnabled: en TRAINER es su propio servicio; en CLIENT indica si su entrenador ofrece nutrición. */
    public record UserDto(Long id, String email, String fullName, Role role, Long trainerId, boolean nutritionEnabled) {}
    public record CreateClientRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password,
                                      @NotBlank @Size(max = 120) String fullName) {}

    public record ExerciseRequest(@NotBlank @Size(max = 120) String name, @Size(max = 60) String muscleGroup,
                                  @Size(max = 2000) String description) {}
    public record ExerciseDto(Long id, String name, String muscleGroup, String description, boolean global) {}

    public record WorkoutRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String notes) {}
    public record WorkoutDto(Long id, String name, String notes) {}

    public record AssignRequest(@NotNull Long workoutId, @NotNull Long clientId, @NotNull LocalDate scheduledDate) {}
    public record AssignmentDto(Long id, Long workoutId, String workoutName, Long clientId,
                                LocalDate scheduledDate, LocalDate completedDate) {}

    public record ProgressRequest(@NotNull Long clientId, @NotNull LocalDate entryDate,
                                  @DecimalMin("20.0") @DecimalMax("500.0") BigDecimal weightKg,
                                  @DecimalMin("1.0") @DecimalMax("80.0") BigDecimal bodyFatPct,
                                  @Size(max = 500) String notes) {}
    public record ProgressDto(Long id, Long clientId, LocalDate entryDate, BigDecimal weightKg,
                              BigDecimal bodyFatPct, String notes) {}

    public record AppointmentRequest(@NotNull Long clientId, @NotNull @Future LocalDateTime startAt,
                                     @NotNull @Future LocalDateTime endAt, @Size(max = 200) String title) {}
    public record AppointmentDto(Long id, Long trainerId, Long clientId, LocalDateTime startAt,
                                 LocalDateTime endAt, String status, String title) {}

    public record MessageRequest(@NotNull Long recipientId, @NotBlank @Size(max = 2000) String body) {}
    public record MessageDto(Long id, Long senderId, Long recipientId, String body, LocalDateTime sentAt) {}

    public record TrainerDashboard(long clients, long upcomingAppointments) {}
    public record ClientDashboard(long pendingWorkouts, long completedWorkouts, BigDecimal lastWeightKg) {}

    // ---- Nutrición ----
    public record NutritionServiceRequest(boolean enabled) {}
    public record FoodRequest(@NotBlank @Size(max = 120) String name, @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal kcal100g,
                              @DecimalMin("0") @DecimalMax("100") BigDecimal proteinG, @DecimalMin("0") @DecimalMax("100") BigDecimal carbsG,
                              @DecimalMin("0") @DecimalMax("100") BigDecimal fatG) {}
    public record FoodDto(Long id, String name, BigDecimal kcal100g, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG) {}
    public record PlanRequest(@NotNull Long clientId, @NotBlank @Size(max = 120) String name,
                              @Min(800) @Max(6000) Integer dailyKcal, @Size(max = 2000) String notes) {}
    public record MealRequest(@NotBlank @Size(max = 60) String mealName, @NotNull Long foodId,
                              @NotNull @DecimalMin("1") @DecimalMax("5000") BigDecimal grams) {}
    public record MealDto(Long id, String mealName, Long foodId, String foodName, BigDecimal grams,
                          BigDecimal kcal, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG) {}
    public record Totals(BigDecimal kcal, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG) {}
    public record PlanDto(Long id, Long clientId, String name, Integer dailyKcal, String notes,
                          java.util.List<MealDto> meals, Totals totals) {}

    public record ErrorResponse(Instant timestamp, int status, String error, String message) {}
}
