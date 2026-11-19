package com.turbooptimizer.cleaner

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import com.turbooptimizer.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

enum class ScanCategory(val title: String, val description: String) {
    APP_CACHE("Caché de TurboOptimizer", "Archivos temporales de esta app. Seguro de borrar."),
    TEMP("Archivos temporales", "Descargas incompletas (.part, .crdownload), .tmp y archivos temporales de más de 24 h."),
    THUMBNAILS("Miniaturas", "Carpetas .thumbnails: Android las regenera cuando se necesitan. No afecta a tus fotos."),
    EMPTY_FOLDERS("Carpetas vacías", "Carpetas sin ningún archivo. Las carpetas estándar de Android se excluyen."),
    APKS("APK", "Instaladores .apk/.apks/.xapk. Solo se preseleccionan los de apps ya instaladas con la misma o mayor versión."),
    RESIDUAL("Residuos de apps desinstaladas", "Carpetas con el nombre de una app que ya no está instalada. Solo se pueden borrar si no contienen fotos, vídeos ni documentos."),
    OLD_DOWNLOADS("Descargas antiguas (> 90 días)", "Revísalas manualmente: pueden ser documentos personales. Nada se preselecciona."),
    LARGE_FILES("Archivos grandes (> 100 MB)", "Solo informativo y selección manual. Las fotos y vídeos se envían a la papelera."),
    HIDDEN("Archivos y carpetas ocultos", "Elementos que empiezan por «.». Solo se pueden borrar los identificados como seguros."),
}

data class ScanItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val category: ScanCategory,
    val isDirectory: Boolean,
    val deletable: Boolean,
    val preselected: Boolean,
    val note: String? = null,
    val appHint: String? = null,
    val isMedia: Boolean = false,
)

data class ScanResult(
    val items: List<ScanItem>,
    val scannedFiles: Int,
    val durationMs: Long,
    val fullAccess: Boolean,
    val otherAppsCacheBytes: Long?,
    val truncated: Boolean,
) {
    val byCategory: Map<ScanCategory, List<ScanItem>> by lazy { items.groupBy { it.category } }
}

/**
 * Analiza el almacenamiento compartido. NUNCA borra nada: solo produce una lista
 * que el usuario revisa (modo simulación por diseño).
 */
class StorageScanner(private val context: Context) {

    private class DirStats(val size: Long, val files: Int)
    private class Measure(val size: Long, val files: Int, val hasPersonal: Boolean)

    private class WalkCtx(val installed: Map<String, String>, val now: Long) {
        var files = 0
        var truncated = false
        val items = mutableListOf<ScanItem>()
        val perCategory = HashMap<ScanCategory, Int>()
        fun add(item: ScanItem) {
            val n = perCategory[item.category] ?: 0
            if (n >= MAX_PER_CATEGORY) { truncated = true; return }
            perCategory[item.category] = n + 1
            items += item
        }
    }

