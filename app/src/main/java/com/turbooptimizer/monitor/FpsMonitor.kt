package com.turbooptimizer.monitor

import android.util.Log
import com.turbooptimizer.core.shizuku.CommandPolicy
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * FPS del juego en primer plano, por orden de preferencia (todo vía Shizuku, solo lectura):
 *
 * 1. PRINCIPAL: `dumpsys SurfaceFlinger --latency <capa>`. Cada fila trae el instante real en que
 *    SurfaceFlinger presentó el fotograma (última columna). Se busca la capa del paquete con
 *    `dumpsys SurfaceFlinger --list` y se usa la que más recientemente presentó.
 * 2. ALTERNATIVO: `service call SurfaceFlinger 1013` (contador global de composiciones, delta entre muestras).
 *    Mezcla todas las capas, así que es menos preciso.
 * 3. Sin datos: null («N/D»). Nunca se inventa un valor.
 *
 * Limitaciones: mide fotogramas presentados por el sistema, no los que dibuja el juego. Si la pantalla
 * no cambia, SurfaceFlinger no presenta nada nuevo y el valor baja a 0. La frecuencia del panel
 * no se usa como FPS. Los logs con tag [TAG] son diagnóstico temporal.
 *
 * @param maxRefreshHz frecuencia máxima de la pantalla; muestras por encima se consideran erróneas.
 */
