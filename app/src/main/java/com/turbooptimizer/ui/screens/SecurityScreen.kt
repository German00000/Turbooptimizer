package com.turbooptimizer.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.apps.AppEntry
import com.turbooptimizer.core.shizuku.CommandPolicy
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.LogEntry
import com.turbooptimizer.ui.components.AppIcon
import com.turbooptimizer.ui.components.AppPickerDialog
import com.turbooptimizer.ui.components.CommandBox
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.EmptyState
import com.turbooptimizer.ui.components.Pill
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.ShizukuStatusCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SecurityViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val log = c.log.entries
    val shizuku = c.shizuku.state
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps = _apps.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch { _apps.value = runCatching { c.apps.loadApps() }.getOrDefault(emptyList()) }
    }

    fun label(pkg: String) = _apps.value.firstOrNull { it.packageName == pkg }?.label ?: pkg
    fun setWhitelist(s: Set<String>) = viewModelScope.launch { c.settings.update { it.copy(whitelist = s, blacklist = it.blacklist - s) } }
    fun setBlacklist(s: Set<String>) = viewModelScope.launch {
        c.settings.update { it.copy(blacklist = s.filterNot(CommandPolicy::isCriticalPackage).toSet(), whitelist = it.whitelist - s) }
    }
    fun setSimulation(v: Boolean) = viewModelScope.launch { c.settings.update { it.copy(simulationMode = v) } }
    fun setSystemTheme(v: Boolean) = viewModelScope.launch { c.settings.update { it.copy(followSystemTheme = v) } }
    fun undo(e: LogEntry) = viewModelScope.launch { _message.value = c.undo.undo(e).joinToString("\n") }
    fun clearLog() = viewModelScope.launch { c.log.clear() }
    fun restoreDisabled() = viewModelScope.launch { _message.value = c.undo.restoreDisabled().joinToString("\n") }
    fun requestShizuku() = c.shizuku.requestPermission()
    fun openShizuku() = c.shizuku.openShizukuApp()
    fun refreshShizuku() = c.shizuku.refresh()
    fun consume() { _message.value = null }
}

@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val vm = turboViewModel { SecurityViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it.take(300)); vm.consume() } }

    TurboScaffold("Seguridad y registro", onBack, snackbar = snackbar) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Listas", "Registro", "Permisos", "Ajustes").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                when (tab) {
                    0 -> listsTab(settings, apps, vm)
                    1 -> logTab(log, vm)
                    2 -> item { PermissionsTab(vm) }
                    else -> settingsTab(settings, shizuku, vm)
                }
            }
        }
    }
}

private fun LazyListScope.listsTab(settings: AppSettings, apps: List<AppEntry>, vm: SecurityViewModel) {
    item { ListCard("Lista blanca (protegidas)", "Nunca se detienen ni se restringen.", Icons.Default.Lock, TurboColors.Good, settings.whitelist, apps, vm::label, vm::setWhitelist, null) }
    item {
        ListCard(
            "Lista negra (pueden detenerse)", "Solo estas apps se detienen en los perfiles, RAM Boost y Game Booster.",
            Icons.Default.Block, TurboColors.Violet, settings.blacklist, apps, vm::label, vm::setBlacklist,
        ) { pkg -> if (CommandPolicy.isCriticalPackage(pkg)) "Componente crítico: no se puede añadir" else null }
    }
    item {
        SectionCard(title = "Protección automática", icon = Icons.Default.CheckCircle, accent = TurboColors.Good) {
            Text(
                "Además de tu lista blanca, siempre se protegen: componentes críticos de Android y Google Play Services, el launcher, el teclado, " +
                    "las apps de teléfono y SMS predeterminadas, servicios de accesibilidad activos, el fondo animado, Shizuku, TurboOptimizer, " +
                    "procesos persistentes y el juego en curso.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ListCard(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color,
    current: Set<String>, apps: List<AppEntry>, label: (String) -> String, onSave: (Set<String>) -> Unit,
    disabledReason: ((String) -> String?)?,
) {
    var picking by remember { mutableStateOf(false) }
    SectionCard(title = "$title · ${current.size}", icon = icon, accent = color, trailing = { TextButton(onClick = { picking = true }) { Text("Editar") } }) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current.isEmpty()) Text("Vacía", style = MaterialTheme.typography.bodySmall)
        current.sortedBy { label(it).lowercase() }.forEach { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(label(pkg), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onSave(current - pkg) }) { Icon(Icons.Default.Close, "Quitar") }
            }
        }
    }
    if (picking) {
        AppPickerDialog(
            title = title, apps = apps, initial = current,
            disabledReason = disabledReason ?: { null },
            onConfirm = { onSave(it); picking = false }, onDismiss = { picking = false },
        )
    }
}

private fun LazyListScope.logTab(log: List<LogEntry>, vm: SecurityViewModel) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${log.size} operaciones registradas (solo en este dispositivo)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearLog() }) { Text("Limpiar") }
        }
    }
    if (log.isEmpty()) item { EmptyState("Aún no hay operaciones") }
    items(log, key = { it.id }) { e -> LogCard(e, vm) }
}

