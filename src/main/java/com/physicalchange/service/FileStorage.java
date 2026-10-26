package com.physicalchange.service;

import com.physicalchange.domain.StoredFile;
import java.time.Duration;

/**
 * Dónde se guarda el contenido de los archivos subidos. DB = PostgreSQL (ADR-001), R2 = Cloudflare R2 (ADR-005).
 * Cada archivo recuerda su almacenamiento (stored_file.storage), así que conviven ambos.
 */
public interface FileStorage {
    /** Valor de stored_file.storage: DB | R2. */
    String kind();
    void save(StoredFile f, byte[] data);
    byte[] read(StoredFile f);
    void delete(StoredFile f);

    /** true si admite subida directa desde el navegador y descarga redirigida (URLs prefirmadas). */
    default boolean direct() { return false; }
    /** URL PUT prefirmada; el navegador debe enviar Content-Type y Content-Disposition de FileService.uploadHeaders. */
    default String uploadUrl(StoredFile f, Duration ttl) { throw new UnsupportedOperationException(); }
    default String downloadUrl(StoredFile f, Duration ttl) { throw new UnsupportedOperationException(); }
    /** Tamaño del objeto subido; -1 si no existe. */
    default long size(StoredFile f) { throw new UnsupportedOperationException(); }
    /** Primeros bytes del objeto (para comprobar su firma). */
    default byte[] head(StoredFile f, int bytes) { throw new UnsupportedOperationException(); }
    /** Subida directa validada: mueve el objeto de su ubicación temporal a la definitiva. */
    default void promote(StoredFile f) { throw new UnsupportedOperationException(); }
}
