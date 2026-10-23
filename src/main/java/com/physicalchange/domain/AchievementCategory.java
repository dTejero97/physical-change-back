package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Categoría de logros. trainerId nulo + systemKey = categoría del sistema. visibility: ALL | TAGS | HIDDEN. */
@Entity
@Table(name = "ACHIEVEMENT_CATEGORY")
public class AchievementCategory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID") private Long trainerId;
    @Column(name = "SYSTEM_KEY", length = 20) private String systemKey;
    @Column(nullable = false, length = 80) private String name;
    @Column(length = 500) private String description;
    @Column(nullable = false, length = 10) private String icon = "custom";
    @Column(nullable = false, length = 6) private String visibility = "ALL";
    @Column(nullable = false) private Integer position = 0;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection
    @CollectionTable(name = "ACHIEVEMENT_CATEGORY_TAG", joinColumns = @JoinColumn(name = "CATEGORY_ID"))
    @Column(name = "TAG_ID")
    private Set<Long> tagIds = new HashSet<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getSystemKey() { return systemKey; }
    public void setSystemKey(String k) { this.systemKey = k; }
    public boolean isSystem() { return trainerId == null; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getIcon() { return icon; }
    public void setIcon(String i) { this.icon = i; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Set<Long> getTagIds() { return tagIds; }
}
