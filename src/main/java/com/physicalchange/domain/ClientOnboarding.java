package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Respuestas del asistente de bienvenida del cliente (docs/api-onboarding.md). Clave = id del cliente.
 * Las listas de varias opciones se guardan como códigos separados por comas; los datos básicos están en ClientProfile.
 */
@Entity
@Table(name = "CLIENT_ONBOARDING")
public class ClientOnboarding {
    @Id @Column(name = "CLIENT_ID") private Long clientId;
    @Column(length = 60) private String goals;
    @Column(length = 15) private String experience;
    @Column(length = 12) private String commitment;
    @Column(name = "TECHNIQUE_LEVEL") private Integer techniqueLevel;
    @Column(name = "TRAINING_STYLE", length = 20) private String trainingStyle;
    @Column(length = 10) private String cardio;
    @Column(name = "DIET_STYLE", length = 10) private String dietStyle;
    @Column(name = "MEALS_PER_DAY") private Integer mealsPerDay;
    @Column(name = "LIKED_EXERCISES", length = 60) private String likedExercises;
    @Column(name = "AVOID_AREAS", length = 30) private String avoid;
    // Datos de salud: solo con consentimiento (el servicio los borra al retirarlo).
    @Column(name = "HEALTH_CONSENT") private Boolean healthConsent;
    @Column(name = "HEALTH_CONSENT_AT") private LocalDateTime healthConsentAt;
    @Column(length = 60) private String injuries;
    @Column(name = "INJURIES_OTHER", length = 200) private String injuriesOther;
    @Column(name = "MEDICAL_NOTES", length = 1000) private String medicalNotes;
    @Column(name = "HEALTH_CONDITIONS", length = 120) private String conditions;
    @Column(name = "FOOD_ALLERGY") private Boolean foodAllergy;
    @Column(name = "ALLERGY_DETAILS", length = 300) private String allergyDetails;
    // Hábitos
    @Column(name = "SPECIAL_DIET", length = 12) private String specialDiet;
    @Column(name = "MUST_KEEP", length = 500) private String mustKeep;
    @Column(name = "DISLIKED_FOODS", length = 500) private String dislikedFoods;
    @Column(length = 8) private String cooks;
    @Column(name = "COMPLETED_AT") private LocalDateTime completedAt;
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = now();

    protected ClientOnboarding() {}
    public ClientOnboarding(Long clientId) { this.clientId = clientId; }

    /** Borra todos los datos de salud (consentimiento retirado). */
    public void clearHealthData() {
        injuries = null; injuriesOther = null; medicalNotes = null; conditions = null; foodAllergy = null; allergyDetails = null;
    }

    public Long getClientId() { return clientId; }
    public String getGoals() { return goals; }
    public void setGoals(String g) { this.goals = g; }
    public String getExperience() { return experience; }
    public void setExperience(String e) { this.experience = e; }
    public String getCommitment() { return commitment; }
    public void setCommitment(String c) { this.commitment = c; }
    public Integer getTechniqueLevel() { return techniqueLevel; }
    public void setTechniqueLevel(Integer t) { this.techniqueLevel = t; }
    public String getTrainingStyle() { return trainingStyle; }
    public void setTrainingStyle(String t) { this.trainingStyle = t; }
    public String getCardio() { return cardio; }
    public void setCardio(String c) { this.cardio = c; }
    public String getDietStyle() { return dietStyle; }
    public void setDietStyle(String d) { this.dietStyle = d; }
    public Integer getMealsPerDay() { return mealsPerDay; }
    public void setMealsPerDay(Integer m) { this.mealsPerDay = m; }
    public String getLikedExercises() { return likedExercises; }
    public void setLikedExercises(String l) { this.likedExercises = l; }
    public String getAvoid() { return avoid; }
    public void setAvoid(String a) { this.avoid = a; }
    public Boolean getHealthConsent() { return healthConsent; }
    public void setHealthConsent(Boolean h) { this.healthConsent = h; }
    public LocalDateTime getHealthConsentAt() { return healthConsentAt; }
    public void setHealthConsentAt(LocalDateTime h) { this.healthConsentAt = h; }
    public String getInjuries() { return injuries; }
    public void setInjuries(String i) { this.injuries = i; }
    public String getInjuriesOther() { return injuriesOther; }
    public void setInjuriesOther(String i) { this.injuriesOther = i; }
    public String getMedicalNotes() { return medicalNotes; }
    public void setMedicalNotes(String m) { this.medicalNotes = m; }
    public String getConditions() { return conditions; }
    public void setConditions(String c) { this.conditions = c; }
    public Boolean getFoodAllergy() { return foodAllergy; }
    public void setFoodAllergy(Boolean f) { this.foodAllergy = f; }
    public String getAllergyDetails() { return allergyDetails; }
    public void setAllergyDetails(String a) { this.allergyDetails = a; }
    public String getSpecialDiet() { return specialDiet; }
    public void setSpecialDiet(String s) { this.specialDiet = s; }
    public String getMustKeep() { return mustKeep; }
    public void setMustKeep(String m) { this.mustKeep = m; }
    public String getDislikedFoods() { return dislikedFoods; }
    public void setDislikedFoods(String d) { this.dislikedFoods = d; }
    public String getCooks() { return cooks; }
    public void setCooks(String c) { this.cooks = c; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime c) { this.completedAt = c; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = now(); }

    /** Al segundo, para que la respuesta coincida con lo que se lee después de la BD. */
    private static LocalDateTime now() { return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); }
}
