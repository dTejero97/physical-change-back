package com.physicalchange.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Etiquetas de clientes con sus automatizaciones. Cada entrenador gestiona las suyas. */
@Service
public class TagService {
    private final ClientTagRepository tags;
    private final ClientProfileRepository profiles;
    private final ClientInvitationRepository invitations;
    private final ClientProfileService profileService;
    private final AccessService access;
    private final ObjectMapper json;

    public TagService(ClientTagRepository t, ClientProfileRepository p, ClientInvitationRepository i,
                      ClientProfileService ps, AccessService a, ObjectMapper j) {
        tags = t; profiles = p; invitations = i; profileService = ps; access = a; json = j;
    }

    @Transactional(readOnly = true)
    public List<TagDto> list() {
        AuthUser me = access.current();
        List<ClientTag> list = me.role() == Role.ADMIN ? tags.findAllByOrderByNameAsc() : tags.findByTrainerIdOrderByNameAsc(me.id());
        if (list.isEmpty()) return List.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : profiles.countByTags(list.stream().map(ClientTag::getId).toList())) counts.put((Long) row[0], (Long) row[1]);
        return list.stream().map(t -> toDto(t, counts.getOrDefault(t.getId(), 0L))).toList();
    }

    @Transactional
    public TagDto create(TagRequest r) {
        AuthUser me = access.current();
        String name = r.name().trim();
        if (tags.findByTrainerIdAndNameIgnoreCase(me.id(), name).isPresent()) throw new ConflictException("Ya tienes una etiqueta con ese nombre");
        ClientTag t = new ClientTag();
        t.setTrainerId(me.id());
        t.setName(name);
        t.setAutomations(write(r.automations()));
        return toDto(tags.save(t), 0);
    }

    @Transactional
    public TagDto update(Long id, TagRequest r) {
        ClientTag t = owned(id);
        String name = r.name().trim();
        tags.findByTrainerIdAndNameIgnoreCase(t.getTrainerId(), name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new ConflictException("Ya tienes una etiqueta con ese nombre"); });
        t.setName(name);
        t.setAutomations(write(r.automations()));
        return toDto(t, count(id));
    }

    /** Borra la etiqueta y la quita de clientes e invitaciones. */
    @Transactional
    public void delete(Long id) {
        ClientTag t = owned(id);
        profiles.findByTag(id).forEach(p -> p.getTagIds().remove(id));
        invitations.findByTagId(id).forEach(i -> i.setTagId(null));
        profiles.flush();
        tags.delete(t);
    }

    /** Asignación masiva desde "Mis clientes". */
    @Transactional
    public TagDto assign(Long id, TagClientsRequest r) {
        ClientTag t = owned(id);
        for (Long clientId : new LinkedHashSet<>(r.clientIds())) {
            access.assertCanAccessClient(clientId);
            profileService.ensure(clientId).getTagIds().add(id);
        }
        profiles.flush();
        return toDto(t, count(id));
    }

    @Transactional
    public TagDto unassign(Long id, Long clientId) {
        ClientTag t = owned(id);
        access.assertCanAccessClient(clientId);
        profileService.ensure(clientId).getTagIds().remove(id);
        profiles.flush();
        return toDto(t, count(id));
    }

    // ---------- internos ----------

    ClientTag owned(Long id) {
        ClientTag t = tags.findById(id).orElseThrow(() -> new NotFoundException("Etiqueta no encontrada"));
        access.assertOwnerOrAdmin(t.getTrainerId());
        return t;
    }

    private long count(Long tagId) {
        return profiles.countByTags(List.of(tagId)).stream().findFirst().map(row -> (Long) row[1]).orElse(0L);
    }

    private TagDto toDto(ClientTag t, long clientCount) {
        return new TagDto(t.getId(), t.getName(), t.getCreatedAt(), profileService.automationsOf(t), clientCount);
    }

    private String write(TagAutomations a) {
        try { return json.writeValueAsString(a == null ? TagAutomations.none() : a); }
        catch (JsonProcessingException e) { throw new BadRequestException("Automatizaciones no válidas"); }
    }
}
