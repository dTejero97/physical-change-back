package com.physicalchange.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "NUTRITION_PLAN")
public class NutritionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(name = "CLIENT_ID", nullable = false) private Long clientId;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "DAILY_KCAL") private Integer dailyKcal;
    @Column(length = 2000) private String notes;

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long c) { this.clientId = c; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public Integer getDailyKcal() { return dailyKcal; }
    public void setDailyKcal(Integer d) { this.dailyKcal = d; }
    public String getNotes() { return notes; }
    public void setNotes(String n) { this.notes = n; }
}
