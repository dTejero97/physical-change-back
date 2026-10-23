package com.physicalchange.domain;

import jakarta.persistence.*;

/** Plantilla dentro de un pack. payload: JSON con los ejercicios (WORKOUT) o semanas y workouts (PROGRAM). */
@Entity
@Table(name = "TEMPLATE_ITEM")
public class TemplateItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "PACK_ID", nullable = false) private Long packId;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 1000) private String description;
    @Column(nullable = false, length = 40) private String detail;
    @Column(nullable = false, length = 8000) private String payload;
    @Column(nullable = false) private Integer position;

    public Long getId() { return id; }
    public Long getPackId() { return packId; }
    public void setPackId(Long p) { this.packId = p; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getDetail() { return detail; }
    public void setDetail(String d) { this.detail = d; }
    public String getPayload() { return payload; }
    public void setPayload(String p) { this.payload = p; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
}
