package com.physicalchange.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Conversión de fechas para consultas por rango (inicio inclusivo, fin exclusivo). */
public final class DateTimes {
    private DateTimes() {}

    public static LocalDateTime startOfDay(LocalDate d) { return d.atStartOfDay(); }

    /** Primer instante del día siguiente: sirve como límite superior exclusivo. */
    public static LocalDateTime endExclusive(LocalDate d) { return d.plusDays(1).atStartOfDay(); }
}
