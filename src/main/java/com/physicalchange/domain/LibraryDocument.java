package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Documento de la Librería. visibility: HIDDEN | ALL | TAGS. El archivo es propio y exclusivo del documento. */
@Entity
@Table(name = "LIBRARY_DOCUMENT")
public class LibraryDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "TRAINER_ID", nullable = false) private Long trainerId;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 2000) private String description;
    @Column(nullable = false, length = 6) private String visibility = "HIDDEN";
    @Column(name = "FILE_ID", nullable = false) private Long fileId;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "UPDATED_AT", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    @ElementCollection
    @CollectionTable(name = "LIBRARY_DOCUMENT_TAG", joinColumns = @JoinColumn(name = "DOCUMENT_ID"))
    @Column(name = "TAG_ID")
    private Set<Long> tagIds = new HashSet<>();

    public Long getId() { return id; }
    public Long getTrainerId() { return trainerId; }
    public void setTrainerId(Long t) { this.trainerId = t; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long f) { this.fileId = f; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
    public Set<Long> getTagIds() { return tagIds; }
}
