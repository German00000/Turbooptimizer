package com.turbooptimizer.device

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.storage.StorageManager
import android.view.Display
import com.turbooptimizer.monitor.SystemSnapshot
import com.turbooptimizer.util.SysFiles
import com.turbooptimizer.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

data class InfoSection(val title: String, val items: List<Pair<String, String>>)

data class GpuInfo(val renderer: String?, val vendor: String?, val glVersion: String?)

data class DeviceSummary(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val manufacturerUi: String,
    val soc: String,
    val abi: String,
    val ramTotal: Long,
    val resolution: String,
    val densityDpi: Int,
    val refreshRate: String,
    val storageTotal: Long,
    val gpu: String?,
    val cores: Int,
)

/**
 * Recopila información de hardware y software usando solo APIs públicas y propiedades legibles.
 * No se recopilan identificadores personales (IMEI, número de serie, cuentas) ni se envía nada.
 */
class DeviceInfoProvider(private val context: Context) {

    private val pm = context.packageManager
    @Volatile private var gpuCache: GpuInfo? = null

    fun manufacturerUi(): String {
        val p = SysFiles.props()
        p["ro.build.version.oneui"]?.toIntOrNull()?.let { v ->
            val major = v / 10000
            val minor = (v / 100) % 100
            val patch = v % 100
            return "One UI $major.$minor" + if (patch != 0) ".$patch" else ""
        }
        p["ro.mi.os.version.name"]?.takeIf { it.isNotBlank() }?.let { return "HyperOS $it" }
        p["ro.miui.ui.version.name"]?.takeIf { it.isNotBlank() }?.let { return "MIUI $it" }
        p["ro.build.version.oplusrom"]?.takeIf { it.isNotBlank() }?.let {
            return if (Build.MANUFACTURER.equals("OnePlus", true)) "OxygenOS/ColorOS $it" else "ColorOS $it"
        }
        p["ro.build.version.opporom"]?.takeIf { it.isNotBlank() }?.let { return "ColorOS $it" }
        p["ro.build.version.realmeui"]?.takeIf { it.isNotBlank() }?.let { return "realme UI $it" }
        p["ro.vivo.os.version"]?.takeIf { it.isNotBlank() }?.let { return "${p["ro.vivo.os.name"] ?: "Funtouch OS"} $it" }
        p["ro.build.version.magic"]?.takeIf { it.isNotBlank() }?.let { return "MagicOS $it" }
        p["ro.build.version.emui"]?.takeIf { it.isNotBlank() }?.let { return it }
        p["ro.build.nothing.version"]?.takeIf { it.isNotBlank() }?.let { return "Nothing OS $it" }
        return "Android (sin capa detectada)"
    }

    fun socName(): String {
        val model = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        val maker = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        val platform = SysFiles.prop("ro.board.platform") ?: SysFiles.prop("ro.hardware.chipname") ?: SysFiles.prop("ro.chipname")
        return listOfNotNull(maker, model).joinToString(" ").ifBlank { platform ?: Build.HARDWARE }
    }

    private fun display(): Display? = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    fun gpuInfo(): GpuInfo {
        gpuCache?.let { return it }
        val info = runCatching { queryGl() }.getOrElse { GpuInfo(null, null, null) }
        gpuCache = info
        return info
    }

