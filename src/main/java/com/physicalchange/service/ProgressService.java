package com.physicalchange.service;

import com.physicalchange.domain.ProgressEntry;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.repository.ProgressEntryRepository;
import com.physicalchange.security.AccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ProgressService {
    private final ProgressEntryRepository repo; private final AccessService access;
    public ProgressService(ProgressEntryRepository r, AccessService a) { repo = r; access = a; }

    private static ProgressDto dto(ProgressEntry e) { return new ProgressDto(e.getId(), e.getClientId(), e.getEntryDate(), e.getWeightKg(), e.getBodyFatPct(), e.getNotes()); }

    @Transactional(readOnly = true)
    public List<ProgressDto> history(Long clientId) {
        access.assertCanAccessClient(clientId);
        return repo.findByClientIdOrderByEntryDateAsc(clientId).stream().map(ProgressService::dto).toList();
    }

    @Transactional
    public ProgressDto add(ProgressRequest r) {
        access.assertCanAccessClient(r.clientId());
        ProgressEntry e = new ProgressEntry();
        e.setClientId(r.clientId()); e.setEntryDate(r.entryDate()); e.setWeightKg(r.weightKg());
        e.setBodyFatPct(r.bodyFatPct()); e.setNotes(r.notes());
        return dto(repo.save(e));
    }
}
