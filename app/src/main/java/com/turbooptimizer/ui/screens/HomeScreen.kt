package com.turbooptimizer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.device.DeviceSummary
import com.turbooptimizer.monitor.SystemSnapshot
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.LabeledDot
import com.turbooptimizer.ui.components.LineChart
import com.turbooptimizer.ui.components.MetricTile
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.ShizukuStatusCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.Routes
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatPercent
import com.turbooptimizer.util.formatTemp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryPoint(val ram: Float, val cpu: Float?, val temp: Float?)

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    val snapshot = c.snapshots
    val shizuku = c.shizuku.state
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _history = MutableStateFlow<List<HistoryPoint>>(emptyList())
    val history = _history.asStateFlow()
    private val _summary = MutableStateFlow<DeviceSummary?>(null)
    val summary = _summary.asStateFlow()

    init {
        viewModelScope.launch {
            c.snapshots.filterNotNull().collect { s ->
                _history.update {
                    (it + HistoryPoint(s.ramUsedFraction, s.cpuUsagePercent?.div(100f), s.bestTempC?.div(80f))).takeLast(60)
                }
            }
        }
        viewModelScope.launch { _summary.value = runCatching { c.deviceInfo.summary() }.getOrNull() }
    }

    fun requestShizuku() = c.shizuku.requestPermission()
    fun openShizuku() = c.shizuku.openShizukuApp()
    fun toggleIp() = viewModelScope.launch { c.settings.update { it.copy(showLocalIp = !it.showLocalIp) } }
    fun restoreDisabled(done: (String) -> Unit) = viewModelScope.launch { done(c.undo.restoreDisabled().joinToString("\n")) }
}

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val vm = turboViewModel { DashboardViewModel(it) }
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    TurboScaffold(
        title = "TurboOptimizer",
        onBack = null,
        snackbar = snackbar,
        actions = { IconButton(onClick = { go(Routes.SECURITY) }) { Icon(Icons.Default.Shield, "Seguridad y registro") } },
    ) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ShizukuStatusCard(shizuku, vm::requestShizuku, vm::openShizuku, compact = true) }
            item { MetricsGrid(snap, go) }
            item { HistoryCard(history) }
            item {
                Button(
                    onClick = { go(Routes.OPTIMIZE) },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Optimizar ahora", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            item { FeatureGrid(go) }
            if (settings.disabledByApp.isNotEmpty()) {
                item {
                    SectionCard(title = "Apps desactivadas temporalmente", icon = Icons.Default.Restore, accent = TurboColors.Warn) {
                        Text("${settings.disabledByApp.size} apps desactivadas por TurboOptimizer: ${settings.disabledByApp.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { vm.restoreDisabled { msg -> scope.launch { snackbar.showSnackbar(msg) } } }) { Text("Reactivar todas") }
                    }
                }
            }
            item { DeviceCard(summary, snap, settings.showLocalIp, vm::toggleIp, go) }
        }
    }
}

@Composable
private fun MetricsGrid(s: SystemSnapshot?, go: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                "RAM", s?.let { formatPercent(it.ramUsedFraction * 100) } ?: "…",
                s?.let { "${formatBytes(it.ramAvail)} libres de ${formatBytes(it.ramTotal)}" } ?: "",
                s?.ramUsedFraction, TurboColors.level(s?.ramUsedFraction ?: 0f), Icons.Default.Memory,
                Modifier.weight(1f), onClick = { go(Routes.RAM_BOOST) },
            )
            val t = s?.bestTempC
            MetricTile(
                "TEMPERATURA", formatTemp(t),
                s?.let { "Estado: ${it.thermalStatusLabel}" + (it.thermal?.cpu?.let { " · CPU" } ?: " · batería") } ?: "",
                t?.let { (it / 60f) }, TurboColors.temp(t), Icons.Default.Thermostat, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val cpu = s?.cpuUsagePercent
            MetricTile(
                "CPU", formatPercent(cpu),
                s?.maxCpuFreqMhz?.let { "Máx. actual $it MHz" + (s?.gpuLoadPercent?.let { g -> " · GPU ${g.toInt()}%" } ?: "") }
                    ?: if (cpu == null) "Uso: requiere Shizuku" else "Frecuencia no disponible",
                cpu?.div(100f), TurboColors.level((cpu ?: 0f) / 100f), Icons.Default.DeveloperBoard, Modifier.weight(1f),
            )
            MetricTile(
                "BATERÍA", s?.let { "${it.batteryPercent} %" } ?: "…",
                s?.let { it.batteryStatus + (it.batteryCurrentMa?.let { ma -> " · $ma mA" } ?: "") } ?: "",
                s?.batteryPercent?.div(100f),
                when { (s?.batteryPercent ?: 100) <= 15 -> TurboColors.Bad; (s?.batteryPercent ?: 100) <= 30 -> TurboColors.Warn; else -> TurboColors.Good },
                Icons.Default.BatteryChargingFull, Modifier.weight(1f),
            )
        }
        MetricTile(
            "ALMACENAMIENTO", s?.let { formatPercent(it.storageUsedFraction * 100) + " usado" } ?: "…",
            s?.let { "${formatBytes(it.storageFree)} libres de ${formatBytes(it.storageTotal)}" } ?: "",
            s?.storageUsedFraction, TurboColors.level(s?.storageUsedFraction ?: 0f, 0.8f, 0.92f), Icons.Default.Storage,
            Modifier.fillMaxWidth(), onClick = { go(Routes.CLEANER) },
        )
    }
}

