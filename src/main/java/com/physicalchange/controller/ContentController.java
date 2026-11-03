package com.physicalchange.controller;

import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.dto.Dtos.CountDto;
import com.physicalchange.service.content.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

/** Generador de contenido: catálogo, Inicio, calendario y analíticas (docs/api-contenido.md §0, §7, §8). */
@RestController @RequestMapping("/api/content") @Tag(name = "Contenido")
public class ContentController {
    private final ContentLibraryService library;
    private final ContentPostService posts;
    private final ContentAnalyticsService analytics;
    private final ContentPublishJob job;

    public ContentController(ContentLibraryService l, ContentPostService p, ContentAnalyticsService a, ContentPublishJob j) {
        library = l; posts = p; analytics = a; job = j;
    }

    @GetMapping("/catalog") public CatalogDto catalog() { return library.catalog(); }

    /** Al consultar también se procesan las publicaciones vencidas (como mucho cada 30 s; Render se duerme). */
    @GetMapping("/home")
    public ContentHomeDto home(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        job.touch();
        return posts.home(date);
    }

    @GetMapping("/calendar")
    public CalendarDto calendar(@RequestParam(defaultValue = "WEEK") String view,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @RequestParam(required = false) String network, @RequestParam(required = false) String format) {
        job.touch();
        return posts.calendar(view, date, network, format);
    }

    /** D-06: contador ligero de publicaciones con alguna red en "toca publicar". */
    @GetMapping("/posts/manual-due/count")
    public CountDto manualDue() {
        job.touch();
        return new CountDto(posts.manualDueCount());
    }

    @GetMapping("/analytics/summary")
    public AnalyticsSummaryDto summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) String network, @RequestParam(required = false) String format) {
        return analytics.summary(from, to, network, format);
    }
}
