package com.physicalchange.dto;

import com.physicalchange.domain.Role;
import jakarta.validation.Valid;
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
    /** tagIds (opcional): etiquetas propias a asignar; sus automatizaciones se ejecutan al crear la ficha. */
    public record CreateClientRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password,
                                      @NotBlank @Size(max = 120) String fullName, @Size(max = 50) List<Long> tagIds) {}
    public record UpdateClientRequest(@NotBlank @Size(max = 120) String fullName, boolean active) {}

    // ---- Clientes: ficha (contrato en docs/api-clientes.md) ----
    static final String PLAN_STATUSES = "ACTIVE|PENDING_START|SENT|NO_PLAN|ENDING_SOON|FINISHED|DISPUTE";
    static final String SERVICES = "FITNESS|NUTRITION";
    public record ClientProfileRequest(@Size(max = 120) String planName,
                                       @NotNull @Pattern(regexp = PLAN_STATUSES) String planStatus,
                                       @NotNull @Pattern(regexp = "ME|OTHERS|NONE") String assignee,
                                       boolean appAccess,
                                       @Min(50) @Max(260) Integer heightCm,
                                       @DecimalMin("20") @DecimalMax("500") BigDecimal weightKg,
                                       @NotNull @Pattern(regexp = "NONE|MALE|FEMALE") String gender,
                                       @Past LocalDate birthDate,
                                       @NotNull @Size(max = 2) List<@Pattern(regexp = SERVICES) String> services,
                                       @NotNull @Size(max = 50) List<Long> tagIds) {}
    /** complianceWeek/Month, lastActivity y lastPayment se calculan en el servidor. */
    public record ClientProfileDto(Long clientId, String fullName, String email, boolean active, List<Long> tagIds,
                                   String planName, String planStatus, String lastPayment, LocalDate lastPaymentDate,
                                   int complianceWeek, int complianceMonth, LocalDate activatedAt, String assignee,
                                   LocalDate lastActivity, boolean appAccess, Integer heightCm, BigDecimal weightKg,
                                   String gender, LocalDate birthDate, List<String> services) {}

    // ---- Clientes: etiquetas ----
    public record TagAutomations(boolean chat, @Size(max = 2000) String chatMessage,
                                 boolean forms, @Size(max = 120) String formName,
                                 boolean program, @Size(max = 120) String programName, @Size(max = 20) String programDate,
                                 boolean nutrition, @Size(max = 120) String nutritionPlan,
                                 boolean limitChat, @Min(-1) @Max(10000) Integer chatLimit,
                                 boolean service, @Size(max = 2) List<@Pattern(regexp = SERVICES) String> services) {
        public static TagAutomations none() {
            return new TagAutomations(false, "", false, "", false, "", "", false, "", false, -1, false, List.of());
        }
    }
    public record TagRequest(@NotBlank @Size(max = 60) String name, @Valid TagAutomations automations) {}
    public record TagDto(Long id, String name, LocalDateTime createdAt, TagAutomations automations, long clientCount) {}
    public record TagClientsRequest(@NotNull @Size(max = 500) List<Long> clientIds) {}

    // ---- Clientes: invitaciones ----
    public record InvitationRequest(@Email @NotBlank @Size(max = 160) String email, @NotBlank @Size(max = 120) String name,
                                    Long tagId, @Size(max = 120) String planName) {}
    public record InvitationLinkRequest(Long tagId) {}
    /** email/name nulos = enlace genérico. El enlace del front es <origen>/invitacion/<token>. */
    public record InvitationDto(Long id, String email, String name, Long tagId, String planName, String token,
                                LocalDateTime invitedAt, LocalDateTime expiresAt) {}
    public record PublicInvitationDto(String trainerName, String email, String name, boolean expired) {}
    /** email solo es obligatorio en los enlaces genéricos (en las invitaciones por email se usa el invitado). */
    public record AcceptInvitationRequest(@NotBlank @Size(max = 120) String fullName, @Email @Size(max = 160) String email,
                                          @NotBlank @Size(min = 8, max = 72) String password) {}

    // ---- Clientes: leads ----
    public record LeadRequest(@NotBlank @Size(max = 120) String name, @Email @Size(max = 160) String email,
                              @Size(max = 40) String phone, @Size(max = 2000) String message,
                              @NotNull @Pattern(regexp = "WEB_FORM|REFERRAL|OTHER") String source) {}
    public record LeadDto(Long id, String name, String email, String phone, String message, String source, LocalDateTime registeredAt) {}

    // ---- Clientes: comunidad ----
    public record GroupRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
                               @Size(max = 16) String emoji, @NotNull @Pattern(regexp = "TRAINER|TEAM|EVERYONE") String whoCanPost,
                               boolean shareResults, @NotNull @Size(max = 500) List<Long> memberIds) {}
    public record MemberDto(Long id, String name) {}
    public record GroupDto(Long id, String name, String description, String emoji, String whoCanPost, boolean shareResults,
                           LocalDateTime createdAt, List<MemberDto> members) {}
    public record PostRequest(@NotBlank @Size(max = 2000) String text, Long workoutId) {}
    public record RankingDto(String name, String result) {}
    public record SharedWorkoutDto(Long id, String name, List<RankingDto> ranking) {}
    public record PostDto(Long id, Long groupId, Long authorId, String authorName, boolean mine, String text,
                          LocalDateTime createdAt, SharedWorkoutDto workout, int likes, boolean liked) {}

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
    /** type: APPOINTMENT (cita), WORKOUT (rutina asignada, día completo) o EVENT (sesión reservada; solo para CLIENT). */
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

    // ---- Agenda (contrato en docs/api-agenda.md) ----
    public static final String SESSION_COLORS = "#5B6CF0|#8B3FD1|#C2187A|#F49AC8|#F07A12|#E9D85A|#3FAE6A|#1F6B4F|#93ACD6";
    static final String HTTPS_URL = "https://\\S+";
    public record SessionTypeRequest(@NotBlank @Size(max = 60) String name, @NotNull @Pattern(regexp = "(?i)" + SESSION_COLORS) String color) {}
    /** usage: eventos + horarios que usan el tipo (en uso si > 0). */
    public record SessionTypeDto(Long id, String name, String color, LocalDateTime createdAt, long usage) {}
    public record ReplaceSessionTypeRequest(@NotNull Long targetId) {}
    public record ReplaceResultDto(int moved) {}

    /** weekdays: 1 = lunes … 7 = domingo. */
    public record RecurrenceRequest(@NotNull @Size(min = 1, max = 7) List<@NotNull @Min(1) @Max(7) Integer> weekdays, @NotNull LocalDate until) {}
    public record EventRequest(@NotNull @Pattern(regexp = "SESSION|MEETING|REMINDER|PRIVATE") String kind,
                               @NotBlank @Size(max = 120) String title, @Size(max = 16) String emoji,
                               @Size(max = 2000) String description, @Pattern(regexp = "IN_PERSON|ONLINE") String mode,
                               @Size(max = 200) String location, @Size(max = 500) @Pattern(regexp = HTTPS_URL) String meetingUrl,
                               @Pattern(regexp = "BUSY|FREE") String showAs, Long sessionTypeId, @Size(max = 300) String cover,
                               @NotNull LocalDate date, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
                               @Valid RecurrenceRequest recurrence, @Min(1) @Max(500) Integer capacity,
                               @NotNull @Pattern(regexp = "NO|YES|TAGS") String bookable,
                               @Size(max = 50) List<Long> bookableTagIds, @Size(max = 500) List<Long> clientIds) {}
    public record EventDto(Long id, String kind, String title, String emoji, String description, String mode, String location,
                           String meetingUrl, String showAs, Long sessionTypeId, String sessionTypeName, String color,
                           String cover, String coverUrl, LocalDateTime start, LocalDateTime end, boolean recurrent,
                           String seriesKey, Integer capacity, long booked, long waitlist, String bookable,
                           List<Long> bookableTagIds, Long organizerId, String organizerName) {}
    public record BookingDto(Long id, Long eventId, Long clientId, String clientName, String status, LocalDateTime createdAt) {}
    public record EventDetailDto(EventDto event, List<BookingDto> bookings) {}
    public record BookingRequest(@NotNull Long clientId) {}
    public record BookingStatusRequest(@NotNull @Pattern(regexp = "BOOKED|CANCELLED|ATTENDED|NO_SHOW") String status) {}
    /** Solo eventos SESSION. booked = BOOKED + ATTENDED + NO_SHOW; vouchersRedeemed = 0 hasta que existan bonos. */
    public record AgendaStatsDto(long totalSessions, long inPerson, long online, long booked, long cancelled, long waitlist,
                                 long attended, int attendancePct, long vouchersRedeemed) {}
    public record BirthdayDto(Long clientId, String name, LocalDate date) {}

    public record SlotDto(@NotNull @Min(1) @Max(7) Integer weekday, @NotNull LocalTime from, @NotNull LocalTime to) {}
    public record ScheduleRequest(@NotBlank @Size(max = 120) String title, @Size(max = 16) String emoji,
                                  @NotNull @Pattern(regexp = "IN_PERSON|ONLINE") String mode, @Pattern(regexp = "VIDEO|PHONE") String source,
                                  @Size(max = 500) @Pattern(regexp = HTTPS_URL) String meetingUrl, @Size(max = 200) String location,
                                  @NotNull Long sessionTypeId, @Size(max = 2000) String description, @NotBlank @Size(max = 300) String cover,
                                  @NotNull LocalDate startDate, @NotNull LocalDate endDate, @NotNull @Min(10) @Max(240) Integer durationMin,
                                  @NotNull @Size(min = 1, max = 50) List<@Valid @NotNull SlotDto> slots,
                                  @NotNull @Pattern(regexp = "ALL|TAGS") String visibility, @Size(max = 50) List<Long> tagIds) {}
    public record ScheduleDto(Long id, String title, String emoji, String mode, String source, String meetingUrl, String location,
                              Long sessionTypeId, String sessionTypeName, String color, String description, String cover, String coverUrl,
                              LocalDate startDate, LocalDate endDate, Integer durationMin, List<SlotDto> slots, String visibility,
                              List<Long> tagIds, boolean active, Long organizerId, String organizerName, LocalDateTime createdAt) {}
    public record ScheduleDetailDto(ScheduleDto schedule, List<MemberDto> availableFor) {}
    public record ActiveRequest(boolean active) {}
    public record ActivityDto(Long id, LocalDateTime at, Long actorId, String actorName, String actorKind, String action,
                              String eventTitle, String eventEmoji, LocalDateTime eventStart, boolean recurrent) {}

    // ---- Librería V2 (contrato en docs/api-libreria-v2.md) ----
    public record FileDto(Long id, String name, String contentType, long size, String url) {}
    /** Subida directa a R2 (ADR-005). purpose: DOCUMENT | IMAGE | VIDEO. */
    public record UploadRequest(@NotBlank @Size(max = 200) String name, @NotBlank String purpose, @Positive long sizeBytes) {}
    /** El navegador hace un PUT a uploadUrl con estas cabeceras y después llama a /api/files/{fileId}/complete. */
    public record UploadDto(Long fileId, String uploadUrl, String method, java.util.Map<String, String> headers, Instant expiresAt) {}

    public record ProgramItemRequest(@NotNull @Min(1) @Max(52) Integer week, @NotNull @Min(1) @Max(7) Integer day,
                                     @NotNull @Pattern(regexp = "WORKOUT|CARDIO|VIDEO|NUTRITION_PLAN|NOTE") String kind,
                                     Long refId, @NotBlank @Size(max = 160) String title) {}
    public record ProgramRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description,
                                 @NotNull @Min(1) @Max(52) Integer weeks, Long linkedProgramId, boolean archiveClient,
                                 @NotNull @Size(max = 500) List<@Valid @NotNull ProgramItemRequest> items) {}
    /** automated = tiene programa enlazado o archiva al cliente al terminar. */
    public record ProgramDto(Long id, String name, String description, int weeks, Long linkedProgramId, String linkedProgramName,
                             boolean archiveClient, boolean automated, long itemCount, String creatorName,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record ProgramItemDto(Long id, int week, int day, String kind, Long refId, String title, int position) {}
    public record ProgramDetailDto(ProgramDto program, List<ProgramItemDto> items) {}

    public record DocumentRequest(@NotBlank @Size(max = 160) String name, @Size(max = 2000) String description,
                                  @NotNull @Pattern(regexp = "HIDDEN|ALL|TAGS") String visibility,
                                  @Size(max = 50) List<Long> tagIds, @NotNull Long fileId) {}
    public record DocumentDto(Long id, String name, String description, String visibility, List<Long> tagIds, Long fileId,
                              String fileName, String contentType, long size, String url, String creatorName, LocalDateTime createdAt) {}

    static final String QUESTION_KINDS = "SHORT_TEXT|LONG_TEXT|NUMBER|SINGLE|MULTIPLE|SCALE|DATE";
    public record QuestionRequest(@NotNull @Pattern(regexp = QUESTION_KINDS) String kind, @NotBlank @Size(max = 300) String label,
                                  boolean required, @Size(max = 20) List<@NotBlank @Size(max = 120) String> options) {}
    public record FormRequest(@NotBlank @Size(max = 160) String name, @Size(max = 2000) String description, boolean autoSend,
                              @NotNull @Size(max = 100) List<@Valid @NotNull QuestionRequest> questions) {}
    public record QuestionDto(Long id, String kind, String label, boolean required, List<String> options) {}
    public record FormDto(Long id, String name, String description, boolean autoSend, long questionCount, String creatorName,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record FormDetailDto(FormDto form, List<QuestionDto> questions) {}

    public record TemplateItemDto(Long id, String name, String description, String detail) {}
    public record PackDto(Long id, String kind, String name, String description, String cover, List<TemplateItemDto> items) {}
    public record CopyResultDto(String kind, Long id, String name) {}

    public record CategoryRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
                                  @NotNull @Pattern(regexp = "ALL|TAGS|HIDDEN") String visibility, @Size(max = 50) List<Long> tagIds) {}
    public record AchievementRequest(@NotNull Long categoryId, @NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
                                     Long badgeFileId,
                                     @NotNull @Pattern(regexp = "WEIGHT_EXERCISE|TOTAL_REPS|STREAK|BODY_WEIGHT|TIME_WITH_YOU") String conditionType,
                                     Long exerciseId, @DecimalMin("1") @DecimalMax("1000") BigDecimal weightKg,
                                     @Min(1) @Max(100000) Integer reps, @Min(2) @Max(365) Integer days,
                                     @DecimalMin("20") @DecimalMax("500") BigDecimal targetKg, @Min(1) @Max(120) Integer months,
                                     @NotNull @Pattern(regexp = "UNLIMITED|DATE_RANGE|AFTER_ASSIGNMENT") String durationType,
                                     LocalDate startDate, LocalDate endDate, @Min(1) @Max(365) Integer daysAfter) {}
    public record AchievementDto(Long id, Long categoryId, String name, String description, String groupLabel, String badge,
                                 Long badgeFileId, String badgeUrl, String tier, String conditionType, Long exerciseId, String exerciseName,
                                 BigDecimal weightKg, Integer reps, Integer days, BigDecimal targetKg, Integer months,
                                 String durationType, LocalDate startDate, LocalDate endDate, Integer daysAfter,
                                 boolean system, boolean hidden, LocalDateTime createdAt) {}
    public record CategoryDto(Long id, String name, String description, String icon, boolean system, String visibility,
                              List<Long> tagIds, LocalDateTime createdAt, List<AchievementDto> achievements) {}
    public record HiddenRequest(boolean hidden) {}

    public record ErrorResponse(Instant timestamp, int status, String error, String message) {}
}
