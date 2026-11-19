package com.turbooptimizer.core.shizuku

import com.turbooptimizer.BuildConfig

/**
 * Lista blanca estricta de los ÚNICOS comandos que TurboOptimizer puede ejecutar a través de Shizuku.
 *
 * - Cada comando es una lista de argumentos que se ejecuta con ProcessBuilder, nunca con `sh -c`,
 *   por lo que no existen tuberías, redirecciones ni encadenamiento de comandos.
 * - Esta política se aplica dos veces: en la app antes de enviar el comando y dentro del
 *   proceso de Shizuku (ShellUserService) antes de ejecutarlo.
 * - Ningún comando escribe en particiones del sistema ni borra archivos.
 */
object CommandPolicy {

    sealed interface Verdict {
        data class Allowed(val mutating: Boolean) : Verdict
        data class Denied(val reason: String) : Verdict
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val USER_REGEX = Regex("^\\d{1,3}$")
    private val SCALE_REGEX = Regex("^(0|0\\.5|1|1\\.0|1\\.5|2|2\\.0|0\\.0)$")
    private val TRIM_REGEX = Regex("^\\d{1,4}[KMG]$")
    private val FORBIDDEN_CHARS = charArrayOf('\n', '\r', '\u0000', ';', '|', '&', '`', '$', '>', '<', '*', '?', '"', '\'', '\\')
    /** Igual que [FORBIDDEN_CHARS] pero admite '$': los nombres de actividad con clases internas (Clase$Interna) lo usan. Sin shell, no es inyección. */
    private val LAYER_FORBIDDEN_CHARS = FORBIDDEN_CHARS.filter { it != '$' }.toCharArray()

    val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    private val GAME_MODES = setOf("standard", "performance", "battery")
    private val APPOPS_RESTORE_MODES = setOf("allow", "default", "ignore")

    /** Rutas de solo lectura permitidas para `cat` (frecuencias, carga de GPU, temperaturas). */
    private val READABLE_PATHS = listOf(
        Regex("^/proc/stat$"),
        Regex("^/sys/devices/system/cpu/cpu\\d{1,2}/cpufreq/(scaling_cur_freq|cpuinfo_max_freq|cpuinfo_min_freq|scaling_available_frequencies)$"),
        Regex("^/sys/class/kgsl/kgsl-3d0/(gpubusy|gpu_busy_percentage|gpuclk)$"),
        Regex("^/sys/kernel/gpu/(gpu_busy|gpu_clock|gpu_model)$"),
        Regex("^/sys/class/thermal/thermal_zone\\d{1,3}/(temp|type)$"),
    )

    /** Paquetes que jamás se detienen, restringen ni desactivan. */
    val CRITICAL_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.se",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.server.telecom",
        "com.android.keychain",
        "com.android.location.fused",
        "com.android.externalstorage",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.google.android.ext.services",
        "com.android.emergency",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
        "com.android.wifi.resources",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.samsung.android.lool",
        "com.samsung.android.sm.devicesecurity",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.oplus.launcher",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.mms",
        "com.google.android.apps.messaging",
        SHIZUKU_PACKAGE,
    )

    private val CRITICAL_PREFIXES = listOf(
        "com.android.providers.",
        "com.android.server.",
        "com.android.systemui.",
        "com.android.internal.",
        "com.android.overlay.",
        "com.google.android.overlay.",
        "com.qualcomm.qti.",
        "com.qti.",
        "vendor.",
        "com.mediatek.",
        "com.samsung.android.knox",
        "com.samsung.android.providers.",
        "com.samsung.android.server.",
        "com.sec.android.provider.",
        "android.ext.",
        "android.auto_generated",
    )

    fun isValidPackage(pkg: String): Boolean = pkg.length in 3..200 && PACKAGE_REGEX.matches(pkg)

    /** Nombre de capa de SurfaceFlinger (solo lectura): ASCII imprimible, sin opciones ni caracteres de shell. */
    fun isValidLayerName(name: String): Boolean =
        name.length in 1..200 && !name.startsWith("-") && name.all { it in ' '..'~' } && name.none { it in LAYER_FORBIDDEN_CHARS }

    fun isCriticalPackage(pkg: String): Boolean =
        pkg == BuildConfig.APPLICATION_ID ||
            pkg in CRITICAL_PACKAGES ||
            CRITICAL_PREFIXES.any { pkg.startsWith(it) }

