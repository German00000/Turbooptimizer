package com.turbooptimizer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.core.shizuku.ShizukuStatus
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.OptimizationProfile
import com.turbooptimizer.optimizer.OptimizationPlan
import com.turbooptimizer.optimizer.OptimizationResult
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.PlanList
import com.turbooptimizer.ui.components.ResultSummary
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.ShizukuStatusCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OptimizeUi(
    val loading: Boolean = false,
    val plan: OptimizationPlan? = null,
    val selected: Set<String> = emptySet(),
    val executing: Boolean = false,
    val result: OptimizationResult? = null,
    val message: String? = null,
)

class OptimizeViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val shizuku = c.shizuku.state
    private val _ui = MutableStateFlow(OptimizeUi())
    val ui = _ui.asStateFlow()

    fun selectProfile(p: OptimizationProfile) = viewModelScope.launch {
        c.settings.update { it.copy(profile = p) }
        _ui.update { OptimizeUi() }
    }

    fun setSimulation(v: Boolean) = viewModelScope.launch { c.settings.update { it.copy(simulationMode = v) } }

    fun analyze() {
        _ui.update { it.copy(loading = true, result = null, plan = null) }
        viewModelScope.launch {
            val profile = c.settings.current().profile
            val plan = runCatching { c.optimizer.buildPlan(profile) }.getOrElse {
                _ui.update { s -> s.copy(loading = false, message = "No se pudo analizar: ${it.message}") }
                return@launch
            }
            val ready = c.shizuku.isReady
            _ui.update {
                it.copy(
                    loading = false, plan = plan,
                    selected = plan.actions.filter { a -> a.selectedByDefault && (!a.requiresShizuku || ready) }.map { a -> a.id }.toSet(),
                )
            }
        }
    }

    fun toggle(id: String) = _ui.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }

    fun execute() {
        val plan = _ui.value.plan ?: return
        val selected = _ui.value.selected
        _ui.update { it.copy(executing = true) }
        viewModelScope.launch {
            val result = c.optimizer.execute(plan, selected, c.settings.current().simulationMode)
            _ui.update { it.copy(executing = false, plan = null, result = result) }
        }
    }

    fun undo() {
        val id = _ui.value.result?.logId ?: return
        viewModelScope.launch {
            val entry = c.log.find(id) ?: return@launch
            val msgs = c.undo.undo(entry)
            _ui.update { it.copy(result = it.result?.copy(undoAvailable = false), message = msgs.joinToString("\n")) }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    fun requestShizuku() = c.shizuku.requestPermission()
    fun openShizuku() = c.shizuku.openShizukuApp()
}

@Composable
fun OptimizeScreen(onBack: () -> Unit) {
    val vm = turboViewModel { OptimizeViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var extremeWarning by remember { mutableStateOf(false) }
    var confirmRun by remember { mutableStateOf(false) }
    val ready = shizuku.status == ShizukuStatus.READY

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    TurboScaffold("Optimizar ahora", onBack, snackbar = snackbar) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ready) item { ShizukuStatusCard(shizuku, vm::requestShizuku, vm::openShizuku) }
            item {
                Text("Elige un perfil", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileCard(OptimizationProfile.BALANCED, Icons.Default.Tune, TurboColors.Teal, settings.profile) { vm.selectProfile(it) }
                    ProfileCard(OptimizationProfile.PERFORMANCE, Icons.Default.Speed, TurboColors.Violet, settings.profile) { vm.selectProfile(it) }
                    ProfileCard(OptimizationProfile.EXTREME, Icons.Default.LocalFireDepartment, TurboColors.Bad, settings.profile) { extremeWarning = true }
                }
            }
            item {
                SectionCard(title = "Modo simulación", icon = Icons.Default.Science, accent = TurboColors.Warn,
                    trailing = { Switch(settings.simulationMode, { vm.setSimulation(it) }) }) {
                    Text("Muestra y registra qué se haría sin aplicar ningún cambio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                if (ui.plan == null) {
                    Button(onClick = vm::analyze, enabled = !ui.loading, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                        if (ui.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text("Analizar qué se va a modificar")
                    }
                }
            }
            ui.plan?.let { plan ->
                item {
                    SectionCard(title = "Plan · perfil ${plan.profile.title}", icon = Icons.Default.Bolt) {
                        PlanList(plan, ui.selected, ready, vm::toggle)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::analyze, modifier = Modifier.weight(1f)) { Text("Volver a analizar") }
                        Button(
                            onClick = { confirmRun = true }, enabled = ui.selected.isNotEmpty() && !ui.executing,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (ui.executing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(if (settings.simulationMode) "Simular (${ui.selected.size})" else "Aplicar (${ui.selected.size})")
                        }
                    }
                }
            }
            ui.result?.let { r -> item { ResultSummary(r, onUndo = vm::undo) } }
        }
    }

    if (extremeWarning) {
        ConfirmDialog(
            title = "Activar modo extremo",
            message = "El modo extremo detiene todas las apps de tu lista negra, restringe su ejecución en segundo plano, " +
                "cierra procesos en segundo plano, libera caché y desactiva las animaciones.\n\n" +
                "• No modifica archivos del sistema ni hace nada irreversible.\n" +
                "• Las apps detenidas pueden no mostrar notificaciones hasta que las abras.\n" +
                "• Podrás revisar y desmarcar cada acción antes de aplicarla, y deshacer los cambios reversibles.",
            confirmText = "Entendido, activar",
            dangerous = true,
            onConfirm = { extremeWarning = false; vm.selectProfile(OptimizationProfile.EXTREME) },
            onDismiss = { extremeWarning = false },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = TurboColors.Warn)
                Text("  No promete más FPS: el resultado depende del dispositivo y del juego.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmRun) {
        val plan = ui.plan
        ConfirmDialog(
            title = if (settings.simulationMode) "Confirmar simulación" else "Confirmar optimización",
            message = "Se ${if (settings.simulationMode) "simularán" else "aplicarán"} ${ui.selected.size} acciones:\n" +
                plan?.actions?.filter { it.id in ui.selected }?.joinToString("\n") { "• ${it.title}" }.orEmpty(),
            confirmText = if (settings.simulationMode) "Simular" else "Aplicar",
            dangerous = settings.profile == OptimizationProfile.EXTREME && !settings.simulationMode,
            onConfirm = { confirmRun = false; vm.execute() },
            onDismiss = { confirmRun = false },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: OptimizationProfile,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    current: OptimizationProfile,
    onSelect: (OptimizationProfile) -> Unit,
) {
    val selected = profile == current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { if (!selected) onSelect(profile) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(profile.title, style = MaterialTheme.typography.titleMedium)
                Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = { if (!selected) onSelect(profile) })
        }
    }
}