@Composable
private fun HistoryCard(history: List<HistoryPoint>) {
    SectionCard(title = "Últimos 2 minutos", icon = Icons.Default.ShowChart) {
        LineChart(
            series = listOf(
                TurboColors.Teal to history.map { it.ram },
                TurboColors.Violet to history.map { it.cpu },
                TurboColors.Amber to history.map { it.temp },
            ),
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledDot("RAM", TurboColors.Teal)
            LabeledDot("CPU", TurboColors.Violet)
            LabeledDot("Temp (0–80 °C)", TurboColors.Amber)
        }
    }
}

private data class Feature(val title: String, val icon: ImageVector, val route: String, val color: androidx.compose.ui.graphics.Color)

@Composable
private fun FeatureGrid(go: (String) -> Unit) {
    val features = listOf(
        Feature("Game Booster", Icons.Default.SportsEsports, Routes.GAME, TurboColors.Pink),
        Feature("Limpiador", Icons.Default.CleaningServices, Routes.CLEANER, TurboColors.Teal),
        Feature("Aplicaciones", Icons.Default.Apps, Routes.APPS, TurboColors.Sky),
        Feature("Grabador", Icons.Default.Videocam, Routes.RECORDER, TurboColors.Bad),
        Feature("RAM Boost", Icons.Default.Speed, Routes.RAM_BOOST, TurboColors.Violet),
        Feature("Información del dispositivo", Icons.Default.Info, Routes.DEVICE, TurboColors.Amber),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        features.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { f ->
                    SectionCard(Modifier.weight(1f), onClick = { go(f.route) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(f.icon, null, tint = f.color, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceSummary?, s: SystemSnapshot?, showIp: Boolean, onToggleIp: () -> Unit, go: (String) -> Unit) {
    SectionCard(
        title = "Tu dispositivo", icon = Icons.Default.PhoneAndroid,
        trailing = { androidx.compose.material3.TextButton(onClick = { go(Routes.DEVICE) }) { Text("Ver todo") } },
    ) {
        if (d == null) {
            Text("Cargando…")
            return@SectionCard
        }
        InfoRow("Modelo", d.model)
        InfoRow("Fabricante", d.manufacturer)
        InfoRow("Sistema", d.androidVersion)
        InfoRow("Interfaz", d.manufacturerUi)
        InfoRow("SoC", d.soc)
        InfoRow("CPU", "${d.cores} núcleos · ${d.abi}")
        d.gpu?.let { InfoRow("GPU", it) }
        InfoRow("RAM", formatBytes(d.ramTotal))
        InfoRow("Almacenamiento", formatBytes(d.storageTotal))
        InfoRow("Pantalla", "${d.resolution} · ${d.densityDpi} dpi · ${d.refreshRate}")
        s?.network?.let { n ->
            InfoRow("Red", if (n.connected) "${n.type}${if (n.validated) "" else " (sin Internet)"}${if (n.metered) " · medida" else ""}" else n.type)
            if (n.localIp != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoRow("IP local", if (showIp) n.localIp else "•••.•••.•••.•••", Modifier.weight(1f), mono = true)
                    IconButton(onClick = onToggleIp) {
                        Icon(if (showIp) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostrar u ocultar IP local")
                    }
                }
            }
        }
        Text("FPS en juegos: disponible en Game Booster (requiere Shizuku).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
