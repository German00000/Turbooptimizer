package com.turbooptimizer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.game.GameSessionState
import com.turbooptimizer.optimizer.RamBoostResult
import com.turbooptimizer.optimizer.RamCandidate
import com.turbooptimizer.ui.components.AppIcon
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.EmptyState
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RamBoostViewModel(private val c: AppContainer) : ViewModel() {
    val snapshot = c.snapshots
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val last = c.ramBoost.last
    val history = c.log.entries.map { list -> list.filter { it.category == LogCategory.RAM_BOOST }.take(20) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _candidates = MutableStateFlow<List<RamCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _candidates.value = c.ramBoost.candidates(GameSessionState.session.value?.packageName)
    }

    fun run() = viewModelScope.launch {
        _running.value = true
        c.ramBoost.run(GameSessionState.session.value?.packageName, c.settings.current().simulationMode, "manual")
        _running.value = false
        refresh()
    }
}

@Composable
fun RamBoostScreen(onBack: () -> Unit) {
    val vm = turboViewModel { RamBoostViewModel(it) }
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val last by vm.last.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    TurboScaffold("RAM Boost", onBack) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Cómo gestiona Android la RAM", icon = Icons.Default.Info, accent = TurboColors.Sky) {
                    Text(
                        "Android administra la memoria automáticamente: mantiene apps en caché para abrirlas más rápido y las cierra solo cuando necesita espacio. " +
                            "La RAM «libre» no es más rápida que la RAM en caché, y limpiarla constantemente obliga a recargar apps, gasta batería y puede empeorar el rendimiento.\n\n" +
                            "Por eso RAM Boost solo detiene las apps que TÚ marcaste en la lista negra, nunca el juego actual ni servicios del sistema, y se ejecuta como mucho una vez por intervalo (mínimo 5 minutos).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                SectionCard(title = "Memoria ahora", icon = Icons.Default.Memory) {
                    snap?.let { s ->
                        LinearProgressIndicator(progress = { s.ramUsedFraction }, modifier = Modifier.fillMaxWidth().height(8.dp), color = TurboColors.level(s.ramUsedFraction))
                        InfoRow("Disponible", "${formatBytes(s.ramAvail)} (${s.ramAvailPercent.toInt()} %)")
                        InfoRow("Total", formatBytes(s.ramTotal))
                        InfoRow("Memoria baja según Android", if (s.lowMemory) "Sí" else "No")
                    } ?: Text("Cargando…")
                }
            }
            last?.let { r -> item { BoostResultCard(r) } }
            item {
                SectionCard(title = "Apps que se detendrían (${candidates.size})", icon = Icons.Default.Speed, accent = TurboColors.Violet) {
                    if (candidates.isEmpty()) {
                        Text(
                            if (settings.blacklist.isEmpty()) "Tu lista negra está vacía. Añade apps desde Aplicaciones → «Permitir detener»."
                            else "Ninguna app de tu lista negra está consumiendo memoria ahora mismo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    candidates.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(c.packageName, 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.label, Modifier.weight(1f))
                            Text(c.ramKb?.let { formatBytes(it * 1024) } ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { confirm = true }, enabled = !running && candidates.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    if (running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text(if (settings.simulationMode) "Simular RAM Boost" else "Ejecutar RAM Boost ahora")
                }
            }
            item { Text("Historial", style = MaterialTheme.typography.titleMedium) }
            if (history.isEmpty()) item { EmptyState("Aún no se ha ejecutado RAM Boost") }
            items(history, key = { it.id }) { e ->
                SectionCard(title = e.title + if (e.simulated) " · simulado" else "", icon = Icons.Default.History) {
                    Text(formatDate(e.timestamp), style = MaterialTheme.typography.labelSmall)
                    e.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "RAM Boost",
            message = "Se detendrán ${candidates.size} apps de tu lista negra:\n" + candidates.joinToString("\n") { "• ${it.label}" },
            confirmText = if (settings.simulationMode) "Simular" else "Ejecutar",
            onConfirm = { confirm = false; vm.run() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun BoostResultCard(r: RamBoostResult) {
    SectionCard(title = "Último RAM Boost" + if (r.simulated) " (simulado)" else "", icon = Icons.Default.Speed, accent = TurboColors.Good) {
        InfoRow("Antes", formatBytes(r.before))
        InfoRow("Después", formatBytes(r.after))
        InfoRow("Liberado", formatBytes(r.freed))
        InfoRow("Detenidas", r.stopped.joinToString().ifBlank { "Ninguna" })
        InfoRow("Cuándo", formatDate(r.timestamp))
    }
}
