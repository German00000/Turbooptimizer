package com.turbooptimizer.core.safety

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import com.turbooptimizer.core.shizuku.CommandPolicy

/**
 * Decide si una app puede detenerse/restringirse. Combina:
 *  - lista estática de componentes críticos ([CommandPolicy]),
 *  - apps que el sistema está usando ahora (launcher, teclado, teléfono, SMS, accesibilidad, fondo animado),
 *  - la lista blanca del usuario y el juego en curso,
 *  - procesos persistentes del sistema.
 */
class SafetyGuard(private val context: Context) {

    private val pm = context.packageManager
    @Volatile private var cache: Pair<Long, Map<String, String>>? = null

    fun dynamicProtected(): Map<String, String> {
        cache?.let { (time, map) -> if (System.currentTimeMillis() - time < 30_000) return map }
        val map = LinkedHashMap<String, String>()
        runCatching {
            pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )?.activityInfo?.packageName
        }.getOrNull()?.let { map[it] = "Launcher predeterminado" }
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) }
            .getOrNull()?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let { map[it] = "Teclado activo" }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
            .getOrNull()?.let { map[it] = "App de teléfono predeterminada" }
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }
            .getOrNull()?.let { map[it] = "App de SMS predeterminada" }
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }
            .getOrNull()?.split(':')?.map { it.substringBefore('/') }?.filter { it.isNotBlank() }
            ?.forEach { map[it] = "Servicio de accesibilidad activo" }
        runCatching { WallpaperManager.getInstance(context).wallpaperInfo?.packageName }
            .getOrNull()?.let { map[it] = "Fondo de pantalla animado activo" }
        map[context.packageName] = "TurboOptimizer"
        map[CommandPolicy.SHIZUKU_PACKAGE] = "Shizuku"
        cache = System.currentTimeMillis() to map
        return map
    }

    /** Devuelve el motivo de protección o null si la app puede detenerse. */
    fun protectionReason(pkg: String, whitelist: Set<String>, extraProtected: Set<String> = emptySet()): String? {
        if (CommandPolicy.isCriticalPackage(pkg)) return "Componente crítico del sistema"
        dynamicProtected()[pkg]?.let { return it }
        if (pkg in extraProtected) return "Juego en uso"
        if (pkg in whitelist) return "En la lista blanca"
        val info = runCatching { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull()
            ?: return null
        if (info.flags and ApplicationInfo.FLAG_PERSISTENT != 0) return "Proceso persistente del sistema"
        return null
    }

    /** Solo apps de usuario y no críticas pueden desinstalarse desde la app. */
    fun canUninstall(pkg: String, isSystem: Boolean): Boolean =
        !isSystem && !CommandPolicy.isCriticalPackage(pkg) && pkg != context.packageName
}
