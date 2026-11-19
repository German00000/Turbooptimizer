package com.screencastpro

import kotlinx.coroutines.flow.MutableStateFlow

/** Estado visible de la transmisión. Se actualiza desde el servicio y se muestra en la pantalla principal. */
data class StreamStats(
    val running: Boolean = false,
    val status: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** FPS medidos a la salida del codificador. */
    val fps: Double = 0.0,
    /** Bitrate objetivo actual del control adaptativo. */
    val targetBitrateBps: Int = 0,
    /** Bitrate medido a la salida del codificador. */
    val measuredBitrateBps: Long = 0,
    val latencyMs: Int = 0,
    val clients: Int = 0,
    val audioActive: Boolean = false,
    val thermal: String = "",
    val qualityLabel: String = "",
    val tsUrl: String? = null,
    /** URL MP4 fragmentado: la que usa DLNA. */
    val mp4Url: String? = null,
    val hlsUrl: String? = null,
    val error: String? = null,
)

/** Estado compartido entre el servicio de captura y la interfaz. */
object SessionState {
    val stats = MutableStateFlow(StreamStats())

    fun update(transform: (StreamStats) -> StreamStats) {
        stats.value = transform(stats.value)
    }

    fun reset(error: String? = null) {
        stats.value = StreamStats(error = error)
    }
}
