package com.physicalchange.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Conversión de fechas para consultas por rango (inicio inclusivo, fin exclusivo). */
public final class DateTimes {
    private DateTimes() {}

    public static LocalDateTime startOfDay(LocalDate d) { return d.atStartOfDay(); }

    /** Primer instante del día siguiente: sirve como límite superior exclusivo. */
    public static LocalDateTime endExclusive(LocalDate d) { return d.plusDays(1).atStartOfDay(); }

    /** 21/10/2026, para textos que ve el usuario. */
    public static String ddMMyyyy(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }
}
