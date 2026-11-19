package com.screencastpro.core

/** Resolución objetivo por el lado corto de la imagen (480p = 480 px de alto en vertical u horizontal). */
enum class Resolution(val shortSide: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p");

    companion object {
        fun fromShortSide(v: Int): Resolution = values().firstOrNull { it.shortSide == v } ?: P720
    }
}

/** Parámetros concretos que recibe el codificador. */
data class EncodeParams(val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) {
    val label: String get() = "${minOf(width, height)}p@$fps"
}

/** Un escalón de calidad: lado corto y FPS. Índice 0 = mejor calidad. */
data class Level(val shortSide: Int, val fps: Int)

object ProfileMath {

    /** Redondeo a número par: el codificador H.264 con 4:2:0 exige dimensiones pares. */
    private fun even(v: Int): Int = maxOf(2, v and 1.inv())

    /**
     * Ajusta el tamaño de origen al lado corto objetivo conservando la relación de aspecto.
     * No amplía la imagen: si el origen ya es menor, se mantiene. Las dimensiones resultantes son pares.
     */
    fun fitSize(srcW: Int, srcH: Int, targetShortSide: Int): Pair<Int, Int> {
        require(srcW > 0 && srcH > 0) { "Dimensiones no válidas" }
        val currentShort = minOf(srcW, srcH)
        val scale = if (currentShort <= targetShortSide) 1.0 else targetShortSide.toDouble() / currentShort
        val w = even(Math.round(srcW * scale).toInt())
        val h = even(Math.round(srcH * scale).toInt())
        return w to h
    }

    /**
     * Escalones de calidad a partir de la petición del usuario, de mayor a menor calidad.
     * El controlador adaptativo solo baja de escalón (o sube hasta el escalón solicitado).
     */
    fun ladder(shortSide: Int, fps: Int): List<Level> {
        val candidates = mutableListOf(Level(shortSide, fps))
        if (fps > 30) candidates += Level(shortSide, 30)
        if (shortSide > 720) candidates += Level(720, 30)
        if (shortSide >= 720) candidates += Level(720, 24)
        candidates += Level(480, 24)
        // Quita duplicados conservando el orden.
        return candidates.distinct().filter { it.shortSide <= shortSide }
    }

    /**
     * Bitrate por defecto según píxeles por segundo. Factor ~0,07 bits por píxel: suficiente para
     * imagen de escritorio/UI con H.264 y razonable en gama baja. Ahorro de batería reduce un 40 %.
     */
    fun defaultBitrate(width: Int, height: Int, fps: Int, batterySaver: Boolean): Int {
        val raw = width.toLong() * height * fps * 0.07
        val clamped = raw.coerceIn(800_000.0, 10_000_000.0).toInt()
        return if (batterySaver) (clamped * 0.6).toInt() else clamped
    }

    /** Multiplicador de bitrate del ajuste manual del usuario. */
    fun bitrateScale(choice: BitrateChoice): Double = when (choice) {
        BitrateChoice.AUTO -> 1.0
        BitrateChoice.LOW -> 0.5
        BitrateChoice.MEDIUM -> 0.75
        BitrateChoice.HIGH -> 1.25
    }

    /**
     * Latencia aproximada en milisegundos: un fotograma de captura, un fotograma de codificación,
     * la cola medida en el servidor y el tiempo medio de envío al televisor.
     */
    fun estimateLatencyMs(fps: Int, queueMs: Double, writeMs: Double): Int {
        val frame = 1000.0 / maxOf(1, fps)
        return Math.round(frame * 2 + queueMs + writeMs).toInt()
    }
}

enum class BitrateChoice(val label: String) {
    AUTO("Automático"), LOW("Bajo"), MEDIUM("Medio"), HIGH("Alto");

    companion object {
        fun fromOrdinal(i: Int): BitrateChoice = values().getOrElse(i) { AUTO }
    }
}
