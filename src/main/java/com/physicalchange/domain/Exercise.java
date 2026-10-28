package com.physicalchange.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "EXERCISE")
public class Exercise {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "MUSCLE_GROUP", length = 60) private String muscleGroup;
    @Column(length = 2000) private String description;
    @Column(name = "OWNER_ID") private Long ownerId; // null = ejercicio global

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getMuscleGroup() { return muscleGroup; }
    public void setMuscleGroup(String m) { this.muscleGroup = m; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long o) { this.ownerId = o; }
    public String getVideoUrl() { return videoUrl; }
    public String getLevel() { return level; }
    public String getLaterality() { return laterality; }
    public String getMechanics() { return mechanics; }
    public String getZone() { return zone; }
    public String getTags() { return tags; }
}
