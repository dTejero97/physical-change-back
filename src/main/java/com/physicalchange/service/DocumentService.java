package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Documentos de la Librería: un archivo propio con nombre, descripción y visibilidad (docs/api-libreria-v2.md). */
@Service
public class DocumentService {
    private final LibraryDocumentRepository documents;
    private final StoredFileRepository storedFiles;
    private final FileService files;
    private final TagRefs tags;
    private final UserRepository users;
    private final AccessService access;

    public DocumentService(LibraryDocumentRepository d, StoredFileRepository sf, FileService f, TagRefs t, UserRepository u, AccessService a) {
        documents = d; storedFiles = sf; files = f; tags = t; users = u; access = a;
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> list() {
        Long me = access.current().id();
        String creator = users.findById(me).map(AppUser::getFullName).orElse("-");
        return documents.findByTrainerIdOrderByCreatedAtDesc(me).stream().map(d -> toDto(d, creator)).toList();
    }

    @Transactional
    public DocumentDto create(DocumentRequest r) {
        LibraryDocument d = new LibraryDocument();
        d.setTrainerId(access.current().id());
        checkFile(r.fileId());
        apply(d, r);
        return toDto(documents.save(d));
    }

    /** Si cambia el archivo, el anterior se borra. */
    @Transactional
    public DocumentDto update(Long id, DocumentRequest r) {
        LibraryDocument d = owned(id);
        Long oldFile = d.getFileId();
        if (!oldFile.equals(r.fileId())) checkFile(r.fileId());
        apply(d, r);
        d.touch();
        documents.flush();
        if (!oldFile.equals(r.fileId())) files.removeById(oldFile);
        return toDto(d);
    }

    @Transactional
    public void delete(Long id) {
        LibraryDocument d = owned(id);
        Long file = d.getFileId();
        documents.delete(d);
        documents.flush();
        files.removeById(file);
    }

    // ---------- internos ----------

    private LibraryDocument owned(Long id) {
        LibraryDocument d = documents.findById(id).orElseThrow(() -> new NotFoundException("Documento no encontrado"));
        access.assertOwnerOrAdmin(d.getTrainerId());
        return d;
    }

    /** El archivo debe ser propio, de tipo DOCUMENT y no estar ya en otro documento. */
    private void checkFile(Long fileId) {
        files.owned(fileId, "DOCUMENT");
        if (documents.existsByFileId(fileId)) throw new ConflictException("Ese archivo ya pertenece a otro documento");
    }

    private void apply(LibraryDocument d, DocumentRequest r) {
        d.setName(r.name().trim());
        d.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        d.setVisibility(r.visibility());
        d.setFileId(r.fileId());
        d.getTagIds().clear();
        if ("TAGS".equals(r.visibility())) d.getTagIds().addAll(tags.own(r.tagIds(), d.getTrainerId(), true));
    }

    private DocumentDto toDto(LibraryDocument d) {
        return toDto(d, users.findById(d.getTrainerId()).map(AppUser::getFullName).orElse("-"));
    }

    private DocumentDto toDto(LibraryDocument d, String creator) {
        StoredFile f = storedFiles.findById(d.getFileId()).orElse(null);
        return new DocumentDto(d.getId(), d.getName(), d.getDescription(), d.getVisibility(), new ArrayList<>(d.getTagIds()), d.getFileId(),
                f == null ? "" : f.getName(), f == null ? "" : f.getContentType(), f == null ? 0 : f.getSizeBytes(),
                files.url(d.getFileId()), creator, d.getCreatedAt());
    }
}
