package com.physicalchange.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportaciones de tablas (ADR-004 §7): CSV para Excel en español (UTF-8 con BOM, ";" y coma decimal) y XLSX mínimo
 * escrito a mano (una hoja, sin estilos salvo el formato de fecha). Celdas: String, Number (BigDecimal, Integer, Long) o LocalDate.
 * Todo texto que empiece por = + - @ tabulador o retorno se antepone con "'" (inyección de fórmulas); números y fechas nunca son texto.
 */
public final class Spreadsheet {
    private Spreadsheet() {}

    public static String safeText(String s) {
        if (s == null) return "";
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    public static byte[] csv(List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(csvCell(row.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvCell(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return b.toPlainString().replace('.', ',');
        if (v instanceof Number n) return n.toString();
        if (v instanceof LocalDate d) return d.toString();
        String s = safeText(v.toString());
        return s.matches("(?s).*[;\"\r\n].*") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    public static byte[] xlsx(String sheetName, List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>""");
            put(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""");
            put(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>""".formatted(xml(sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName)));
            put(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""");
            // Estilo 1 = formato de fecha integrado 14 (dd/mm/aaaa según la configuración regional de Excel).
            put(zip, "xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
                <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf/></cellStyleXfs>
                <cellXfs count="2"><xf/><xf numFmtId="14" applyNumberFormat="1"/></cellXfs>
                </styleSheet>""");
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                sheet.append("<row r=\"").append(r + 1).append("\">");
                List<Object> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) sheet.append(xlsxCell(col(c) + (r + 1), row.get(c)));
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String xlsxCell(String ref, Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return "<c r=\"" + ref + "\"><v>" + b.toPlainString() + "</v></c>";
        if (v instanceof Number n) return "<c r=\"" + ref + "\"><v>" + n + "</v></c>";
        if (v instanceof LocalDate d) {
            long serial = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d);
            return "<c r=\"" + ref + "\" s=\"1\"><v>" + serial + "</v></c>";
        }
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + xml(safeText(v.toString())) + "</t></is></c>";
    }

    private static String col(int i) {
        StringBuilder s = new StringBuilder();
        for (int n = i + 1; n > 0; n = (n - 1) / 26) s.insert(0, (char) ('A' + (n - 1) % 26));
        return s.toString();
    }

    /** Escapa XML y quita los caracteres de control que XML 1.0 no admite. */
    private static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> { if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') b.append(ch); }
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
