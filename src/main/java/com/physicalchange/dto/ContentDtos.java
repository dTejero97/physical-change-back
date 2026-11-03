package com.physicalchange.dto;

import com.physicalchange.dto.Dtos.AiCreditsDto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs del generador de contenido (docs/api-contenido.md). Las entidades JPA nunca salen del backend; los tokens tampoco. */
public final class ContentDtos {
    private ContentDtos() {}

    public static final String NETWORK = "INSTAGRAM|TIKTOK|YOUTUBE|LINKEDIN|X|FACEBOOK|THREADS|PINTEREST";
    public static final String FORMAT = "REEL|CAROUSEL|SHORT|STORY|POST|LONG_VIDEO";
    public static final String OBJECTIVE = "ATTRACT_CLIENTS|AUTHORITY|COMMUNITY|SALES";
    /** Etiqueta: 1-30 caracteres sin comas (se guardan separadas por comas). */
    public static final String TAG = "[^,]{1,30}";
    /** Hashtag: 1-100 caracteres sin espacios, comas ni '#' interiores; el '#' inicial se quita al guardar. */
    public static final String HASHTAG = "#?[^\\s,#]{1,100}";

    // ---- Catálogo ----
    public record LabelDto(String id, String label) {}
    /** charLimit: límite de caracteres del texto en la red; null hasta verificarlo contra su documentación oficial (T194). */
    public record NetworkLabelDto(String id, String label, Integer charLimit) {}
    public record CatalogDto(List<NetworkLabelDto> networks, List<LabelDto> formats, List<LabelDto> objectives, List<String> themes,
                             List<String> timeZones) {}

    // ---- Cuentas ----
    public record CapabilitiesDto(boolean publish, List<String> publishFormats, boolean metrics) {}
    public record SocialAccountDto(String network, String label, String status, boolean configured, String statusReason,
                                   CapabilitiesDto capabilities, String externalUsername, OffsetDateTime connectedAt,
                                   OffsetDateTime lastSyncAt, OffsetDateTime tokenExpiresAt, String lastError) {}
    public record ConnectResponse(String authorizationUrl, OffsetDateTime expiresAt) {}
    public record OAuthCallbackRequest(@NotNull @Pattern(regexp = NETWORK) String network, @NotBlank @Size(max = 200) String state,
                                       @Size(max = 2000) String code, @Size(max = 200) String error) {}