    fun check(cmd: List<String>): Verdict {
        if (cmd.isEmpty() || cmd.size > 20) return Verdict.Denied("Comando vacío o demasiado largo")
        val forbidden = if (cmd.size == 4 && cmd[0] == "dumpsys" && cmd[1] == "SurfaceFlinger") LAYER_FORBIDDEN_CHARS else FORBIDDEN_CHARS
        if (cmd.any { it.isEmpty() || it.length > 256 || it.any { c -> c in forbidden } }) {
            return Verdict.Denied("Argumentos con caracteres no permitidos")
        }
        return when (cmd[0]) {
            "am" -> checkAm(cmd)
            "pm" -> checkPm(cmd)
            "cmd" -> checkCmd(cmd)
            "settings" -> checkSettings(cmd)
            "dumpsys" -> checkDumpsys(cmd)
            "cat" -> if (cmd.size in 2..17 && cmd.drop(1).all { p -> READABLE_PATHS.any { it.matches(p) } }) {
                Verdict.Allowed(mutating = false)
            } else Verdict.Denied("Ruta de lectura no permitida")
            "service" -> if (cmd == listOf("service", "call", "SurfaceFlinger", "1013")) {
                Verdict.Allowed(mutating = false)
            } else Verdict.Denied("Llamada de servicio no permitida")
            else -> Verdict.Denied("Binario no permitido: ${cmd[0]}")
        }
    }

    private fun stoppable(pkg: String): Verdict? = when {
        !isValidPackage(pkg) -> Verdict.Denied("Nombre de paquete no válido")
        isCriticalPackage(pkg) -> Verdict.Denied("$pkg es un componente crítico y está protegido")
        else -> null
    }

    private fun checkAm(cmd: List<String>): Verdict = when {
        cmd.size == 3 && cmd[1] == "force-stop" -> stoppable(cmd[2]) ?: Verdict.Allowed(true)
        cmd.size == 3 && cmd[1] == "kill" -> stoppable(cmd[2]) ?: Verdict.Allowed(true)
        cmd.size == 2 && cmd[1] == "kill-all" -> Verdict.Allowed(true) // solo procesos en segundo plano
        else -> Verdict.Denied("Subcomando 'am' no permitido")
    }

    private fun checkPm(cmd: List<String>): Verdict = when {
        cmd.size == 5 && cmd[1] == "disable-user" && cmd[2] == "--user" && USER_REGEX.matches(cmd[3]) ->
            stoppable(cmd[4]) ?: Verdict.Allowed(true)
        // Reactivar siempre está permitido (es la operación de deshacer)
        cmd.size == 5 && cmd[1] == "enable" && cmd[2] == "--user" && USER_REGEX.matches(cmd[3]) ->
            if (isValidPackage(cmd[4])) Verdict.Allowed(true) else Verdict.Denied("Paquete no válido")
        cmd.size == 3 && cmd[1] == "trim-caches" && TRIM_REGEX.matches(cmd[2]) -> Verdict.Allowed(true)
        else -> Verdict.Denied("Subcomando 'pm' no permitido")
    }

    private fun checkCmd(cmd: List<String>): Verdict = when {
        cmd.size == 5 && cmd[1] == "appops" && cmd[2] == "get" && cmd[4] == "RUN_ANY_IN_BACKGROUND" ->
            if (isValidPackage(cmd[3])) Verdict.Allowed(false) else Verdict.Denied("Paquete no válido")
        cmd.size == 6 && cmd[1] == "appops" && cmd[2] == "set" && cmd[4] == "RUN_ANY_IN_BACKGROUND" && cmd[5] in APPOPS_RESTORE_MODES -> {
            if (cmd[5] == "ignore") stoppable(cmd[3]) ?: Verdict.Allowed(true)
            else if (isValidPackage(cmd[3])) Verdict.Allowed(true) else Verdict.Denied("Paquete no válido")
        }
        cmd.size == 5 && cmd[1] == "game" && cmd[2] == "mode" && cmd[3] in GAME_MODES ->
            if (isValidPackage(cmd[4])) Verdict.Allowed(true) else Verdict.Denied("Paquete no válido")
        else -> Verdict.Denied("Subcomando 'cmd' no permitido")
    }

    private fun checkSettings(cmd: List<String>): Verdict = when {
        cmd.size == 4 && cmd[1] == "get" && cmd[2] == "global" && cmd[3] in ANIMATION_KEYS -> Verdict.Allowed(false)
        cmd.size == 5 && cmd[1] == "put" && cmd[2] == "global" && cmd[3] in ANIMATION_KEYS && SCALE_REGEX.matches(cmd[4]) ->
            Verdict.Allowed(true)
        else -> Verdict.Denied("Solo se permiten las escalas de animación")
    }

    private fun checkDumpsys(cmd: List<String>): Verdict = when {
        cmd.drop(1) == listOf("meminfo", "-c") || cmd.drop(1) == listOf("thermalservice") -> Verdict.Allowed(false)
        // Lista de capas de SurfaceFlinger (solo lectura)
        cmd.drop(1) == listOf("SurfaceFlinger", "--list") -> Verdict.Allowed(false)
        // Marcas de presentación de una capa concreta (solo lectura)
        cmd.size == 4 && cmd[1] == "SurfaceFlinger" && cmd[2] == "--latency" && isValidLayerName(cmd[3]) ->
            Verdict.Allowed(false)
        else -> Verdict.Denied("dumpsys no permitido")
    }
}