    suspend fun scan(onProgress: (String) -> Unit = {}): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val pm = context.packageManager
        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
                .associate { it.packageName to runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.packageName) }
        }.getOrDefault(emptyMap())
        val ctx = WalkCtx(installed, start)

        onProgress("Analizando caché de la app…")
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            val m = measure(dir)
            if (m.size > 0) {
                ctx.add(ScanItem(dir.path, if (dir == context.cacheDir) "Caché interna" else "Caché externa", m.size, start,
                    ScanCategory.APP_CACHE, true, deletable = true, preselected = true, note = "${m.files} archivos"))
            }
        }

        val full = Permissions.hasAllFilesAccess()
        if (full) {
            onProgress("Analizando almacenamiento compartido…")
            walk(PathSafety.root, 0, ctx, onProgress)
        }

        ScanResult(
            items = ctx.items.sortedByDescending { it.size },
            scannedFiles = ctx.files,
            durationMs = System.currentTimeMillis() - start,
            fullAccess = full,
            otherAppsCacheBytes = otherAppsCache(),
            truncated = ctx.truncated,
        )
    }

    /** Caché total de las demás apps (solo lectura; requiere acceso de uso). */
    private fun otherAppsCache(): Long? {
        if (!Permissions.hasUsageAccess(context)) return null
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)).sumOf { info ->
                if (info.packageName == context.packageName) 0L
                else runCatching { ssm.queryStatsForPackage(info.storageUuid, info.packageName, Process.myUserHandle()).cacheBytes }.getOrDefault(0L)
            }
        }.getOrNull()
    }

    private suspend fun walk(dir: File, depth: Int, ctx: WalkCtx, onProgress: (String) -> Unit): DirStats {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_DEPTH || ctx.files > MAX_FILES) { ctx.truncated = true; return DirStats(0, 1) }
        val rel = PathSafety.relative(dir) ?: return DirStats(0, 1)

        // /Android: solo se entra en Android/media (data y obb son inaccesibles y están protegidos).
        val children: Array<File> = when {
            rel == "Android" -> listOfNotNull(File(dir, "media").takeIf { it.isDirectory }).toTypedArray()
            PathSafety.isAndroidProtected(rel) && rel != "Android/media" && !rel.startsWith("Android/media/") -> return DirStats(0, 1)
            else -> dir.listFiles() ?: return DirStats(0, 1)
        }
        if (depth <= 1) onProgress("Analizando ${if (rel.isEmpty()) "/" else rel}…")

        var size = 0L
        var files = 0
        for (child in children) {
            if (child.isDirectory) {
                val name = child.name
                val parentRel = rel
                when {
                    name == ".thumbnails" || name == ".thumbs" -> {
                        val m = measure(child)
                        if (m.size > 0) ctx.add(item(child, m.size, ScanCategory.THUMBNAILS, true, deletable = true, preselected = true,
                            note = "${m.files} miniaturas regenerables"))
                        size += m.size; files += maxOf(1, m.files)
                    }
                    name.startsWith(".") -> {
                        val m = measure(child)
                        classifyHiddenDir(child, m, ctx)
                        size += m.size; files += maxOf(1, m.files)
                    }
                    (parentRel == "Android/media" || parentRel.isEmpty()) && looksLikePackage(name) && name !in ctx.installed -> {
                        val m = measure(child)
                        ctx.add(item(child, m.size, ScanCategory.RESIDUAL, true,
                            deletable = !m.hasPersonal, preselected = false,
                            note = if (m.hasPersonal) "Contiene archivos personales: bloqueado" else "Sin archivos personales (${m.files} archivos)",
                            appHint = "$name (desinstalada)"))
                        size += m.size; files += maxOf(1, m.files)
                    }
                    else -> {
                        val sub = walk(child, depth + 1, ctx, onProgress)
                        size += sub.size
                        files += sub.files
                        if (sub.files == 0 && PathSafety.checkDeletable(context, child) == null) {
                            ctx.add(item(child, 0, ScanCategory.EMPTY_FOLDERS, true, deletable = true, preselected = true))
                        }
                    }
                }
            } else {
                ctx.files++
                files++
                val len = child.length()
                size += len
                classifyFile(child, len, rel, ctx)
            }
        }
        return DirStats(size, files)
    }

    private fun classifyFile(f: File, len: Long, parentRel: String, ctx: WalkCtx) {
        val name = f.name
        val ext = f.extension.lowercase()
        val age = ctx.now - f.lastModified()
        when {
            name == ".nomedia" -> Unit // marcador importante: nunca se lista para borrar
            name.startsWith(".trashed-") -> ctx.add(item(f, len, ScanCategory.HIDDEN, false, deletable = false, preselected = false,
                note = "Elemento en la papelera del sistema (se elimina solo en 30 días)"))
            ext in APK_EXT -> {
                val installedOk = if (ext == "apk") apkAlreadyInstalled(f) else false
                ctx.add(item(f, len, ScanCategory.APKS, false, deletable = true, preselected = installedOk,
                    note = if (installedOk) "La app ya está instalada" else "App no instalada o versión distinta"))
            }
            (ext in TEMP_EXT || name.startsWith("~$") || name.startsWith(".pending-")) && age > TimeUnit.HOURS.toMillis(24) ->
                ctx.add(item(f, len, ScanCategory.TEMP, false, deletable = true, preselected = !name.startsWith(".pending-")))
            name.startsWith(".") -> ctx.add(item(f, len, ScanCategory.HIDDEN, false, deletable = false, preselected = false,
                note = "Archivo oculto de uso desconocido: solo informativo", appHint = guessApp(name, ctx)))
            (parentRel == "Download" || parentRel.startsWith("Download/")) && age > TimeUnit.DAYS.toMillis(90) ->
                ctx.add(item(f, len, ScanCategory.OLD_DOWNLOADS, false, deletable = true, preselected = false,
                    note = if (PathSafety.isPersonal(f)) "Posible archivo personal: revísalo" else null))
            len >= LARGE_FILE -> ctx.add(item(f, len, ScanCategory.LARGE_FILES, false, deletable = true, preselected = false))
        }
    }

    private fun classifyHiddenDir(dir: File, m: Measure, ctx: WalkCtx) {
        val bare = dir.name.trimStart('.').lowercase()
        val hint = guessApp(dir.name, ctx)
        val residual = hint == null && looksLikePackage(bare) && bare !in ctx.installed
        val (deletable, note) = when {
            m.hasPersonal -> false to "Contiene fotos, vídeos, documentos o datos de juego: no se puede borrar desde aquí"
            bare in SAFE_HIDDEN -> true to "Caché/temporales regenerables"
            residual -> true to "Residuo de una app desinstalada sin archivos personales"
            else -> false to "Uso desconocido: se muestra solo como información"
        }
        ctx.add(item(dir, m.size, ScanCategory.HIDDEN, true, deletable = deletable, preselected = false, note = note,
            appHint = hint ?: if (residual) "$bare (desinstalada)" else null))
    }

    private fun guessApp(name: String, ctx: WalkCtx): String? {
        val bare = name.trimStart('.').lowercase()
        ctx.installed[bare]?.let { return it }
        if (bare.length < 4) return null
        return ctx.installed.entries.firstOrNull { (pkg, label) ->
            pkg.split('.').any { it.equals(bare, true) && it.length >= 4 } || label.equals(bare, true)
        }?.value
    }

    private fun apkAlreadyInstalled(f: File): Boolean = runCatching {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(0)) ?: return false
        val installed = pm.getPackageInfo(archive.packageName, PackageManager.PackageInfoFlags.of(0))
        installed.longVersionCode >= archive.longVersionCode
    }.getOrDefault(false)

    private fun looksLikePackage(name: String) =
        name.count { it == '.' } >= 2 && Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$").matches(name)

    /**
     * Mide un directorio con los mismos límites que el borrado ([PathSafety.inspectDirectory]).
     * Si se alcanza el límite de profundidad o de entradas, el resultado es "personal" y no se ofrece para borrar.
     */
    private fun measure(dir: File): Measure = runCatching {
        val inspection = PathSafety.inspectDirectory(dir)
        Measure(inspection.size, inspection.files, inspection.personal)
    }.getOrDefault(Measure(0, 0, hasPersonal = true))

    private fun item(
        f: File, size: Long, category: ScanCategory, isDir: Boolean, deletable: Boolean, preselected: Boolean,
        note: String? = null, appHint: String? = null,
    ) = ScanItem(
        path = f.path, name = f.name, size = size, lastModified = f.lastModified(), category = category,
        isDirectory = isDir, deletable = deletable, preselected = preselected && deletable, note = note,
        appHint = appHint, isMedia = !isDir && PathSafety.isMedia(f),
    )

    companion object {
        private const val MAX_DEPTH = 24
        private const val MAX_FILES = 300_000
        private const val MAX_PER_CATEGORY = 1_000
        private const val LARGE_FILE = 100L * 1024 * 1024
        private val APK_EXT = setOf("apk", "apks", "xapk", "apkm")
        private val TEMP_EXT = setOf("tmp", "temp", "part", "crdownload", "partial", "download")
        private val SAFE_HIDDEN = setOf("cache", "caches", "tmp", "temp", "thumbnails", "thumbs", "thumbcache", "imagecache", "glide_cache", "picasso-cache")

        @Suppress("unused")
        val sharedRoot: File get() = Environment.getExternalStorageDirectory()
    }
}
