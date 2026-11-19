package com.turbooptimizer.monitor

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.storage.StorageManager
import android.util.Log
import android.view.Display
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.util.SysFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import kotlin.math.abs

data class NetworkStatus(
    val type: String,
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val localIp: String?,
)

/** Temperaturas de la HAL térmica. Cada valor es el sensor elegido de ese grupo, o null si no hay. */
data class ThermalReadings(
    val cpu: Float?,
    val gpu: Float?,
    val skin: Float?,
    /** Nombre del sensor CPU usado (p. ej. "cpu4-..."); null si no hay sensor CPU. */
    val cpuSensor: String? = null,
    /** Número de sensores válidos leídos de la HAL. */
    val sensorCount: Int = 0,
)

data class SystemSnapshot(
    val timestamp: Long,
    val ramTotal: Long,
    val ramAvail: Long,
    val lowMemory: Boolean,
    val storageTotal: Long,
    val storageFree: Long,
    val batteryPercent: Int,
    val batteryStatus: String,
    val charging: Boolean,
    val plugged: String,
    val batteryHealth: String,
    val batteryTempC: Float?,
    val batteryVoltageMv: Int?,
    val batteryCurrentMa: Int?,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val thermal: ThermalReadings?,
    val cpuUsagePercent: Float?,
    val cpuFreqsMhz: List<Int?>,
    val gpuLoadPercent: Float?,
    val network: NetworkStatus,
    /** Frecuencia de refresco ACTUAL de la pantalla (Hz). No son FPS del contenido. */
    val screenHz: Float? = null,
    /** Frecuencia de refresco MÁXIMA soportada por la pantalla (Hz). */
    val maxScreenHz: Float? = null,
) {
    /** RAM usada = total − disponible, acotada a [0, total]. */
    val ramUsed: Long get() = MetricMath.ramUsedBytes(ramTotal, ramAvail)
    val ramUsedFraction: Float get() = MetricMath.ramUsedFraction(ramTotal, ramAvail)
    val ramAvailPercent: Float get() = if (ramTotal > 0) (ramAvail.coerceIn(0L, ramTotal) * 100f / ramTotal) else 0f
    val storageUsedFraction: Float get() = if (storageTotal > 0) (storageTotal - storageFree).toFloat() / storageTotal else 0f
    val maxCpuFreqMhz: Int? get() = cpuFreqsMhz.filterNotNull().maxOrNull()

    /** Mejor temperatura disponible: CPU (HAL, Shizuku) > carcasa (HAL) > batería (API pública). */
    val bestTempC: Float? get() = thermal?.cpu ?: thermal?.skin ?: batteryTempC

    /** Origen de [bestTempC] para mostrarlo al usuario. */
    val tempSource: String? get() = when {
        thermal?.cpu != null -> "CPU" + (thermal.cpuSensor?.let { " ($it)" } ?: "")
        thermal?.skin != null -> "Carcasa"
        batteryTempC != null -> "Batería"
        else -> null
    }

    val thermalStatusLabel: String get() = thermalLabel(thermalStatus)

    companion object {
        fun thermalLabel(status: Int) = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Leve"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderado"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severo"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Crítico"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergencia"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Apagado inminente"
            else -> "Desconocido"
        }
    }
}

/**
 * Lee métricas mediante APIs públicas y, cuando Shizuku está activo, mediante lecturas de solo
 * lectura permitidas (/proc/stat, cpufreq, dumpsys thermalservice). Nada se envía fuera del dispositivo.
 *
 * Cadencia: [stream] emite ~1 vez por segundo. Las lecturas pesadas (Shizuku) tienen su propia
 * cadencia: temperatura cada [THERMAL_EVERY] ticks, almacenamiento cada [STORAGE_EVERY] ticks.
 */
class SystemMonitor(private val context: Context, private val shizuku: ShizukuManager) {

