package com.turbooptimizer.recorder

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Grabación con la API oficial MediaProjection:
 *  vídeo H.264 (MediaCodec + Surface de entrada) y audio AAC (AudioPlaybackCapture y/o micrófono,
 *  mezclados), multiplexados en MP4 con MediaMuxer. Admite pausa/reanudar ajustando las marcas de tiempo.
 */
class RecorderEngine(
    private val projection: MediaProjection,
    private val config: RecordingConfig,
    private val output: RecordingOutput,
    private val onFatalError: (Throwable) -> Unit,
) {
    private val lock = Any()
    private lateinit var muxer: MediaMuxer
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var audioEnabled = false
    private var videoTrackAddedAt = 0L
    private var videoKeyWritten = false
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var samplesWritten = 0

    @Volatile private var stopRequested = false
    @Volatile var paused = false
        private set
    @Volatile private var pausedTotalUs = 0L
    @Volatile private var pauseStartUs = 0L
    @Volatile private var lastResumeUs = 0L
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** Mensaje informativo sobre el audio (p. ej. si el micrófono no está disponible). */
    var audioNote: String? = null
        private set

    fun start() {
        muxer = MediaMuxer(output.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / config.fps)
            setFloat("max-fps-to-encoder", config.fps.toFloat()) // limita los FPS reales al valor elegido
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec

        audioEnabled = (config.internalAudio || config.microphone) && setupAudio()

        virtualDisplay = projection.createVirtualDisplay(
            "TurboRecorder", config.width, config.height, config.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )

        videoThread = thread(name = "rec-video") { runGuarded { videoLoop() } }
        if (audioEnabled) audioThread = thread(name = "rec-audio") { runGuarded { audioLoop() } }
    }

    private inline fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Error en el hilo de grabación", t)
            if (!stopRequested) onFatalError(t)
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO se comprueba antes de iniciar el servicio
    private fun setupAudio(): Boolean {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 4)
        if (config.internalAudio) {
            internalRecord = runCatching {
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(capture).build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
            if (internalRecord == null) audioNote = "El audio interno no está disponible en este dispositivo."
        }
        if (config.microphone) {
            micRecord = runCatching {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize).build()
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }.getOrNull()
            if (micRecord == null) audioNote = listOfNotNull(audioNote, "No se pudo abrir el micrófono.").joinToString(" ")
        }
        if (internalRecord == null && micRecord == null) return false

        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SAMPLES * 2 * 2)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        internalRecord?.startRecording()
        micRecord?.startRecording()
        return true
    }

    private fun nowUs() = System.nanoTime() / 1000

    // ------------------------- Vídeo -------------------------

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var eosSignaled = false
        var deadline = 0L
        while (true) {
            if (stopRequested && !eosSignaled) {
                runCatching { codec.signalEndOfInputStream() }
                eosSignaled = true
                deadline = System.currentTimeMillis() + 2_000
            }
            if (eosSignaled && System.currentTimeMillis() > deadline) break
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    if (videoTrack < 0) {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        videoTrackAddedAt = System.currentTimeMillis()
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) write(isVideo = true, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // Si el audio no produce datos en 3 s, se graba solo vídeo para no perder la grabación.
            if (!muxerStarted && videoTrack >= 0 && System.currentTimeMillis() - videoTrackAddedAt > 3_000) {
                synchronized(lock) {
                    if (!muxerStarted) {
                        audioEnabled = false
                        audioNote = listOfNotNull(audioNote, "No se recibió audio: se graba solo vídeo.").joinToString(" ")
                        startMuxer()
                    }
                }
            }
        }
    }

    // ------------------------- Audio -------------------------

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val primary = micRecord ?: internalRecord ?: return
        val secondary = if (micRecord != null) internalRecord else null
        val chunk = ShortArray(CHUNK_SAMPLES * 2)
        val other = ShortArray(CHUNK_SAMPLES * 2)
        val info = MediaCodec.BufferInfo()
        while (!stopRequested) {
            val n = primary.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            if (secondary != null) {
                val m = secondary.read(other, 0, n, AudioRecord.READ_NON_BLOCKING)
                for (i in 0 until maxOf(0, m)) {
                    chunk[i] = (chunk[i] + other[i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            if (!paused) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    val bytes = minOf(n * 2, inBuf.capacity())
                    inBuf.order(ByteOrder.nativeOrder()).asShortBuffer().put(chunk, 0, bytes / 2)
                    val durationUs = (bytes / 4) * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inIdx, 0, bytes, nowUs() - durationUs, 0)
                }
            }
            drainAudio(codec, info, 0)
        }
        // Fin de flujo
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, nowUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainAudio(codec, info, 1_000)
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, untilEosMs: Long) {
        val deadline = System.currentTimeMillis() + untilEosMs
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEosMs > 0) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (untilEosMs == 0L || System.currentTimeMillis() > deadline) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    if (audioTrack < 0 && !muxerStarted) {
                        audioTrack = muxer.addTrack(codec.outputFormat)
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) write(isVideo = false, buf, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ------------------------- Muxer -------------------------

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && (!audioEnabled || audioTrack >= 0)) startMuxer()
    }

    private fun startMuxer() {
        muxer.start()
        muxerStarted = true
        requestKeyFrame()
    }

    private fun requestKeyFrame() {
        runCatching { videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo): Unit = synchronized(lock) {
        if (!muxerStarted) return@synchronized
        if (!isVideo && (audioTrack < 0 || !audioEnabled)) return@synchronized
        val original = info.presentationTimeUs
        // Descarta lo producido durante la pausa (incluye fotogramas repetidos por el codificador).
        if (paused || (original in pauseStartUs until lastResumeUs)) return@synchronized
        if (isVideo && !videoKeyWritten) {
            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) { requestKeyFrame(); return@synchronized }
            videoKeyWritten = true
        }
        var pts = original - pausedTotalUs
        val last = if (isVideo) lastVideoPts else lastAudioPts
        if (pts <= last) pts = last + 1
        if (isVideo) lastVideoPts = pts else lastAudioPts = pts
        val out = MediaCodec.BufferInfo().apply { set(0, info.size, pts, info.flags) }
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buf.slice(), out)
        samplesWritten++
        Unit
    }

    // ------------------------- Control -------------------------

    fun pause() {
        if (paused) return
        pauseStartUs = nowUs()
        paused = true
        virtualDisplay?.surface = null
    }

    fun resume() {
        if (!paused) return
        val now = nowUs()
        pausedTotalUs += now - pauseStartUs
        lastResumeUs = now
        virtualDisplay?.surface = inputSurface
        paused = false
        requestKeyFrame()
    }

    /** @return true si el archivo contiene datos válidos. */
    fun stop(): Boolean {
        if (paused) resume()
        stopRequested = true
        audioThread?.join(3_000)
        videoThread?.join(4_000)
        runCatching { virtualDisplay?.release() }
        runCatching { internalRecord?.stop(); internalRecord?.release() }
        runCatching { micRecord?.stop(); micRecord?.release() }
        runCatching { videoCodec?.stop(); videoCodec?.release() }
        runCatching { audioCodec?.stop(); audioCodec?.release() }
        runCatching { inputSurface?.release() }
        val ok = synchronized(lock) {
            val valid = muxerStarted && samplesWritten > 0
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            valid
        }
        return ok
    }

    companion object {
        private const val TAG = "RecorderEngine"
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 1024 // por canal
    }
}
