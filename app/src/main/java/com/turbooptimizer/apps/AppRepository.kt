package com.turbooptimizer.apps

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val enabled: Boolean,
    val versionName: String?,
    val installTime: Long,
    val isGame: Boolean,
    val launchable: Boolean,
    val sizeBytes: Long?,
    val cacheBytes: Long?,
    val lastUsed: Long?,
    val ramKb: Long?,
)

class AppRepository(private val context: Context, private val shizuku: ShizukuManager) {

    private val pm = context.packageManager
    private val labelCache = ConcurrentHashMap<String, String>()
    @Volatile private var pssCache: Pair<Long, Map<String, Long>>? = null

    @Suppress("DEPRECATION") // FLAG_IS_GAME: se mantiene para juegos antiguos sin categoría
    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val infos = pm.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
        )
        val launchable = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

        val usageAccess = Permissions.hasUsageAccess(context)
        val lastUsed: Map<String, Long> = if (usageAccess) runCatching {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            usm.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(60), now)
                .mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap()) else emptyMap()

        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val ram = runningPss()

        infos.map { info ->
            val pkg = info.packageName
            val label = label(info)
            val stats = if (usageAccess) runCatching {
                ssm.queryStatsForPackage(info.storageUuid, pkg, Process.myUserHandle())
            }.getOrNull() else null
            val pkgInfo = runCatching { pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
            AppEntry(
                packageName = pkg,
                label = label,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isUpdatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                enabled = info.enabled,
                versionName = pkgInfo?.versionName,
                installTime = pkgInfo?.firstInstallTime ?: 0L,
                isGame = info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0,
                launchable = pkg in launchable,
                sizeBytes = stats?.let { it.appBytes + it.dataBytes },
                cacheBytes = stats?.cacheBytes,
                lastUsed = lastUsed[pkg]?.takeIf { it > 0 },
                ramKb = ram?.get(pkg),
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Memoria (PSS, KB) por paquete de los procesos en ejecución, vía `dumpsys meminfo -c` con Shizuku.
     * Android no permite obtener esto para otras apps sin permisos elevados. Se cachea 10 s.
     */
    suspend fun runningPss(): Map<String, Long>? {
        if (!shizuku.isReady) return null
        pssCache?.let { (t, m) -> if (System.currentTimeMillis() - t < 10_000) return m }
        val r = shizuku.exec(ShellCommands.meminfo(), timeoutMs = 20_000)
        if (!r.isSuccess && r.stdout.isBlank()) return null
        val map = parseMeminfo(r.stdout)
        pssCache = System.currentTimeMillis() to map
        return map
    }

    fun invalidateRunning() {
        pssCache = null
    }

    private fun parseMeminfo(out: String): Map<String, Long> {
        val map = HashMap<String, Long>()
        out.lineSequence().forEach { line ->
            if (!line.startsWith("proc,")) return@forEach
            val parts = line.split(',')
            if (parts.size < 5) return@forEach
            val pkg = parts[2].substringBefore(':')
            val pss = parts[4].toLongOrNull() ?: return@forEach
            map[pkg] = (map[pkg] ?: 0L) + pss
        }
        return map
    }

    fun label(pkg: String): String = labelCache[pkg] ?: runCatching {
        label(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())))
    }.getOrElse { pkg }

    private fun label(info: ApplicationInfo): String =
        labelCache.getOrPut(info.packageName) { runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName) }

    fun isInstalled(pkg: String): Boolean = runCatching {
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        true
    }.getOrDefault(false)

    fun isUserApp(pkg: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        info.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)

    fun installedPackageNames(): Set<String> = runCatching {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
            .map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun launch(pkg: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        }.getOrDefault(false)
    }

    fun uninstallIntent(pkg: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
