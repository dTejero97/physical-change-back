package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.exception.BackgroundException.Reason;
import com.physicalchange.repository.*;
import com.physicalchange.security.AccessService;
import com.physicalchange.security.AuthUser;
import com.physicalchange.service.BackgroundImages.Format;
import com.physicalchange.service.BackgroundImages.Header;
import com.physicalchange.service.BackgroundImages.Processed;
import com.physicalchange.service.BackgroundImages.UnreadableException;
import com.physicalchange.service.BackgroundImages.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fondos de la página de carga (T208, docs/api-fondos.md, ADR-015): galería, subida validada (firma, dimensiones, tamaño,
 * límite de píxeles), variantes reducidas generadas en el servidor, fondo activo único (L-03) y borrado (L-05).
 * Los originales y variantes subidos se guardan con FileService (ADR-001/ADR-005, purpose BACKGROUND); los iniciales van en el
 * classpath (backgrounds/{clave}.jpg) y sus variantes se generan en memoria la primera vez.
 */
@Service
public class BackgroundService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MIN_LONG_SIDE = 1920, MIN_SHORT_SIDE = 1080;
    /** Bombas de descompresión: se rechaza antes de decodificar (40 MP cubre de sobra el 8K, 33 MP). */
    public static final long MAX_PIXELS = 40_000_000L;
    public static final int MAX_SIDE = 16_384;
    /**
     * JPEG progresivo (T215, M-2 de T211): el decodificador de Java guarda los coeficientes a resolución completa aunque se
     * submuestree (≈ 4 bytes por píxel), así que su tope es menor.
     */
    public static final long MAX_PROGRESSIVE_PIXELS = 12_000_000L;
    /** Barridos (SOS) admitidos: un progresivo normal tiene unos 10; cada barrido recorre otra vez los coeficientes. */
    public static final int MAX_SCANS = 64;
    /** Nombre del fondo subido en el JSON público (B-3 de T211: no se publica el nombre del archivo). */
    static final String PUBLIC_UPLOADED_NAME = "Fondo personalizado";
    static final String CACHE_BUILTIN = "public, max-age=86400", CACHE_UPLOADED = "public, max-age=300";
    /** Nombres válidos en las URL de imagen. */
    public static final Set<String> IMAGE_NAMES = Set.of("original", "w768", "w1280", "w1920", "p1080", "thumb");
    static final long SIGNED_TTL_SECONDS = 3600;

    /** Fondos iniciales (decisión del usuario, L-08: solo fondo3 y fondo4; activo por defecto fondo4). */
    record Builtin(String key, String name, String objectPosition) {}
    static final List<Builtin> BUILTINS = List.of(
            new Builtin("fondo3", "Fondo 3", "45% 55%"),
            new Builtin("fondo4", "Fondo 4", "50% 35%"));
    public static final String DEFAULT_ACTIVE = "fondo4";

    private static final Logger log = LoggerFactory.getLogger(BackgroundService.class);

    private final LoadingBackgroundRepository backgrounds;
    private final LoadingBackgroundVariantRepository variants;
    private final LoadingBackgroundSettingRepository settings;
    private final StoredFileRepository storedFiles;
    private final UserRepository users;
    private final FileService files;
    private final AccessService access;
    private final TransactionTemplate tx;
    private final byte[] key;
    /** Una imagen cada vez: acota la memoria de la decodificación (≈ 25 MB por imagen, ADR-015). */
    private final Semaphore processing = new Semaphore(1);
    private final Map<String, byte[]> builtinBytes = new ConcurrentHashMap<>();
    private final Map<String, Processed> builtinProcessed = new ConcurrentHashMap<>();

    public BackgroundService(LoadingBackgroundRepository b, LoadingBackgroundVariantRepository v, LoadingBackgroundSettingRepository s,
                             StoredFileRepository sf, UserRepository u, FileService f, AccessService a, PlatformTransactionManager tm,
                             @Value("${app.jwt.secret}") String secret) {
        backgrounds = b; variants = v; settings = s; storedFiles = sf; users = u; files = f; access = a;
        tx = new TransactionTemplate(tm);
        key = ("backgrounds:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    // ---------- Galería (ADMIN y TRAINER) ----------

    @Transactional(readOnly = true)
    public BackgroundGalleryDto gallery() {
        AuthUser me = access.current();
        LoadingBackgroundSetting s = setting();
        List<LoadingBackground> all = backgrounds.findAllByOrderByCreatedAtDescIdDesc();
        Map<Long, List<LoadingBackgroundVariant>> vs = variants.findByBackgroundIdIn(all.stream().map(LoadingBackground::getId).toList())
                .stream().collect(Collectors.groupingBy(LoadingBackgroundVariant::getBackgroundId));
        Set<Long> people = new HashSet<>();
        all.forEach(x -> { if (x.getUploadedBy() != null) people.add(x.getUploadedBy()); });
        if (s.getChangedBy() != null) people.add(s.getChangedBy());
        Map<Long, AppUser> names = users.findAllById(people).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        // Orden (diseño §6.2): el activo, después los subidos (más recientes primero) y al final los iniciales.
        List<LoadingBackground> ordered = new ArrayList<>();
        all.stream().filter(x -> x.getId().equals(s.getActiveBackgroundId())).forEach(ordered::add);
        all.stream().filter(x -> !x.isBuiltin() && !x.getId().equals(s.getActiveBackgroundId())).forEach(ordered::add);
        all.stream().filter(x -> x.isBuiltin() && !x.getId().equals(s.getActiveBackgroundId()))
                .sorted(Comparator.comparing(LoadingBackground::getBuiltinKey)).forEach(ordered::add);

        List<BackgroundDto> items = ordered.stream()
                .map(x -> toDto(x, vs.getOrDefault(x.getId(), List.of()), x.getId().equals(s.getActiveBackgroundId()), me, names)).toList();
        return new BackgroundGalleryDto(new BackgroundActiveInfoDto(s.getActiveBackgroundId(), person(s.getChangedBy(), names), s.getChangedAt()), items);
    }

    /** Subida validada (firma, formato, tamaño, dimensiones y píxeles); genera las variantes antes de guardar nada. */
    public BackgroundDto upload(MultipartFile file) {
        AuthUser me = access.current();
        Checked c = check(file);
        Processed p;
        try {
            if (!processing.tryAcquire(30, TimeUnit.SECONDS))
                throw new BackgroundException(HttpStatus.SERVICE_UNAVAILABLE, "BACKGROUND_BUSY", "Se está procesando otra imagen. Inténtalo de nuevo en unos segundos.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackgroundException(HttpStatus.SERVICE_UNAVAILABLE, "BACKGROUND_BUSY", "Se está procesando otra imagen. Inténtalo de nuevo en unos segundos.");
        }
        try {
            p = BackgroundImages.process(c.bytes(), c.header(), 0.5, 0.5);
        } catch (UnreadableException e) {
            throw unreadable(c.name(), c.header().width(), c.header().height(), c.bytes().length);
        } finally {
            processing.release();
        }
        try {
            Long id = tx.execute(st -> persist(me.id(), c, p));
            return tx.execute(st -> {
                LoadingBackground b = backgrounds.findById(id).orElseThrow();
                return toDto(b, variants.findByBackgroundIdOrderByIdAsc(id), false, me,
                        users.findById(me.id()).map(u -> Map.of(u.getId(), u)).orElse(Map.of()));
            });
        } catch (ConflictException e) { // cuota del profesional o tope global (ADR-001/ADR-005)
            throw new BackgroundException(HttpStatus.CONFLICT, "BACKGROUND_QUOTA", e.getMessage());
        }
    }

    /** "Cambiar fondo": un único fondo activo para todo el sitio; se registra quién y cuándo (L-03). */
    @Transactional
    public BackgroundGalleryDto setActive(Long backgroundId) {
        AuthUser me = access.current();
        LoadingBackgroundSetting s = settings.lockSingleton().orElseThrow(() -> new IllegalStateException("Falta la fila del fondo activo (V18)"));
        LoadingBackground b = backgrounds.findById(backgroundId)
                .orElseThrow(() -> new BackgroundException(HttpStatus.NOT_FOUND, "BACKGROUND_NOT_FOUND", "Fondo no encontrado"));
        s.change(b.getId(), me.id(), LocalDateTime.now());
        settings.save(s);
        return gallery();
    }

    /** L-05: no se borran el activo ni los iniciales; el TRAINER solo borra los que subió; el ADMIN, cualquiera. */
    @Transactional
    public void delete(Long id) {
        AuthUser me = access.current();
        LoadingBackgroundSetting s = settings.lockSingleton().orElseThrow(() -> new IllegalStateException("Falta la fila del fondo activo (V18)"));
        LoadingBackground b = backgrounds.findById(id)
                .orElseThrow(() -> new BackgroundException(HttpStatus.NOT_FOUND, "BACKGROUND_NOT_FOUND", "Fondo no encontrado"));
        if (b.isBuiltin()) throw new BackgroundException(HttpStatus.CONFLICT, "BACKGROUND_BUILTIN", "Los fondos iniciales no se pueden eliminar");
        if (b.getId().equals(s.getActiveBackgroundId()))
            throw new BackgroundException(HttpStatus.CONFLICT, "BACKGROUND_ACTIVE", "Es el fondo activo: cambia a otro antes de eliminarlo");
        if (me.role() != Role.ADMIN && !me.id().equals(b.getUploadedBy()))
            throw new BackgroundException(HttpStatus.FORBIDDEN, "BACKGROUND_NOT_OWNER", "Solo puedes eliminar los fondos que has subido");
        List<LoadingBackgroundVariant> vs = variants.findByBackgroundIdOrderByIdAsc(id);
        variants.deleteAll(vs);
        variants.flush();
        backgrounds.delete(b);
        backgrounds.flush();
        vs.forEach(v -> files.removeById(v.getFileId()));
        files.removeById(b.getFileId());
    }

    // ---------- Público (sin login) ----------

    /** Fondo activo para la página de carga, sin datos personales. Cache-Control lo pone el controlador. */
    @Transactional(readOnly = true)
    public PublicBackgroundDto publicActive() {
        LoadingBackground b = activeBackground();
        List<BackgroundImageDto> vs = images(b, variants.findByBackgroundIdOrderByIdAsc(b.getId()), true);
        // T215: el original solo se publica si no hay variantes (WebP); el nombre del archivo subido no se publica.
        return new PublicBackgroundDto(b.getId(), b.isBuiltin() ? b.getName() : PUBLIC_UPLOADED_NAME, b.isBuiltin(), b.getContentType(),
                b.getWidth(), b.getHeight(), b.getObjectPosition(), lqip(b), vs.isEmpty() ? original(b, true) : null, vs);
    }

    public Long activeId() { return setting().getActiveBackgroundId(); }

    /**
     * Imagen de un fondo. Sin firma solo se sirven el activo y los iniciales (públicos); el resto necesita la URL firmada
     * de la galería (1 hora). Un fondo subido no activo sin firma responde 404 (no se revela que existe).
     */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> image(Long id, String name, Long exp, String sig, String ifNoneMatch) {
        if (!IMAGE_NAMES.contains(name)) throw notFound();
        LoadingBackground b = backgrounds.findById(id).orElseThrow(BackgroundService::notFound);
        boolean signed = exp != null || sig != null;
        if (signed) {
            if (exp == null || sig == null || exp < Instant.now().getEpochSecond()
                    || !MessageDigest.isEqual(sign(id, name, exp).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII)))
                throw new ForbiddenException("Enlace no válido o caducado");
        } else if (!b.isBuiltin() && !b.getId().equals(activeId())) {
            throw notFound();
        } else if ("original".equals(name) && hasVariants(b)) {
            throw notFound(); // T215 (M-1/M-3): el original de un JPEG/PNG no se publica sin firma
        }
        String cache = signed ? "private, max-age=" + SIGNED_TTL_SECONDS : b.isBuiltin() ? CACHE_BUILTIN : CACHE_UPLOADED;
        return send(b, name, cache, ifNoneMatch, false);
    }

    /**
     * Alias estable del fondo activo (para <link rel="preload">): caché corta. Sin variantes (WebP), el original; si falta la
     * variante pedida (o se pide el original), la w* más ancha. Nunca cae al original de un JPEG/PNG (T215).
     */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> activeImage(String name, String ifNoneMatch) {
        if (!IMAGE_NAMES.contains(name)) throw notFound();
        return send(activeBackground(), name, CACHE_UPLOADED, ifNoneMatch, true);
    }

    // ---------- Siembra de los fondos iniciales ----------

    /**
     * Crea los fondos iniciales y la fila del activo si faltan (en PostgreSQL ya los crea V18; en los tests el esquema lo crea
     * Hibernate). No cambia nada si ya existen.
     */
    @Transactional
    public void ensureSeeded() {
        for (Builtin bi : BUILTINS) {
            if (backgrounds.findByBuiltinKey(bi.key()).isPresent()) continue;
            byte[] bytes = builtinBytes(bi.key());
            Header h;
            try { h = BackgroundImages.header(bytes); } catch (UnreadableException e) { throw new IllegalStateException("Fondo inicial ilegible: " + bi.key(), e); }
            LoadingBackground b = new LoadingBackground();
            b.setBuiltinKey(bi.key()); b.setName(bi.name()); b.setContentType(h.format().contentType);
            b.setWidth(h.width()); b.setHeight(h.height()); b.setSizeBytes((long) bytes.length); b.setObjectPosition(bi.objectPosition());
            backgrounds.save(b);
        }
        if (settings.findById(LoadingBackgroundSetting.SINGLETON).isEmpty())
            settings.save(new LoadingBackgroundSetting(backgrounds.findByBuiltinKey(DEFAULT_ACTIVE).orElseThrow().getId()));
    }

    /** Genera de antemano las variantes de los iniciales (evita la espera en la primera visita). */
    public void warmUp() {
        for (Builtin bi : BUILTINS) {
            try { builtinProcessed(bi.key()); }
            catch (RuntimeException e) { log.warn("No se pudieron preparar las variantes de {}: {}", bi.key(), e.getMessage()); }
        }
    }

    // ---------- validación ----------

    record Checked(String name, byte[] bytes, Header header) {}

    /** Motivos con código propio para que el front muestre el mensaje correcto (diseño §6.4). */
    Checked check(MultipartFile file) {
        if (file == null || file.isEmpty()) throw unreadable(file == null ? "archivo" : FileService.cleanName(file.getOriginalFilename()), null, null, 0);
        String name = FileService.cleanName(file.getOriginalFilename());
        Format byExt = switch (FileService.extension(name)) {
            case "jpg", "jpeg" -> Format.JPEG;
            case "png" -> Format.PNG;
            case "webp" -> Format.WEBP;
            default -> null;
        };
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        boolean declaredOk = declared.isEmpty() || Set.of("image/jpeg", "image/jpg", "image/png", "image/webp", "application/octet-stream").contains(declared);
        List<Reason> reasons = new ArrayList<>();
        if (byExt == null || !declaredOk)
            reasons.add(new Reason("BACKGROUND_FORMAT", "«" + name + "» no es un formato válido. Usa una imagen JPG, PNG o WebP."));
        if (file.getSize() > MAX_BYTES)
            reasons.add(new Reason("BACKGROUND_TOO_LARGE", "La imagen pesa " + mb(file.getSize()) + " MB y el máximo es 10 MB."));
        if (!reasons.isEmpty()) {
            boolean big = reasons.stream().anyMatch(r -> r.code().equals("BACKGROUND_TOO_LARGE"));
            // 413 si pesa demasiado (el motivo de tamaño va primero); 400 si solo falla el formato.
            if (big && reasons.size() > 1) reasons = List.of(reasons.get(1), reasons.get(0));
            throw new BackgroundException(big ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST, reasons, null, null, file.getSize());
        }
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw unreadable(name, null, null, file.getSize()); }
        Format real = BackgroundImages.sniff(bytes);
        if (real != byExt)
            throw new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_SIGNATURE",
                    "«" + name + "» no es una imagen JPG, PNG o WebP válida, aunque su extensión lo parezca. Vuelve a exportarla desde tu editor de fotos.")),
                    null, null, (long) bytes.length);
        Header h;
        try { h = BackgroundImages.header(bytes); } catch (UnreadableException e) { throw unreadable(name, null, null, bytes.length); }
        long px = (long) h.width() * h.height();
        if (px > MAX_PIXELS || h.width() > MAX_SIDE || h.height() > MAX_SIDE)
            throw new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_TOO_MANY_PIXELS",
                    "La imagen mide " + h.width() + " × " + h.height() + " px y el máximo es 40 megapíxeles (" + MAX_SIDE + " px por lado). Redúcela a 3840 × 2160.")),
                    h.width(), h.height(), (long) bytes.length);
        // Estructura completa sin decodificar y original sin metadatos (T215).
        BackgroundImages.Cleaned cl;
        try { cl = BackgroundImages.clean(bytes, h); } catch (UnreadableException e) { throw unreadable(name, h.width(), h.height(), bytes.length); }
        if (h.format() == Format.JPEG) {
            int sof = cl.sof();
            if (sof != BackgroundImages.SOF_BASELINE && sof != BackgroundImages.SOF_EXTENDED && sof != BackgroundImages.SOF_PROGRESSIVE)
                throw new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_UNREADABLE",
                        "«" + name + "» usa un tipo de JPEG que no se admite (aritmético, sin pérdida o jerárquico). Vuelve a exportarla desde tu editor de fotos como JPEG normal.")),
                        h.width(), h.height(), (long) bytes.length);
            if (sof == BackgroundImages.SOF_PROGRESSIVE && px > MAX_PROGRESSIVE_PIXELS)
                throw new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_TOO_MANY_PIXELS",
                        "La imagen es un JPEG progresivo de " + h.width() + " × " + h.height() + " px y en ese tipo el máximo es 12 megapíxeles. Redúcela a 3840 × 2160 o expórtala como JPEG normal (no progresivo).")),
                        h.width(), h.height(), (long) bytes.length);
            if (cl.scans() > MAX_SCANS)
                throw new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_UNREADABLE",
                        "«" + name + "» tiene una estructura JPEG poco habitual (" + cl.scans() + " barridos). Vuelve a exportarla desde tu editor de fotos.")),
                        h.width(), h.height(), (long) bytes.length);
        }
        bytes = cl.data();
        int longSide = Math.max(h.width(), h.height()), shortSide = Math.min(h.width(), h.height());
        if (longSide < MIN_LONG_SIDE || shortSide < MIN_SHORT_SIDE)
            throw new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_TOO_SMALL",
                    "La imagen es demasiado pequeña: mide " + h.width() + " × " + h.height() + " px. El mínimo es 1920 × 1080 px (lado largo de 1920 y lado corto de 1080).")),
                    h.width(), h.height(), (long) bytes.length);
        return new Checked(name, bytes, h);
    }

    private static BackgroundException unreadable(String name, Integer w, Integer h, long size) {
        return new BackgroundException(HttpStatus.BAD_REQUEST, List.of(new Reason("BACKGROUND_UNREADABLE",
                "No se ha podido leer «" + name + "». Puede estar dañada. Prueba con otra imagen.")), w, h, size);
    }

    private static String mb(long bytes) { return String.format(Locale.of("es", "ES"), "%.1f", bytes / (1024.0 * 1024.0)); }

    // ---------- internos ----------

    private Long persist(Long owner, Checked c, Processed p) {
        StoredFile orig = files.storeValidated(owner, c.name(), c.header().format().contentType, c.bytes(), FileService.BACKGROUND);
        LoadingBackground b = new LoadingBackground();
        b.setName(c.name()); b.setContentType(c.header().format().contentType);
        b.setWidth(c.header().width()); b.setHeight(c.header().height()); b.setSizeBytes((long) c.bytes().length);
        b.setLqip(p.lqip()); b.setFileId(orig.getId()); b.setUploadedBy(owner);
        b = backgrounds.save(b);
        String base = c.name().contains(".") ? c.name().substring(0, c.name().lastIndexOf('.')) : c.name();
        for (Variant v : p.variants()) {
            StoredFile vf = files.storeValidated(owner, base + "-" + v.name() + ".jpg", "image/jpeg", v.data(), FileService.BACKGROUND);
            variants.save(new LoadingBackgroundVariant(b.getId(), v.name(), vf.getId(), v.width(), v.height()));
        }
        return b.getId();
    }

    private LoadingBackgroundSetting setting() {
        return settings.findById(LoadingBackgroundSetting.SINGLETON).orElseThrow(() -> new IllegalStateException("Falta la fila del fondo activo (V18)"));
    }

    private LoadingBackground activeBackground() {
        return backgrounds.findById(activeId()).orElseThrow(() -> new IllegalStateException("El fondo activo no existe"));
    }

    private BackgroundDto toDto(LoadingBackground b, List<LoadingBackgroundVariant> vs, boolean active, AuthUser me, Map<Long, AppUser> names) {
        boolean open = b.isBuiltin() || active;
        boolean canDelete = !b.isBuiltin() && !active && (me.role() == Role.ADMIN || me.id().equals(b.getUploadedBy()));
        return new BackgroundDto(b.getId(), b.getName(), b.isBuiltin(), active, b.getContentType(), b.getWidth(), b.getHeight(), b.getSizeBytes(),
                b.getHeight() > b.getWidth(), b.getObjectPosition(), lqip(b), original(b, open && !b.isBuiltin() && vs.isEmpty()), images(b, vs, open),
                person(b.getUploadedBy(), names), b.isBuiltin() ? null : b.getCreatedAt(), canDelete);
    }

    private static BackgroundPersonDto person(Long id, Map<Long, AppUser> names) {
        if (id == null) return null;
        AppUser u = names.get(id);
        return new BackgroundPersonDto(id, u == null ? null : u.getFullName());
    }

    private BackgroundImageDto original(LoadingBackground b, boolean open) {
        return new BackgroundImageDto("original", b.getWidth(), b.getHeight(), b.getContentType(), url(b.getId(), "original", open));
    }

    private List<BackgroundImageDto> images(LoadingBackground b, List<LoadingBackgroundVariant> vs, boolean open) {
        if (b.isBuiltin())
            return builtinProcessed(b.getBuiltinKey()).variants().stream()
                    .map(v -> new BackgroundImageDto(v.name(), v.width(), v.height(), "image/jpeg", url(b.getId(), v.name(), open))).toList();
        return vs.stream().map(v -> new BackgroundImageDto(v.getVariant(), v.getWidth(), v.getHeight(), "image/jpeg", url(b.getId(), v.getVariant(), open))).toList();
    }

    private String lqip(LoadingBackground b) { return b.isBuiltin() ? builtinProcessed(b.getBuiltinKey()).lqip() : b.getLqip(); }

    /** URL relativa a la base de la API; firmada (1 hora) si el fondo no es público. */
    private String url(Long id, String name, boolean open) {
        String u = "/public/backgrounds/" + id + "/" + name;
        if (open) return u;
        long exp = Instant.now().getEpochSecond() + SIGNED_TTL_SECONDS;
        return u + "?exp=" + exp + "&sig=" + sign(id, name, exp);
    }

    /** true si el fondo tiene variantes (todos salvo los WebP subidos). */
    private boolean hasVariants(LoadingBackground b) {
        return b.isBuiltin() ? !builtinProcessed(b.getBuiltinKey()).variants().isEmpty() : variants.existsByBackgroundId(b.getId());
    }

    /** La w* más ancha de la lista (fallback del alias); null si no hay ninguna. */
    private static <T> T widest(List<T> list, Function<T, String> name, Function<T, Integer> width) {
        return list.stream().filter(x -> name.apply(x).startsWith("w")).max(Comparator.comparing(width)).orElse(null);
    }

    /**
     * Imagen de un fondo. alias = true (ruta active/{name}): si falta la variante o se pide el original, la w* más ancha; el
     * original solo si no hay variantes (WebP). alias = false: la imagen pedida o 404.
     */
    private ResponseEntity<byte[]> send(LoadingBackground b, String name, String cache, String ifNoneMatch, boolean alias) {
        String resolved = name;
        StoredFile stored = null;
        byte[] body = null;
        String type = "image/jpeg";
        if (b.isBuiltin()) {
            List<Variant> vs = builtinProcessed(b.getBuiltinKey()).variants();
            Variant v = vs.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
            if (v == null && alias) v = widest(vs, Variant::name, Variant::width);
            if (v != null) { resolved = v.name(); body = v.data(); }
            else if ("original".equals(name) && !alias) { body = builtinBytes(b.getBuiltinKey()); type = b.getContentType(); }
            else if (alias && vs.isEmpty()) { resolved = "original"; body = builtinBytes(b.getBuiltinKey()); type = b.getContentType(); }
            else throw notFound();
        } else {
            List<LoadingBackgroundVariant> vs = variants.findByBackgroundIdOrderByIdAsc(b.getId());
            LoadingBackgroundVariant v = vs.stream().filter(x -> x.getVariant().equals(name)).findFirst().orElse(null);
            if (v == null && alias) v = widest(vs, LoadingBackgroundVariant::getVariant, LoadingBackgroundVariant::getWidth);
            Long fileId;
            if (v != null) { resolved = v.getVariant(); fileId = v.getFileId(); }
            else if (("original".equals(name) && !alias) || (alias && vs.isEmpty())) { resolved = "original"; fileId = b.getFileId(); type = b.getContentType(); }
            else throw notFound();
            stored = storedFiles.findById(fileId).orElseThrow(BackgroundService::notFound);
        }
        String etag = "\"bg-" + b.getId() + "-" + resolved + "\"";
        if (ifNoneMatch != null && Arrays.stream(ifNoneMatch.split(",")).map(String::trim).anyMatch(t -> t.equals(etag) || t.equals("W/" + etag) || t.equals("*")))
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).header(HttpHeaders.CACHE_CONTROL, cache).build();
        if (stored != null) {
            String direct = files.directDownloadUrl(stored, Duration.ofSeconds(SIGNED_TTL_SECONDS));
            if (direct != null) // R2: redirección a una URL prefirmada de 1 hora; la redirección se cachea poco
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(direct))
                        .header(HttpHeaders.CACHE_CONTROL, cache.startsWith("public") ? "public, max-age=300" : "private, max-age=300").build();
            body = files.content(stored);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, cache)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(body);
    }

    private byte[] builtinBytes(String k) {
        return builtinBytes.computeIfAbsent(k, x -> {
            try (InputStream in = new ClassPathResource("backgrounds/" + x + ".jpg").getInputStream()) { return in.readAllBytes(); }
            catch (IOException e) { throw new IllegalStateException("Falta el fondo inicial backgrounds/" + x + ".jpg", e); }
        });
    }

    /** Variantes de un fondo inicial, generadas una vez y guardadas en memoria (≈ 1 MB por fondo). */
    private Processed builtinProcessed(String k) {
        Processed p = builtinProcessed.get(k);
        if (p != null) return p;
        synchronized (builtinProcessed) {
            p = builtinProcessed.get(k);
            if (p != null) return p;
            byte[] bytes = builtinBytes(k);
            Builtin bi = BUILTINS.stream().filter(x -> x.key().equals(k)).findFirst().orElseThrow();
            String[] pos = bi.objectPosition().replace("%", "").trim().split("\\s+");
            processing.acquireUninterruptibly();
            try {
                p = BackgroundImages.process(bytes, BackgroundImages.header(bytes), Double.parseDouble(pos[0]) / 100, Double.parseDouble(pos[1]) / 100);
            } catch (UnreadableException e) {
                throw new IllegalStateException("Fondo inicial ilegible: " + k, e);
            } finally {
                processing.release();
            }
            builtinProcessed.put(k, p);
            return p;
        }
    }

    private String sign(Long id, String name, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal((id + ":" + name + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) { throw new IllegalStateException("No se pudo firmar la URL", e); }
    }

    private static BackgroundException notFound() { return new BackgroundException(HttpStatus.NOT_FOUND, "BACKGROUND_NOT_FOUND", "Fondo no encontrado"); }
}
