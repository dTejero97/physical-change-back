package com.physicalchange.domain;

import jakarta.persistence.*;

/** Contenido de un archivo (bytea), en tabla aparte para que los listados no lo carguen. */
@Entity
@Table(name = "STORED_FILE_DATA")
public class StoredFileData {
    @Id @Column(name = "FILE_ID") private Long fileId;
    /** bytea en PostgreSQL; H2 (tests, modo PostgreSQL) también lo acepta. */
    @Column(nullable = false, columnDefinition = "bytea") private byte[] data;

    protected StoredFileData() {}
    public StoredFileData(Long fileId, byte[] data) { this.fileId = fileId; this.data = data; }

    public Long getFileId() { return fileId; }
    public byte[] getData() { return data; }
}
