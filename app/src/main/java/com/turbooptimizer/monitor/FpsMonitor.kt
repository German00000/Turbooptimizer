package com.turbooptimizer.monitor

import com.turbooptimizer.core.diagnostics.DiagLevel
import com.turbooptimizer.core.diagnostics.Diagnostics
import com.turbooptimizer.core.shizuku.CommandPolicy
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.core.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * FPS del juego en primer plano, por orden de preferencia (todo vía Shizuku, solo lectura):
 *
 * 1. PRINCIPAL: `dumpsys SurfaceFlinger --latency <capa>`. Cada fila trae instantes de presentación
 *    de SurfaceFlinger. Se busca la capa del paquete con `dumpsys SurfaceFlinger --list` y se usa la
 *    que más recientemente presentó.
 * 2. ALTERNATIVO: `service call SurfaceFlinger 1013` (contador global de composiciones, delta entre muestras).
 *    Mezcla todas las capas, así que es menos preciso.
 * 3. Sin datos: null. El motivo se expone en [status] («Midiendo…», «Sin permisos», «No compatible»…).
 *    Nunca se inventa un valor.
 *
 * Diseño temporal: los instantes de SurfaceFlinger se usan solo entre sí (diferencias entre marcas).
 * No se comparan con `System.nanoTime()`, porque la base de reloj de SurfaceFlinger no está
 * documentada y una discrepancia la dejaría sin datos para siempre. La frescura se mide localmente:
 * si la marca más reciente de la capa no cambia durante [STALE_NS], el juego no presenta nada → 0.
 *
 * Limitaciones: mide fotogramas presentados por el sistema, no los que dibuja el juego. La frecuencia
 * del panel no se usa como FPS. Los logs con tag [TAG] son diagnóstico.
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

    /** Última marca de presentación vista en una capa y el instante local en que cambió. */
    private class PresentTrack(val newest: Long, val changedAtNs: Long)

    private val mutex = Mutex()
    private val _status = MutableStateFlow(NO_MEASUREMENT)

    /** Estado legible de la última muestra (diagnóstico y placeholder explicado en la UI). */
    val status: StateFlow<String> = _status.asStateFlow()

    private val tracker = FpsTracker()
    private val _summary = MutableStateFlow<FpsSummary?>(null)
    /** Mínimo, máximo y promedio de las muestras aceptadas desde el último [reset]. */
    val summary: StateFlow<FpsSummary?> = _summary.asStateFlow()

    private var failReason: String = NO_MEASUREMENT
    private var latencyReason: String = "sin intentar"
    private var compositeReason: String = "sin intentar"
    /** true si SurfaceFlinger rechaza el contador a Shizuku (p. ej. «Operation not permitted»): no se vuelve a llamar. */
    private var compositeDenied = false
    private var lastAttemptNs = 0L
    private var lastLatencyError: String = "sin datos de latencia"
    private var cachedLayer: String? = null
    private val presentTracks = HashMap<String, PresentTrack>()
    private var lastCount: Long? = null
    private var lastTimeNs = 0L
    private var smoothed: Float? = null
    private var lastMethod: Method? = null
    private var consecutiveFailures = 0
    private var samples = 0

    /** false tras [MAX_FAILURES] fallos seguidos; [reset] lo vuelve a intentar (p. ej. en otra sesión). */
    val supported: Boolean get() = shizuku.isReady && consecutiveFailures < MAX_FAILURES

    /**
     * Cambia el estado legible y lo registra solo cuando cambia. Así el diagnóstico muestra
     * el motivo de cada N/D (sin permisos, Shizuku inactivo, reintentos, no compatible…).
     */
    private fun setStatus(value: String) {
        if (_status.value == value) return
        _status.value = value
        val level = when {
            value.startsWith("Presentados") || value.startsWith("Contador") -> DiagLevel.INFO
            value == NO_MEASUREMENT || value.startsWith(STATUS_MEASURING) -> DiagLevel.DEBUG
            else -> DiagLevel.WARN
        }
        val text = if (level == DiagLevel.INFO) "FPS medido: $value"
        else "Medidor sin FPS (se muestra «${placeholder(value)}»). Motivo: $value"
        Diagnostics.record(level, TAG, text)
    }

    suspend fun reset() = mutex.withLock {
        setStatus(NO_MEASUREMENT)
        cachedLayer = null
        presentTracks.clear()
        lastCount = null
        lastTimeNs = 0L
        smoothed = null
        lastMethod = null
        consecutiveFailures = 0
        samples = 0
        compositeDenied = false
        latencyReason = "sin intentar"
        compositeReason = "sin intentar"
        lastAttemptNs = 0L
        tracker.reset()
        _summary.value = null
    }

    /**
     * Toma una muestra. Devuelve null si no hay referencia todavía, si la muestra no es fiable o si el
     * sistema no permite la consulta (en ese caso [status] explica el motivo).
     * [packageName] es el juego en primer plano (método principal). Pensado para llamarse ~2 veces por segundo.
     */
    suspend fun sample(packageName: String? = null): Float? = mutex.withLock {
        if (!shizuku.isReady) {
            setStatus(shizukuProblemStatus())
            return@withLock null
        }
        val nowNs = System.nanoTime()
        if (!supported && nowNs - lastAttemptNs < RETRY_NS) {
            setStatus("No compatible: $failReason (nuevo intento en unos segundos)")
            return@withLock null
        }
        lastAttemptNs = nowNs
        samples++

        val latency = if (packageName != null && CommandPolicy.isValidPackage(packageName)) {
            sampleLatency(packageName)
        } else {
            latencyReason = "paquete del juego no válido"
            null
        }
        val reading: Reading = if (latency is Reading.Value) latency else sampleComposite()
        val method = if (latency is Reading.Value) Method.LATENCY else Method.COMPOSITE

        val fps = when (reading) {
            is Reading.Value -> reading.fps
            Reading.Warmup -> {
                setStatus(STATUS_MEASURING)
                return@withLock null
            }
            Reading.Failed -> {
                consecutiveFailures++
                failReason = "capa: $latencyReason · contador: $compositeReason"
                cachedLayer = null
                setStatus(if (consecutiveFailures >= MAX_FAILURES) "No compatible: $failReason" else "Reintentando: $failReason")
                Diagnostics.w(TAG, "Error al obtener FPS (fallo $consecutiveFailures de $MAX_FAILURES): $failReason")
                return@withLock null
            }
        }
        consecutiveFailures = 0
        setStatus(
            if (method == Method.LATENCY) "Presentados por SurfaceFlinger (capa del juego)"
            else "Contador global de SurfaceFlinger (aproximado)",
        )

        if (method != lastMethod) {
            Diagnostics.i(TAG, "Método de FPS: $method")
            smoothed = null
            lastMethod = method
        }
        if (samples % LOG_EVERY == 1) Diagnostics.i(TAG, "Muestra #$samples: ${"%.1f".format(fps)} FPS via $method")

        if (fps > maxRefreshHz().coerceAtLeast(30f) * TOLERANCE) {
            Diagnostics.w(TAG, "Muestra descartada, valor imposible: ${"%.1f".format(fps)} FPS")
            return@withLock smoothed
        }

        // Mínimo/máximo/promedio con los valores sin suavizar; el suavizado solo afecta al valor instantáneo.
        tracker.add(fps)
        _summary.value = tracker.summary()

        // Media móvil exponencial: evita saltos bruscos en el overlay.
        val value = smoothed?.let { it + SMOOTHING * (fps - it) } ?: fps
        smoothed = value
        value
    }

    private fun shizukuProblemStatus(): String = when (shizuku.state.value.status) {
        ShizukuStatus.NO_PERMISSION -> STATUS_NO_PERMISSION
        ShizukuStatus.NOT_RUNNING, ShizukuStatus.NOT_INSTALLED, ShizukuStatus.UNSUPPORTED -> STATUS_SHIZUKU_OFF
        ShizukuStatus.READY -> STATUS_MEASURING
    }

    /** Método principal. Devuelve [Reading.Failed] si ninguna capa del paquete da datos utilizables. */
    private suspend fun sampleLatency(pkg: String): Reading {
        // 1) Reutilizar la capa conocida mientras siga existiendo.
        cachedLayer?.let { layer ->
            latencyFor(layer)?.let { return Reading.Value(it) }
        }
        cachedLayer = null

        // 2) Buscar capas candidatas del paquete y quedarse con la que presentó más recientemente.
        val list = shizuku.exec(ShellCommands.surfaceFlingerLayers(), CALL_TIMEOUT_MS)
        if (!list.isSuccess) {
            Diagnostics.w(TAG, "dumpsys SurfaceFlinger --list falló: código ${list.exitCode}: ${list.message}")
            latencyReason = "dumpsys SurfaceFlinger --list falló (código ${list.exitCode}): ${list.message}"
            return Reading.Failed
        }
        val candidates = parseLayerNames(list.stdout, pkg)
        Diagnostics.i(TAG, "Salida de SurfaceFlinger --list: ${list.stdout.lines().size} líneas, ${candidates.size} capa(s) de $pkg")
        Diagnostics.d(TAG, "Capas candidatas de $pkg: ${candidates.joinToString()}")

        var best: Pair<String, Float>? = null
        for (layer in candidates) {
            val fps = latencyFor(layer) ?: continue
            if (best == null || fps > best.second) best = layer to fps
        }
        val (layer, fps) = best ?: run {
            latencyReason = if (candidates.isEmpty()) {
                Diagnostics.w(TAG, "Ninguna capa de $pkg en SurfaceFlinger --list (${list.stdout.lines().count { it.isNotBlank() }} líneas no vacías)")
                "no hay capa del juego en SurfaceFlinger ($pkg)"
            } else {
                "la capa del juego no devolvió marcas de presentación: $lastLatencyError"
            }
            return Reading.Failed
        }
        cachedLayer = layer
        Diagnostics.d(TAG, "Capa elegida: $layer, fps=$fps")
        return Reading.Value(fps)
    }

    /** FPS de una capa a partir de `--latency`, o null si la lectura no es utilizable. */
    private suspend fun latencyFor(layer: String): Float? {
        val r = shizuku.exec(ShellCommands.surfaceFlingerLatency(layer), CALL_TIMEOUT_MS)
        val nowNs = System.nanoTime()
        if (!r.isSuccess) {
            lastLatencyError = "código ${r.exitCode}: ${r.message}"
            Diagnostics.w(TAG, "dumpsys SurfaceFlinger --latency falló en la capa del juego: $lastLatencyError")
            return null
        }
        val presents = parseLatencyPresents(r.stdout)
        val newest = presents.maxOrNull()
        if (newest == null) {
            lastLatencyError = "sin marcas de presentación válidas (${r.stdout.lines().size} líneas)"
            Diagnostics.d(TAG, "--latency '$layer': $lastLatencyError")
            return null
        }
        val track = presentTracks[layer]
        if (track == null || track.newest != newest) presentTracks[layer] = PresentTrack(newest, nowNs)
        val changedAtNs = presentTracks.getValue(layer).changedAtNs

        val fps = computeLatencyFps(presents)
        if (fps == null) {
            lastLatencyError = "marcas insuficientes para calcular FPS (${presents.size})"
            Diagnostics.d(TAG, "--latency '$layer': $lastLatencyError")
            return null
        }
        Diagnostics.d(TAG, "--latency '$layer': ${presents.size} presentaciones, fps=$fps, stale=${isStale(changedAtNs, nowNs)}")
        val value = if (isStale(changedAtNs, nowNs)) 0f else fps
        if (value > maxRefreshHz().coerceAtLeast(30f) * TOLERANCE) {
            lastLatencyError = "FPS imposible ($value)"
            Diagnostics.w(TAG, "Error al obtener FPS: $lastLatencyError")
            return null
        }
        return value
    }

    /** Método alternativo: contador compuesto global (delta entre muestras). */
    private suspend fun sampleComposite(): Reading {
        if (compositeDenied) return Reading.Failed
        val startNs = System.nanoTime()
        val result = shizuku.exec(ShellCommands.surfaceFlingerFrames(), CALL_TIMEOUT_MS)
        val endNs = System.nanoTime()

        val count = if (result.isSuccess) parseFrameCount(result.stdout) else null
        if (count == null) {
            Diagnostics.w(TAG, "service call 1013 sin datos válidos: código ${result.exitCode}, salida «${result.stdout.take(160)}»")
            compositeReason = "service call 1013 sin respuesta válida (código ${result.exitCode}): ${result.message}"
            if (DENIED_MARKERS.any { (result.stdout + result.stderr).contains(it) }) {
                compositeDenied = true
                compositeReason = "SurfaceFlinger no permite el contador a Shizuku (${result.message}); no se reintenta"
                Diagnostics.w(TAG, compositeReason)
            }
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
        const val STATUS_MEASURING = "Midiendo…"
        const val STATUS_NO_PERMISSION = "Sin permisos de Shizuku"
        const val STATUS_SHIZUKU_OFF = "Shizuku no está activo"
        private const val MAX_FAILURES = 5
        /** Con la medición caída, se reintenta como mucho una vez cada 5 s (el juego puede empezar a dibujar más tarde). */
        private const val RETRY_NS = 5_000_000_000L
        private val DENIED_MARKERS = listOf("Operation not permitted", "Permission denied")
        private const val CALL_TIMEOUT_MS = 2_000L
        private const val MAX_CALL_LATENCY_NS = 400_000_000L
        private const val MIN_INTERVAL_S = 0.25
        private const val COUNTER_RANGE = 0x1_0000_0000L
        private const val TOLERANCE = 1.2f
        private const val SMOOTHING = 0.5f
        private const val MAX_CANDIDATES = 6
        private const val LOG_EVERY = 10
        /** Sin cambios en la marca más reciente durante este intervalo, el juego no está presentando (0 FPS). */
        internal const val STALE_NS = 1_500_000_000L
        /** Ventana de cálculo de FPS, anclada a la marca más reciente. */
        internal const val WINDOW_NS = 2_000_000_000L

        private val PARCEL_INT = Regex("""Parcel\(\s*([0-9a-fA-F]{8})\b""")
        private val LATENCY_ROW = Regex("""^(\d+)\s+(\d+)\s+(\d+)$""")

        /**
         * Texto corto para mostrar en lugar de un FPS cuando no hay valor. Nunca «N/D»: siempre explica
         * si se está midiendo, si faltan permisos, si Shizuku está apagado o si el dispositivo no es compatible.
         */
        fun placeholder(status: String): String = when {
            status.startsWith(STATUS_NO_PERMISSION) -> "Sin permisos"
            status.startsWith(STATUS_SHIZUKU_OFF) -> "Shizuku inactivo"
            status.startsWith("No compatible") -> "No compatible"
            else -> STATUS_MEASURING
        }

        /**
         * Extrae el contador de `Result: Parcel(0002a3f1    '....')`.
         * SurfaceFlinger responde un único int32, impreso como 8 dígitos hexadecimales.
         * Cualquier otro formato (p. ej. `Parcel(Error: 0xffffffff ...)`) devuelve null:
         * es preferible mostrar un estado a un FPS inventado.
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
         * tres columnas separadas por espacios o tabuladores; se usa la última (instante de presentación).
         * Los valores 0 y INT64_MAX son filas pendientes y se descartan.
         */
        internal fun parseLatencyPresents(output: String): List<Long> =
            output.lineSequence()
                .mapNotNull { line -> LATENCY_ROW.find(line.trim())?.groupValues?.get(3)?.toLongOrNull() }
                .filter { it > 0L && it != Long.MAX_VALUE }
                .toList()

        /**
         * FPS a partir de las presentaciones de una capa, usando solo diferencias entre marcas (no depende
         * del reloj del sistema). Usa las presentaciones dentro de [WINDOW_NS] antes de la más reciente
         * (o las dos últimas si hay pocas). Devuelve null si no hay al menos dos marcas válidas.
         */
        internal fun computeLatencyFps(presents: List<Long>): Float? {
            val valid = presents.filter { it > 0L && it != Long.MAX_VALUE }.sorted()
            if (valid.size < 2) return null
            val last = valid.last()
            val recent = valid.filter { it >= last - WINDOW_NS }.takeIf { it.size >= 2 } ?: valid.takeLast(2)
            val spanS = (recent.last() - recent.first()) / 1_000_000_000.0
            if (spanS <= 0.0) return null
            return ((recent.size - 1) / spanS).toFloat()
        }

        /** true si la marca más reciente no ha cambiado en [STALE_NS] según el reloj local. */
        internal fun isStale(changedAtNs: Long, nowNs: Long): Boolean = nowNs - changedAtNs > STALE_NS
    }
}
