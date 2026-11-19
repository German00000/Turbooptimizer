package com.turbooptimizer.cleaner

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Protección contra el borrado de carpetas importantes.
 * Toda eliminación pasa por [checkDeletable] justo antes de ejecutarse, sin excepciones por categoría.
 */
object PathSafety {

    /**
     * Límites del recorrido de inspección. Al alcanzarlos (cuántas entradas o a qué profundidad) el
     * directorio no se puede verificar y se trata como potencialmente personal: bloquea el borrado recursivo.
     */
    const val MAX_SCAN_ENTRIES = 50_000
    const val MAX_SCAN_DEPTH = 16

    val root: File by lazy { Environment.getExternalStorageDirectory().canonicalFile }

    /** Carpetas estándar de primer nivel que nunca se eliminan (aunque estén vacías). */
    private val STANDARD_TOP = setOf(
        "Android", "DCIM", "Pictures", "Movies", "Music", "Documents", "Download", "Downloads",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings", "Screenshots", "Books",
    )

    private val STANDARD_NESTED = setOf(
        "DCIM/Camera", "DCIM/Screenshots", "Pictures/Screenshots", "Movies/TurboOptimizer",
        "Pictures/Instagram", "Download/Bluetooth", "Documents/Screenshots",
    )

    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "avif")
    val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v", "ts")
    val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "mid")
    private val DOC_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "epub", "md",
        "zip", "rar", "7z", "tar", "gz", "vcf", "kdbx", "key", "pem", "p12",
    )
    /** Posibles partidas guardadas o bases de datos de apps. */
    private val SAVE_EXT = setOf("sav", "save", "dat", "db", "sqlite", "sqlite3", "json", "xml", "bin", "obb")

    fun isMedia(f: File) = f.extension.lowercase().let { it in IMAGE_EXT || it in VIDEO_EXT || it in AUDIO_EXT }

    /** Archivos personales: nunca se preseleccionan ni se borran automáticamente. */
    fun isPersonal(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext in IMAGE_EXT || ext in VIDEO_EXT || ext in AUDIO_EXT || ext in DOC_EXT || ext in SAVE_EXT
    }

    fun relative(f: File): String? {
        val path = runCatching { f.canonicalPath }.getOrNull() ?: return null
        return relativeTo(root.path, path)
    }

    /** Ruta relativa a [rootPath] ("" si es la propia raíz), o null si está fuera. */
    internal fun relativeTo(rootPath: String, path: String): String? = when {
        path == rootPath -> ""
        path.startsWith("$rootPath/") -> path.removePrefix("$rootPath/")
        else -> null
    }

    private fun isStandardRel(rel: String): Boolean {
        if (rel.isEmpty()) return true
        if (!rel.contains('/') && rel in STANDARD_TOP) return true
        return STANDARD_NESTED.any { it.equals(rel, ignoreCase = true) }
    }

    fun isStandardDir(f: File): Boolean {
        val rel = relative(f) ?: return true
        return isStandardRel(rel)
    }

    fun isAndroidProtected(rel: String): Boolean =
        rel == "Android" || rel == "Android/data" || rel.startsWith("Android/data/") ||
            rel == "Android/obb" || rel.startsWith("Android/obb/") || rel == "Android/media" ||
            (rel.startsWith("Android/") && !rel.startsWith("Android/media/"))

    /**
     * Regla de borrado sin dependencias de Android (testeable).
     * @param canonicalPath ruta canónica del elemento
     * @param ownCacheDirs cachés propias de la app (rutas canónicas)
     * @param rootPath ruta canónica del almacenamiento compartido
     * @return null si puede borrarse, o el motivo del bloqueo.
     */
    fun deletableReason(canonicalPath: String, ownCacheDirs: List<String>, rootPath: String): String? {
        // Caché propia de la app: la carpeta en sí o cualquier ruta dentro de ella.
        if (ownCacheDirs.any { canonicalPath == it || canonicalPath.startsWith("$it/") }) return null
        val rel = relativeTo(rootPath, canonicalPath) ?: return "Fuera del almacenamiento compartido"
        if (rel.isEmpty()) return "Raíz del almacenamiento"
        if (isAndroidProtected(rel)) return "Carpeta protegida de Android (/Android/data, /Android/obb o del sistema)"
        if (isStandardRel(rel)) return "Carpeta estándar de Android"
        return null
    }

    /** @return null si es seguro eliminar, o el motivo por el que se bloquea. */
    fun checkDeletable(context: Context, f: File): String? {
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return "Ruta no válida"
        val ownDirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        return deletableReason(canonical.path, ownDirs, root.path)
    }

    /** Resultado de inspeccionar un directorio. [personal] incluye los casos de recuento incompleto. */
    data class DirInspection(val size: Long, val files: Int, val personal: Boolean, val complete: Boolean)

    /**
     * Recorre [dir] en profundidad (la profundidad acotada evita bucles por enlaces simbólicos) y devuelve
     * tamaño, nº de archivos y si contiene archivos personales.
     *
     * Si se alcanza [maxDepth] con contenido por debajo, o se superan [maxEntries] entradas, o un
     * subdirectorio no se puede leer, el recuento es incompleto: [DirInspection.personal] queda en true.
     */
    fun inspectDirectory(
        dir: File,
        maxEntries: Int = MAX_SCAN_ENTRIES,
        maxDepth: Int = MAX_SCAN_DEPTH,
    ): DirInspection {
        if (dir.isFile) return DirInspection(dir.length(), 1, isPersonal(dir), complete = true)
        var size = 0L
        var files = 0
        var entries = 0
        var personal = false
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.addLast(dir to 0)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeLast()
            val children = current.listFiles() ?: return DirInspection(size, files, personal = true, complete = false)
            for (child in children) {
                entries++
                if (entries > maxEntries) return DirInspection(size, files, personal = true, complete = false)
                if (child.isDirectory) {
                    if (depth + 1 > maxDepth) {
                        // Más profundo que el límite: solo es seguro si está vacío.
                        val empty = child.list()?.isEmpty() == true
                        if (!empty) return DirInspection(size, files, personal = true, complete = false)
                    } else {
                        pending.addLast(child to depth + 1)
                    }
                } else {
                    files++
                    size += child.length()
                    if (isPersonal(child)) personal = true
                }
            }
        }
        return DirInspection(size, files, personal, complete = true)
    }

    /**
     * true si el directorio contiene archivos personales, o si no se puede verificar que no los contiene
     * (límite de profundidad o de entradas alcanzado). En ese caso el borrado recursivo queda bloqueado.
     */
    fun containsPersonal(dir: File, maxEntries: Int = MAX_SCAN_ENTRIES, maxDepth: Int = MAX_SCAN_DEPTH): Boolean =
        inspectDirectory(dir, maxEntries, maxDepth).personal
}
