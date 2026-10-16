package com.physicalchange.dto;

import com.physicalchange.domain.Role;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** DTOs de la API. Las entidades JPA nunca salen del backend. */
public final class Dtos {
    private Dtos() {}

    // ---- Auth y perfil ----
    /** username: email o el usuario "admin". */
    public record LoginRequest(@NotBlank @Size(max = 160) String username, @NotBlank String password) {}
    public record LoginResponse(String token, UserDto user) {}
    /** nutritionEnabled: en TRAINER es su propio servicio; en CLIENT indica si su entrenador ofrece nutrición. */
    public record UserDto(Long id, String email, String fullName, Role role, Long trainerId, boolean nutritionEnabled) {}
    public record UpdateProfileRequest(@NotBlank @Size(max = 120) String fullName) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    // ---- Clientes ----
    public record CreateClientRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password,
                                      @NotBlank @Size(max = 120) String fullName) {}
    public record UpdateClientRequest(@NotBlank @Size(max = 120) String fullName, boolean active) {}

    // ---- Ejercicios ----
    public record ExerciseRequest(@NotBlank @Size(max = 120) String name, @Size(max = 60) String muscleGroup,
                                  @Size(max = 2000) String description) {}
    public record ExerciseDto(Long id, String name, String muscleGroup, String description, boolean global) {}

    // ---- Rutinas ----
    public record WorkoutRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String notes) {}
    public record WorkoutDto(Long id, String name, String notes) {}
    public record WorkoutItemRequest(@NotNull Long exerciseId, @NotNull @Min(1) @Max(20) Integer sets,
                                     @NotNull @Min(1) @Max(200) Integer reps,
                                     @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal weightKg,
                                     @Min(0) @Max(900) Integer restSeconds, @Size(max = 300) String notes) {}
    public record WorkoutItemDto(Long id, Long exerciseId, String exerciseName, String muscleGroup, Integer position,
                                 Integer sets, Integer reps, BigDecimal weightKg, Integer restSeconds, String notes) {}
    public record WorkoutDetailDto(Long id, String name, String notes, List<WorkoutItemDto> items) {}

    // ---- Asignaciones y cumplimiento ----
    public record AssignRequest(@NotNull Long workoutId, @NotNull Long clientId, @NotNull LocalDate scheduledDate) {}
    public record AssignmentDto(Long id, Long workoutId, String workoutName, Long clientId,
                                LocalDate scheduledDate, LocalDate completedDate) {}
    /** Comparación planificado vs. realizado para una asignación. */
    public record ComplianceItemDto(Long workoutItemId, Long exerciseId, String exerciseName, String muscleGroup,
                                    Integer plannedSets, Integer plannedReps, BigDecimal plannedWeightKg,
                                    int loggedSets, int loggedMaxReps, BigDecimal loggedMaxWeightKg,
                                    BigDecimal loggedVolumeKg, int percent) {}
    public record ComplianceDto(Long assignmentId, Long workoutId, String workoutName, Long clientId, LocalDate scheduledDate,
                                LocalDate completedDate, int percent, List<ComplianceItemDto> items) {}

    // ---- Registro de series (lo que levanta el cliente) ----
    public record ExerciseLogRequest(Long clientId, Long assignmentId, Long workoutItemId, @NotNull Long exerciseId,
                                     @NotNull LocalDate logDate, @NotNull @Min(1) @Max(50) Integer setNumber,
                                     @NotNull @Min(0) @Max(500) Integer reps,
                                     @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal weightKg) {}
    public record ExerciseLogDto(Long id, Long clientId, Long assignmentId, Long workoutItemId, Long exerciseId,
                                 String exerciseName, String muscleGroup, LocalDate logDate, Integer setNumber,
                                 Integer reps, BigDecimal weightKg) {}

    // ---- Progreso de fuerza y musculatura ----
    public record StrengthPointDto(LocalDate date, BigDecimal maxWeightKg, BigDecimal estimated1RmKg, BigDecimal volumeKg) {}
    public record ExerciseProgressDto(Long exerciseId, String name, String muscleGroup, List<StrengthPointDto> points) {}
    public record MuscleVolumeDto(String muscleGroup, int sets, BigDecimal volumeKg) {}
    public record StrengthReportDto(List<ExerciseProgressDto> exercises, List<MuscleVolumeDto> muscles) {}

    // ---- Seguimiento corporal ----
    public record ProgressRequest(@NotNull Long clientId, @NotNull LocalDate entryDate,
                                  @DecimalMin("20.0") @DecimalMax("500.0") BigDecimal weightKg,
                                  @DecimalMin("1.0") @DecimalMax("80.0") BigDecimal bodyFatPct,
                                  @Size(max = 500) String notes) {}
    public record ProgressDto(Long id, Long clientId, LocalDate entryDate, BigDecimal weightKg,
                              BigDecimal bodyFatPct, String notes) {}

    // ---- Citas y calendario ----
    public record AppointmentRequest(@NotNull Long clientId, @NotNull @Future LocalDateTime startAt,
                                     @NotNull @Future LocalDateTime endAt, @Size(max = 200) String title) {}
    public record AppointmentDto(Long id, Long trainerId, Long clientId, LocalDateTime startAt,
                                 LocalDateTime endAt, String status, String title) {}
    /** type: APPOINTMENT (cita) o WORKOUT (rutina asignada, día completo). */
    public record CalendarEventDto(String type, Long id, String title, LocalDateTime start, LocalDateTime end,
                                   Long clientId, String clientName, String status) {}

    // ---- Mensajes ----
    public record MessageRequest(@NotNull Long recipientId, @NotBlank @Size(max = 2000) String body) {}
    public record MessageDto(Long id, Long senderId, Long recipientId, String body, LocalDateTime sentAt) {}

    // ---- Pagos ----
    public record PaymentRequest(@NotNull Long clientId, @NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal amount,
                                 @NotBlank @Size(max = 160) String concept, @NotNull LocalDate dueDate) {}
    public record PaymentDto(Long id, Long trainerId, Long clientId, String clientName, BigDecimal amount, String concept,
                             LocalDate dueDate, LocalDate paidDate, String status, boolean overdue) {}

    // ---- Dashboards e informes ----
    public record TrainerDashboard(long clients, long upcomingAppointments) {}
    public record ClientDashboard(long pendingWorkouts, long completedWorkouts, BigDecimal lastWeightKg) {}
    public record TrainerReportDto(long clients, long upcomingAppointments, long cancelledAppointments,
                                   long workoutsAssigned, long workoutsCompleted, int completionPercent,
                                   BigDecimal paidTotal, BigDecimal pendingTotal, long overduePayments) {}

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
                          List<MealDto> meals, Totals totals) {}

    public record ErrorResponse(Instant timestamp, int status, String error, String message) {}
}
