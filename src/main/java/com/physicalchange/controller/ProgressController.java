package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.ProgressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/progress") @Tag(name = "Progreso")
public class ProgressController {
    private final ProgressService svc;
    public ProgressController(ProgressService s) { svc = s; }

    @GetMapping("/client/{clientId}") public List<ProgressDto> history(@PathVariable Long clientId) { return svc.history(clientId); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ProgressDto add(@Valid @RequestBody ProgressRequest r) { return svc.add(r); }
}
