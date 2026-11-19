package com.turbooptimizer.recorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RecordingConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val densityDpi: Int,
    val internalAudio: Boolean,
    val microphone: Boolean,
)

object RecorderState {
    enum class Status { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

    data class State(
        val status: Status = Status.IDLE,
        val elapsedMs: Long = 0,
        val config: RecordingConfig? = null,
        val outputName: String? = null,
        val lastOutput: Uri? = null,
        val error: String? = null,
        val audioNote: String? = null,
    )

    val state = MutableStateFlow(State())
}

class RecordingOutput(
    val uri: Uri,
    val pfd: ParcelFileDescriptor,
    val name: String,
    val mediaStore: Boolean,
)

object RecordingOutputFactory {

    fun create(context: Context, folderUri: String?): RecordingOutput {
        val name = "TurboRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        if (folderUri != null) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: error("Carpeta no disponible")
                val file = tree.createFile("video/mp4", name.removeSuffix(".mp4")) ?: error("No se pudo crear el archivo")
                val pfd = resolver.openFileDescriptor(file.uri, "rw") ?: error("No se pudo abrir el archivo")
                return RecordingOutput(file.uri, pfd, name, mediaStore = false)
            }
        }
        // Por defecto: Movies/TurboOptimizer mediante MediaStore (no requiere permisos).
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TurboOptimizer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("No se pudo crear el vídeo en MediaStore")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("No se pudo abrir el vídeo")
        return RecordingOutput(uri, pfd, name, mediaStore = true)
    }

    fun finish(context: Context, out: RecordingOutput, success: Boolean) {
        runCatching { out.pfd.close() }
        val resolver = context.contentResolver
        runCatching {
            if (out.mediaStore) {
                if (success) {
                    resolver.update(out.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } else resolver.delete(out.uri, null, null)
            } else if (!success) {
                DocumentsContract.deleteDocument(resolver, out.uri)
            }
        }
    }
}

object RecorderCapabilities {

    private val avc: MediaCodecInfo.VideoCapabilities? by lazy {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        val chosen = infos.firstOrNull { it.isHardwareAccelerated } ?: infos.firstOrNull()
        chosen?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
    }

    fun supports(width: Int, height: Int, fps: Int): Boolean {
        val caps = avc ?: return false
        return runCatching {
            caps.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                caps.areSizeAndRateSupported(height, width, fps.toDouble())
        }.getOrDefault(false)
    }

    private fun display(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** Tamaño físico de la pantalla en la orientación actual (válido también desde un Service). */
    fun screenSize(context: Context): Pair<Int, Int> {
        val d = display(context) ?: return 1080 to 2400
        val w = d.mode.physicalWidth
        val h = d.mode.physicalHeight
        val rotated = d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270
        val natural = if (w <= h) w to h else h to w
        return if (rotated) natural.second to natural.first else natural
    }

    /** Calcula la resolución de salida manteniendo la proporción y alineando a 16 px. 0 = nativa. */
    fun outputSize(context: Context, shortSide: Int): Pair<Int, Int> {
        val (w, h) = screenSize(context)
        val native = min(w, h)
        val target = if (shortSide <= 0) native else min(shortSide, native)
        val scale = target.toFloat() / native
        fun align(v: Float) = max(16, ((v / 16f).roundToInt()) * 16)
        return align(w * scale) to align(h * scale)
    }

    fun maxRefreshRate(context: Context): Float =
        display(context)?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f

    fun supports60(context: Context): Boolean {
        val (w, h) = outputSize(context, 720)
        return maxRefreshRate(context) >= 59f && supports(w, h, 60)
    }

    fun availableBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)

    /** Tamaño estimado por minuto (vídeo + audio AAC 128 kbps). */
    fun bytesPerMinute(bitrateMbps: Int, audio: Boolean): Long =
        (bitrateMbps * 1_000_000L / 8 + if (audio) 16_000L else 0L) * 60
}
