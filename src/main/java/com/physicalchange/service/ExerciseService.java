package com.physicalchange.service;

import com.physicalchange.domain.Exercise;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.ExerciseRepository;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExerciseService {
    private final ExerciseRepository repo; private final AccessService access;
    public ExerciseService(ExerciseRepository r, AccessService a) { repo = r; access = a; }

    private static ExerciseDto dto(Exercise e) { return new ExerciseDto(e.getId(), e.getName(), e.getMuscleGroup(), e.getDescription(), e.getOwnerId() == null,
            e.getVideoUrl(), e.getLevel(), e.getLaterality(), e.getMechanics(), e.getZone(), e.getTags()); }

    @Transactional(readOnly = true)
    public Page<ExerciseDto> search(String q, String muscle, Pageable p) {
        return repo.search(access.workspaceId(), blank(q), blank(muscle), p).map(ExerciseService::dto);
    }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    @Transactional
    public ExerciseDto create(ExerciseRequest r) {
        Exercise e = new Exercise();
        e.setName(r.name()); e.setMuscleGroup(r.muscleGroup()); e.setDescription(r.description());
        e.setOwnerId(access.workspaceId());
        return dto(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        Exercise e = repo.findById(id).orElseThrow(() -> new NotFoundException("Ejercicio no encontrado"));
        if (e.getOwnerId() == null) throw new ForbiddenException("Los ejercicios globales no se pueden borrar");
        access.assertOwnerOrAdmin(e.getOwnerId());
        repo.delete(e);
    }
}
