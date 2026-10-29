package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/notifications") @Tag(name = "Notificaciones")
public class NotificationController {
    private final NotificationService svc;
    public NotificationController(NotificationService s) { svc = s; }

    @GetMapping public List<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly) { return svc.mine(unreadOnly); }
    @GetMapping("/unread-count") public CountDto unread() { return svc.unreadCount(); }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void read(@PathVariable Long id) { svc.markRead(id); }
    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT) public void readAll() { svc.markAllRead(); }
}
