package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Metadatos de un archivo subido (ADR-001). El contenido está en StoredFileData. purpose: DOCUMENT | IMAGE. */
@Entity
@Table(name = "STORED_FILE")
public class StoredFile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "OWNER_ID", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "CONTENT_TYPE", nullable = false, length = 100) private String contentType;
    @Column(name = "SIZE_BYTES", nullable = false) private Long sizeBytes;
    @Column(nullable = false, length = 10) private String purpose;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long o) { this.ownerId = o; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getContentType() { return contentType; }
    public void setContentType(String c) { this.contentType = c; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long s) { this.sizeBytes = s; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String p) { this.purpose = p; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
