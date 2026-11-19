package com.screencastpro.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.screencastpro.core.EncodeParams

/**
 * Codificador H.264 con entrada de superficie (MediaCodec + VirtualDisplay).
 *
 *  - Perfil Baseline: sin fotogramas B, decodificable por casi cualquier TV y con menor latencia.
 *  - CBR para un bitrate predecible en redes Wi-Fi.
 *  - Keyframe cada 1 s (baja latencia) o cada 2 s (ahorro de batería).
 *  - SPS/PPS (CODEC_CONFIG) se anteponen a cada keyframe, así cada punto de acceso es autocontenido.
 *
 * Las salidas se entregan en formato Annex B en el hilo de codificación. [timelineOffsetUs] permite
 * reiniciar el codificador (cambio de calidad) sin que las marcas de tiempo retrocedan.
 */
class VideoEncoder(
    val params: EncodeParams,
    private val lowLatency: Boolean,
    private val timelineOffsetUs: Long,
    private val onEncoded: (annexB: ByteArray, ptsUs: Long, keyframe: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "VideoEncoder"
    }

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var configData: ByteArray? = null
    private var baseUs = -1L

    /** Superficie de entrada: se asigna al VirtualDisplay. Válida tras [start]. */
    lateinit var inputSurface: Surface
        private set

    /** Último PTS entregado (µs en la línea temporal de la transmisión). */
    @Volatile var lastPtsUs: Long = -1L
        private set

    fun start() {
        val info = Capabilities.pickEncoder()
            ?: throw IllegalStateException("El dispositivo no tiene codificador H.264")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, params.width, params.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, params.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, params.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (lowLatency) 1 else 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (lowLatency && Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }

        val c = MediaCodec.createByCodecName(info.name)
        try {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            try {
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: IllegalArgumentException) {
                // Algunos codificadores no admiten CBR: reintenta con VBR.
                Log.w(TAG, "CBR no admitido, usando VBR", e)
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        codec = c
        running = true
        thread = Thread(::drainLoop, "video-encoder").apply { isDaemon = true; start() }
        Log.i(TAG, "Codificador ${info.name} ${Capabilities.describe(params)} ${params.bitrateBps} bps")
    }

    /** Ajusta el bitrate en caliente (sin reiniciar el codificador). */
    fun setBitrate(bps: Int) {
        val c = codec ?: return
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cambiar el bitrate", e)
        }
    }

    /** Pide un keyframe inmediato (cuando entra un televisor nuevo). */
    fun requestKeyframe() {
        val c = codec ?: return
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo pedir keyframe", e)
        }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx < 0) continue // INFO_TRY_AGAIN_LATER o cambio de formato
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        configData = data
                    } else {
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val au = if (key && configData != null) configData!! + data else data
                        if (baseUs < 0) baseUs = info.presentationTimeUs
                        val pts = info.presentationTimeUs - baseUs + timelineOffsetUs
                        lastPtsUs = pts
                        onEncoded(au, pts, key)
                    }
                }
                c.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Bucle de salida interrumpido", e)
        }
    }

    /** Detiene el hilo, libera el codificador y la superficie. Idempotente. */
    fun stop() {
        running = false
        thread?.let { t ->
            try { t.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        thread = null
        codec?.let { c ->
            try { c.stop() } catch (_: Exception) {}
            c.release()
        }
        codec = null
        if (::inputSurface.isInitialized) {
            inputSurface.release()
        }
    }
}
