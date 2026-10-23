package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Programa de la Librería: semanas de contenido y automatizaciones (programa enlazado, archivar cliente). */
@Entity
@Table(name = "LIBRARY_PROGRAM")
public class LibraryProgram {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 2000) private String description;
    @Column(nullable = false) private Integer weeks = 1;
    @Column(name = "LINKED_PROGRAM_ID") private Long linkedProgramId;
    @Column(name = "ARCHIVE_CLIENT", nullable = false) private boolean archiveClient;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public Integer getWeeks() { return weeks; }
    public void setWeeks(Integer w) { this.weeks = w; }
    public Long getLinkedProgramId() { return linkedProgramId; }
    public void setLinkedProgramId(Long l) { this.linkedProgramId = l; }
    public boolean isArchiveClient() { return archiveClient; }
    public void setArchiveClient(boolean a) { this.archiveClient = a; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
}
