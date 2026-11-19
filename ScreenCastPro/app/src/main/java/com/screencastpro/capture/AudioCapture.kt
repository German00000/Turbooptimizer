package com.screencastpro.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresApi
import com.screencastpro.core.TsMuxer

/**
 * Captura del audio que reproduce otra app (AudioPlaybackCapture, Android 10+) y codificación AAC-LC.
 *
 * Limitaciones oficiales: no se captura audio de apps que declaren allowAudioPlaybackCapture=false,
 * ni audio de llamadas, ni contenido protegido. Si AudioRecord no se inicializa, [start] devuelve false
 * y la transmisión continúa solo con vídeo.
 */
@RequiresApi(29)
class AudioCapture(
    private val projection: MediaProjection,
    private val onEncoded: (adts: ByteArray, ptsUs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        private const val READ_BYTES = 4096          // 1024 frames estéreo de 16 bits
        private const val BITRATE = 128_000
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO se solicita en la interfaz antes de llamar aquí.
    fun start(): Boolean {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, READ_BYTES * 2))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord no disponible", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        val enc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.w(TAG, "Codificador AAC no disponible", e)
            rec.release()
            return false
        }
        try {
            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            enc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo configurar el codificador AAC", e)
            enc.release()
            rec.release()
            return false
        }

        record = rec
        codec = enc
        running = true
        rec.startRecording()
        thread = Thread(::loop, "audio-capture").apply { isDaemon = true; start() }
        return true
    }

    private fun loop() {
        val rec = record ?: return
        val enc = codec ?: return
        val pcm = ByteArray(READ_BYTES)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size) // bloqueante, ~23 ms por lectura
                if (n <= 0) continue
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)
                    if (ib != null) {
                        ib.clear()
                        ib.put(pcm, 0, n)
                        val ptsUs = frames * 1_000_000L / SAMPLE_RATE
                        enc.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                        frames += (n / (2 * CHANNELS)).toLong()
                    } else {
                        enc.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    }
                }
                drain(enc, info)
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Captura de audio interrumpida", e)
        }
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (idx < 0) continue
            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val frame = ByteArray(info.size)
                buf.get(frame)
                // El AAC del codificador no lleva cabecera: se añade ADTS para poder mezclarlo en MPEG-TS.
                val adts = TsMuxer.adtsHeader(frame.size, SAMPLE_RATE, CHANNELS) + frame
                onEncoded(adts, info.presentationTimeUs)
            }
            enc.releaseOutputBuffer(idx, false)
        }
    }

    fun stop() {
        running = false
        thread?.let { t -> try { t.join(1000) } catch (_: InterruptedException) {} }
        thread = null
        record?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        record = null
        codec?.let { c ->
            try { c.stop() } catch (_: Exception) {}
            c.release()
        }
        codec = null
    }
}
