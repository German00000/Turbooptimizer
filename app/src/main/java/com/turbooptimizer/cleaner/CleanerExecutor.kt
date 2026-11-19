package com.turbooptimizer.cleaner

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.data.OperationLogRepository
import com.turbooptimizer.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CleanOutcome(
    val deletedCount: Int,
    val bytesFreed: Long,
    val failures: List<String>,
    /** Fotos/vídeos/audio que deben ir a la papelera con confirmación del sistema. */
    val trashUris: List<Uri>,
    val trashBytes: Long,
    val simulated: Boolean,
)

class CleanerExecutor(private val context: Context, private val log: OperationLogRepository) {

    /**
     * Elimina los elementos elegidos por el usuario tras revalidar cada ruta con [PathSafety].
     * Los archivos multimedia no se borran: se devuelven para enviarlos a la papelera.
     */
    suspend fun clean(items: List<ScanItem>, simulate: Boolean): CleanOutcome = withContext(Dispatchers.IO) {
        var freed = 0L
        var count = 0
        val failures = mutableListOf<String>()
        val trash = mutableListOf<Uri>()
        var trashBytes = 0L
        val details = mutableListOf<String>()

        // Más profundo primero: así las carpetas vacías anidadas se borran en orden.
        for (item in items.filter { it.deletable }.sortedByDescending { it.path.count { c -> c == '/' } }) {
            val f = File(item.path)
            // Validación obligatoria para TODAS las categorías, incluida APP_CACHE: la categoría nunca salta la ruta.
            val blocked = PathSafety.checkDeletable(context, f)
            if (simulate && blocked == null) {
                count++
                freed += item.size
                details += "[simulado] ${item.path}"
                continue
            }
            // Multimedia: nunca se borra localmente; solo se envía a la papelera si MediaStore da un URI válido.
            when (val gate = CleanGates.decide(blocked, item.isMedia) { mediaUriFor(f) }) {
                is CleanGate.Skip -> failures += "${item.name}: ${gate.reason}"
                is CleanGate.SendToTrash -> {
                    trash += gate.uri
                    trashBytes += item.size
                }
                CleanGate.DeleteLocally -> {
                    if (deleteLocally(item, f)) {
                        count++
                        freed += item.size
                        details += item.path
                    } else {
                        failures += "${item.name}: no se pudo eliminar"
                    }
                }
            }
        }
        if (count > 0 || failures.isNotEmpty()) {
            log.add(
                category = LogCategory.CLEAN,
                title = "Limpieza: ${formatBytes(freed)} liberados ($count elementos)",
                details = details.take(200) + failures.map { "✖ $it" },
                success = failures.isEmpty(),
                simulated = simulate,
                bytesFreed = freed,
            )
        }
        CleanOutcome(count, freed, failures, trash, trashBytes, simulate)
    }

    /** Borrado local de elementos NO multimedia ya validados por [CleanGates]. */
    private fun deleteLocally(item: ScanItem, f: File): Boolean = when (item.category) {
        ScanCategory.APP_CACHE, ScanCategory.THUMBNAILS -> deleteChildren(f)
        ScanCategory.EMPTY_FOLDERS -> f.isDirectory && f.walkTopDown().none { it.isFile } && f.deleteRecursively()
        // Recursivo solo si no hay (ni puede verificarse que no haya) archivos personales.
        ScanCategory.RESIDUAL, ScanCategory.HIDDEN ->
            if (item.isDirectory) !PathSafety.containsPersonal(f) && f.deleteRecursively() else f.delete()
        else -> f.isFile && f.delete()
    }

    private fun deleteChildren(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        var ok = true
        children.forEach { if (!it.deleteRecursively()) ok = false }
        return ok
    }

    @Suppress("DEPRECATION")
    private fun mediaUriFor(f: File): Uri? = runCatching {
        val ext = f.extension.lowercase()
        val collection = when (ext) {
            in PathSafety.IMAGE_EXT -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            in PathSafety.VIDEO_EXT -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DATA} = ?",
            arrayOf(f.path),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()

    /** Solicitud oficial de Android para mover a la papelera (el usuario confirma en un diálogo del sistema). */
    fun trashRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(context.contentResolver, uris, true)

    suspend fun logTrash(count: Int, bytes: Long, confirmed: Boolean) {
        log.add(
            category = LogCategory.CLEAN,
            title = if (confirmed) "Enviados a la papelera: $count archivos (${formatBytes(bytes)})" else "Envío a la papelera cancelado",
            details = listOf("Los elementos permanecen 30 días en la papelera del sistema y pueden restaurarse desde la galería."),
            success = confirmed,
            bytesFreed = if (confirmed) bytes else 0,
        )
    }
}
