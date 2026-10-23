package com.physicalchange.domain;

import jakarta.persistence.*;

/** Pack del catálogo de plantillas (solo lectura). kind: WORKOUT | PROGRAM. */
@Entity
@Table(name = "TEMPLATE_PACK")
public class TemplatePack {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 8) private String kind;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 500) private String description;
    @Column(nullable = false, length = 20) private String cover;
    @Column(nullable = false) private Integer position;

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getCover() { return cover; }
    public void setCover(String c) { this.cover = c; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
}
