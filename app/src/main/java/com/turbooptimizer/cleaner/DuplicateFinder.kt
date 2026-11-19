package com.turbooptimizer.cleaner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class MediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val path: String,
    val dateTaken: Long,
    val mime: String,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

data class DuplicateGroup(val id: String, val items: List<MediaItem>, val exact: Boolean) {
    val recoverable: Long get() = items.drop(1).sumOf { it.size }
}

/**
 * Detecta fotos y vídeos potencialmente duplicados mediante MediaStore:
 * 1) agrupa por tamaño, 2) huella parcial (inicio + final), 3) SHA-256 completo si ≤ 150 MB.
 * No borra nada: el usuario elige qué enviar a la papelera.
 */
class DuplicateFinder(private val context: Context) {

    suspend fun find(onProgress: (Float) -> Unit = {}): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val all = query()
        val bySize = all.groupBy { it.size }.filter { it.value.size > 1 }
        val total = bySize.values.sumOf { it.size }.coerceAtLeast(1)
        var done = 0
        val groups = mutableListOf<DuplicateGroup>()
        for ((size, list) in bySize) {
            currentCoroutineContext().ensureActive()
            val byPartial = list.groupBy { item ->
                done++
                onProgress(done.toFloat() / total)
                partialHash(item.uri, size)
            }.filter { it.key != null && it.value.size > 1 }
            for ((partial, candidates) in byPartial) {
                if (size <= FULL_HASH_LIMIT) {
                    candidates.groupBy { fullHash(it.uri) }
                        .filter { it.key != null && it.value.size > 1 }
                        .forEach { (hash, items) -> groups += DuplicateGroup(hash!!, items.sortedBy { it.dateTaken }, exact = true) }
                } else {
                    groups += DuplicateGroup(partial!!, candidates.sortedBy { it.dateTaken }, exact = false)
                }
            }
        }
        onProgress(1f)
        groups.sortedByDescending { it.recoverable }
    }

    private fun query(): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val list = mutableListOf<MediaItem>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val path = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val taken = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val added = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val mime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val w = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val h = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val dur = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            while (c.moveToNext()) {
                val isVideo = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val takenMs = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000)
                list += MediaItem(
                    uri = ContentUris.withAppendedId(base, c.getLong(id)),
                    name = c.getString(name) ?: "?",
                    size = c.getLong(size),
                    path = c.getString(path) ?: "",
                    dateTaken = takenMs,
                    mime = c.getString(mime) ?: "",
                    isVideo = isVideo,
                    width = c.getInt(w),
                    height = c.getInt(h),
                    durationMs = c.getLong(dur),
                )
            }
        }
        return list
    }

    private fun partialHash(uri: Uri, size: Long): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteBuffer.allocate(CHUNK)
                fun readAt(pos: Long) {
                    buf.clear()
                    ch.position(pos)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    buf.flip()
                    md.update(buf)
                }
                readAt(0)
                if (size > CHUNK * 2) readAt(size - CHUNK)
                md.update(size.toString().toByteArray())
                md.digest().toHex()
            }
        }
    }.getOrNull()

    private fun fullHash(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().toHex()
        }
    }.getOrNull()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CHUNK = 256 * 1024
        private const val FULL_HASH_LIMIT = 150L * 1024 * 1024
    }
}
