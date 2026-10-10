package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {
    private final ChatMessageRepository repo; private final UserRepository users; private final AccessService access;
    public MessageService(ChatMessageRepository r, UserRepository u, AccessService a) { repo = r; users = u; access = a; }

    private static MessageDto dto(ChatMessage m) { return new MessageDto(m.getId(), m.getSenderId(), m.getRecipientId(), m.getBody(), m.getSentAt()); }

    /** Solo se puede chatear con la pareja cliente-entrenador (o ADMIN). */
    private void assertPair(AuthUser me, Long otherId) {
        if (me.role() == Role.ADMIN) return;
        AppUser other = users.findById(otherId).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        AppUser self = users.findById(me.id()).orElseThrow();
        boolean ok = (self.getRole() == Role.CLIENT && me.id().equals(other.getId()) == false && other.getId().equals(self.getTrainerId()))
                  || (self.getRole() == Role.TRAINER && me.id().equals(other.getTrainerId()));
        if (!ok) throw new ForbiddenException("No puedes chatear con este usuario");
    }

    @Transactional(readOnly = true)
    public Page<MessageDto> conversation(Long otherId, Pageable p) {
        AuthUser me = access.current(); assertPair(me, otherId);
        return repo.conversation(me.id(), otherId, p).map(MessageService::dto);
    }

    @Transactional
    public MessageDto send(MessageRequest r) {
        AuthUser me = access.current(); assertPair(me, r.recipientId());
        ChatMessage m = new ChatMessage(); m.setSenderId(me.id()); m.setRecipientId(r.recipientId()); m.setBody(r.body());
        return dto(repo.save(m));
    }
}
