package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.MessageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/messages") @Tag(name = "Mensajes")
public class MessageController {
    private final MessageService svc;
    public MessageController(MessageService s) { svc = s; }

    @GetMapping("/with/{userId}") public Page<MessageDto> conversation(@PathVariable Long userId, Pageable p) { return svc.conversation(userId, p); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public MessageDto send(@Valid @RequestBody MessageRequest r) { return svc.send(r); }
}
