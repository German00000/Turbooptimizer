package com.turbooptimizer.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.apps.AppEntry
import com.turbooptimizer.core.shizuku.ShizukuStatus
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.OptimizationProfile
import com.turbooptimizer.game.GameSessionState
import com.turbooptimizer.optimizer.OptimizationPlan
import com.turbooptimizer.ui.components.AppIcon
import com.turbooptimizer.ui.components.AppPickerDialog
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.PlanPreviewDialog
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.Routes
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatDuration
import com.turbooptimizer.util.formatPercent
import com.turbooptimizer.util.formatTemp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class GameViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val shizuku = c.shizuku.state
    val snapshot = c.snapshots
    val session = GameSessionState.session
    val fps = GameSessionState.fps
    val fpsStatus = GameSessionState.fpsStatus
    val overlayVisible = GameSessionState.overlayVisible
    val watching = GameSessionState.watching
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps = _apps.asStateFlow()
    private val _plan = MutableStateFlow<OptimizationPlan?>(null)
    val plan = _plan.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val list = runCatching { c.apps.loadApps() }.getOrDefault(emptyList())
            _apps.value = list
            // Añade automáticamente los juegos detectados la primera vez.
            val detected = list.filter { it.isGame && it.launchable }.map { it.packageName }
            c.settings.update { s ->
                if (s.games.isEmpty() && detected.isNotEmpty()) s.copy(games = detected.toSet(), selectedGame = s.selectedGame ?: detected.first()) else s
            }
        }
    }

    fun setGames(pkgs: Set<String>) = viewModelScope.launch {
        c.settings.update { it.copy(games = pkgs, selectedGame = it.selectedGame?.takeIf { g -> g in pkgs } ?: pkgs.firstOrNull()) }
    }

    fun selectGame(pkg: String) = viewModelScope.launch { c.settings.update { it.copy(selectedGame = pkg, games = it.games + pkg) } }
    fun setProfile(p: OptimizationProfile) = viewModelScope.launch { c.settings.update { it.copy(gameProfile = p) } }
    fun setOverlay(v: Boolean) = viewModelScope.launch { c.settings.update { it.copy(overlayEnabled = v) }; GameSessionState.overlayVisible.value = v }
    fun toggleOverlayVisible() { GameSessionState.overlayVisible.value = !GameSessionState.overlayVisible.value }
    fun setRamBoost(v: Boolean) = viewModelScope.launch { c.settings.update { it.copy(ramBoostEnabled = v) } }
    fun setInterval(min: Int) = viewModelScope.launch { c.settings.update { it.copy(ramBoostIntervalMin = min) } }

    fun setAutoDetect(v: Boolean) = viewModelScope.launch {
        c.settings.update { it.copy(autoDetectGames = v) }
        c.gameBooster.setWatching(v)
    }

    fun prepare() {
        val game = settings.value.selectedGame ?: run { _message.value = "Selecciona un juego"; return }
        _busy.value = true
        viewModelScope.launch {
            val p = c.gameBooster.prepare(game, settings.value.gameProfile)
            val ready = c.shizuku.isReady
            _selected.value = p.actions.filter { it.selectedByDefault && (!it.requiresShizuku || ready) }.map { it.id }.toSet()
            _plan.value = p
            _busy.value = false
        }
    }

    fun toggle(id: String) { _selected.value = _selected.value.let { if (id in it) it - id else it + id } }
    fun dismissPlan() { _plan.value = null }

    fun start() {
        val p = _plan.value ?: return
        val game = p.focusPackage ?: settings.value.selectedGame ?: return
        _plan.value = null
        _busy.value = true
        viewModelScope.launch {
            val r = c.gameBooster.startSession(game, p, _selected.value, settings.value.simulationMode)
            _busy.value = false
            _message.value = "Sesión iniciada: ${r.outcomes.count { it.success }}/${r.outcomes.size} acciones aplicadas"
        }
    }

    fun end() = viewModelScope.launch {
        val msgs = c.gameBooster.endSession()
        _message.value = if (msgs.isEmpty()) "Sesión finalizada" else "Sesión finalizada. Ajustes restaurados."
    }

    fun consume() { _message.value = null }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GameBoosterScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val vm = turboViewModel { GameViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val fps by vm.fps.collectAsStateWithLifecycle()
    val fpsStatus by vm.fpsStatus.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val overlayVisible by vm.overlayVisible.collectAsStateWithLifecycle()
    val watching by vm.watching.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf(false) }
    var extremeWarn by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consume() } }
    LaunchedEffect(session) { while (session != null) { now = System.currentTimeMillis(); delay(1_000) } }
    LaunchedEffect(Unit) { if (!Permissions.hasNotifications(context)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val ready = shizuku.status == ShizukuStatus.READY
    val gameEntries = apps.filter { it.packageName in settings.games }

    TurboScaffold("Game Booster", onBack, snackbar = snackbar) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            session?.let { s ->
                item {
                    SectionCard(title = "Sesión activa: ${s.label}", icon = Icons.Default.SportsEsports, accent = TurboColors.Pink) {
                        InfoRow("Tiempo de partida", formatDuration(now - s.startedAt))
                        InfoRow("FPS", fps?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "N/D")
                        InfoRow("Estado FPS", fpsStatus)
                        InfoRow("Pantalla", snap?.screenHz?.let { hz ->
                            String.format(Locale.getDefault(), "%.0f Hz", hz) + (snap?.maxScreenHz?.let { m -> " (máx. ${String.format(Locale.getDefault(), "%.0f", m)} Hz)" } ?: "")
                        } ?: "N/D")
                        InfoRow("Temperatura", snap?.bestTempC?.let { formatTemp(it) + (snap?.tempSource?.let { s -> " · $s" } ?: "") } ?: "N/D")
                        InfoRow("RAM", snap?.let { "${formatPercent(it.ramUsedFraction * 100)} · ${formatBytes(it.ramAvail)} libres" } ?: "…")
                        InfoRow("CPU", formatPercent(snap?.cpuUsagePercent))
                        InfoRow("Batería", snap?.let { "${it.batteryPercent} % · ${it.batteryStatus}" } ?: "…")
                        InfoRow("Perfil", s.profile.title + if (s.auto) " (automático)" else "")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (settings.overlayEnabled) OutlinedButton(onClick = vm::toggleOverlayVisible) { Text(if (overlayVisible) "Ocultar overlay" else "Mostrar overlay") }
                            Button(onClick = { vm.end() }) { Text("Finalizar y restaurar") }
                        }
                    }
                }
            }
            item {
                SectionCard(
                    title = "Juego seleccionado", icon = Icons.Default.SportsEsports, accent = TurboColors.Pink,
                    trailing = { TextButton(onClick = { picker = true }) { Text("Elegir juegos") } },
                ) {
                    if (gameEntries.isEmpty()) Text("No hay juegos en tu lista. Pulsa «Elegir juegos».", style = MaterialTheme.typography.bodySmall)
                    gameEntries.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            AppIcon(g.packageName, 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(g.label, Modifier.weight(1f))
                            androidx.compose.material3.RadioButton(settings.selectedGame == g.packageName, { vm.selectGame(g.packageName) })
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Perfil al iniciar el juego", icon = Icons.Default.Speed) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptimizationProfile.entries.forEach { p ->
                            FilterChip(settings.gameProfile == p, {
                                if (p == OptimizationProfile.EXTREME && settings.gameProfile != p) extremeWarn = true else vm.setProfile(p)
                            }, { Text(p.title) })
                        }
                    }
                    Text(settings.gameProfile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!ready) Text("Sin Shizuku solo se aplica el modo limitado.", style = MaterialTheme.typography.bodySmall, color = TurboColors.Warn)
                }
            }
            item {
                SectionCard(title = "Overlay de estadísticas", icon = Icons.Default.Layers, trailing = { Switch(settings.overlayEnabled, { vm.setOverlay(it) }) }) {
                    Text("Panel flotante con FPS, temperatura, RAM, CPU, batería y tiempo. Arrástralo para moverlo, tócalo para compactarlo y pulsa «×» para ocultarlo.", style = MaterialTheme.typography.bodySmall)
                    if (settings.overlayEnabled && !Permissions.canDrawOverlays(context)) {
                        TextButton(onClick = { Permissions.open(context, Permissions.overlayIntent(context)) }) { Text("Permitir mostrar sobre otras apps") }
                    }
                }
            }
            item {
                SectionCard(title = "RAM Boost durante el juego", icon = Icons.Default.Timer, trailing = { Switch(settings.ramBoostEnabled, { vm.setRamBoost(it) }) }) {
                    Text("Detiene solo apps de tu lista negra cuando la RAM libre baja del ${settings.ramBoostThresholdPercent} %, como máximo una vez por intervalo. Nunca toca el juego.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 30, 60).forEach { m -> FilterChip(settings.ramBoostIntervalMin == m, { vm.setInterval(m) }, { Text("$m min") }) }
                    }
                    TextButton(onClick = { go(Routes.RAM_BOOST) }) { Text("Abrir RAM Boost") }
                }
            }
            item {
                SectionCard(title = "Detección automática", icon = Icons.Default.PlayArrow, trailing = { Switch(settings.autoDetectGames && watching, { vm.setAutoDetect(it) }) }) {
                    Text("Al abrir un juego de tu lista se aplican automáticamente las acciones marcadas por defecto del perfil (nunca la desactivación de apps). Al salir del juego durante 1 minuto se restauran los ajustes.", style = MaterialTheme.typography.bodySmall)
                    if (!Permissions.hasUsageAccess(context)) {
                        TextButton(onClick = { Permissions.open(context, Permissions.usageAccessIntent(context)) }) { Text("Conceder acceso de uso (necesario)") }
                    }
                }
            }
            item {
                Button(
                    onClick = vm::prepare, enabled = settings.selectedGame != null && !busy && session == null,
                    modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (session != null) "Sesión en curso" else "Iniciar juego con boost")
                    }
                }
            }
            item {
                Text(
                    "Game Booster no aumenta los FPS por encima de lo que permite tu hardware: libera recursos y evita interrupciones. FPS medidos con SurfaceFlinger mediante Shizuku cuando el sistema lo permite.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (picker) {
        AppPickerDialog(
            title = "Juegos para Game Booster",
            apps = apps.filter { it.launchable },
            initial = settings.games,
            gamesFirst = true,
            onConfirm = { vm.setGames(it); picker = false },
            onDismiss = { picker = false },
        )
    }

    if (extremeWarn) {
        ConfirmDialog(
            title = "Perfil extremo para juegos",
            message = "Detendrá y restringirá las apps de tu lista negra, cerrará procesos en segundo plano y desactivará animaciones durante la partida. Todo lo reversible se restaurará al finalizar la sesión.",
            confirmText = "Usar perfil extremo",
            dangerous = true,
            onConfirm = { vm.setProfile(OptimizationProfile.EXTREME); extremeWarn = false },
            onDismiss = { extremeWarn = false },
        )
    }

    plan?.let { p ->
        PlanPreviewDialog(
            title = "Antes de jugar",
            plan = p, selected = selected, shizukuReady = ready, simulate = settings.simulationMode,
            onToggle = vm::toggle, onConfirm = vm::start, onDismiss = vm::dismissPlan,
        )
    }
}