@Composable
private fun LogCard(e: LogEntry, vm: SecurityViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    SectionCard(
        title = e.title, icon = if (e.success) Icons.Default.History else Icons.Default.Warning,
        accent = if (e.success) MaterialTheme.colorScheme.primary else TurboColors.Warn,
        onClick = { expanded = !expanded },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDate(e.timestamp), style = MaterialTheme.typography.labelSmall)
            Pill(e.category.label, TurboColors.Sky)
            if (e.simulated) Pill("Simulado", TurboColors.Warn)
            if (e.undone) Pill("Deshecho", TurboColors.Good)
        }
        if (expanded) e.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (e.canUndo) TextButton(onClick = { confirmUndo = true }) { Text("Deshacer (${e.undo.size} cambios)") }
    }
    if (confirmUndo) {
        ConfirmDialog(
            title = "Deshacer cambios",
            message = "Se restaurará:\n" + e.undo.joinToString("\n") { "• ${it.description()}" },
            confirmText = "Deshacer",
            onConfirm = { confirmUndo = false; vm.undo(e) },
            onDismiss = { confirmUndo = false },
        ) { CommandBox(e.undo.joinToString("\n") { it.command().joinToString(" ") }) }
    }
}

@Composable
private fun PermissionsTab(vm: SecurityViewModel) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; vm.refreshShizuku() }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    androidx.compose.runtime.key(tick) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionRow("Notificaciones", "Para Game Booster y grabación en primer plano.", Permissions.hasNotifications(context)) {
            runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        PermissionRow("Acceso de uso", "Último uso, tamaño de apps y detección automática de juegos.", Permissions.hasUsageAccess(context)) {
            Permissions.open(context, Permissions.usageAccessIntent(context))
        }
        PermissionRow("Mostrar sobre otras apps", "Overlay de estadísticas durante el juego.", Permissions.canDrawOverlays(context)) {
            Permissions.open(context, Permissions.overlayIntent(context))
        }
        PermissionRow("Acceso a todos los archivos", "Limpiador completo: temporales, APK, carpetas vacías y ocultos.", Permissions.hasAllFilesAccess()) {
            Permissions.open(context, Permissions.allFilesIntent(context))
        }
        PermissionRow("Fotos y vídeos", "Detección de duplicados.", Permissions.hasMediaAccess(context)) {
            runtime.launch(Permissions.mediaPermissions())
        }
        PermissionRow("Micrófono", "Audio interno y micrófono en el grabador.", Permissions.hasMicrophone(context)) {
            runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        Text("Todos los permisos son opcionales: sin ellos la función correspondiente se desactiva o funciona en modo limitado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    SectionCard(
        title = title,
        icon = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
        accent = if (granted) TurboColors.Good else TurboColors.Warn,
        trailing = { if (!granted) TextButton(onClick = onGrant) { Text("Conceder") } else Pill("Concedido", TurboColors.Good) },
    ) { Text(desc, style = MaterialTheme.typography.bodySmall) }
}

private fun LazyListScope.settingsTab(settings: AppSettings, shizuku: com.turbooptimizer.core.shizuku.ShizukuState, vm: SecurityViewModel) {
    item { ShizukuStatusCard(shizuku, vm::requestShizuku, vm::openShizuku) }
    item {
        SectionCard(title = "Modo simulación", icon = Icons.Default.Settings, trailing = { Switch(settings.simulationMode, { vm.setSimulation(it) }) }) {
            Text("Todas las operaciones (optimizar, RAM Boost, limpiar, detener apps) se muestran y registran sin aplicarse.", style = MaterialTheme.typography.bodySmall)
        }
    }
    item {
        SectionCard(title = "Seguir tema del sistema", icon = Icons.Default.Settings, trailing = { Switch(settings.followSystemTheme, { vm.setSystemTheme(it) }) }) {
            Text("Por defecto se usa el modo oscuro.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (settings.disabledByApp.isNotEmpty()) {
        item {
            SectionCard(title = "Apps desactivadas temporalmente", icon = Icons.Default.Warning, accent = TurboColors.Warn) {
                Text(settings.disabledByApp.joinToString(), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.restoreDisabled() }) { Text("Reactivar todas") }
            }
        }
    }
    item {
        SectionCard(title = "Comandos que puede usar TurboOptimizer con Shizuku", icon = Icons.Default.Code) {
            Text(
                "Lista cerrada. Se ejecutan sin intérprete de shell (sin tuberías ni redirecciones) y se validan dos veces. Nunca se modifican particiones ni archivos del sistema.",
                style = MaterialTheme.typography.bodySmall,
            )
            CommandBox(
                """
                am force-stop <app>          Detener app elegida
                am kill <app>                Cerrar sus procesos en 2º plano
                am kill-all                  Cerrar procesos en 2º plano (modo extremo)
                pm disable-user --user N <app>  Desactivar temporalmente (reversible)
                pm enable --user N <app>     Reactivar
                pm trim-caches 999G          Liberar caché (gestiona Android)
                cmd appops get|set <app> RUN_ANY_IN_BACKGROUND …  Restringir 2º plano
                cmd game mode performance|standard <app>          Modo de juego
                settings get|put global *_animation_scale …       Animaciones
                dumpsys meminfo -c           RAM por app (lectura)
                dumpsys thermalservice       Temperaturas (lectura)
                cat /proc/stat, cpufreq, gpu, thermal (lectura)
                service call SurfaceFlinger 1013  Contador de fotogramas (FPS, lectura)
                """.trimIndent(),
            )
        }
    }
    item {
        Column {
            Text("TurboOptimizer no usa root, exploits ni Internet. No hace overclock ni modifica particiones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