    private val am = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val ssm = context.getSystemService(StorageStatsManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()
    private val mutex = Mutex()

    private var prevCpu: MetricMath.CpuTimes? = null
    private var directFreq: Boolean? = null
    private var directStat: Boolean? = null
    private var gpuSource: Pair<String, Boolean>? = null // ruta, lectura directa
    private var gpuProbed = false
    private var tick = 0
    private var thermalAt = Int.MIN_VALUE
    private var cachedThermal: ThermalReadings? = null
    private var cachedStorage: Pair<Long, Long>? = null

    /** Último mensaje publicado por cada métrica; evita repetir el mismo log cada segundo. */
    private val reported = HashMap<String, String>()

    fun stream(intervalMs: Long = 1_000L): Flow<SystemSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    fun memoryInfo(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    suspend fun snapshot(): SystemSnapshot = mutex.withLock {
        tick++
        val mem = memoryInfo()
        val storage = cachedStorage?.takeUnless { tick % STORAGE_EVERY == 1 } ?: readStorage().also { cachedStorage = it }
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        // EXTRA_TEMPERATURE llega en décimas de grado (p. ej. 312 = 31,2 °C).
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val voltage = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        // BATTERY_PROPERTY_CURRENT_NOW: µA en la mayoría de dispositivos (negativo = descarga); algunos informan mA.
        val current = runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }
            ?.let { if (abs(it) > 20_000) it / 1000 else it }

        val headroom = runCatching { power.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() }
        if (!shizuku.isReady) {
            cachedThermal = null
            thermalAt = Int.MIN_VALUE
        } else if (tick - thermalAt >= THERMAL_EVERY) {
            thermalAt = tick
            cachedThermal = readThermal()
        }

        val (screenHz, maxScreenHz) = screenRates()

        SystemSnapshot(
            timestamp = System.currentTimeMillis(),
            ramTotal = mem.totalMem,
            ramAvail = mem.availMem,
            lowMemory = mem.lowMemory,
            storageTotal = storage.first,
            storageFree = storage.second,
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1,
            batteryStatus = batteryStatusLabel(status),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = pluggedLabel(plugged),
            batteryHealth = healthLabel(health),
            batteryTempC = temp,
            batteryVoltageMv = voltage,
            batteryCurrentMa = current,
            thermalStatus = power.currentThermalStatus,
            thermalHeadroom = headroom,
            thermal = cachedThermal,
            cpuUsagePercent = cpuUsage(),
            cpuFreqsMhz = cpuFreqs(),
            gpuLoadPercent = gpuLoad(),
            network = network(),
            screenHz = screenHz,
            maxScreenHz = maxScreenHz,
        )
    }

    /** Hz actuales y máximos de la pantalla (API pública; no son FPS). */
    private fun screenRates(): Pair<Float?, Float?> {
        val d = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null to null
        val current = runCatching { d.mode.refreshRate }.getOrNull()?.takeIf { it > 0f }
        val max = runCatching { d.supportedModes.maxOfOrNull { it.refreshRate } }.getOrNull()?.takeIf { it > 0f }
        return current to max
    }

    private fun readStorage(): Pair<Long, Long> {
        val total = runCatching { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrDefault(0L)
        val free = runCatching { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrDefault(0L)
        return total to free
    }

    /** Publica un mensaje solo cuando cambia (diagnóstico sin spam). No incluye datos personales. */
    private fun report(key: String, msg: String) {
        if (reported[key] != msg) {
            reported[key] = msg
            Log.i(TAG, msg)
        }
    }

    // ---------- CPU ----------

    /** Texto de /proc/stat: lectura directa si la SELinux lo permite; si no, mediante Shizuku. */
    private suspend fun readProcStat(): String? {
        if (directStat != false) {
            val text = SysFiles.readText("/proc/stat")
            if (text != null) {
                if (directStat != true) report("cpu.src", "CPU: /proc/stat leído directamente")
                directStat = true
                return text
            }
            directStat = false
        }
        if (!shizuku.isReady) {
            report("cpu.src", "CPU: /proc/stat no accesible y Shizuku no está listo")
            return null
        }
        val r = shizuku.exec(ShellCommands.cat(listOf("/proc/stat")), 3_000)
        if (r.isSuccess && r.stdout.isNotBlank()) {
            report("cpu.src", "CPU: /proc/stat leído mediante Shizuku")
            return r.stdout
        }
        report("cpu.src", "CPU: lectura de /proc/stat fallida (código ${r.exitCode})")
        return null
    }

    /** Uso total aproximado de CPU (todos los núcleos), entre dos lecturas consecutivas. */
    private suspend fun cpuUsage(): Float? {
        val cur = readProcStat()?.let { MetricMath.parseCpuTimes(it) } ?: return null
        val pct = MetricMath.cpuPercent(prevCpu, cur)
        prevCpu = cur
        return pct
    }

    private suspend fun cpuFreqs(): List<Int?> {
        val paths = (0 until cores).map { "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq" }
        if (directFreq != false) {
            val values = paths.map { SysFiles.readLong(it) }
            if (values.any { it != null }) {
                directFreq = true
                return values.map { v -> v?.let { (it / 1000).toInt() } }
            }
            directFreq = false
        }
        if (!shizuku.isReady) return List(cores) { null }
        val r = shizuku.exec(ShellCommands.cat(paths), 3_000)
        val numbers = r.stdout.lines().mapNotNull { it.trim().toLongOrNull() }
        return if (numbers.size == cores) numbers.map { (it / 1000).toInt() }
        else List(cores) { i -> numbers.getOrNull(i)?.let { (it / 1000).toInt() } }
    }

    // ---------- GPU ----------

    private val gpuCandidates = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", // Adreno
        "/sys/class/kgsl/kgsl-3d0/gpubusy",             // Adreno (busy total)
        "/sys/kernel/gpu/gpu_busy",                     // Mali (Samsung/MediaTek)
    )

    private suspend fun gpuLoad(): Float? {
        if (!gpuProbed) {
            gpuProbed = true
            for (path in gpuCandidates) {
                if (SysFiles.readText(path) != null) { gpuSource = path to true; break }
                if (shizuku.isReady && shizuku.exec(ShellCommands.cat(listOf(path)), 2_000).isSuccess) { gpuSource = path to false; break }
            }
        }
        val (path, direct) = gpuSource ?: return null
        val text: String = (
            if (direct) SysFiles.readText(path)
            else if (shizuku.isReady) shizuku.exec(ShellCommands.cat(listOf(path)), 2_000).stdout
            else null
            ) ?: return null
        val nums = Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.toList()
        return when {
            path.endsWith("gpubusy") && nums.size >= 2 -> if (nums[1] > 0) nums[0] * 100f / nums[1] else 0f
            nums.isNotEmpty() -> nums[0].toFloat().coerceIn(0f, 100f)
            else -> null
        }
    }

    // ---------- Temperatura ----------

    /**
     * Sensores de la HAL térmica vía `dumpsys thermalservice` (solo lectura, requiere Shizuku).
     * Selección: CPU = sensor más caliente de tipo CPU que no sea auxiliar; carcasa = tipo SKIN.
     * Si no hay sensores válidos devuelve null (N/D); nunca inventa un valor.
     */
    private suspend fun readThermal(): ThermalReadings? {
        val r = shizuku.exec(ShellCommands.thermal(), 5_000)
        if (!r.isSuccess) {
            report("thermal", "Temperatura: dumpsys thermalservice falló (código ${r.exitCode})")
            return null
        }
        val out = r.stdout
        val start = out.indexOf("Current temperatures from HAL")
        val section = if (start >= 0) {
            val end = out.indexOf("Current cooling devices", start).takeIf { it > 0 } ?: out.length
            out.substring(start, end)
        } else out
        val sensors = MetricMath.parseThermalSensors(section)
        if (sensors.isEmpty()) {
            report("thermal", "Temperatura: la HAL no devolvió sensores válidos")
            return null
        }
        val cpu = MetricMath.selectCpu(sensors)
        val gpu = MetricMath.selectOfType(sensors, MetricMath.TYPE_GPU)
        val skin = MetricMath.selectOfType(sensors, MetricMath.TYPE_SKIN)
        report(
            "thermal",
            "Temperatura: ${sensors.size} sensores válidos; CPU=${cpu?.name ?: "N/D"}, carcasa=${skin != null}",
        )
        return ThermalReadings(
            cpu = cpu?.tempC,
            gpu = gpu?.tempC,
            skin = skin?.tempC,
            cpuSensor = cpu?.name,
            sensorCount = sensors.size,
        )
    }

    // ---------- Red ----------

    private fun network(): NetworkStatus {
        val net = cm.activeNetwork ?: return NetworkStatus("Sin conexión", false, false, false, null)
        val caps = cm.getNetworkCapabilities(net)
        val type = when {
            caps == null -> "Desconocida"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos móviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Otra"
        }
        // Solo direcciones IPv4 privadas de la red local; nunca la IP pública.
        val ip = runCatching {
            cm.getLinkProperties(net)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
        return NetworkStatus(
            type = type,
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            localIp = ip,
        )
    }

    private fun batteryStatusLabel(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
        BatteryManager.BATTERY_STATUS_FULL -> "Carga completa"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "No se está cargando"
        else -> "Desconocido"
    }

    private fun pluggedLabel(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "Cargador AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrica"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Base"
        0 -> "Desconectado"
        else -> "Otro"
    }

    private fun healthLabel(health: Int) = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentada"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensión"
        BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Fallo"
        else -> "Desconocida"
    }

    private companion object {
        const val TAG = "SystemMonitor"
        /** Temperatura por Shizuku cada 3 ticks (~3 s). */
        const val THERMAL_EVERY = 3
        /** Almacenamiento cada 30 ticks (~30 s): cambia poco y no hace falta leerlo cada segundo. */
        const val STORAGE_EVERY = 30
    }
}
