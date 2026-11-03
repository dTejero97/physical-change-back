package com.physicalchange.controller;

import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.service.content.ContentPostService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

/** Publicaciones y métricas manuales (docs/api-contenido.md §6, §8). */
@RestController @RequestMapping("/api/content/posts") @Tag(name = "Contenido")
public class ContentPostController {
    private final ContentPostService svc;
    public ContentPostController(ContentPostService s) { svc = s; }

    @GetMapping
    public Page<ContentPostDto> list(@RequestParam(required = false) String status, @RequestParam(required = false) String network,
                                     @RequestParam(required = false) String format,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) String q, Pageable page) {
        return svc.list(status, network, format, from, to, q, page);
    }
    @GetMapping("/{id}") public ContentPostDto get(@PathVariable Long id) { return svc.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ContentPostDto create(@Valid @RequestBody PostRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public ContentPostDto update(@PathVariable Long id, @Valid @RequestBody PostRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id) { svc.delete(id); }

    @PostMapping("/{id}/schedule") public ContentPostDto schedule(@PathVariable Long id, @Valid @RequestBody ScheduleRequest r) { return svc.schedule(id, r, false); }
    @PatchMapping("/{id}/schedule") public ContentPostDto move(@PathVariable Long id, @Valid @RequestBody ScheduleRequest r) { return svc.schedule(id, r, true); }
    @PostMapping("/{id}/unschedule") public ContentPostDto unschedule(@PathVariable Long id) { return svc.unschedule(id); }
    @PostMapping("/{id}/cancel") public ContentPostDto cancel(@PathVariable Long id) { return svc.cancel(id); }
    @PostMapping("/{id}/restore") public ContentPostDto restore(@PathVariable Long id) { return svc.restore(id); }
    @PostMapping("/{id}/targets/{network}/published")
    public ContentPostDto published(@PathVariable Long id, @PathVariable String network, @Valid @RequestBody(required = false) MarkPublishedRequest r) {
        return svc.markPublished(id, network, r);
    }
    @PostMapping("/{id}/duplicate") @ResponseStatus(HttpStatus.CREATED) public ContentPostDto duplicate(@PathVariable Long id) { return svc.duplicate(id); }

    @GetMapping("/{id}/metrics") public List<PostMetricDto> metrics(@PathVariable Long id) { return svc.metrics(id); }
    @PutMapping("/{id}/metrics/{network}/{measuredOn}")
    public PostMetricDto putMetric(@PathVariable Long id, @PathVariable String network,
                                   @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate measuredOn,
                                   @Valid @RequestBody MetricRequest r) {
        return svc.putMetric(id, network, measuredOn, r);
    }
    @DeleteMapping("/{id}/metrics/{network}/{measuredOn}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMetric(@PathVariable Long id, @PathVariable String network,
                             @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate measuredOn) {
        svc.deleteMetric(id, network, measuredOn);
    }
}
