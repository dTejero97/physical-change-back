package com.physicalchange.service;

/**
 * Dónde se guarda el contenido de los archivos subidos (ADR-001). Hoy en PostgreSQL;
 * una implementación S3 se puede añadir sin tocar controladores ni frontend.
 */
public interface FileStorage {
    void save(Long fileId, byte[] data);
    byte[] read(Long fileId);
    void delete(Long fileId);
}
