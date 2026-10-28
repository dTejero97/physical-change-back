package com.physicalchange.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contador por ventana fija en memoria (T141). Suficiente con una sola instancia de Render;
 * si se escalan instancias, cada una cuenta por separado (pasar entonces a Redis o similar).
 */
public class RateLimiter {
    private static final int MAX_KEYS = 20_000;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();   // [inicio de ventana (ms), contador]

    /** Suma una petición. Devuelve 0 si se permite, o los segundos que faltan para que se pueda volver a intentar. */
    public long hit(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_KEYS) windows.values().removeIf(w -> now - w[0] >= windowMs);
        long[] w = windows.compute(key, (k, old) -> {
            if (old == null || now - old[0] >= windowMs) return new long[]{now, 1};
            old[1]++;
            return old;
        });
        return w[1] <= limit ? 0 : Math.max(1, (w[0] + windowMs - now + 999) / 1000);
    }

    /** Segundos de bloqueo pendientes sin sumar una petición (0 si no hay bloqueo). */
    public long blockedFor(String key, int limit, long windowMs) {
        long[] w = windows.get(key);
        long now = System.currentTimeMillis();
        if (w == null || now - w[0] >= windowMs || w[1] < limit) return 0;
        return Math.max(1, (w[0] + windowMs - now + 999) / 1000);
    }

    public void reset(String key) { windows.remove(key); }
}
