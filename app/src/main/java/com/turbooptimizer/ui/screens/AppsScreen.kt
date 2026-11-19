package com.turbooptimizer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turbooptimizer.AppContainer
import com.turbooptimizer.apps.AppEntry
import com.turbooptimizer.core.shizuku.CommandPolicy
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.data.UndoAction
import com.turbooptimizer.ui.components.AppIcon
import com.turbooptimizer.ui.components.CommandBox
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.EmptyState
import com.turbooptimizer.ui.components.Pill
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.components.list
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatRelative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppFilter(val label: String) { USER("Usuario"), SYSTEM("Sistema"), ALL("Todas") }
enum class AppSort(val label: String) { NAME("Nombre"), SIZE("Tamaño"), RAM("RAM"), LAST_USED("Último uso") }

/** Acción múltiple pendiente de confirmación. */
enum class BulkAction(val title: String) {
    STOP("Detener"), RESTRICT("Restringir segundo plano"), BLACKLIST("Añadir a lista negra"), WHITELIST("Añadir a lista blanca")
}

class AppsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val shizuku = c.shizuku.state
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps = _apps.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { reload() }

    fun reload() {
        _loading.value = true
        viewModelScope.launch {
            c.apps.invalidateRunning()
            _apps.value = runCatching { c.apps.loadApps() }.getOrDefault(emptyList())
            _loading.value = false
        }
    }

    fun protection(pkg: String): String? = c.safety.protectionReason(pkg, settings.value.whitelist)
    fun canUninstall(app: AppEntry) = c.safety.canUninstall(app.packageName, app.isSystem)

    fun open(pkg: String) {
        if (!c.apps.launch(pkg)) _message.value = "Esta app no tiene pantalla de inicio"
    }

    fun uninstall(context: android.content.Context, pkg: String) {
        context.startActivity(c.apps.uninstallIntent(pkg))
    }

    fun stop(pkgs: Collection<String>) = viewModelScope.launch {
        val s = c.settings.current()
        val results = mutableListOf<String>()
        for (pkg in pkgs) {
            val reason = c.safety.protectionReason(pkg, s.whitelist)
            if (reason != null) { results += "${c.apps.label(pkg)}: omitida ($reason)"; continue }
            if (s.simulationMode) { results += "${c.apps.label(pkg)}: [simulado]"; continue }
            val ok = if (c.shizuku.isReady) c.shizuku.exec(ShellCommands.forceStop(pkg)).isSuccess
            else runCatching {
                c.app.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(pkg)
            }.isSuccess
            results += "${c.apps.label(pkg)}: ${if (ok) "detenida" else "error"}"
        }
        c.log.add(LogCategory.APPS, "Detener ${pkgs.size} apps", results, simulated = s.simulationMode)
        _message.value = results.joinToString("\n").take(400)
        reload()
    }

    fun restrict(pkgs: Collection<String>) = viewModelScope.launch {
        val s = c.settings.current()
        if (!c.shizuku.isReady) { _message.value = "Requiere Shizuku"; return@launch }
        val undo = mutableListOf<UndoAction>()
        val results = mutableListOf<String>()
        for (pkg in pkgs) {
            if (c.safety.protectionReason(pkg, s.whitelist) != null) { results += "${c.apps.label(pkg)}: protegida"; continue }
            if (s.simulationMode) { results += "${c.apps.label(pkg)}: [simulado]"; continue }
            val prev = Regex("RUN_ANY_IN_BACKGROUND:\\s*(\\w+)").find(c.shizuku.exec(ShellCommands.getBackground(pkg)).stdout)
                ?.groupValues?.get(1)?.takeIf { it in setOf("allow", "ignore", "default") } ?: "default"
            val r = c.shizuku.exec(ShellCommands.setBackground(pkg, "ignore"))
            if (r.isSuccess) undo += UndoAction.SetBackgroundMode(pkg, prev)
            results += "${c.apps.label(pkg)}: ${if (r.isSuccess) "restringida" else r.message}"
        }
        c.log.add(LogCategory.APPS, "Restringir segundo plano de ${pkgs.size} apps", results, simulated = s.simulationMode, undo = undo)
        _message.value = results.joinToString("\n").take(400) + if (undo.isNotEmpty()) "\nPuedes deshacerlo desde el Registro." else ""
    }

    fun addToList(pkgs: Collection<String>, white: Boolean) = viewModelScope.launch {
        c.settings.update {
            if (white) it.copy(whitelist = it.whitelist + pkgs, blacklist = it.blacklist - pkgs.toSet())
            else it.copy(blacklist = it.blacklist + pkgs.filterNot { p -> CommandPolicy.isCriticalPackage(p) }, whitelist = it.whitelist - pkgs.toSet())
        }
        _message.value = "${pkgs.size} apps añadidas a la lista ${if (white) "blanca" else "negra"}"
    }

    fun removeFromLists(pkg: String) = viewModelScope.launch {
        c.settings.update { it.copy(whitelist = it.whitelist - pkg, blacklist = it.blacklist - pkg) }
    }

    fun consume() { _message.value = null }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val vm = turboViewModel { AppsViewModel(it) }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    var sort by rememberSaveable { mutableStateOf(AppSort.NAME) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var pendingBulk by remember { mutableStateOf<Pair<BulkAction, Set<String>>?>(null) }
    var pendingUninstall by remember { mutableStateOf<AppEntry?>(null) }
    val usageAccess = remember { Permissions.hasUsageAccess(context) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consume() } }

    val visible = remember(apps, query, filter, sort) {
        apps.asSequence()
            .filter {
                when (filter) {
                    AppFilter.USER -> !it.isSystem || it.isUpdatedSystem
                    AppFilter.SYSTEM -> it.isSystem
                    AppFilter.ALL -> true
                }
            }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .let { seq ->
                when (sort) {
                    AppSort.NAME -> seq.sortedBy { it.label.lowercase() }
                    AppSort.SIZE -> seq.sortedByDescending { it.sizeBytes ?: -1 }
                    AppSort.RAM -> seq.sortedByDescending { it.ramKb ?: -1 }
                    AppSort.LAST_USED -> seq.sortedByDescending { it.lastUsed ?: 0 }
                }
            }.toList()
    }

    TurboScaffold(
        "Aplicaciones", onBack, snackbar = snackbar,
        actions = { IconButton(onClick = vm::reload) { Icon(Icons.Default.Refresh, "Actualizar") } },
        bottomBar = {
            if (selection.isNotEmpty()) {
                BottomAppBar {
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, "Cancelar selección") }
                    Text("${selection.size}", modifier = Modifier.padding(end = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BulkAction.entries.forEach { a ->
                            OutlinedButton(onClick = { pendingBulk = a to selection }) { Text(a.title) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.list(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(
                    query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Buscar apps") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppSort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) }
                    }
                }
            }
            if (!usageAccess || !shizuku.let { it.status == com.turbooptimizer.core.shizuku.ShizukuStatus.READY }) {
                item {
                    SectionCard {
                        if (!usageAccess) {
                            Text("Concede «Acceso de uso» para ver tamaño y último uso.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { Permissions.open(context, Permissions.usageAccessIntent(context)) }) { Text("Conceder acceso de uso") }
                        }
                        if (shizuku.status != com.turbooptimizer.core.shizuku.ShizukuStatus.READY) {
                            Text("La RAM por app y la detención completa requieren Shizuku. Sin él se usa la API limitada de Android.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text(
                    "Mantén pulsada una app para seleccionar varias. ${visible.size} apps.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading && apps.isEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            if (!loading && visible.isEmpty()) item { EmptyState("No hay apps que coincidan") }
            items(visible, key = { it.packageName }) { app ->
                val isSel = app.packageName in selection
                val inWhite = app.packageName in settings.whitelist
                val inBlack = app.packageName in settings.blacklist
                SectionCard(
                    Modifier.combinedClickable(
                        onClick = {
                            if (selection.isNotEmpty()) selection = if (isSel) selection - app.packageName else selection + app.packageName
                            else expanded = if (expanded == app.packageName) null else app.packageName
                        },
                        onLongClick = { selection = selection + app.packageName },
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selection.isNotEmpty()) Checkbox(isSel, { selection = if (isSel) selection - app.packageName else selection + app.packageName })
                        AppIcon(app.packageName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    app.sizeBytes?.let { "Tamaño ${formatBytes(it)}" },
                                    app.ramKb?.let { "RAM ${formatBytes(it * 1024)}" },
                                    app.lastUsed?.let { "Usada ${formatRelative(it)}" },
                                ).joinToString(" · ").ifBlank { "Sin datos adicionales" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (app.isSystem) (if (app.isUpdatedSystem) "Sistema (actualizada)" else "Sistema") else "Usuario", if (app.isSystem) TurboColors.Warn else TurboColors.Sky)
                        if (!app.enabled) Pill("Desactivada", TurboColors.Bad)
                        if (app.isGame) Pill("Juego", TurboColors.Pink)
                        if (inWhite) Pill("Lista blanca", TurboColors.Good)
                        if (inBlack) Pill("Lista negra", TurboColors.Violet)
                        vm.protection(app.packageName)?.takeIf { !inWhite }?.let { Pill("Protegida", TurboColors.Good) }
                    }
                    if (expanded == app.packageName) {
                        HorizontalDivider()
                        val protection = vm.protection(app.packageName)
                        protection?.let { Text("Protegida: $it", style = MaterialTheme.typography.bodySmall, color = TurboColors.Good) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (app.launchable) OutlinedButton(onClick = { vm.open(app.packageName) }) { Text("Abrir") }
                            OutlinedButton(onClick = { pendingBulk = BulkAction.STOP to setOf(app.packageName) }, enabled = protection == null) { Text("Detener") }
                            if (vm.canUninstall(app)) OutlinedButton(onClick = { pendingUninstall = app }) { Text("Desinstalar") }
                            OutlinedButton(onClick = { Permissions.open(context, Permissions.appDetailsIntent(app.packageName)) }) { Text("Info") }
                            if (!inWhite) OutlinedButton(onClick = { vm.addToList(listOf(app.packageName), white = true) }) { Text("Proteger") }
                            if (!inBlack && !CommandPolicy.isCriticalPackage(app.packageName)) {
                                OutlinedButton(onClick = { vm.addToList(listOf(app.packageName), white = false) }) { Text("Permitir detener") }
                            }
                            if (inWhite || inBlack) OutlinedButton(onClick = { vm.removeFromLists(app.packageName) }) { Text("Quitar de listas") }
                        }
                    }
                }
            }
        }
    }

    pendingBulk?.let { (action, pkgs) ->
        val label = { p: String -> apps.firstOrNull { it.packageName == p }?.label ?: p }
        val cmdPreview = when (action) {
            BulkAction.STOP -> if (shizuku.status == com.turbooptimizer.core.shizuku.ShizukuStatus.READY) pkgs.joinToString("\n") { ShellCommands.display(ShellCommands.forceStop(it)) }
            else pkgs.joinToString("\n") { "ActivityManager.killBackgroundProcesses(\"$it\")" }
            BulkAction.RESTRICT -> pkgs.joinToString("\n") { ShellCommands.display(ShellCommands.setBackground(it, "ignore")) }
            else -> null
        }
        ConfirmDialog(
            title = "${action.title}: ${pkgs.size} apps",
            message = pkgs.joinToString("\n") { "• ${label(it)}" } + when (action) {
                BulkAction.STOP -> "\n\nLas apps protegidas (sistema crítico, lista blanca, launcher, teclado…) se omitirán automáticamente."
                BulkAction.RESTRICT -> "\n\nNo podrán ejecutarse en segundo plano. Reversible desde el Registro."
                else -> ""
            },
            confirmText = action.title,
            onConfirm = {
                when (action) {
                    BulkAction.STOP -> vm.stop(pkgs)
                    BulkAction.RESTRICT -> vm.restrict(pkgs)
                    BulkAction.BLACKLIST -> vm.addToList(pkgs, white = false)
                    BulkAction.WHITELIST -> vm.addToList(pkgs, white = true)
                }
                pendingBulk = null
                selection = emptySet()
            },
            onDismiss = { pendingBulk = null },
        ) { cmdPreview?.let { CommandBox(it) } }
    }

    pendingUninstall?.let { app ->
        ConfirmDialog(
            title = "Desinstalar ${app.label}",
            message = "Se abrirá el diálogo oficial de Android para desinstalar la app. Se borrarán sus datos.",
            confirmText = "Continuar",
            dangerous = true,
            onConfirm = { vm.uninstall(context, app.packageName); pendingUninstall = null },
            onDismiss = { pendingUninstall = null },
        )
    }
}
