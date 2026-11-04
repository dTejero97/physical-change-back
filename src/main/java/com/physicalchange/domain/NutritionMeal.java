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
    /** null en una línea CUSTOM (receta o alimento sin catálogo, V23). */
    @Column(name = "FOOD_ID") private Long foodId;
    @Column private BigDecimal grams;
    /** Opción de su comida (1–10, V23). */
    @Column(name = "OPTION_NO", nullable = false) private Short optionNo = 1;
    // Copia de una línea CUSTOM (T229): nombre, cantidad en texto y macros ya calculadas.
    @Column(name = "ITEM_NAME", length = 160) private String itemName;
    @Column(name = "QUANTITY_LABEL", length = 40) private String quantityLabel;
    @Column(precision = 7, scale = 1) private BigDecimal kcal;
    @Column(name = "PROTEIN_G", precision = 6, scale = 1) private BigDecimal proteinG;
    @Column(name = "CARBS_G", precision = 6, scale = 1) private BigDecimal carbsG;
    @Column(name = "FAT_G", precision = 6, scale = 1) private BigDecimal fatG;

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
    public int getOptionNo() { return optionNo == null ? 1 : optionNo; }
    public void setOptionNo(int o) { this.optionNo = (short) o; }
    public boolean isCustom() { return foodId == null; }
    public String getItemName() { return itemName; }
    public String getQuantityLabel() { return quantityLabel; }
    public BigDecimal getKcal() { return kcal; }
    public BigDecimal getProteinG() { return proteinG; }
    public BigDecimal getCarbsG() { return carbsG; }
    public BigDecimal getFatG() { return fatG; }

    /** Línea CUSTOM: guarda la copia y deja sin alimento del catálogo. */
    public void setCustom(String name, String quantity, BigDecimal grams, BigDecimal kcal, BigDecimal protein, BigDecimal carbs, BigDecimal fat) {
        this.foodId = null; this.grams = grams; this.itemName = name; this.quantityLabel = quantity;
        this.kcal = kcal; this.proteinG = protein; this.carbsG = carbs; this.fatG = fat;
    }

    /** Línea FOOD: alimento del catálogo y gramos; borra la copia CUSTOM si la había. */
    public void setFood(Long foodId, BigDecimal grams) {
        this.foodId = foodId; this.grams = grams;
        this.itemName = null; this.quantityLabel = null; this.kcal = null; this.proteinG = null; this.carbsG = null; this.fatG = null;
    }
}
