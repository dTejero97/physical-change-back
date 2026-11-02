package com.physicalchange.service;

import javax.imageio.*;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Imágenes de la página de carga (T208, docs/api-fondos.md, ADR-015): lectura de la cabecera (formato real y dimensiones
 * sin decodificar) y generación de las variantes reducidas con ImageIO (solo JPEG y PNG; WebP no se decodifica).
 * Sin estado: lo usa BackgroundService.
 */
public final class BackgroundImages {
    /** Formato detectado por la firma del contenido. */
    public enum Format {
        JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp");
        public final String contentType;
        Format(String c) { contentType = c; }
    }

    /** Cabecera leída: formato, dimensiones tal como se ven (con la orientación EXIF aplicada) y orientación EXIF (1-8). */
    public record Header(Format format, int width, int height, int orientation) {}

    /** Variante generada (JPEG). */
    public record Variant(String name, int width, int height, byte[] data) {}

    /** Resultado del procesado: variantes y LQIP (data URI). */
    public record Processed(List<Variant> variants, String lqip) {}

    /** Cabecera o contenido que no se puede leer (motivo BACKGROUND_UNREADABLE). */
    public static class UnreadableException extends Exception {
        public UnreadableException(String m) { super(m); }
    }

    /** Anchura objetivo de las variantes horizontales. */
    static final int[] WIDTHS = {768, 1280, 1920};
    /** Variante vertical 9:19,5 (móvil). */
    static final int PORTRAIT_W = 1080, PORTRAIT_H = 2340;
    static final int THUMB_W = 480, THUMB_H = 270;
    static final int LQIP_W = 32;
    /** Se decodifica como mucho un 4K (≈ 25 MB en memoria): las imágenes mayores se leen con submuestreo. */
    static final long MAX_DECODE_PIXELS = 3840L * 2160;

    static { ImageIO.setUseCache(false); } // sin archivos temporales en disco

    private BackgroundImages() {}

    // ---------- Firma y cabecera ----------

    /** Formato según los primeros bytes; null si no es JPEG, PNG ni WebP. */
    public static Format sniff(byte[] b) {
        if (starts(b, 0xFF, 0xD8, 0xFF)) return Format.JPEG;
        if (starts(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return Format.PNG;
        if (starts(b, 'R', 'I', 'F', 'F') && b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Format.WEBP;
        return null;
    }

    /** Dimensiones desde la cabecera, sin decodificar los píxeles (protección contra bombas de descompresión). */
    public static Header header(byte[] b) throws UnreadableException {
        Format f = sniff(b);
        if (f == null) throw new UnreadableException("Firma desconocida");
        return switch (f) {
            case PNG -> png(b);
            case JPEG -> jpeg(b);
            case WEBP -> webp(b);
        };
    }

    private static Header png(byte[] b) throws UnreadableException {
        // Firma (8) + longitud (4) + "IHDR" (4) + ancho (4) + alto (4)
        if (b.length < 24 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') throw new UnreadableException("PNG sin IHDR");
        long w = u32be(b, 16), h = u32be(b, 20);
        if (w <= 0 || h <= 0 || w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) throw new UnreadableException("PNG con dimensiones no válidas");
        return new Header(Format.PNG, (int) w, (int) h, 1);
    }

    private static Header jpeg(byte[] b) throws UnreadableException {
        int i = 2, orientation = 1;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xFF) != 0xFF) throw new UnreadableException("JPEG con segmentos dañados");
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { i++; continue; }                         // relleno
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { i += 2; continue; } // sin longitud
            if (marker == 0xD9 || marker == 0xDA) break;                  // fin o datos de imagen antes del SOF
            int len = u16be(b, i + 2);
            if (len < 2 || i + 2 + len > b.length) throw new UnreadableException("JPEG truncado");
            if (marker == 0xE1 && orientation == 1) orientation = exifOrientation(b, i + 4, len - 2);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (len < 7) throw new UnreadableException("JPEG con SOF corto");
                int h = u16be(b, i + 5), w = u16be(b, i + 7);
                if (w == 0 || h == 0) throw new UnreadableException("JPEG con dimensiones no válidas");
                boolean swap = orientation >= 5 && orientation <= 8;
                return new Header(Format.JPEG, swap ? h : w, swap ? w : h, orientation);
            }
            i += 2 + len;
        }
        throw new UnreadableException("JPEG sin dimensiones");
    }

