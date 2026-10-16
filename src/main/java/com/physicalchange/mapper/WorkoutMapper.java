package com.physicalchange.mapper;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import java.util.List;

public final class WorkoutMapper {
    private WorkoutMapper() {}

    public static WorkoutDto toDto(Workout w) {
        return new WorkoutDto(w.getId(), w.getName(), w.getNotes());
    }

    public static WorkoutItemDto toItemDto(WorkoutItem i, Exercise e) {
        return new WorkoutItemDto(i.getId(), e.getId(), e.getName(), e.getMuscleGroup(), i.getPosition(),
                i.getSets(), i.getReps(), i.getWeightKg(), i.getRestSeconds(), i.getNotes());
    }

    public static WorkoutDetailDto toDetail(Workout w, List<WorkoutItemDto> items) {
        return new WorkoutDetailDto(w.getId(), w.getName(), w.getNotes(), items);
    }

    public static AssignmentDto toDto(WorkoutAssignment a, Workout w) {
        return new AssignmentDto(a.getId(), a.getWorkoutId(), w.getName(), a.getClientId(),
                a.getScheduledDate(), a.getCompletedDate());
    }
}
