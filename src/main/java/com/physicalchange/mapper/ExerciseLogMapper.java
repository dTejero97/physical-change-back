package com.physicalchange.mapper;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;

public final class ExerciseLogMapper {
    private ExerciseLogMapper() {}

    public static ExerciseLogDto toDto(ExerciseLog l, Exercise e) {
        return new ExerciseLogDto(l.getId(), l.getClientId(), l.getAssignmentId(), l.getWorkoutItemId(), l.getExerciseId(),
                e.getName(), e.getMuscleGroup(), l.getLogDate(), l.getSetNumber(), l.getReps(), l.getWeightKg());
    }
}
