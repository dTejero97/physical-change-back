package com.physicalchange.service;

import com.physicalchange.domain.Notification;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.NotFoundException;
import com.physicalchange.repository.NotificationRepository;
import com.physicalchange.security.AccessService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** Avisos en la app (ADR-012 §4). Cada usuario solo ve y marca los suyos. */
@Service
public class NotificationService {
    public static final String CHANGE_REQUEST_CREATED = "CHANGE_REQUEST_CREATED";
    public static final String CHANGE_REQUEST_ACCEPTED = "CHANGE_REQUEST_ACCEPTED";
    public static final String CHANGE_REQUEST_REJECTED = "CHANGE_REQUEST_REJECTED";
    public static final String PLAN_UPDATED = "PLAN_UPDATED";
    public static final String ACHIEVEMENT_UNLOCKED = "ACHIEVEMENT_UNLOCKED";
    public static final String ONBOARDING_COMPLETED = "ONBOARDING_COMPLETED";
    public static final String ONBOARDING_UPDATE_REQUESTED = "ONBOARDING_UPDATE_REQUESTED";
    private static final int PAGE = 50;

    private final NotificationRepository repo;
    private final AccessService access;

    public NotificationService(NotificationRepository r, AccessService a) { repo = r; access = a; }

    /** Se llama dentro de la transacción de quien origina el aviso: si esta falla, no queda aviso suelto. */
    @Transactional
    public void notify(Long userId, String type, String title, String body, String refType, Long refId) {
        repo.save(new Notification(userId, type, cut(title, 120), cut(body, 500), refType, refId));
    }

    /** ¿Tiene el usuario algún aviso de este tipo sin leer? (para no repetir avisos). */
    @Transactional(readOnly = true)
    public boolean hasUnread(Long userId, String type) { return repo.existsByUserIdAndTypeAndReadAtIsNull(userId, type); }

    @Transactional(readOnly = true)
    public List<NotificationDto> mine(boolean unreadOnly) {
        Long me = access.current().id();
        PageRequest page = PageRequest.of(0, PAGE);
        List<Notification> list = unreadOnly ? repo.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(me, page)
                                             : repo.findByUserIdOrderByCreatedAtDescIdDesc(me, page);
        return list.stream().map(NotificationService::dto).toList();
    }

    @Transactional(readOnly = true)
    public CountDto unreadCount() { return new CountDto(repo.countByUserIdAndReadAtIsNull(access.current().id())); }

    @Transactional
    public void markRead(Long id) {
        Notification n = repo.findById(id).filter(x -> x.getUserId().equals(access.current().id()))
                .orElseThrow(() -> new NotFoundException("Notificación no encontrada"));
        n.markRead();
    }

    @Transactional
    public void markAllRead() { repo.markAllRead(access.current().id(), LocalDateTime.now()); }

    private static NotificationDto dto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getRefType(), n.getRefId(),
                n.getCreatedAt(), n.getReadAt());
    }

    private static String cut(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
