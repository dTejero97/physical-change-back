package com.physicalchange.service;

import com.physicalchange.domain.StoredFileData;
import com.physicalchange.exception.NotFoundException;
import com.physicalchange.repository.StoredFileDataRepository;
import org.springframework.stereotype.Component;

/** Contenido de los archivos en la tabla STORED_FILE_DATA (bytea). */
@Component
public class DatabaseFileStorage implements FileStorage {
    private final StoredFileDataRepository data;
    public DatabaseFileStorage(StoredFileDataRepository d) { data = d; }

    @Override public void save(Long fileId, byte[] bytes) { data.save(new StoredFileData(fileId, bytes)); }

    @Override public byte[] read(Long fileId) {
        return data.findById(fileId).map(StoredFileData::getData).orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
    }

    @Override public void delete(Long fileId) { data.findById(fileId).ifPresent(data::delete); }
}
