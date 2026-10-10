package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "FOOD")
public class Food {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "KCAL_100G", nullable = false) private BigDecimal kcal100g;
    @Column(name = "PROTEIN_G") private BigDecimal proteinG;
    @Column(name = "CARBS_G") private BigDecimal carbsG;
    @Column(name = "FAT_G") private BigDecimal fatG;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public BigDecimal getKcal100g() { return kcal100g; }
    public void setKcal100g(BigDecimal k) { this.kcal100g = k; }
    public BigDecimal getProteinG() { return proteinG; }
    public void setProteinG(BigDecimal p) { this.proteinG = p; }
    public BigDecimal getCarbsG() { return carbsG; }
    public void setCarbsG(BigDecimal c) { this.carbsG = c; }
    public BigDecimal getFatG() { return fatG; }
    public void setFatG(BigDecimal f) { this.fatG = f; }
}