    /** Etiqueta 0x0112 del IFD0 de un segmento APP1 "Exif"; 1 si no está o no se entiende. */
    static int exifOrientation(byte[] b, int start, int len) {
        int end = start + len;
        if (len < 14 || b[start] != 'E' || b[start + 1] != 'x' || b[start + 2] != 'i' || b[start + 3] != 'f') return 1;
        int t = start + 6; // cabecera TIFF
        boolean le;
        if (b[t] == 'I' && b[t + 1] == 'I') le = true; else if (b[t] == 'M' && b[t + 1] == 'M') le = false; else return 1;
        long ifd = le ? u32le(b, t + 4) : u32be(b, t + 4);
        if (ifd < 8 || t + ifd + 2 > end) return 1;
        int p = (int) (t + ifd);
        int n = le ? u16le(b, p) : u16be(b, p);
        for (int k = 0; k < n; k++) {
            int e = p + 2 + k * 12;
            if (e + 12 > end) return 1;
            int tag = le ? u16le(b, e) : u16be(b, e);
            if (tag == 0x0112) {
                int v = le ? u16le(b, e + 8) : u16be(b, e + 8);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static Header webp(byte[] b) throws UnreadableException {
        if (b.length < 30) throw new UnreadableException("WebP truncado");
        // Integridad mínima: el tamaño del contenedor RIFF debe cuadrar con el archivo (no se decodifica el WebP).
        long riff = u32le(b, 4);
        if (riff + 8 != b.length && riff + 9 != b.length) throw new UnreadableException("WebP con tamaño RIFF incoherente");
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        int w, h;
        switch (chunk) {
            case "VP8 " -> {
                if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) throw new UnreadableException("VP8 sin código de inicio");
                w = u16le(b, 26) & 0x3FFF; h = u16le(b, 28) & 0x3FFF;
            }
            case "VP8L" -> {
                if ((b[20] & 0xFF) != 0x2F) throw new UnreadableException("VP8L sin firma");
                long bits = u32le(b, 21);
                w = (int) (bits & 0x3FFF) + 1; h = (int) ((bits >> 14) & 0x3FFF) + 1;
            }
            case "VP8X" -> {
                w = (int) u24le(b, 24) + 1; h = (int) u24le(b, 27) + 1;
            }
            default -> throw new UnreadableException("WebP desconocido");
        }
        if (w <= 0 || h <= 0) throw new UnreadableException("WebP con dimensiones no válidas");
        return new Header(Format.WEBP, w, h, 1);
    }

    // ---------- Limpieza del original (T215, M-1 y M-2 de T211) ----------

    /** Original sin metadatos. sof: marcador SOF del JPEG (0 en PNG y WebP); scans: número de SOS del JPEG. */
    public record Cleaned(byte[] data, int sof, int scans) {}

    public static final int SOF_BASELINE = 0xC0, SOF_EXTENDED = 0xC1, SOF_PROGRESSIVE = 0xC2;

    /** Fragmentos PNG auxiliares que se conservan (color y transparencia); el resto de auxiliares se quita. */
    static final Set<String> PNG_KEEP = Set.of("tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "cLLI", "bKGD", "pHYs");
    /** Fragmentos WebP que se conservan (imagen, alfa, animación y perfil de color). */
    static final Set<String> WEBP_KEEP = Set.of("VP8X", "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L");

    /**
     * Quita los metadatos del original sin recodificar los píxeles y recorre toda su estructura (sin decodificar).
     * JPEG: fuera APP1-APP15 (salvo ICC_PROFILE y Adobe), COM, JPGn y lo que va tras EOI; APP0 solo si es JFIF sin miniatura;
     * la orientación EXIF (si no es 1) se reescribe en un APP1 mínimo con solo esa etiqueta.
     * PNG: fuera los auxiliares que no están en PNG_KEEP (tEXt, zTXt, iTXt, eXIf, tIME…) y lo que va tras IEND.
     * WebP: fuera EXIF, XMP y desconocidos; indicadores de VP8X corregidos y tamaño RIFF recalculado.
     */
    public static Cleaned clean(byte[] b, Header h) throws UnreadableException {
        return switch (h.format()) {
            case JPEG -> cleanJpeg(b, h.orientation());
            case PNG -> cleanPng(b);
            case WEBP -> cleanWebp(b);
        };
    }

    private static Cleaned cleanJpeg(byte[] b, int orientation) throws UnreadableException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF); out.write(0xD8);
        boolean orientationWritten = orientation == 1;
        int i = 2, sof = 0, scans = 0;
        while (true) {
            if (i + 2 > b.length) throw new UnreadableException("JPEG sin EOI");
            if ((b[i] & 0xFF) != 0xFF) throw new UnreadableException("JPEG con segmentos dañados");
            int m = b[i + 1] & 0xFF;
            if (m == 0xFF) { i++; continue; }                                       // relleno
            if (m == 0xD9) { out.write(0xFF); out.write(0xD9); break; }             // EOI: lo que sigue se descarta
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }        // sin longitud y fuera de un barrido: se quita
            if (m == 0xD8 || m == 0x00) throw new UnreadableException("JPEG con marcador fuera de sitio");
            if (i + 4 > b.length) throw new UnreadableException("JPEG truncado");
            int len = u16be(b, i + 2), end = i + 2 + len;
            if (len < 2 || end > b.length) throw new UnreadableException("JPEG truncado");
            boolean keep;
            if (m == 0xE0) keep = false;                                            // se reescribe abajo si es JFIF sin miniatura
            else if (m >= 0xE1 && m <= 0xEF)
                keep = (m == 0xE2 && ascii(b, i + 4, len - 2, "ICC_PROFILE\0")) || (m == 0xEE && ascii(b, i + 4, len - 2, "Adobe"));
            else keep = m != 0xFE && !(m >= 0xF0 && m <= 0xFD);                     // COM y JPGn fuera
            if (m == 0xE0 && len - 2 >= 14 && ascii(b, i + 4, len - 2, "JFIF\0") && b[i + 16] == 0 && b[i + 17] == 0) {
                out.write(0xFF); out.write(0xE0); out.write(0); out.write(16);
                out.write(b, i + 4, 14);
            }
            if (keep && !orientationWritten) { writeOrientation(out, orientation); orientationWritten = true; }
            boolean isSof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (isSof) {
                if (sof != 0) throw new UnreadableException("JPEG con varios SOF");
                sof = m;
            }
            if (keep) out.write(b, i, end - i);
            i = end;
            if (m == 0xDA) {                                                        // SOS: copia los datos del barrido
                if (sof == 0) throw new UnreadableException("JPEG con SOS antes del SOF");
                scans++;
                int j = i;
                while (true) {
                    if (j + 1 >= b.length) throw new UnreadableException("JPEG truncado");
                    if ((b[j] & 0xFF) == 0xFF) {
                        int n = b[j + 1] & 0xFF;
                        if (n == 0x00 || (n >= 0xD0 && n <= 0xD7)) { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                out.write(b, i, j - i);
                i = j;
            }
        }
        if (sof == 0 || scans == 0) throw new UnreadableException("JPEG sin imagen");
        return new Cleaned(out.toByteArray(), sof, scans);
    }

    /** APP1 Exif mínimo (big endian) con solo la etiqueta de orientación (0x0112). */
    private static void writeOrientation(ByteArrayOutputStream out, int orientation) {
        byte[] seg = {(byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        out.write(seg, 0, seg.length);
    }

    private static Cleaned cleanPng(byte[] b) throws UnreadableException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int i = 8;
        while (true) {
            if (i + 12 > b.length) throw new UnreadableException("PNG sin IEND");
            long len = u32be(b, i);
            if (len > b.length - i - 12L) throw new UnreadableException("PNG truncado");
            String type = new String(b, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int total = 12 + (int) len;
            boolean critical = Character.isUpperCase(type.charAt(0));
            if (critical || PNG_KEEP.contains(type)) out.write(b, i, total);
            i += total;
            if (type.equals("IEND")) break;
        }
        return new Cleaned(out.toByteArray(), 0, 0);
    }

    private static Cleaned cleanWebp(byte[] b) throws UnreadableException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 12);
        long riffEnd = Math.min(b.length, u32le(b, 4) + 8);
        int i = 12;
        while (i + 8 <= riffEnd) {
            String fourcc = new String(b, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = u32le(b, i + 4);
            if (size > riffEnd - i - 8) throw new UnreadableException("WebP con un fragmento truncado");
            if (WEBP_KEEP.contains(fourcc)) {
                byte[] chunk = Arrays.copyOfRange(b, i, i + 8 + (int) size);
                if (fourcc.equals("VP8X") && size >= 1) chunk[8] &= (byte) ~0x0C; // sin indicadores EXIF (0x08) ni XMP (0x04)
                out.write(chunk, 0, chunk.length);
                if ((size & 1) == 1) out.write(0);
            }
            i += 8 + (int) size + (int) (size & 1);
        }
        byte[] r = out.toByteArray();
        long riff = r.length - 8L;
        for (int k = 0; k < 4; k++) r[4 + k] = (byte) (riff >>> (8 * k));
        return new Cleaned(r, 0, 0);
    }

    private static boolean ascii(byte[] b, int start, int len, String s) {
        if (len < s.length()) return false;
        for (int k = 0; k < s.length(); k++) if (b[start + k] != (byte) s.charAt(k)) return false;
        return true;
    }

    // ---------- Variantes ----------

    /** true si ImageIO (sin librerías externas) sabe decodificar el formato. */
    public static boolean decodable(Format f) { return f == Format.JPEG || f == Format.PNG; }

    /**
     * Decodifica (submuestreada a ≤ 4K) y genera las variantes. focalX/focalY en [0, 1] (punto focal del recorte).
     * Cualquier aviso del decodificador (archivo truncado) se trata como ilegible.
     */
    public static Processed process(byte[] data, Header h, double focalX, double focalY) throws UnreadableException {
        if (!decodable(h.format())) return new Processed(List.of(), null);
        BufferedImage img = orient(decode(data, h), h.orientation());
        List<Variant> out = new ArrayList<>();
        for (int w : WIDTHS) {
            if (w > h.width()) continue; // ninguna variante mayor que el original
            int tw = Math.min(w, img.getWidth());
            int th = Math.max(1, (int) Math.round((double) img.getHeight() * tw / img.getWidth()));
            out.add(new Variant("w" + w, tw, th, jpeg(resize(img, tw, th), 0.80f)));
        }
        // Vertical 9:19,5 alrededor del punto focal, sin superar el alto disponible.
        Rectangle pc = cover(img.getWidth(), img.getHeight(), PORTRAIT_W, PORTRAIT_H, focalX, focalY);
        int ph = Math.min(PORTRAIT_H, pc.height), pw = Math.max(1, (int) Math.round(ph * (double) PORTRAIT_W / PORTRAIT_H));
        out.add(new Variant("p1080", pw, ph, jpeg(resize(crop(img, pc), pw, ph), 0.80f)));
        Rectangle tc = cover(img.getWidth(), img.getHeight(), THUMB_W, THUMB_H, focalX, focalY);
        int tw = Math.min(THUMB_W, tc.width), th = Math.max(1, (int) Math.round(tw * (double) THUMB_H / THUMB_W));
        out.add(new Variant("thumb", tw, th, jpeg(resize(crop(img, tc), tw, th), 0.75f)));
        int lh = Math.max(1, (int) Math.round(LQIP_W * (double) img.getHeight() / img.getWidth()));
        String lqip = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(resize(img, LQIP_W, lh), 0.40f));
        return new Processed(out, lqip);
    }

    private static BufferedImage decode(byte[] data, Header h) throws UnreadableException {
        String format = h.format() == Format.PNG ? "png" : "jpeg";
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(format);
        if (!it.hasNext()) throw new UnreadableException("Sin lector " + format);
        ImageReader r = it.next();
        List<String> warnings = new ArrayList<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            r.setInput(in, true, true); // sin metadatos: menos memoria
            r.addIIOReadWarningListener((IIOReadWarningListener) (src, w) -> warnings.add(w));
            long px = (long) h.width() * h.height();
            int s = (int) Math.max(1, Math.ceil(Math.sqrt((double) px / MAX_DECODE_PIXELS)));
            boolean swap = h.orientation() >= 5;
            int storedW = swap ? h.height() : h.width(), storedH = swap ? h.width() : h.height();
            if (r.getWidth(0) != storedW || r.getHeight(0) != storedH) throw new UnreadableException("Dimensiones incoherentes");
            ImageReadParam p = r.getDefaultReadParam();
            p.setSourceSubsampling(s, s, 0, 0);
            BufferedImage img = r.read(0, p);
            if (!warnings.isEmpty()) throw new UnreadableException("Imagen dañada: " + warnings.get(0));
            return img;
        } catch (IOException | RuntimeException e) {
            throw new UnreadableException("No se puede decodificar: " + e.getMessage());
        } finally {
            r.dispose();
        }
    }

    /** Aplica la orientación EXIF (2-8) para que las variantes se vean como el original en el navegador. */
    static BufferedImage orient(BufferedImage src, int o) {
        BufferedImage rgb = toRgb(src);
        if (o <= 1 || o > 8) return rgb;
        int w = rgb.getWidth(), h = rgb.getHeight();
        boolean swap = o >= 5;
        AffineTransform t = switch (o) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8
        };
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try { g.drawImage(rgb, t, null); } finally { g.dispose(); }
        return out;
    }

    /** RGB opaco: la transparencia se pinta sobre el color de la página (#0b0d10). */
    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new Color(0x0b0d10));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally { g.dispose(); }
        return out;
    }

    /** Rectángulo con la proporción tw:th que cubre la imagen, centrado en el punto focal. */
    static Rectangle cover(int w, int h, int tw, int th, double fx, double fy) {
        int cw, ch;
        if ((double) w / h > (double) tw / th) { ch = h; cw = Math.max(1, (int) Math.round(h * (double) tw / th)); }
        else { cw = w; ch = Math.max(1, (int) Math.round(w * (double) th / tw)); }
        int x = clamp((int) Math.round(fx * w - cw / 2.0), 0, w - cw);
        int y = clamp((int) Math.round(fy * h - ch / 2.0), 0, h - ch);
        return new Rectangle(x, y, cw, ch);
    }

    private static BufferedImage crop(BufferedImage img, Rectangle r) { return img.getSubimage(r.x, r.y, r.width, r.height); }

    /** Reducción por pasos a la mitad (evita el aliasing de un solo salto grande) y paso final bilineal. */
    static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return cur.getWidth() == w && cur.getHeight() == h && cur.getType() == BufferedImage.TYPE_INT_RGB ? cur : draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally { g.dispose(); }
        return out;
    }

    /** JPEG progresivo con la calidad indicada. */
    static byte[] jpeg(BufferedImage img, float quality) {
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bos)) {
            wr.setOutput(out);
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            wr.write(null, new IIOImage(img, null, null), p);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo codificar el JPEG", e);
        } finally { wr.dispose(); }
        return bos.toByteArray();
    }

    // ---------- bytes ----------

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static boolean starts(byte[] b, int... sig) {
        if (b == null || b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != sig[i]) return false;
        return true;
    }

    private static int u16be(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int u16le(byte[] b, int i) { return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }
    private static long u24le(byte[] b, int i) { return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16); }
    private static long u32be(byte[] b, int i) { return ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL); }
    private static long u32le(byte[] b, int i) { return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24); }
}
