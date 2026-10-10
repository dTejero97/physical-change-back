package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "NUTRITION_MEAL")
public class NutritionMeal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "PLAN_ID", nullable = false) private Long planId;
    @Column(name = "MEAL_NAME", nullable = false, length = 60) private String mealName;
    @Column(nullable = false) private Integer position;
    @Column(name = "FOOD_ID", nullable = false) private Long foodId;
    @Column(nullable = false) private BigDecimal grams;

    public Long getId() { return id; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long p) { this.planId = p; }
    public String getMealName() { return mealName; }
    public void setMealName(String m) { this.mealName = m; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
    public Long getFoodId() { return foodId; }
    public void setFoodId(Long f) { this.foodId = f; }
    public BigDecimal getGrams() { return grams; }
    public void setGrams(BigDecimal g) { this.grams = g; }
}
