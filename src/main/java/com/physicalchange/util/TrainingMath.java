package com.physicalchange.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cálculos de entrenamiento: volumen, 1RM estimado y porcentajes de cumplimiento. */
public final class TrainingMath {
    private TrainingMath() {}

    /** Volumen de una serie = peso x repeticiones (kg). */
    public static BigDecimal volume(BigDecimal weightKg, int reps) {
        return weightKg.multiply(BigDecimal.valueOf(reps)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 1RM estimado con la fórmula de Epley: peso x (1 + reps / 30). Con 1 repetición es el propio peso. */
    public static BigDecimal estimated1Rm(BigDecimal weightKg, int reps) {
        if (reps <= 1) return weightKg.setScale(2, RoundingMode.HALF_UP);
        return weightKg.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(reps).divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Porcentaje realizado sobre lo planificado, acotado a 0..100. */
    public static int percent(int done, int planned) {
        if (planned <= 0) return 0;
        return (int) Math.min(100L, Math.round(done * 100.0 / planned));
    }

    public static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