    // ---- Ideas ----
    public record ContentIdeaDto(Long id, String title, String hook, String script, String format, String objective, String theme,
                                 List<String> tags, String origin, boolean bank, Long sourceId, boolean used, boolean favorite,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record IdeaRequest(@NotBlank @Size(max = 160) String title, @Size(max = 300) String hook, @Size(max = 4000) String script,
                              @NotNull @Pattern(regexp = FORMAT) String format, @Pattern(regexp = OBJECTIVE) String objective,
                              @Size(max = 60) String theme, @Size(max = 10) List<@NotNull @Pattern(regexp = TAG) String> tags) {}
    public record IdeaPatchRequest(Boolean favorite, Boolean used) {}
    public record ToPostRequest(@Size(max = 8) List<@NotNull @Pattern(regexp = NETWORK) String> networks) {}

    // ---- Ganchos y copys ----
    public record ContentSnippetDto(Long id, String kind, String text, String network, String cta, List<String> hashtags, String theme,
                                    String objective, List<String> tags, String origin, boolean bank, Long sourceId, boolean favorite,
                                    OffsetDateTime createdAt, OffsetDateTime updatedAt, Integer charLimit, Boolean overLimit) {}
    /** Gancho: text ≤ 300 y sin network, cta ni hashtags (se ignoran). Copy: text ≤ 5000. */
    public record SnippetRequest(@NotBlank @Size(max = 5000) String text, @Pattern(regexp = NETWORK) String network,
                                 @Size(max = 300) String cta, @Size(max = 30) List<@NotNull @Pattern(regexp = HASHTAG) String> hashtags,
                                 @Size(max = 60) String theme, @Pattern(regexp = OBJECTIVE) String objective,
                                 @Size(max = 10) List<@NotNull @Pattern(regexp = TAG) String> tags) {}
    public record FavoriteRequest(@NotNull Boolean favorite) {}

    // ---- Colecciones ----
    public record CollectionDto(Long id, String name, long itemCount, OffsetDateTime createdAt) {}
    public record CollectionRequest(@NotBlank @Size(max = 80) String name) {}
    public record CollectionItemDto(String type, Long id, String title, OffsetDateTime addedAt) {}

    // ---- Physical AI ----
    public record AiIdeasRequest(@Min(3) @Max(10) Integer count, @Pattern(regexp = FORMAT) String format,
                                 @Pattern(regexp = OBJECTIVE) String objective, @Size(max = 60) String theme,
                                 @Size(max = 8) List<@NotNull @Pattern(regexp = NETWORK) String> networks, @Size(max = 500) String prompt) {}
    public record AiHooksRequest(@Min(3) @Max(10) Integer count, @Size(max = 60) String theme,
                                 @Pattern(regexp = OBJECTIVE) String objective, @Size(max = 500) String prompt) {}
    public record AiCopiesRequest(@NotNull @Size(min = 1, max = 8) List<@NotNull @Pattern(regexp = NETWORK) String> networks, Long ideaId,
                                  @Size(max = 300) String topic, @Pattern(regexp = "CLOSE|PROFESSIONAL|ENERGETIC|EDUCATIONAL") String tone,
                                  @Size(max = 300) String cta, boolean includeHashtags) {}
    public record ContentAiResultDto(String kind, List<ContentIdeaDto> ideas, List<ContentSnippetDto> snippets, int creditsUsed,
                                     AiCreditsDto credits, List<String> warnings) {}

    // ---- Publicaciones ----
    public record MediaDto(Long fileId, String url, String name, String contentType) {}
    public record UserRefDto(Long id, String fullName) {}
    public record PostTargetDto(String network, String status, boolean automatic, String accountStatus, String externalUrl,
                                OffsetDateTime publishedAt, String error) {}
    public record ContentPostDto(Long id, String title, String body, List<String> hashtags, String format, MediaDto media, Long ideaId,
                                 String status, OffsetDateTime scheduledAt, String scheduledLocal, String timeZone,
                                 List<PostTargetDto> targets, boolean hasMetrics, UserRefDto createdBy, UserRefDto updatedBy, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt, int version) {}
    /** version: obligatoria en PUT (bloqueo optimista); se ignora al crear. */
    public record PostRequest(@NotBlank @Size(max = 160) String title, @Size(max = 5000) String body,
                              @Size(max = 30) List<@NotNull @Pattern(regexp = HASHTAG) String> hashtags,
                              @NotNull @Pattern(regexp = FORMAT) String format, Long mediaFileId, @Size(max = 500) String mediaUrl,
                              Long ideaId, @Size(max = 8) List<@NotNull @Pattern(regexp = NETWORK) String> networks, Integer version) {}
    public record ScheduleRequest(@NotNull LocalDate date, @NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String time,
                                  @NotBlank @Size(max = 40) String timeZone,
                                  @Size(max = 8) List<@NotNull @Pattern(regexp = NETWORK) String> networks, Integer version) {}
    public record MarkPublishedRequest(@Size(max = 500) String externalUrl, OffsetDateTime publishedAt) {}

    // ---- Calendario e Inicio ----
    public record CalendarTargetDto(String network, String status) {}
    public record CalendarPostDto(Long id, String title, String format, String status, String time, List<String> networks,
                                  List<CalendarTargetDto> targets, String thumbnailUrl, int version) {}
    public record CalendarDayDto(LocalDate date, List<CalendarPostDto> posts) {}
    public record CalendarDto(String view, LocalDate from, LocalDate to, String timeZone, List<CalendarDayDto> days) {}
    public record HomeKpisDto(long scheduled, long published, BigDecimal onTimeRate, Long views, String viewsSource) {}
    public record AccountStatusDto(String network, String status) {}
    public record ContentHomeDto(CalendarDto week, List<ContentIdeaDto> ideas, List<ContentSnippetDto> hooks, HomeKpisDto kpis,
                                 List<AccountStatusDto> accounts, long manualDue) {}

    // ---- Analíticas (null = "Sin datos", nunca 0) ----
    public record KpiDto(BigDecimal value, BigDecimal previous, BigDecimal changePct, String source) {}
    public record AnalyticsKpisDto(KpiDto scheduled, KpiDto published, KpiDto onTimeRate, KpiDto views, KpiDto reach,
                                   KpiDto interactions, KpiDto engagementRate, KpiDto followersGained) {}
    public record DayValueDto(LocalDate date, Long value, String source) {}
    public record NetworkCountsDto(String network, long published, long scheduled, long failed) {}
    public record FormatCountDto(String format, long count) {}
    public record InteractionsByTypeDto(Long likes, Long comments, Long shares, Long saves, String source) {}
    public record HeatCellDto(int weekday, int hour, long posts, BigDecimal avgEngagementRate) {}
    public record TopPostDto(Long postId, String title, String network, String format, OffsetDateTime publishedAt, Long views,
                             Long interactions, BigDecimal engagementRate, String source) {}
    public record AnalyticsSummaryDto(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, AnalyticsKpisDto kpis,
                                      List<DayValueDto> viewsByDay, List<NetworkCountsDto> postsByNetwork, List<FormatCountDto> formats,
                                      InteractionsByTypeDto interactionsByType, List<HeatCellDto> heatmap, List<TopPostDto> topPosts) {}

    // ---- Métricas de una publicación ----
    public record PostMetricDto(Long id, String network, LocalDate measuredOn, Long views, Long reach, Long likes, Long comments,
                                Long shares, Long saves, String source, Long createdBy, OffsetDateTime createdAt) {}
    public record MetricRequest(@Min(0) @Max(1_000_000_000L) Long views, @Min(0) @Max(1_000_000_000L) Long reach,
                                @Min(0) @Max(1_000_000_000L) Long likes, @Min(0) @Max(1_000_000_000L) Long comments,
                                @Min(0) @Max(1_000_000_000L) Long shares, @Min(0) @Max(1_000_000_000L) Long saves) {}
}
