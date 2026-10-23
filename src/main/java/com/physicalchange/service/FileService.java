package com.physicalchange.service;

import com.physicalchange.domain.StoredFile;
import com.physicalchange.dto.Dtos.FileDto;
import com.physicalchange.exception.*;
import com.physicalchange.repository.StoredFileRepository;
import com.physicalchange.security.AccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Archivos subidos (ADR-001): validación de tipo por extensión y firma del contenido, límites de tamaño y cuota,
 * y URLs firmadas con caducidad para descargarlos sin JWT. url = ruta relativa a la base de la API.
 */
@Service
public class FileService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final long QUOTA_BYTES = 50L * 1024 * 1024;
    static final long URL_TTL_SECONDS = 3600;
    /** Portadas predefinidas de eventos y horarios (el resto son "file:<id>"). */
    public static final Set<String> PRESET_COVERS = Set.of("brand", "gym", "mat", "weights", "yoga", "run");

    private static final Map<String, String> DOCUMENT_TYPES = Map.of(
            "pdf", "application/pdf", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp",
            "txt", "text/plain", "csv", "text/csv",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp");

    private final StoredFileRepository files;
    private final FileStorage storage;
    private final AccessService access;
    private final byte[] key;

    public FileService(StoredFileRepository f, FileStorage s, AccessService a, @Value("${app.jwt.secret}") String secret) {
        files = f; storage = s; access = a;
        key = ("files:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public FileDto upload(MultipartFile file, String purpose) {
        Long owner = access.current().id();
        if (!"DOCUMENT".equals(purpose) && !"IMAGE".equals(purpose)) throw new BadRequestException("purpose debe ser DOCUMENT o IMAGE");
        if (file == null || file.isEmpty()) throw new BadRequestException("El archivo está vacío");
        if (file.getSize() > MAX_BYTES) throw new PayloadTooLargeException("El archivo supera los 10 MB");
        String name = cleanName(file.getOriginalFilename());
        String ext = extension(name);
        String type = ("IMAGE".equals(purpose) ? IMAGE_TYPES : DOCUMENT_TYPES).get(ext);
        if (type == null) throw new BadRequestException("Tipo de archivo no permitido");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw new BadRequestException("No se ha podido leer el archivo"); }
        if (!contentMatches(ext, bytes)) throw new BadRequestException("El contenido no corresponde a un archivo ." + ext);
        if (files.usedBytes(owner) + bytes.length > QUOTA_BYTES) throw new ConflictException("Has superado los 50 MB de almacenamiento");

        StoredFile f = new StoredFile();
        f.setOwnerId(owner);
        f.setName(name);
        f.setContentType(type);
        f.setSizeBytes((long) bytes.length);
        f.setPurpose(purpose);
        f = files.save(f);
        storage.save(f.getId(), bytes);
        return toDto(f);
    }

    /** Borrado por el dueño desde la API. */
    @Transactional
    public void delete(Long id) { remove(owned(id, null)); }

    /** Archivo propio (o de cualquiera si es ADMIN) y, si se indica, con ese propósito. */
    public StoredFile owned(Long id, String purpose) {
        StoredFile f = files.findById(id).orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
        access.assertOwnerOrAdmin(f.getOwnerId());
        if (purpose != null && !purpose.equals(f.getPurpose())) throw new BadRequestException("El archivo no es del tipo esperado");
        return f;
    }

    /** Borrado interno (al borrar el documento o el logro dueño). */
    public void remove(StoredFile f) {
        storage.delete(f.getId());
        files.delete(f);
    }

    public void removeById(Long id) { if (id != null) files.findById(id).ifPresent(this::remove); }

    public FileDto toDto(StoredFile f) { return new FileDto(f.getId(), f.getName(), f.getContentType(), f.getSizeBytes(), url(f.getId())); }

    /** Portada válida: clave predefinida o "file:<id>" de una imagen propia. */
    public String checkCover(String cover, boolean required) {
        if (cover == null || cover.isBlank()) {
            if (required) throw new BadRequestException("Elige una imagen de portada");
            return null;
        }
        if (PRESET_COVERS.contains(cover)) return cover;
        if (cover.startsWith("file:")) {
            owned(parseId(cover.substring(5)), "IMAGE");
            return cover;
        }
        throw new BadRequestException("Imagen de portada no válida");
    }

    /** URL firmada de una portada subida; null si es predefinida. */
    public String coverUrl(String cover) {
        return cover != null && cover.startsWith("file:") ? url(parseId(cover.substring(5))) : null;
    }

    /** URL firmada (relativa a la base de la API) válida 1 hora. */
    public String url(Long fileId) {
        if (fileId == null) return null;
        long exp = Instant.now().getEpochSecond() + URL_TTL_SECONDS;
        return "/public/files/" + fileId + "?exp=" + exp + "&sig=" + sign(fileId, exp);
    }

    /** Descarga pública con URL firmada. */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> serve(Long id, long exp, String sig) {
        if (exp < Instant.now().getEpochSecond() || sig == null
                || !MessageDigest.isEqual(sign(id, exp).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII))) {
            throw new ForbiddenException("Enlace no válido o caducado");
        }
        StoredFile f = files.findById(id).orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
        boolean image = f.getContentType().startsWith("image/");
        String disposition = (image ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + URL_TTL_SECONDS)
                .body(storage.read(id));
    }

    // ---------- internos ----------

    private String sign(Long id, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal((id + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) { throw new IllegalStateException("No se pudo firmar la URL", e); }
    }

    private static Long parseId(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { throw new BadRequestException("Archivo no válido"); }
    }

    /** Sin rutas ni caracteres de control; máximo 200 caracteres. */
    static String cleanName(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (n.isEmpty()) n = "archivo";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Firma del contenido (magic bytes) según la extensión. */
    static boolean contentMatches(String ext, byte[] b) {
        return switch (ext) {
            case "pdf" -> starts(b, 0x25, 0x50, 0x44, 0x46);
            case "png" -> starts(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> starts(b, 0xFF, 0xD8, 0xFF);
            case "webp" -> starts(b, 0x52, 0x49, 0x46, 0x46) && b.length > 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "docx", "xlsx", "pptx" -> starts(b, 0x50, 0x4B, 0x03, 0x04);
            case "txt", "csv" -> isText(b);
            default -> false;
        };
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != sig[i]) return false;
        return true;
    }

    private static boolean isText(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8192); i++) if (b[i] == 0) return false;
        return true;
    }
}