    /** Crea un contexto EGL fuera de pantalla de 1x1 para consultar el renderizador OpenGL ES. */
    private fun queryGl(): GpuInfo {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return GpuInfo(null, null, null)
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return GpuInfo(null, null, null)
            val config = configs[0]
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, ctx)
            val result = GpuInfo(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                glVersion = GLES20.glGetString(GLES20.GL_VERSION),
            )
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            return result
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    suspend fun summary(): DeviceSummary = withContext(Dispatchers.IO) {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val d = display()
        val mode = d?.mode
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        DeviceSummary(
            model = "${Build.MODEL} (${Build.DEVICE})",
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            manufacturerUi = manufacturerUi(),
            soc = socName(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "N/D",
            ramTotal = mem.totalMem,
            resolution = mode?.let { "${it.physicalWidth} × ${it.physicalHeight}" } ?: "N/D",
            densityDpi = context.resources.displayMetrics.densityDpi,
            refreshRate = d?.let { String.format(Locale.getDefault(), "%.0f Hz", it.refreshRate) } ?: "N/D",
            storageTotal = runCatching { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrDefault(0L),
            gpu = gpuInfo().renderer,
            cores = Runtime.getRuntime().availableProcessors(),
        )
    }

    suspend fun sections(snapshot: SystemSnapshot?): List<InfoSection> = withContext(Dispatchers.IO) {
        listOf(
            general(),
            processor(),
            memoryAndStorage(),
            screen(),
            graphics(),
            system(),
            partitions(),
            battery(snapshot),
            connectivity(),
            cameras(),
            sensors(),
            features(),
        ).filter { it.items.isNotEmpty() }
    }

    private fun general() = InfoSection(
        "General",
        listOf(
            "Modelo" to Build.MODEL,
            "Fabricante" to Build.MANUFACTURER,
            "Marca" to Build.BRAND,
            "Dispositivo" to Build.DEVICE,
            "Producto" to Build.PRODUCT,
            "Placa" to Build.BOARD,
            "Hardware" to Build.HARDWARE,
            "Capa del fabricante" to manufacturerUi(),
        ),
    )

    private fun processor(): InfoSection {
        val cores = Runtime.getRuntime().availableProcessors()
        val items = mutableListOf(
            "SoC" to socName(),
            "Plataforma" to (SysFiles.prop("ro.board.platform") ?: "N/D"),
            "Núcleos" to cores.toString(),
            "ABI principal" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "N/D"),
            "ABIs soportadas" to Build.SUPPORTED_ABIS.joinToString(", "),
            "ABIs 64 bits" to Build.SUPPORTED_64_BIT_ABIS.joinToString(", ").ifBlank { "Ninguna" },
            "Arquitectura (os.arch)" to (System.getProperty("os.arch") ?: "N/D"),
        )
        // Agrupa núcleos por clúster según su frecuencia máxima.
        val clusters = (0 until cores).mapNotNull { i ->
            val max = SysFiles.readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: return@mapNotNull null
            val min = SysFiles.readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
            Triple(i, min, max)
        }.groupBy { it.third }
        clusters.entries.sortedBy { it.key }.forEachIndexed { idx, (max, list) ->
            val min = list.first().second
            val ids = list.joinToString(",") { it.first.toString() }
            items += "Clúster ${idx + 1} (CPU $ids)" to "${min?.div(1000) ?: "?"} – ${max / 1000} MHz"
        }
        SysFiles.readText("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies")?.let { freqs ->
            items += "Frecuencias disponibles CPU0" to freqs.split(Regex("\\s+")).mapNotNull { it.toLongOrNull()?.div(1000) }.joinToString(" · ") { "$it" } + " MHz"
        }
        if (clusters.isEmpty()) items += "Frecuencias" to "Android no permite leerlas en este dispositivo (prueba con Shizuku en el panel)"
        return InfoSection("Procesador (CPU)", items)
    }

    private fun memoryAndStorage(): InfoSection {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val meminfo = SysFiles.readText("/proc/meminfo")
        fun memKey(key: String) = meminfo?.lineSequence()?.firstOrNull { it.startsWith("$key:") }
            ?.let { Regex("\\d+").find(it)?.value?.toLongOrNull()?.times(1024) }
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val total = runCatching { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
        val free = runCatching { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
        return InfoSection(
            "Memoria y almacenamiento",
            listOfNotNull(
                "RAM total" to formatBytes(mem.totalMem),
                "RAM disponible" to formatBytes(mem.availMem),
                "Umbral de memoria baja" to formatBytes(mem.threshold),
                memKey("SwapTotal")?.let { "Swap/ZRAM" to formatBytes(it) },
                "Clase de memoria por app" to "${am.memoryClass} MB (grande: ${am.largeMemoryClass} MB)",
                "Dispositivo de poca RAM" to if (am.isLowRamDevice) "Sí" else "No",
                "Almacenamiento total" to formatBytes(total),
                "Almacenamiento libre" to formatBytes(free),
                "Volúmenes" to volumes.joinToString("\n") { v ->
                    "${v.getDescription(context)}${if (v.isRemovable) " (extraíble)" else ""}${if (v.isPrimary) " (principal)" else ""}"
                },
            ),
        )
    }

    private fun screen(): InfoSection {
        val d = display() ?: return InfoSection("Pantalla", emptyList())
        val metrics = context.resources.displayMetrics
        val mode = d.mode
        val inches = sqrt(
            (mode.physicalWidth / metrics.xdpi).let { it * it } + (mode.physicalHeight / metrics.ydpi).let { it * it },
        )
        @Suppress("DEPRECATION")
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.joinToString { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "Tipo $type"
            }
        }
        return InfoSection(
            "Pantalla",
            listOf(
                "Resolución" to "${mode.physicalWidth} × ${mode.physicalHeight} px",
                "Densidad" to "${metrics.densityDpi} dpi (${densityBucket(metrics.densityDpi)})",
                "DPI físico" to String.format(Locale.getDefault(), "%.0f × %.0f", metrics.xdpi, metrics.ydpi),
                "Tamaño aproximado" to String.format(Locale.getDefault(), "%.1f\"", inches),
                "Frecuencia actual" to String.format(Locale.getDefault(), "%.0f Hz", d.refreshRate),
                "Frecuencias soportadas" to d.supportedModes.map { it.refreshRate }.distinct().sorted()
                    .joinToString(" · ") { String.format(Locale.getDefault(), "%.0f Hz", it) },
                "HDR" to (hdr?.ifBlank { null } ?: "No"),
                "Gama de color amplia" to if (d.isWideColorGamut) "Sí" else "No",
            ),
        )
    }

    private fun densityBucket(dpi: Int) = when {
        dpi <= 120 -> "ldpi"
        dpi <= 160 -> "mdpi"
        dpi <= 240 -> "hdpi"
        dpi <= 320 -> "xhdpi"
        dpi <= 480 -> "xxhdpi"
        else -> "xxxhdpi"
    }

    private fun graphics(): InfoSection {
        val am = context.getSystemService(ActivityManager::class.java)
        val gpu = gpuInfo()
        val features = pm.systemAvailableFeatures
        val vkVersion = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        val vkLevel = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version
        val deqp = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_DEQP_LEVEL }?.version
        val vk = vkVersion?.let { "${it shr 22}.${(it shr 12) and 0x3ff}.${it and 0xfff}" }
        return InfoSection(
            "Gráficos (GPU)",
            listOf(
                "GPU" to (gpu.renderer ?: "N/D"),
                "Fabricante GPU" to (gpu.vendor ?: "N/D"),
                "OpenGL ES" to (gpu.glVersion ?: am.deviceConfigurationInfo.glEsVersion),
                "Vulkan" to (vk ?: "No soportado"),
                "Nivel de hardware Vulkan" to (vkLevel?.toString() ?: "N/D"),
                "Nivel dEQP Vulkan" to (deqp?.let { decodeDeqp(it) } ?: "N/D"),
            ),
        )
    }

    private fun decodeDeqp(v: Int): String {
        val year = v shr 16
        val month = (v shr 8) and 0xff
        val day = v and 0xff
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun system(): InfoSection {
        val kernel = System.getProperty("os.version") ?: "N/D"
        return InfoSection(
            "Sistema",
            listOf(
                "Android" to Build.VERSION.RELEASE,
                "API Level" to Build.VERSION.SDK_INT.toString(),
                "Parche de seguridad" to Build.VERSION.SECURITY_PATCH,
                "Build ID" to Build.ID,
                "Build" to Build.DISPLAY,
                "Incremental" to Build.VERSION.INCREMENTAL,
                "Tipo / etiquetas" to "${Build.TYPE} / ${Build.TAGS}",
                "Huella (fingerprint)" to Build.FINGERPRINT,
                "Kernel" to kernel,
                "Bootloader" to Build.BOOTLOADER,
                "Baseband" to (Build.getRadioVersion()?.ifBlank { null } ?: "N/D"),
                "Máquina virtual" to "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}",
                "Capa del fabricante" to manufacturerUi(),
            ),
        )
    }

    private fun partitions(): InfoSection {
        val p = SysFiles.props()
        fun yesNo(v: String?) = when (v) {
            "true", "1" -> "Sí"
            "false", "0" -> "No"
            null, "" -> "No detectado"
            else -> v
        }
        val slot = p["ro.boot.slot_suffix"]
        return InfoSection(
            "Treble y particiones",
            listOf(
                "Project Treble" to yesNo(p["ro.treble.enabled"]),
                "VNDK" to (p["ro.vndk.version"] ?: "N/D"),
                "Actualizaciones A/B" to yesNo(p["ro.build.ab_update"]),
                "Ranura activa" to (slot?.ifBlank { null } ?: "N/D (A-only o no expuesto)"),
                "Virtual A/B" to yesNo(p["ro.virtual_ab.enabled"]),
                "Particiones dinámicas" to yesNo(p["ro.boot.dynamic_partitions"]),
                "Versión API de la vendor" to (p["ro.vendor.api_level"] ?: p["ro.board.first_api_level"] ?: "N/D"),
                "Primera API de lanzamiento" to (p["ro.product.first_api_level"] ?: "N/D"),
            ),
        )
    }

    private fun battery(snapshot: SystemSnapshot?): InfoSection {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
        val bm = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        val chargeCounter = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()
            ?.takeIf { it > 0 && it != Int.MIN_VALUE }
        return InfoSection(
            "Batería y temperatura",
            listOfNotNull(
                snapshot?.let { "Nivel" to "${it.batteryPercent} %" },
                snapshot?.let { "Estado" to it.batteryStatus },
                snapshot?.let { "Fuente" to it.plugged },
                snapshot?.let { "Salud" to it.batteryHealth },
                "Tecnología" to (intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "N/D"),
                snapshot?.batteryTempC?.let { "Temperatura batería" to String.format(Locale.getDefault(), "%.1f °C", it) },
                snapshot?.batteryVoltageMv?.let { "Voltaje" to "$it mV" },
                snapshot?.batteryCurrentMa?.let { "Corriente" to "$it mA" },
                chargeCounter?.let { "Carga restante" to "${it / 1000} mAh" },
                cycles?.let { "Ciclos de carga" to it.toString() },
                "Estado térmico" to SystemSnapshot.thermalLabel(power.currentThermalStatus),
                snapshot?.thermal?.cpu?.let { "Temperatura CPU" to String.format(Locale.getDefault(), "%.1f °C", it) },
                snapshot?.thermal?.gpu?.let { "Temperatura GPU" to String.format(Locale.getDefault(), "%.1f °C", it) },
                snapshot?.thermal?.skin?.let { "Temperatura carcasa" to String.format(Locale.getDefault(), "%.1f °C", it) },
            ),
        )
    }

    private fun connectivity(): InfoSection {
        fun has(feature: String) = if (pm.hasSystemFeature(feature)) "Sí" else "No"
        val wifi = context.getSystemService(WifiManager::class.java)
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        val nfc = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        val wifiStd = buildList {
            if (runCatching { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N) }.getOrDefault(false)) add("Wi-Fi 4")
            if (runCatching { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC) }.getOrDefault(false)) add("Wi-Fi 5")
            if (runCatching { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) }.getOrDefault(false)) add("Wi-Fi 6")
            if (runCatching { wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE) }.getOrDefault(false)) add("Wi-Fi 7")
        }
        return InfoSection(
            "Conectividad",
            listOf(
                "Wi-Fi" to has(PackageManager.FEATURE_WIFI),
                "Wi-Fi activado" to if (runCatching { wifi.isWifiEnabled }.getOrDefault(false)) "Sí" else "No",
                "Estándares Wi-Fi" to wifiStd.joinToString(", ").ifBlank { "N/D" },
                "Banda 5 GHz" to if (runCatching { wifi.is5GHzBandSupported }.getOrDefault(false)) "Sí" else "No",
                "Banda 6 GHz" to if (runCatching { wifi.is6GHzBandSupported }.getOrDefault(false)) "Sí" else "No",
                "Wi-Fi Direct" to has(PackageManager.FEATURE_WIFI_DIRECT),
                "Bluetooth" to if (bt != null) "Sí" else "No",
                "Bluetooth LE" to has(PackageManager.FEATURE_BLUETOOTH_LE),
                "NFC" to if (nfc != null) (if (nfc.isEnabled) "Sí (activado)" else "Sí (desactivado)") else "No",
                "USB host (OTG)" to has(PackageManager.FEATURE_USB_HOST),
                "USB accesorio" to has(PackageManager.FEATURE_USB_ACCESSORY),
                "Telefonía" to has(PackageManager.FEATURE_TELEPHONY),
                "Infrarrojos" to has(PackageManager.FEATURE_CONSUMER_IR),
                "GPS" to has(PackageManager.FEATURE_LOCATION_GPS),
            ),
        )
    }

    private fun cameras(): InfoSection {
        val cm = context.getSystemService(CameraManager::class.java) ?: return InfoSection("Cámaras", emptyList())
        val items = runCatching {
            cm.cameraIdList.map { id ->
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_FRONT -> "Frontal"
                    CameraMetadata.LENS_FACING_BACK -> "Trasera"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "Externa"
                    else -> "Desconocida"
                }
                val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = px?.let { String.format(Locale.getDefault(), "%.1f MP", it.width * it.height / 1_000_000f) } ?: "? MP"
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { String.format(Locale.getDefault(), "%.1f mm", it) }
                val flash = if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "con flash" else "sin flash"
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "Externa"
                    else -> "?"
                }
                "Cámara $id ($facing)" to "$mp · ${focal ?: "?"} · $flash · Camera2 $level"
            }
        }.getOrDefault(emptyList())
        return InfoSection("Cámaras", items)
    }

    private fun sensors(): InfoSection {
        val sm = context.getSystemService(SensorManager::class.java) ?: return InfoSection("Sensores", emptyList())
        val items = sm.getSensorList(Sensor.TYPE_ALL).map { s ->
            s.name to "${s.vendor} · ${s.stringType.substringAfterLast('.')}"
        }
        return InfoSection("Sensores (${items.size})", items)
    }

    private fun features(): InfoSection {
        val list = pm.systemAvailableFeatures.mapNotNull { it.name }.sorted()
        return InfoSection("Características de hardware (${list.size})", list.map { it.removePrefix("android.hardware.").removePrefix("android.software.") to "Disponible" })
    }
}
