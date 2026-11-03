package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.NotificationService;
import com.physicalchange.service.content.ContentPublishJob;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/notifications") @Tag(name = "Notificaciones")
public class NotificationController {
    private final NotificationService svc;
    private final ContentPublishJob contentJob;
    public NotificationController(NotificationService s, ContentPublishJob j) { svc = s; contentJob = j; }

    @GetMapping public List<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly) { return svc.mine(unreadOnly); }
    /** También procesa las publicaciones programadas vencidas (ADR-014 §6: Render gratuito se duerme; como mucho cada 30 s). */
    @GetMapping("/unread-count") public CountDto unread() { contentJob.touch(); return svc.unreadCount(); }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void read(@PathVariable Long id) { svc.markRead(id); }
    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT) public void readAll() { svc.markAllRead(); }
}
