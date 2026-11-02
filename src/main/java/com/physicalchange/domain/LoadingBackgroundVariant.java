package com.physicalchange.domain;

import jakarta.persistence.*;

/** Versión reducida (JPEG) de un fondo subido, generada por el servidor (ADR-015). variant: w768 | w1280 | w1920 | p1080 | thumb. */
@Entity
@Table(name = "LOADING_BACKGROUND_VARIANT",
       uniqueConstraints = @UniqueConstraint(name = "uq_background_variant", columnNames = {"BACKGROUND_ID", "VARIANT"}))
public class LoadingBackgroundVariant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "BACKGROUND_ID", nullable = false) private Long backgroundId;
    @Column(name = "VARIANT", nullable = false, length = 10) private String variant;
    @Column(name = "FILE_ID", nullable = false, unique = true) private Long fileId;
    @Column(nullable = false) private Integer width;
    @Column(nullable = false) private Integer height;

    protected LoadingBackgroundVariant() {}
    public LoadingBackgroundVariant(Long backgroundId, String variant, Long fileId, int width, int height) {
        this.backgroundId = backgroundId; this.variant = variant; this.fileId = fileId; this.width = width; this.height = height;
    }

    public Long getId() { return id; }
    public Long getBackgroundId() { return backgroundId; }
    public String getVariant() { return variant; }
    public Long getFileId() { return fileId; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
}
