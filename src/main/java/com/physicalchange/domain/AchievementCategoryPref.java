package com.physicalchange.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/** Visibilidad (ALL | HIDDEN) que un profesional elige para una categoría del sistema. */
@Entity
@Table(name = "ACHIEVEMENT_CATEGORY_PREF")
@IdClass(AchievementCategoryPref.Key.class)
public class AchievementCategoryPref {
    @Id @Column(name = "TRAINER_ID") private Long trainerId;
    @Id @Column(name = "CATEGORY_ID") private Long categoryId;
    @Column(nullable = false, length = 6) private String visibility;

    protected AchievementCategoryPref() {}
    public AchievementCategoryPref(Long trainerId, Long categoryId, String visibility) {
        this.trainerId = trainerId; this.categoryId = categoryId; this.visibility = visibility;
    }

    public Long getTrainerId() { return trainerId; }
    public Long getCategoryId() { return categoryId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }

    public static class Key implements Serializable {
        private Long trainerId;
        private Long categoryId;
        public Key() {}
        public Key(Long trainerId, Long categoryId) { this.trainerId = trainerId; this.categoryId = categoryId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(trainerId, k.trainerId) && Objects.equals(categoryId, k.categoryId);
        }
        @Override public int hashCode() { return Objects.hash(trainerId, categoryId); }
    }
}
