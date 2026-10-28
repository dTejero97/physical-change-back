package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Elemento de la Librería guardado como documento JSON (docs/api-libreria.md). kind: CARDIO | VIDEO | RECIPE | NUTRITION_PLAN | PLAN_GROUP | FOOD. */
@Entity
@Table(name = "LIBRARY_ITEM")
public class LibraryItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 15) private String kind;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, columnDefinition = "TEXT") private String data;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    /** Imágenes en orden; cada archivo pertenece a un solo elemento. */
    @ElementCollection
    @CollectionTable(name = "LIBRARY_ITEM_FILE", joinColumns = @JoinColumn(name = "ITEM_ID"))
    @OrderColumn(name = "POSITION")
    @Column(name = "FILE_ID")
    private List<Long> fileIds = new ArrayList<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getData() { return data; }
    public void setData(String d) { this.data = d; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
    public List<Long> getFileIds() { return fileIds; }
}
