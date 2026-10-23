package com.physicalchange.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/** Logro que un profesional ha ocultado a sus clientes. */
@Entity
@Table(name = "ACHIEVEMENT_HIDDEN")
@IdClass(AchievementHidden.Key.class)
public class AchievementHidden {
    @Id @Column(name = "TRAINER_ID") private Long trainerId;
    @Id @Column(name = "ACHIEVEMENT_ID") private Long achievementId;

    protected AchievementHidden() {}
    public AchievementHidden(Long trainerId, Long achievementId) { this.trainerId = trainerId; this.achievementId = achievementId; }

    public Long getTrainerId() { return trainerId; }
    public Long getAchievementId() { return achievementId; }

    public static class Key implements Serializable {
        private Long trainerId;
        private Long achievementId;
        public Key() {}
        public Key(Long trainerId, Long achievementId) { this.trainerId = trainerId; this.achievementId = achievementId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(trainerId, k.trainerId) && Objects.equals(achievementId, k.achievementId);
        }
        @Override public int hashCode() { return Objects.hash(trainerId, achievementId); }
    }
}
