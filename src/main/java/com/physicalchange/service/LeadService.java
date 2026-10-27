package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.ClientLeadRepository;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Leads: personas interesadas. La captación pública desde el Sitio web llegará con la fase de Marketing. */
@Service
public class LeadService {
    private final ClientLeadRepository leads;
    private final InvitationService invitations;
    private final AccessService access;

    public LeadService(ClientLeadRepository l, InvitationService i, AccessService a) { leads = l; invitations = i; access = a; }

    @Transactional(readOnly = true)
    public List<LeadDto> list() {
        AuthUser me = access.current();
        List<ClientLead> list = me.role() == Role.ADMIN ? leads.findAllByOrderByRegisteredAtDesc() : leads.findByTrainerIdOrderByRegisteredAtDesc(me.id());
        return list.stream().map(LeadService::toDto).toList();
    }

    @Transactional
    public LeadDto create(LeadRequest r) {
        ClientLead l = new ClientLead();
        l.setTrainerId(access.current().id());
        l.setName(r.name().trim());
        l.setEmail(blankToNull(r.email()) == null ? null : r.email().trim().toLowerCase());
        l.setPhone(blankToNull(r.phone()));
        l.setMessage(blankToNull(r.message()));
        l.setSource(r.source());
        return toDto(leads.save(l));
    }

    @Transactional
    public void delete(Long id) { leads.delete(owned(id)); }

    /** Convierte el lead en invitación y lo elimina. */
    @Transactional
    public InvitationDto invite(Long id) {
        ClientLead l = owned(id);
        if (l.getEmail() == null) throw new BadRequestException("El lead no tiene email para invitarle");
        InvitationDto inv = invitations.create(new InvitationRequest(l.getEmail(), l.getName(), null, null, null));
        leads.delete(l);
        return inv;
    }

    private ClientLead owned(Long id) {
        ClientLead l = leads.findById(id).orElseThrow(() -> new NotFoundException("Lead no encontrado"));
        access.assertOwnerOrAdmin(l.getTrainerId());
        return l;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    static LeadDto toDto(ClientLead l) {
        return new LeadDto(l.getId(), l.getName(), l.getEmail(), l.getPhone(), l.getMessage(), l.getSource(), l.getRegisteredAt());
    }
}
