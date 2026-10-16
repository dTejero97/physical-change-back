package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.CalendarEventDto;
import com.physicalchange.service.CalendarService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController @RequestMapping("/api/calendar") @Tag(name = "Calendario")
public class CalendarController {
    private final CalendarService svc;
    public CalendarController(CalendarService s) { svc = s; }

    /** Citas y rutinas asignadas entre from y to (ambos incluidos). */
    @GetMapping
    public List<CalendarEventDto> events(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.events(from, to);
    }
}
