package com.physicalchange.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Preferencia JSON por profesional y clave (EXERCISE_META, EXERCISE_TAGS, WORKOUT_META, FOOD_META). */
@Entity
@Table(name = "LIBRARY_PREF")
@IdClass(LibraryPref.Key.class)
public class LibraryPref {
    @Id @Column(name = "TRAINER_ID") private Long trainerId;
    @Id @Column(name = "PREF_KEY", length = 20) private String prefKey;
    @Column(nullable = false, columnDefinition = "TEXT") private String data;
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    protected LibraryPref() {}
    public LibraryPref(Long trainerId, String prefKey) { this.trainerId = trainerId; this.prefKey = prefKey; }

    public String getPrefKey() { return prefKey; }
    public String getData() { return data; }
    public void setData(String d) { this.data = d; this.updatedAt = LocalDateTime.now(); }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static class Key implements Serializable {
        private Long trainerId;
        private String prefKey;
        public Key() {}
        public Key(Long t, String k) { trainerId = t; prefKey = k; }
        @Override public boolean equals(Object o) { return o instanceof Key k && Objects.equals(trainerId, k.trainerId) && Objects.equals(prefKey, k.prefKey); }
        @Override public int hashCode() { return Objects.hash(trainerId, prefKey); }
    }
}
