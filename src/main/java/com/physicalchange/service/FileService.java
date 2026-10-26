package com.physicalchange.service;

import com.physicalchange.domain.StoredFile;
import com.physicalchange.dto.Dtos.FileDto;
import com.physicalchange.dto.Dtos.UploadDto;
import com.physicalchange.dto.Dtos.UploadRequest;
import com.physicalchange.exception.*;
import com.physicalchange.repository.StoredFileRepository;
import com.physicalchange.security.AccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Archivos subidos (ADR-001, ADR-005): validación de tipo por extensión y firma del contenido, límites de tamaño y cuota,
 * y URLs firmadas con caducidad para descargarlos sin JWT. url = ruta relativa a la base de la API.
 * Con STORAGE_TYPE=r2 los archivos nuevos van a Cloudflare R2 y los vídeos se suben directamente desde el navegador.
 */
@Service
public class FileService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;
    static final long URL_TTL_SECONDS = 3600;
    static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    static final Duration PENDING_MAX_AGE = Duration.ofHours(24);
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
    private static final Map<String, String> VIDEO_TYPES = Map.of(
            "mp4", "video/mp4", "mov", "video/quicktime", "webm", "video/webm");

    private final StoredFileRepository files;
    private final Map<String, FileStorage> storages = new HashMap<>();
    private final FileStorage active;
    private final long quotaBytes;
    private final long globalMaxBytes;
    private final AccessService access;
    private final byte[] key;
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    public FileService(StoredFileRepository f, List<FileStorage> all, AccessService a, @Value("${app.jwt.secret}") String secret,
                       @Value("${app.storage.type:database}") String type, @Value("${app.storage.quota-mb:}") String quotaMb,
                       @Value("${app.storage.global-max-mb:}") String globalMaxMb) {
        files = f; access = a;
        all.forEach(s -> storages.put(s.kind(), s));
        active = storages.get("r2".equalsIgnoreCase(type.trim()) ? "R2" : "DB");
        if (active == null) throw new IllegalStateException("STORAGE_TYPE=" + type + " necesita R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY y R2_BUCKET");
        long mb = quotaMb == null || quotaMb.isBlank() ? (active.direct() ? 2048 : 50) : Long.parseLong(quotaMb.trim());
        quotaBytes = mb * 1024 * 1024;
        // R2: 9 GB por defecto, con margen sobre los 10 GB gratuitos. 0 = sin tope. Con la base de datos, sin tope.
        long gmb = globalMaxMb == null || globalMaxMb.isBlank() ? (active.direct() ? 9216 : 0) : Long.parseLong(globalMaxMb.trim());
        globalMaxBytes = gmb * 1024 * 1024;
        key = ("files:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public FileDto upload(MultipartFile file, String purpose) {
        Long owner = access.current().id();
        if ("VIDEO".equals(purpose)) throw new BadRequestException("Los vídeos se suben directamente: POST /api/files/uploads");
        if (!"DOCUMENT".equals(purpose) && !"IMAGE".equals(purpose)) throw new BadRequestException("purpose debe ser DOCUMENT o IMAGE");
        if (file == null || file.isEmpty()) throw new BadRequestException("El archivo está vacío");
        if (file.getSize() > MAX_BYTES) throw new PayloadTooLargeException("El archivo supera los 10 MB");
        String name = cleanName(file.getOriginalFilename());
        String ext = extension(name);
        String type = types(purpose).get(ext);
        if (type == null) throw new BadRequestException("Tipo de archivo no permitido");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw new BadRequestException("No se ha podido leer el archivo"); }
        if (!contentMatches(ext, bytes)) throw new BadRequestException("El contenido no corresponde a un archivo ." + ext);
        checkQuota(owner, bytes.length);

        StoredFile f = newFile(owner, name, type, bytes.length, purpose);
        f = files.save(f);
        active.save(f, bytes);
        return toDto(f);
    }

    /** Subida directa a R2 (ADR-005): registra el archivo como PENDING y devuelve la URL PUT prefirmada. */
    @Transactional
    public UploadDto createUpload(UploadRequest r) {
        Long owner = access.current().id();
        if (!active.direct()) throw new ConflictException("La subida directa necesita Cloudflare R2 (STORAGE_TYPE=r2)");
        if (!"DOCUMENT".equals(r.purpose()) && !"IMAGE".equals(r.purpose()) && !"VIDEO".equals(r.purpose()))
            throw new BadRequestException("purpose debe ser DOCUMENT, IMAGE o VIDEO");
        String name = cleanName(r.name());
        String type = types(r.purpose()).get(extension(name));
        if (type == null) throw new BadRequestException("Tipo de archivo no permitido");
        boolean video = "VIDEO".equals(r.purpose());
        if (r.sizeBytes() > (video ? VIDEO_MAX_BYTES : MAX_BYTES))
            throw new PayloadTooLargeException(video ? "El vídeo supera los 500 MB" : "El archivo supera los 10 MB");
        purgePending();
        checkQuota(owner, r.sizeBytes());

        StoredFile f = newFile(owner, name, type, r.sizeBytes(), r.purpose());
        f.setStatus("PENDING");
        f = files.save(f);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", f.getContentType());
        headers.put("Content-Disposition", disposition(f));
        return new UploadDto(f.getId(), active.uploadUrl(f, UPLOAD_TTL), "PUT", headers, Instant.now().plus(UPLOAD_TTL));
    }

    /** Fin de la subida directa: comprueba tamaño y firma del contenido; si no cuadran, borra el objeto. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public FileDto complete(Long id) {
        StoredFile f = files.findById(id).orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
        access.assertOwnerOrAdmin(f.getOwnerId());
        if ("READY".equals(f.getStatus())) return toDto(f);
        FileStorage s = storageFor(f);
        long size = s.size(f);
        if (size < 0) throw new BadRequestException("El archivo todavía no se ha subido");
        if (size != f.getSizeBytes()) { remove(f); throw new BadRequestException("El tamaño subido no coincide con el declarado"); }
        String ext = extension(f.getName());
        if (!contentMatches(ext, s.head(f, 16))) { remove(f); throw new BadRequestException("El contenido no corresponde a un archivo ." + ext); }
        s.promote(f);
        f.setStatus("READY");
        return toDto(files.save(f));
    }

    /** Borrado por el dueño desde la API. */
    @Transactional
    public void delete(Long id) { remove(owned(id, null)); }

    /** Archivo propio (o de cualquiera si es ADMIN) y, si se indica, con ese propósito y ya subido. */
    public StoredFile owned(Long id, String purpose) {
        StoredFile f = files.findById(id).orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
        access.assertOwnerOrAdmin(f.getOwnerId());
        if (purpose != null && !purpose.equals(f.getPurpose())) throw new BadRequestException("El archivo no es del tipo esperado");
        if (purpose != null && !"READY".equals(f.getStatus())) throw new BadRequestException("El archivo todavía no se ha subido");
        return f;
    }

    /** Borrado interno (al borrar el documento o el logro dueño). */
    public void remove(StoredFile f) {
        storageFor(f).delete(f);
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
        StoredFile f = files.findById(id).filter(x -> "READY".equals(x.getStatus()))
                .orElseThrow(() -> new NotFoundException("Archivo no encontrado"));
        FileStorage s = storageFor(f);
        if (s.direct()) {
            // R2: redirección a una URL prefirmada; las cabeceras se fijaron al subir el objeto.
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(s.downloadUrl(f, Duration.ofSeconds(URL_TTL_SECONDS))))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(f))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + URL_TTL_SECONDS)
                .body(s.read(f));
    }

    /** inline para imágenes y vídeos (se muestran en la página), attachment para documentos. */
    public static String disposition(StoredFile f) {
        boolean inline = f.getContentType().startsWith("image/") || f.getContentType().startsWith("video/");
        return (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------- internos ----------

    private FileStorage storageFor(StoredFile f) {
        FileStorage s = storages.get(f.getStorage());
        if (s == null) throw new IllegalStateException("El archivo " + f.getId() + " está en " + f.getStorage() + ", que no está configurado");
        return s;
    }

    private static Map<String, String> types(String purpose) {
        return switch (purpose) { case "IMAGE" -> IMAGE_TYPES; case "VIDEO" -> VIDEO_TYPES; default -> DOCUMENT_TYPES; };
    }

    private StoredFile newFile(Long owner, String name, String type, long size, String purpose) {
        StoredFile f = new StoredFile();
        f.setOwnerId(owner);
        f.setName(name);
        f.setContentType(type);
        f.setSizeBytes(size);
        f.setPurpose(purpose);
        f.setStorage(active.kind());
        return f;
    }

    /**
     * Cuota por profesional (con su fila bloqueada para que dos subidas a la vez no la sobrepasen) y,
     * en R2, tope global para no salir del plan gratuito de Cloudflare.
     */
    private void checkQuota(Long owner, long bytes) {
        files.lockOwner(owner);
        if (files.usedBytes(owner) + bytes > quotaBytes)
            throw new ConflictException("Has superado los " + quotaBytes / (1024 * 1024) + " MB de almacenamiento");
        if (globalMaxBytes > 0 && files.usedBytesIn(active.kind()) + bytes > globalMaxBytes)
            throw new ConflictException("El almacenamiento de la plataforma está lleno. Avisa al administrador");
    }

    /**
     * Subidas directas sin completar en 24 h, de cualquier profesional: se borran y dejan de contar en la cuota.
     * Si R2 falla, no impide subir: se reintenta en la próxima subida (y la regla de ciclo de vida de uploads/ las borra igualmente).
     */
    private void purgePending() {
        for (StoredFile f : files.findTop50ByStatusAndCreatedAtBefore("PENDING", LocalDateTime.now().minus(PENDING_MAX_AGE))) {
            try { remove(f); }
            catch (RuntimeException e) { log.warn("No se pudo purgar la subida pendiente {}: {}", f.getId(), e.getMessage()); }
        }
    }

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
            case "mp4", "mov" -> b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            case "webm" -> starts(b, 0x1A, 0x45, 0xDF, 0xA3);
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
