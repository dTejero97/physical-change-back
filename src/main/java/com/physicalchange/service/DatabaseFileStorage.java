package com.physicalchange.service;

import com.physicalchange.domain.StoredFile;
import com.physicalchange.domain.StoredFileData;
import com.physicalchange.exception.NotFoundException;
import com.physicalchange.repository.StoredFileDataRepository;
import org.springframework.stereotype.Component;

/** Contenido de los archivos en la tabla STORED_FILE_DATA (bytea). */
@Component
public class DatabaseFileStorage implements FileStorage {
    private final StoredFileDataRepository data;
    public DatabaseFileStorage(StoredFileDataRepository d) { data = d; }

    @Override public String kind() { return "DB"; }

    @Override public void save(StoredFile f, byte[] bytes) { data.save(new StoredFileData(f.getId(), bytes)); }

    @Override public byte[] read(StoredFile f) {
        return data.findById(f.getId()).map(StoredFileData::getData).orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
    }

    @Override public void delete(StoredFile f) { data.findById(f.getId()).ifPresent(data::delete); }
}
