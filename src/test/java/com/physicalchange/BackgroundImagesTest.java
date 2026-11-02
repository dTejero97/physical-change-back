package com.physicalchange;

import com.physicalchange.service.BackgroundImages;
import com.physicalchange.service.BackgroundImages.Format;
import com.physicalchange.service.BackgroundImages.Header;
import com.physicalchange.service.BackgroundImages.Processed;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Lectura de cabeceras y variantes de los fondos (T208, ADR-015), sin Spring. Las imágenes se generan en el test. */
class BackgroundImagesTest {

    static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y += 8) for (int x = 0; x < w; x += 8) {
            int rgb = ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | 0x40;
            for (int dy = 0; dy < 8 && y + dy < h; dy++) for (int dx = 0; dx < 8 && x + dx < w; dx++) img.setRGB(x + dx, y + dy, rgb);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    /** PNG con solo la firma y el IHDR (sin píxeles): sirve para probar el límite de píxeles sin decodificar nada. */
    static byte[] pngHeaderOnly(long w, long h) {
        byte[] b = new byte[33];
        int[] sig = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        for (int i = 0; i < sig.length; i++) b[i] = (byte) sig[i];
        for (int i = 0; i < 4; i++) { b[16 + i] = (byte) (w >>> (24 - 8 * i)); b[20 + i] = (byte) (h >>> (24 - 8 * i)); }
        b[24] = 8; b[25] = 2;
        return b;
    }

    /** WebP VP8X mínimo (30 bytes) con tamaño RIFF correcto. */
    static byte[] webpVp8x(int w, int h) {
        byte[] b = new byte[30];
        put(b, 0, "RIFF"); le32(b, 4, b.length - 8); put(b, 8, "WEBP"); put(b, 12, "VP8X"); le32(b, 16, 10);
        le24(b, 24, w - 1); le24(b, 27, h - 1);
        return b;
    }

    /** Inserta un APP1 Exif (big endian) con la orientación indicada justo después del SOI. */
    static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        byte[] exif = new byte[6 + tiff.length];
        put(exif, 0, "Exif"); System.arraycopy(tiff, 0, exif, 6, tiff.length);
        int len = exif.length + 2;
        byte[] out = new byte[jpeg.length + 4 + exif.length];
        out[0] = (byte) 0xFF; out[1] = (byte) 0xD8; out[2] = (byte) 0xFF; out[3] = (byte) 0xE1; out[4] = (byte) (len >> 8); out[5] = (byte) len;
        System.arraycopy(exif, 0, out, 6, exif.length);
        System.arraycopy(jpeg, 2, out, 6 + exif.length, jpeg.length - 2);
        return out;
    }

    // ---------- Imágenes con metadatos y JPEG sintéticos (T215) ----------

    static final String SECRET_MAKE = "SecretCam", SECRET_XMP = "http://ns.adobe.com/xap/1.0/ secreto", SECRET_COMMENT = "comentario-secreto",
            SECRET_TRAILER = "DATOS-TRAS-EOI";

    /** Segmento JPEG con marcador y contenido. */
    static byte[] segment(int marker, byte[] payload) {
        byte[] s = new byte[4 + payload.length];
        s[0] = (byte) 0xFF; s[1] = (byte) marker; s[2] = (byte) ((payload.length + 2) >> 8); s[3] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, s, 4, payload.length);
        return s;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    /** APP1 Exif (big endian) con orientación, marca de cámara y un IFD GPS con latitud: lo que deja un móvil. */
    static byte[] exifWithGps(int orientation) {
        java.nio.ByteBuffer t = java.nio.ByteBuffer.allocate(102);
        t.put(new byte[]{'M', 'M', 0, 42}).putInt(8);
        t.putShort((short) 3);
        t.putShort((short) 0x010F).putShort((short) 2).putInt(10).putInt(50);         // Make → 50
        t.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        t.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(60);          // GPS IFD → 60
        t.putInt(0);
        t.put((SECRET_MAKE + "\0").getBytes());                                       // 50..60
        t.putShort((short) 1);
        t.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(78);          // GPSLatitude → 78
        t.putInt(0);
        t.putInt(40).putInt(1).putInt(25).putInt(1).putInt(7).putInt(1);             // 78..102
        return concat("Exif\0\0".getBytes(), t.array());
    }

    /** JPEG con EXIF/GPS, XMP, IPTC (APP13), comentario y datos tras EOI. */
    static byte[] jpegWithMetadata(byte[] jpeg, int orientation) {
        byte[] trailer = SECRET_TRAILER.getBytes();
        return concat(Arrays.copyOf(jpeg, 2),
                segment(0xE1, exifWithGps(orientation)),
                segment(0xE1, SECRET_XMP.getBytes()),
                segment(0xED, "Photoshop 3.0\0 IPTC secreto".getBytes()),
                segment(0xFE, SECRET_COMMENT.getBytes()),
                Arrays.copyOfRange(jpeg, 2, jpeg.length), trailer);
    }

    /** PNG con fragmentos tEXt, iTXt, eXIf y tIME añadidos antes de IEND. */
    static byte[] pngWithMetadata(byte[] png) {
        int iend = png.length - 12;
        return concat(Arrays.copyOf(png, iend),
                pngChunk("tEXt", ("Comment\0" + SECRET_COMMENT).getBytes()),
                pngChunk("iTXt", ("XML:com.adobe.xmp\0\0\0\0\0" + SECRET_XMP).getBytes()),
                pngChunk("eXIf", Arrays.copyOfRange(exifWithGps(1), 6, 6 + 102)),
                pngChunk("tIME", new byte[]{0x07, (byte) 0xEA, 11, 1, 10, 0, 0}),
                Arrays.copyOfRange(png, iend, png.length), SECRET_TRAILER.getBytes());
    }

    static byte[] pngChunk(String type, byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(type.getBytes()); crc.update(data);
        return java.nio.ByteBuffer.allocate(12 + data.length).putInt(data.length).put(type.getBytes()).put(data).putInt((int) crc.getValue()).array();
    }

    /** WebP VP8X con indicadores EXIF y XMP, y fragmentos EXIF (tamaño impar), XMP y uno desconocido. */
    static byte[] webpWithMetadata(int w, int h) {
        byte[] vp8x = new byte[18];
        put(vp8x, 0, "VP8X"); le32(vp8x, 4, 10); vp8x[8] = 0x0C | 0x10; le24(vp8x, 12, w - 1); le24(vp8x, 15, h - 1);
        byte[] exif = webpChunk("EXIF", SECRET_MAKE.getBytes());   // 9 bytes: lleva relleno
        byte[] xmp = webpChunk("XMP ", SECRET_XMP.getBytes());
        byte[] unknown = webpChunk("ZZZZ", SECRET_COMMENT.getBytes());
        byte[] body = concat("WEBP".getBytes(), vp8x, exif, xmp, unknown);
        byte[] riff = new byte[8];
        put(riff, 0, "RIFF"); le32(riff, 4, body.length);
        return concat(riff, body);
    }

    static byte[] webpChunk(String fourcc, byte[] data) {
        byte[] c = new byte[8 + data.length + (data.length & 1)];
        put(c, 0, fourcc); le32(c, 4, data.length); System.arraycopy(data, 0, c, 8, data.length);
        return c;
    }

    /** JPEG sintético (sin píxeles válidos) con el SOF y el número de barridos indicados: solo sirve para la validación de estructura. */
    static byte[] syntheticJpeg(int sof, int w, int h, int scans) {
        byte[] sofPayload = {8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1};
        byte[] sos = {3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0};
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        o.writeBytes(segment(sof, sofPayload));
        for (int k = 0; k < scans; k++) {
            o.writeBytes(segment(0xDA, sos));
            o.writeBytes(new byte[]{0x12, 0x34, (byte) 0xFF, 0x00, 0x56});
        }
        o.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return o.toByteArray();
    }

    /** JPEG progresivo real (SOF2) generado con ImageIO. */
    static byte[] progressiveJpeg(int w, int h) throws Exception {
        BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image(w, h, "png")));
        javax.imageio.ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var out = ImageIO.createImageOutputStream(bos)) {
            wr.setOutput(out);
            javax.imageio.ImageWriteParam p = wr.getDefaultWriteParam();
            p.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            wr.write(null, new javax.imageio.IIOImage(img, null, null), p);
        } finally { wr.dispose(); }
        return bos.toByteArray();
    }

    static boolean containsAscii(byte[] b, String s) {
        return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).contains(s);
    }

    static void put(byte[] b, int at, String s) { for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i); }
    static void le32(byte[] b, int at, long v) { for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >>> (8 * i)); }
    static void le24(byte[] b, int at, int v) { for (int i = 0; i < 3; i++) b[at + i] = (byte) (v >>> (8 * i)); }

    @Test void fondosInicialesCoincidenConLaMigracionV18() throws Exception {
        // Mismos valores que el INSERT de V18__fondos_pagina_carga.sql
        Map<String, long[]> expected = Map.of("fondo3", new long[]{3800, 2376, 1377054}, "fondo4", new long[]{5440, 3310, 298553});
        for (var e : expected.entrySet()) {
            byte[] b;
            try (InputStream in = getClass().getResourceAsStream("/backgrounds/" + e.getKey() + ".jpg")) { b = in.readAllBytes(); }
            Header h = BackgroundImages.header(b);
            assertEquals(Format.JPEG, h.format());
            assertArrayEquals(e.getValue(), new long[]{h.width(), h.height(), b.length}, e.getKey());
        }
        // fondo1 y fondo2 quedan fuera (L-08): no se distribuyen.
        assertNull(getClass().getResource("/backgrounds/fondo1.jpg"));
        assertNull(getClass().getResource("/backgrounds/fondo2.jpg"));
    }

    @Test void cabecerasSinDecodificar() throws Exception {
        assertEquals(new Header(Format.PNG, 50000, 40000, 1), BackgroundImages.header(pngHeaderOnly(50000, 40000)));
        assertEquals(new Header(Format.WEBP, 3840, 2160, 1), BackgroundImages.header(webpVp8x(3840, 2160)));
        Header j = BackgroundImages.header(image(2000, 1200, "jpg"));
        assertEquals(new Header(Format.JPEG, 2000, 1200, 1), j);
        byte[] badRiff = webpVp8x(3840, 2160); badRiff[4] = 0x7F;
        assertThrows(BackgroundImages.UnreadableException.class, () -> BackgroundImages.header(badRiff));
        assertThrows(BackgroundImages.UnreadableException.class, () -> BackgroundImages.header(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 1, 2, 3}));
        assertNull(BackgroundImages.sniff("%PDF-1.4".getBytes()));
    }

    @Test void variantesDeUnJpegNoSuperanElOriginal() throws Exception {
        byte[] src = image(1920, 1080, "jpg");
        Processed p = BackgroundImages.process(src, BackgroundImages.header(src), 0.5, 0.5);
        Map<String, int[]> v = p.variants().stream().collect(Collectors.toMap(x -> x.name(), x -> new int[]{x.width(), x.height()}));
        assertArrayEquals(new int[]{768, 432}, v.get("w768"));
        assertArrayEquals(new int[]{1280, 720}, v.get("w1280"));
        assertArrayEquals(new int[]{1920, 1080}, v.get("w1920"));
        assertArrayEquals(new int[]{480, 270}, v.get("thumb"));
        assertArrayEquals(new int[]{498, 1080}, v.get("p1080")); // 9:19,5 con el alto disponible
        assertTrue(p.lqip().startsWith("data:image/jpeg;base64,"));
        assertTrue(p.lqip().length() < 2000);
        for (var x : p.variants()) assertEquals(Format.JPEG, BackgroundImages.sniff(x.data()));
    }

    @Test void imagenGrandeSeDecodificaSubmuestreada() throws Exception {
        byte[] b;
        try (InputStream in = getClass().getResourceAsStream("/backgrounds/fondo4.jpg")) { b = in.readAllBytes(); }
        Processed p = BackgroundImages.process(b, BackgroundImages.header(b), 0.5, 0.35);
        assertEquals(5, p.variants().size());
        var w1920 = p.variants().stream().filter(x -> x.name().equals("w1920")).findFirst().orElseThrow();
        assertEquals(1920, w1920.width());
    }

    @Test void orientacionExifSeAplica() throws Exception {
        byte[] src = withOrientation(image(2000, 1200, "jpg"), 6);
        Header h = BackgroundImages.header(src);
        assertEquals(1200, h.width());   // se ve vertical
        assertEquals(2000, h.height());
        assertEquals(6, h.orientation());
        Processed p = BackgroundImages.process(src, h, 0.5, 0.5);
        var w768 = p.variants().stream().filter(x -> x.name().equals("w768")).findFirst().orElseThrow();
        assertEquals(768, w768.width());
        assertEquals(1280, w768.height());
    }

    @Test void jpegTruncadoEsIlegible() throws Exception {
        byte[] src = image(1920, 1080, "jpg");
        byte[] cut = Arrays.copyOf(src, src.length / 2);
        Header h = BackgroundImages.header(cut);
        assertThrows(BackgroundImages.UnreadableException.class, () -> BackgroundImages.process(cut, h, 0.5, 0.5));
    }

    // ---------- Limpieza del original (T215) ----------

    @Test void jpegSinMetadatosConservaLaOrientacionYSeDecodifica() throws Exception {
        byte[] src = jpegWithMetadata(image(2000, 1200, "jpg"), 6);
        assertTrue(containsAscii(src, SECRET_MAKE));
        Header h = BackgroundImages.header(src);
        BackgroundImages.Cleaned c = BackgroundImages.clean(src, h);
        for (String s : new String[]{SECRET_MAKE, SECRET_XMP, SECRET_COMMENT, SECRET_TRAILER, "Photoshop", "IPTC"})
            assertFalse(containsAscii(c.data(), s), s);
        assertEquals(BackgroundImages.SOF_BASELINE, c.sof());
        assertEquals(1, c.scans());
        // Orientación reescrita en un APP1 mínimo (36 bytes, solo la etiqueta 0x0112): se sigue viendo igual (vertical).
        assertEquals(h, BackgroundImages.header(c.data()));
        assertEquals((byte) 0xD9, c.data()[c.data().length - 1]);
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(c.data()));
        assertEquals(2000, img.getWidth());
        // Se ve vertical (1200 de ancho): solo w768, p1080 y thumb (w1280/w1920 exigen ese ancho, api-fondos.md)
        assertEquals(List.of("w768", "p1080", "thumb"),
                BackgroundImages.process(c.data(), h, 0.5, 0.5).variants().stream().map(v -> v.name()).toList());
    }

    @Test void jpegSinOrientacionNoLlevaApp1() throws Exception {
        byte[] src = jpegWithMetadata(image(1920, 1080, "jpg"), 1);
        BackgroundImages.Cleaned c = BackgroundImages.clean(src, BackgroundImages.header(src));
        assertFalse(containsAscii(c.data(), "Exif"));
        assertTrue(containsAscii(c.data(), "JFIF"));   // APP0 sin miniatura se conserva
    }

    @Test void jpegProgresivoYEstructuraCompleta() throws Exception {
        byte[] prog = progressiveJpeg(1920, 1080);
        BackgroundImages.Cleaned c = BackgroundImages.clean(prog, BackgroundImages.header(prog));
        assertEquals(BackgroundImages.SOF_PROGRESSIVE, c.sof());
        assertTrue(c.scans() > 1);
        byte[] arith = syntheticJpeg(0xC9, 1920, 1080, 1);
        assertEquals(0xC9, BackgroundImages.clean(arith, BackgroundImages.header(arith)).sof());
        byte[] many = syntheticJpeg(0xC2, 1920, 1080, 200);
        assertEquals(200, BackgroundImages.clean(many, BackgroundImages.header(many)).scans());
        // Sin EOI o con dos SOF: ilegible.
        byte[] noEoi = Arrays.copyOf(prog, prog.length - 2);
        assertThrows(BackgroundImages.UnreadableException.class, () -> BackgroundImages.clean(noEoi, BackgroundImages.header(noEoi)));
        byte[] twoSof = concat(Arrays.copyOf(arith, arith.length - 2), segment(0xC0, Arrays.copyOfRange(arith, 6, 21)), new byte[]{(byte) 0xFF, (byte) 0xD9});
        assertThrows(BackgroundImages.UnreadableException.class, () -> BackgroundImages.clean(twoSof, BackgroundImages.header(twoSof)));
    }

    @Test void pngSinFragmentosDeTexto() throws Exception {
        byte[] src = pngWithMetadata(image(1920, 1080, "png"));
        BackgroundImages.Cleaned c = BackgroundImages.clean(src, BackgroundImages.header(src));
        for (String s : new String[]{"tEXt", "iTXt", "eXIf", "tIME", SECRET_COMMENT, SECRET_XMP, SECRET_MAKE, SECRET_TRAILER})
            assertFalse(containsAscii(c.data(), s), s);
        assertTrue(containsAscii(c.data(), "IDAT"));
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(c.data()));
        assertEquals(1920, img.getWidth());
        byte[] cut = Arrays.copyOf(src, 200);
        assertThrows(BackgroundImages.UnreadableException.class, () -> BackgroundImages.clean(cut, BackgroundImages.header(cut)));
    }

    @Test void webpSinExifNiXmp() throws Exception {
        byte[] src = webpWithMetadata(3840, 2160);
        Header h = BackgroundImages.header(src);
        BackgroundImages.Cleaned c = BackgroundImages.clean(src, h);
        byte[] d = c.data();
        for (String s : new String[]{"EXIF", "XMP ", "ZZZZ", SECRET_MAKE, SECRET_XMP, SECRET_COMMENT}) assertFalse(containsAscii(d, s), s);
        assertEquals(30, d.length);                       // RIFF + WEBP + VP8X
        assertEquals(d.length - 8, d[4] & 0xFF);          // tamaño RIFF recalculado
        assertEquals(0x10, d[20]);                        // indicadores EXIF y XMP apagados, alfa intacto
        assertEquals(h, BackgroundImages.header(d));
    }

    @Test void pngConTransparenciaYWebpSinVariantes() throws Exception {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        Processed p = BackgroundImages.process(out.toByteArray(), BackgroundImages.header(out.toByteArray()), 0.5, 0.5);
        assertEquals(5, p.variants().size());
        Processed w = BackgroundImages.process(webpVp8x(3840, 2160), BackgroundImages.header(webpVp8x(3840, 2160)), 0.5, 0.5);
        assertTrue(w.variants().isEmpty());
        assertNull(w.lqip());
    }
}
