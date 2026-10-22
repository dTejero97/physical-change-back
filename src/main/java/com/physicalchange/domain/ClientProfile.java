package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Ficha del cliente: datos de gestión que no están en APP_USER. Clave = id del cliente. */
@Entity
@Table(name = "CLIENT_PROFILE")
public class ClientProfile {
    @Id @Column(name = "CLIENT_ID") private Long clientId;
    @Column(name = "ACTIVATED_AT", nullable = false) private LocalDate activatedAt = LocalDate.now();
    @Column(name = "PLAN_NAME", length = 120) private String planName;
    @Column(name = "PLAN_STATUS", nullable = false, length = 20) private String planStatus = "NO_PLAN";
    @Column(nullable = false, length = 10) private String assignee = "ME";
    @Column(name = "APP_ACCESS", nullable = false) private boolean appAccess = true;
    @Column(name = "HEIGHT_CM") private Integer heightCm;
    @Column(name = "WEIGHT_KG", precision = 5, scale = 1) private BigDecimal weightKg;
    @Column(nullable = false, length = 10) private String gender = "NONE";
    @Column(name = "BIRTH_DATE") private LocalDate birthDate;
    /** Lista separada por comas: FITNESS,NUTRITION. */
    @Column(nullable = false, length = 40) private String services = "FITNESS";
    @Column(name = "REMOVED_AT") private LocalDateTime removedAt;

    @ElementCollection
    @CollectionTable(name = "CLIENT_TAG_LINK", joinColumns = @JoinColumn(name = "CLIENT_ID"))
    @Column(name = "TAG_ID")
    private Set<Long> tagIds = new HashSet<>();

    protected ClientProfile() {}
    public ClientProfile(Long clientId) { this.clientId = clientId; }

    public Long getClientId() { return clientId; }
    public LocalDate getActivatedAt() { return activatedAt; }
    public String getPlanName() { return planName; }
    public void setPlanName(String p) { this.planName = p; }
    public String getPlanStatus() { return planStatus; }
    public void setPlanStatus(String s) { this.planStatus = s; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String a) { this.assignee = a; }
    public boolean isAppAccess() { return appAccess; }
    public void setAppAccess(boolean a) { this.appAccess = a; }
    public Integer getHeightCm() { return heightCm; }
    public void setHeightCm(Integer h) { this.heightCm = h; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal w) { this.weightKg = w; }
    public String getGender() { return gender; }
    public void setGender(String g) { this.gender = g; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate b) { this.birthDate = b; }
    public String getServices() { return services; }
    public void setServices(String s) { this.services = s; }
    public LocalDateTime getRemovedAt() { return removedAt; }
    public void setRemovedAt(LocalDateTime r) { this.removedAt = r; }
    public Set<Long> getTagIds() { return tagIds; }
}
