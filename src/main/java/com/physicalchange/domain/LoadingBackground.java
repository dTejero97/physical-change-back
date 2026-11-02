package com.physicalchange.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Fondo de la galería de la página de carga (T208, docs/api-fondos.md). builtinKey: fondo inicial cuya imagen va en el classpath
 * (backgrounds/{key}.jpg) y no se puede borrar; si es null, la imagen original es fileId (stored_file, purpose BACKGROUND).
 */
@Entity
@Table(name = "LOADING_BACKGROUND")
public class LoadingBackground {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "BUILTIN_KEY", length = 20, unique = true) private String builtinKey;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "CONTENT_TYPE", nullable = false, length = 20) private String contentType;
    @Column(nullable = false) private Integer width;
    @Column(nullable = false) private Integer height;
    @Column(name = "SIZE_BYTES", nullable = false) private Long sizeBytes;
    @Column(name = "OBJECT_POSITION", nullable = false, length = 20) private String objectPosition = "50% 50%";
    @Column(length = 4000) private String lqip;
    @Column(name = "FILE_ID", unique = true) private Long fileId;
    @Column(name = "UPLOADED_BY") private Long uploadedBy;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public String getBuiltinKey() { return builtinKey; }
    public void setBuiltinKey(String k) { builtinKey = k; }
    public boolean isBuiltin() { return builtinKey != null; }
    public String getName() { return name; }
    public void setName(String n) { name = n; }
    public String getContentType() { return contentType; }
    public void setContentType(String c) { contentType = c; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer w) { width = w; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer h) { height = h; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long s) { sizeBytes = s; }
    public String getObjectPosition() { return objectPosition; }
    public void setObjectPosition(String p) { objectPosition = p; }
    public String getLqip() { return lqip; }
    public void setLqip(String l) { lqip = l; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long f) { fileId = f; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long u) { uploadedBy = u; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
