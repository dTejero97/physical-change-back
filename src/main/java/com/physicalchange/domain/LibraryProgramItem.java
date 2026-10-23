package com.physicalchange.domain;

import jakarta.persistence.*;

/** Elemento de un día del programa. kind: WORKOUT | CARDIO | VIDEO | NUTRITION_PLAN | NOTE; title guarda el nombre. */
@Entity
@Table(name = "LIBRARY_PROGRAM_ITEM")
public class LibraryProgramItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "PROGRAM_ID", nullable = false) private Long programId;
    @Column(nullable = false) private Integer week;
    @Column(nullable = false) private Integer day;
    @Column(nullable = false, length = 15) private String kind;
    @Column(name = "REF_ID") private Long refId;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false) private Integer position;

    public Long getId() { return id; }
    public Long getProgramId() { return programId; }
    public void setProgramId(Long p) { this.programId = p; }
    public Integer getWeek() { return week; }
    public void setWeek(Integer w) { this.week = w; }
    public Integer getDay() { return day; }
    public void setDay(Integer d) { this.day = d; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public Long getRefId() { return refId; }
    public void setRefId(Long r) { this.refId = r; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
}
