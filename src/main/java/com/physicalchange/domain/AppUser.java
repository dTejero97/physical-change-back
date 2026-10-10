package com.physicalchange.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "APP_USER")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 160) private String email;
    @Column(name = "PASSWORD_HASH", nullable = false, length = 100) private String passwordHash;
    @Column(name = "FULL_NAME", nullable = false, length = 120) private String fullName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
    /** Entrenador responsable (solo CLIENT). */
    @Column(name = "TRAINER_ID") private Long trainerId;
    @Column(nullable = false) private Integer active = 1;
    /** El entrenador ofrece nutrición como servicio (solo TRAINER). */
    @Column(name = "NUTRITION_ENABLED", nullable = false) private Integer nutritionEnabled = 0;

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String e) { this.email = e; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String p) { this.passwordHash = p; }
    public String getFullName() { return fullName; }
    public void setFullName(String n) { this.fullName = n; }
    public Role getRole() { return role; }
    public void setRole(Role r) { this.role = r; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public boolean isActive() { return active != null && active == 1; }
    public void setActive(boolean a) { this.active = a ? 1 : 0; }
    public boolean isNutritionEnabled() { return nutritionEnabled != null && nutritionEnabled == 1; }
    public void setNutritionEnabled(boolean n) { this.nutritionEnabled = n ? 1 : 0; }
}