class FpsMonitor(
    private val shizuku: ShizukuManager,
    private val maxRefreshHz: () -> Float = { 240f },
) {
    private enum class Method { LATENCY, COMPOSITE }

    private sealed interface Reading {
        data class Value(val fps: Float) : Reading
        /** Primera muestra del método alternativo: aún no hay referencia. No cuenta como fallo. */
        data object Warmup : Reading
        data object Failed : Reading
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow(NO_MEASUREMENT)

    /** Estado legible de la última muestra (diagnóstico y «N/D» explicado en la UI). */
    val status: StateFlow<String> = _status.asStateFlow()
    private var failReason: String = NO_MEASUREMENT
    private var cachedLayer: String? = null
    private var lastCount: Long? = null
    private var lastTimeNs = 0L
    private var smoothed: Float? = null
    private var lastMethod: Method? = null
    private var consecutiveFailures = 0
    private var samples = 0

    /** false tras [MAX_FAILURES] fallos seguidos; [reset] lo vuelve a intentar (p. ej. en otra sesión). */
    val supported: Boolean get() = shizuku.isReady && consecutiveFailures < MAX_FAILURES

    suspend fun reset() = mutex.withLock {
        _status.value = NO_MEASUREMENT
        cachedLayer = null
        lastCount = null
        lastTimeNs = 0L
        smoothed = null
        lastMethod = null
        consecutiveFailures = 0
        samples = 0
    }

    /**
     * Toma una muestra. Devuelve null si no hay referencia todavía, si la muestra no es fiable o si el
     * sistema no permite la consulta. [packageName] es el juego en primer plano (método principal).
     * Pensado para llamarse ~1 vez por segundo.
     */
    suspend fun sample(packageName: String? = null): Float? = mutex.withLock {
        if (!shizuku.isReady) {
            _status.value = "Requiere Shizuku"
            return@withLock null
        }
        if (!supported) {
            _status.value = "Sin datos: fallos repetidos (reinicia la sesión)"
            return@withLock null
        }
        samples++

        val latency = packageName?.takeIf { CommandPolicy.isValidPackage(it) }?.let { sampleLatency(it) }
        val reading: Reading = if (latency is Reading.Value) latency else sampleComposite()
        val method = if (latency is Reading.Value) Method.LATENCY else Method.COMPOSITE

        val fps = when (reading) {
            is Reading.Value -> reading.fps
            Reading.Warmup -> {
                _status.value = "Midiendo…"
                return@withLock null
            }
            Reading.Failed -> {
                consecutiveFailures++
                cachedLayer = null
                _status.value = "N/D: ${failReason}"
                Log.w(TAG, "Sin FPS (fallo $consecutiveFailures/$MAX_FAILURES): $failReason")
                return@withLock null
            }
        }
        consecutiveFailures = 0
        _status.value = if (method == Method.LATENCY) "Presentados por SurfaceFlinger (capa del juego)"
        else "Contador global de SurfaceFlinger (aproximado)"

        if (method != lastMethod) {
            Log.i(TAG, "Método de FPS: $method")
            smoothed = null
            lastMethod = method
        }
        if (samples % LOG_EVERY == 1) Log.i(TAG, "Muestra #$samples: ${"%.1f".format(fps)} FPS via $method")

        if (fps > maxRefreshHz().coerceAtLeast(30f) * TOLERANCE) {
            Log.w(TAG, "Muestra descartada, valor imposible: $fps")
            return@withLock smoothed
        }

        // Media móvil exponencial: evita saltos bruscos en el overlay.
        val value = smoothed?.let { it + SMOOTHING * (fps - it) } ?: fps
        smoothed = value
        value
    }

    /** Método principal. Devuelve [Reading.Failed] si ninguna capa del paquete da datos utilizables. */
    private suspend fun sampleLatency(pkg: String): Reading {
        // 1) Reutilizar la capa conocida mientras siga presentando fotogramas recientes.
        cachedLayer?.let { layer ->
            val stats = latencyFor(layer)
            if (stats != null && System.nanoTime() - stats.lastPresentNs <= STALE_NS) return Reading.Value(stats.fps)
        }
        cachedLayer = null

        // 2) Buscar capas candidatas del paquete y quedarse con la que presentó más recientemente.
        val list = shizuku.exec(ShellCommands.surfaceFlingerLayers(), CALL_TIMEOUT_MS)
        if (!list.isSuccess) {
            Log.w(TAG, "--list falló: code=${list.exitCode} out=${list.stdout.take(160)}")
            failReason = "dumpsys SurfaceFlinger --list falló (código ${list.exitCode})"
            return Reading.Failed
        }
        val candidates = parseLayerNames(list.stdout, pkg)
        Log.d(TAG, "Capas para $pkg: ${candidates.size} candidatas. Primeras líneas de --list: ${list.stdout.lines().take(3)}")

        var best: Pair<String, LatencyStats>? = null
        for (layer in candidates) {
            val stats = latencyFor(layer) ?: continue
            if (best == null || stats.lastPresentNs > best.second.lastPresentNs) best = layer to stats
        }
        val (layer, stats) = best ?: run {
            failReason = if (candidates.isEmpty()) "no hay capa del juego en SurfaceFlinger"
            else "la capa del juego no devolvió marcas de presentación"
            return Reading.Failed
        }
        cachedLayer = layer
        Log.d(TAG, "Capa elegida: $layer, fps=${stats.fps}")
        return Reading.Value(stats.fps)
    }

    private suspend fun latencyFor(layer: String): LatencyStats? {
        val r = shizuku.exec(ShellCommands.surfaceFlingerLatency(layer), CALL_TIMEOUT_MS)
        val nowNs = System.nanoTime()
        if (!r.isSuccess) {
            Log.d(TAG, "--latency '$layer' falló: code=${r.exitCode} out=${r.stdout.take(120)}")
            return null
        }
        val presents = parseLatencyPresents(r.stdout)
        val stats = computeLatencyStats(presents, nowNs)
        Log.d(TAG, "--latency '$layer': ${presents.size} presentaciones válidas, stats=$stats")
        return stats?.takeIf { it.fps <= maxRefreshHz().coerceAtLeast(30f) * TOLERANCE }
    }

    /** Método alternativo: contador compuesto global (delta entre muestras). */
    private suspend fun sampleComposite(): Reading {
        val startNs = System.nanoTime()
        val result = shizuku.exec(ShellCommands.surfaceFlingerFrames(), CALL_TIMEOUT_MS)
        val endNs = System.nanoTime()

        val count = if (result.isSuccess) parseFrameCount(result.stdout) else null
        if (count == null) {
            Log.w(TAG, "service call 1013 sin datos válidos: code=${result.exitCode} out=${result.stdout.take(160)}")
            failReason = "service call 1013 sin respuesta válida (código ${result.exitCode})"
            lastCount = null // la siguiente muestra válida empieza de cero
            return Reading.Failed
        }

        // Una llamada lenta desplaza el instante de lectura: la marca se toma en el punto medio
        // y, si tardó demasiado, la muestra se usa solo como nueva referencia.
        val now = startNs + (endNs - startNs) / 2
        val slow = endNs - startNs > MAX_CALL_LATENCY_NS
        val prevCount = lastCount
        val prevTime = lastTimeNs
        lastCount = count
        lastTimeNs = now
        if (prevCount == null || slow) return Reading.Warmup

        val seconds = (now - prevTime) / 1_000_000_000.0
        if (seconds < MIN_INTERVAL_S) return Reading.Warmup

        var delta = count - prevCount
        if (delta < 0) delta += COUNTER_RANGE // el contador es un int32 que da la vuelta
        return Reading.Value((delta / seconds).toFloat())
    }

    companion object {
        private const val TAG = "FpsMonitor"
        const val NO_MEASUREMENT = "Sin medición"
        private const val MAX_FAILURES = 5
        private const val CALL_TIMEOUT_MS = 2_000L
        private const val MAX_CALL_LATENCY_NS = 400_000_000L
        private const val MIN_INTERVAL_S = 0.25
        private const val COUNTER_RANGE = 0x1_0000_0000L
        private const val TOLERANCE = 1.2f
        private const val SMOOTHING = 0.5f
        private const val MAX_CANDIDATES = 6
        private const val LOG_EVERY = 10
        /** Sin presentaciones en este intervalo, el juego no está presentando (0 FPS reales). */
        internal const val STALE_NS = 1_500_000_000L
        /** Ventana de cálculo de FPS. */
        internal const val WINDOW_NS = 2_000_000_000L

        private val PARCEL_INT = Regex("""Parcel\(\s*([0-9a-fA-F]{8})\b""")
        private val LATENCY_ROW = Regex("""^(\d+)\s+(\d+)\s+(\d+)$""")

        /** Resultado de una capa: FPS calculados y última presentación (reloj monótono, ns). */
        internal data class LatencyStats(val fps: Float, val lastPresentNs: Long)

        /**
         * Extrae el contador de `Result: Parcel(0002a3f1    '....')`.
         * SurfaceFlinger responde un único int32, impreso como 8 dígitos hexadecimales.
         * Cualquier otro formato (p. ej. `Parcel(Error: 0xffffffff ...)`) devuelve null:
         * es preferible mostrar «N/D» a un FPS inventado.
         */
        internal fun parseFrameCount(output: String): Long? {
            if (output.contains("Error", ignoreCase = true)) return null
            val hex = PARCEL_INT.find(output)?.groupValues?.get(1) ?: return null
            return hex.toLongOrNull(16)
        }

        /**
         * Nombres de capa de `dumpsys SurfaceFlinger --list` que pertenecen al paquete.
         * Se exige que el paquete no vaya seguido de otro segmento (com.a no coincide con com.a.b).
         */
        internal fun parseLayerNames(output: String, pkg: String): List<String> {
            val owner = Regex("(?<![A-Za-z0-9_.])${Regex.escape(pkg)}(?![A-Za-z0-9_.])")
            return output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && owner.containsMatchIn(it) && CommandPolicy.isValidLayerName(it) }
                .distinct()
                .take(MAX_CANDIDATES)
                .toList()
        }

        /**
         * Instantes de presentación de `dumpsys SurfaceFlinger --latency <capa>`.
         * La primera línea es el periodo de refresco (una sola cifra) y se ignora. Cada fila tiene
         * tres columnas: instante deseado, inicio de render y presentación real (la última).
         * Los valores 0 y INT64_MAX son filas pendientes y se descartan.
         */
        internal fun parseLatencyPresents(output: String): List<Long> =
            output.lineSequence()
                .mapNotNull { line -> LATENCY_ROW.find(line.trim())?.groupValues?.get(3)?.toLongOrNull() }
                .filter { it > 0L && it != Long.MAX_VALUE }
                .toList()

        /**
         * FPS a partir de las presentaciones de una capa. Usa los fotogramas de la última ventana
         * (o los dos últimos si la ventana tiene pocos). Devuelve null si no hay datos suficientes
         * o si las marcas están en el futuro (reloj incompatible). Sin presentaciones recientes, 0.
         */
        internal fun computeLatencyStats(presents: List<Long>, nowNs: Long): LatencyStats? {
            val valid = presents.filter { it > 0L && it != Long.MAX_VALUE }.sorted()
            if (valid.size < 2) return null
            val last = valid.last()
            if (last - nowNs > 100_000_000L) return null // marcas en el futuro: reloj no comparable
            if (nowNs - last > STALE_NS) return LatencyStats(0f, last)

            val recent = valid.filter { it >= nowNs - WINDOW_NS }.takeIf { it.size >= 2 } ?: valid.takeLast(2)
            val spanS = (recent.last() - recent.first()) / 1_000_000_000.0
            if (spanS <= 0.0) return null
            return LatencyStats(((recent.size - 1) / spanS).toFloat(), last)
        }
    }
}
