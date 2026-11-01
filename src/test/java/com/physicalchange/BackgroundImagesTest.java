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
