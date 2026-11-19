package com.turbooptimizer.ui.screens

import android.app.Activity
import android.app.PendingIntent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.turbooptimizer.AppContainer
import com.turbooptimizer.cleaner.DuplicateGroup
import com.turbooptimizer.cleaner.MediaItem
import com.turbooptimizer.cleaner.ScanCategory
import com.turbooptimizer.cleaner.ScanItem
import com.turbooptimizer.cleaner.ScanResult
import com.turbooptimizer.core.shizuku.ShellCommands
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.data.LogCategory
import com.turbooptimizer.ui.components.CommandBox
import com.turbooptimizer.ui.components.ConfirmDialog
import com.turbooptimizer.ui.components.EmptyState
import com.turbooptimizer.ui.components.InfoRow
import com.turbooptimizer.ui.components.Pill
import com.turbooptimizer.ui.components.SectionCard
import com.turbooptimizer.ui.components.TurboScaffold
import com.turbooptimizer.ui.navigation.turboViewModel
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.Permissions
import com.turbooptimizer.util.formatBytes
import com.turbooptimizer.util.formatDate
import com.turbooptimizer.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CleanerUi(
    val scanning: Boolean = false,
    val progress: String = "",
    val result: ScanResult? = null,
    val selected: Set<String> = emptySet(),
    val cleaning: Boolean = false,
    val findingDuplicates: Boolean = false,
    val dupProgress: Float = 0f,
    val groups: List<DuplicateGroup>? = null,
    val selectedMedia: Set<Uri> = emptySet(),
)

class CleanerViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val shizuku = c.shizuku.state
    val history = c.log.entries.map { l -> l.filter { it.category == LogCategory.CLEAN } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _ui = MutableStateFlow(CleanerUi())
    val ui = _ui.asStateFlow()
    private val _trash = MutableStateFlow<Pair<PendingIntent, Long>?>(null)
    val trash = _trash.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var pendingTrashCount = 0
    private var scanJob: Job? = null

    fun scan() {
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, result = null, selected = emptySet()) }
        scanJob = viewModelScope.launch {
            val r = runCatching { c.scanner.scan { p -> _ui.update { s -> s.copy(progress = p) } } }.getOrNull()
            _ui.update { s -> s.copy(scanning = false, result = r, selected = r?.items?.filter { it.preselected }?.map { it.path }?.toSet().orEmpty()) }
            if (r == null) _message.value = "No se pudo completar el análisis"
        }
    }

    fun toggle(path: String) = _ui.update { s -> s.copy(selected = if (path in s.selected) s.selected - path else s.selected + path) }

    fun toggleCategory(cat: ScanCategory, on: Boolean) = _ui.update { s ->
        val paths = s.result?.byCategory?.get(cat).orEmpty().filter { it.deletable }.map { it.path }
        s.copy(selected = if (on) s.selected + paths else s.selected - paths.toSet())
    }

    fun selectedItems(): List<ScanItem> = _ui.value.result?.items.orEmpty().filter { it.path in _ui.value.selected && it.deletable }

    fun clean() {
        val items = selectedItems()
        if (items.isEmpty()) return
        _ui.update { it.copy(cleaning = true) }
        viewModelScope.launch {
            val simulate = c.settings.current().simulationMode
            val out = c.cleaner.clean(items, simulate)
            _ui.update { it.copy(cleaning = false) }
            _message.value = buildString {
                append(if (simulate) "Simulación: se liberarían " else "Liberados ")
                append("${formatBytes(out.bytesFreed)} (${out.deletedCount} elementos)")
                if (out.failures.isNotEmpty()) append(". ${out.failures.size} no se pudieron eliminar")
            }
            if (out.trashUris.isNotEmpty() && !simulate) {
                pendingTrashCount = out.trashUris.size
                _trash.value = runCatching { c.cleaner.trashRequest(out.trashUris) }.getOrNull()?.let { it to out.trashBytes }
            }
            scan()
        }
    }

    fun findDuplicates() {
        _ui.update { it.copy(findingDuplicates = true, dupProgress = 0f, groups = null, selectedMedia = emptySet()) }
        viewModelScope.launch {
            val groups = runCatching { c.duplicates.find { p -> _ui.update { s -> s.copy(dupProgress = p) } } }.getOrElse {
                _message.value = "Error buscando duplicados: ${it.message}"
                emptyList()
            }
            _ui.update { it.copy(findingDuplicates = false, groups = groups) }
        }
    }

    fun toggleMedia(uri: Uri) = _ui.update { s -> s.copy(selectedMedia = if (uri in s.selectedMedia) s.selectedMedia - uri else s.selectedMedia + uri) }

    /** Atajo opcional: marca todos los duplicados excepto el más antiguo de cada grupo. */
    fun selectAllButOldest() = _ui.update { s ->
        s.copy(selectedMedia = s.groups.orEmpty().flatMap { g -> g.items.drop(1).map { it.uri } }.toSet())
    }

    fun trashSelectedMedia() {
        val uris = _ui.value.selectedMedia.toList()
        if (uris.isEmpty()) return
        val bytes = _ui.value.groups.orEmpty().flatMap { it.items }.filter { it.uri in _ui.value.selectedMedia }.sumOf { it.size }
        if (settings.value.simulationMode) {
            _message.value = "Simulación: se enviarían ${uris.size} archivos (${formatBytes(bytes)}) a la papelera"
            return
        }
        pendingTrashCount = uris.size
        _trash.value = runCatching { c.cleaner.trashRequest(uris) }.getOrNull()?.let { it to bytes }
    }

    fun onTrashResult(ok: Boolean) {
        val bytes = _trash.value?.second ?: 0
        _trash.value = null
        viewModelScope.launch {
            c.cleaner.logTrash(pendingTrashCount, bytes, ok)
            _message.value = if (ok) "Enviados a la papelera (recuperables durante 30 días)" else "Cancelado"
            if (ok && _ui.value.groups != null) findDuplicates()
        }
    }

    fun trimCaches() = viewModelScope.launch {
        if (settings.value.simulationMode) { _message.value = "Simulación: se pediría a Android liberar la caché de las apps"; return@launch }
        val r = c.shizuku.exec(ShellCommands.trimCaches(), 60_000)
        c.log.add(LogCategory.CLEAN, "Liberar caché de apps (pm trim-caches)", listOf(r.message), success = r.isSuccess)
        _message.value = if (r.isSuccess) "Android liberó la caché de las apps" else "Error: ${r.message}"
        scan()
    }

    fun consume() { _message.value = null }
}

@Composable
fun CleanerScreen(onBack: () -> Unit) {
    val vm = turboViewModel { CleanerViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClean by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmTrim by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MediaItem?>(null) }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onTrashResult(res.resultCode == Activity.RESULT_OK)
    }
    val mediaPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.hasMediaAccess(context)) vm.findDuplicates()
    }
    LaunchedEffect(trash) { trash?.let { (pi, _) -> trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) } }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consume() } }
    LaunchedEffect(Unit) { if (ui.result == null && !ui.scanning) vm.scan() }

    TurboScaffold("Limpiador", onBack, snackbar = snackbar) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Análisis", "Duplicados", "Ocultos", "Historial").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> analysisTab(ui, settings.simulationMode, shizuku.status == com.turbooptimizer.core.shizuku.ShizukuStatus.READY, vm,
                        onClean = { confirmClean = true }, onTrim = { confirmTrim = true },
                        onGrantAll = { Permissions.open(context, Permissions.allFilesIntent(context)) },
                        onGrantUsage = { Permissions.open(context, Permissions.usageAccessIntent(context)) },
                        hasAll = Permissions.hasAllFilesAccess(), hasUsage = Permissions.hasUsageAccess(context))
                    1 -> duplicatesTab(ui, vm, Permissions.hasMediaAccess(context),
                        onGrant = { mediaPerms.launch(Permissions.mediaPermissions()) },
                        onPreview = { preview = it }, onTrash = { confirmTrash = true })
                    2 -> hiddenTab(ui, vm)
                    else -> {
                        if (history.isEmpty()) item { EmptyState("Aún no hay limpiezas registradas") }
                        items(history, key = { it.id }) { e ->
                            SectionCard(title = e.title + if (e.simulated) " · simulado" else "", icon = Icons.Default.History) {
                                Text(formatDate(e.timestamp), style = MaterialTheme.typography.labelSmall)
                                e.details.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                if (e.details.size > 5) Text("… y ${e.details.size - 5} más", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClean) {
        val items = vm.selectedItems()
        val personal = items.count { it.category in setOf(ScanCategory.OLD_DOWNLOADS, ScanCategory.LARGE_FILES) }
        val media = items.count { it.isMedia }
        ConfirmDialog(
            title = if (settings.simulationMode) "Simular limpieza" else "Confirmar limpieza",
            message = "Se ${if (settings.simulationMode) "simulará la eliminación de" else "eliminarán"} ${items.size} elementos (${formatBytes(items.sumOf { it.size })})." +
                (if (personal > 0) "\n\n⚠ Incluye $personal archivos de descargas antiguas o grandes elegidos por ti. Revisa que no sean documentos importantes." else "") +
                (if (media > 0) "\n\n$media fotos/vídeos/audios se enviarán a la papelera del sistema (recuperables 30 días)." else "") +
                "\n\nLas carpetas protegidas (/Android/data, /Android/obb, DCIM, Download…) nunca se eliminan.",
            confirmText = if (settings.simulationMode) "Simular" else "Eliminar",
            dangerous = personal > 0 && !settings.simulationMode,
            onConfirm = { confirmClean = false; vm.clean() },
            onDismiss = { confirmClean = false },
        )
    }
    if (confirmTrash) {
        ConfirmDialog(
            title = "Enviar a la papelera",
            message = "${ui.selectedMedia.size} archivos elegidos por ti se moverán a la papelera del sistema. Android te pedirá confirmarlo y podrás restaurarlos durante 30 días desde la galería.",
            confirmText = "Continuar",
            onConfirm = { confirmTrash = false; vm.trashSelectedMedia() },
            onDismiss = { confirmTrash = false },
        )
    }
    if (confirmTrim) {
        ConfirmDialog(
            title = "Liberar caché de todas las apps",
            message = "Se pedirá a Android que libere los archivos de caché de las apps mediante Shizuku. Android decide qué borra; no afecta a datos, cuentas, fotos ni partidas guardadas.",
            confirmText = "Liberar",
            onConfirm = { confirmTrim = false; vm.trimCaches() },
            onDismiss = { confirmTrim = false },
        ) { CommandBox(ShellCommands.display(ShellCommands.trimCaches())) }
    }
    preview?.let { item ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AsyncImage(model = item.uri, contentDescription = item.name, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    InfoRow("Tamaño", formatBytes(item.size))
                    InfoRow("Ubicación", item.path.ifBlank { "/" })
                    InfoRow("Fecha", formatDate(item.dateTaken))
                    if (item.width > 0) InfoRow("Dimensiones", "${item.width} × ${item.height}")
                    if (item.isVideo && item.durationMs > 0) InfoRow("Duración", formatDuration(item.durationMs))
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Cerrar") } },
        )
    }
}

private fun LazyListScope.analysisTab(
    ui: CleanerUi, simulate: Boolean, shizukuReady: Boolean, vm: CleanerViewModel,
    onClean: () -> Unit, onTrim: () -> Unit, onGrantAll: () -> Unit, onGrantUsage: () -> Unit, hasAll: Boolean, hasUsage: Boolean,
) {
    if (!hasAll) {
        item {
            SectionCard(title = "Modo limitado", icon = Icons.Default.Storage, accent = TurboColors.Warn) {
                Text("Para analizar temporales, APK, carpetas vacías y archivos ocultos concede «Acceso a todos los archivos». Sin él solo se analiza la caché de esta app.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onGrantAll) { Text("Conceder acceso") }
            }
        }
    }
    item {
        val r = ui.result
        SectionCard(title = "Resumen", icon = Icons.Default.CleaningServices) {
            when {
                ui.scanning -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(ui.progress, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                r != null -> {
                    val selectedBytes = r.items.filter { it.path in ui.selected }.sumOf { it.size }
                    InfoRow("Archivos analizados", "${r.scannedFiles} en ${r.durationMs / 1000} s" + if (r.truncated) " (limitado)" else "")
                    InfoRow("Seleccionado", "${ui.selected.size} elementos · ${formatBytes(selectedBytes)}")
                    InfoRow("Caché de otras apps", r.otherAppsCacheBytes?.let { formatBytes(it) } ?: "Requiere acceso de uso")
                    if (simulate) Pill("Modo simulación activo", TurboColors.Warn)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::scan) { Text("Analizar de nuevo") }
                        Button(onClick = onClean, enabled = ui.selected.isNotEmpty() && !ui.cleaning) {
                            if (ui.cleaning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (simulate) "Simular" else "Limpiar")
                        }
                    }
                    if (r.otherAppsCacheBytes == null && !hasUsage) TextButton(onClick = onGrantUsage) { Text("Conceder acceso de uso") }
                    if (shizukuReady) TextButton(onClick = onTrim) { Text("Liberar caché de todas las apps (Shizuku)") }
                    else Text("La caché de otras apps solo puede liberarla Android (desde Ajustes o con Shizuku).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Button(onClick = vm::scan) { Text("Analizar") }
            }
        }
    }
    val r = ui.result ?: return
    ScanCategory.entries.filter { it != ScanCategory.HIDDEN }.forEach { cat ->
        val list = r.byCategory[cat].orEmpty()
        if (list.isEmpty()) return@forEach
        item(key = cat.name) { CategoryCard(cat, list, ui.selected, vm) }
    }
    item {
        Text(
            "Nunca se borran automáticamente fotos, vídeos, documentos ni partidas guardadas. Las descargas antiguas y archivos grandes solo se eliminan si los marcas tú.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryCard(cat: ScanCategory, list: List<ScanItem>, selected: Set<String>, vm: CleanerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val deletable = list.filter { it.deletable }
    val allOn = deletable.isNotEmpty() && deletable.all { it.path in selected }
    SectionCard(
        title = "${cat.title} · ${formatBytes(list.sumOf { it.size })}",
        icon = if (cat == ScanCategory.EMPTY_FOLDERS) Icons.Default.FolderOff else Icons.Default.CleaningServices,
        trailing = { Checkbox(allOn, { vm.toggleCategory(cat, it) }, enabled = deletable.isNotEmpty()) },
        onClick = { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} elementos. ${cat.description}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) list.take(200).forEach { ItemRow(it, it.path in selected) { vm.toggle(it.path) } }
        if (expanded && list.size > 200) Text("… y ${list.size - 200} más", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ItemRow(item: ScanItem, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = item.deletable, onClick = onToggle)) {
        Checkbox(checked, { onToggle() }, enabled = item.deletable)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.path.removePrefix(com.turbooptimizer.cleaner.PathSafety.root.path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(formatBytes(item.size), item.appHint?.let { "App: $it" }, item.note).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun LazyListScope.hiddenTab(ui: CleanerUi, vm: CleanerViewModel) {
    val r = ui.result
    item {
        SectionCard(title = "Archivos ocultos", icon = Icons.Default.VisibilityOff) {
            Text(
                "Se muestran su ubicación, tamaño y la app asociada cuando se puede identificar. Solo se pueden eliminar los marcados como seguros " +
                    "(cachés regenerables o residuos de apps desinstaladas sin archivos personales). /Android/data, /Android/obb y carpetas del sistema están protegidas.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (r == null) Text(if (ui.scanning) "Analizando…" else "Realiza un análisis primero.")
        }
    }
    val hidden = r?.byCategory?.get(ScanCategory.HIDDEN).orEmpty()
    if (r != null && hidden.isEmpty()) item { EmptyState(if (r.fullAccess) "No se encontraron elementos ocultos" else "Requiere «Acceso a todos los archivos»") }
    items(hidden, key = { "h:" + it.path }) { h ->
        SectionCard {
            ItemRow(h, h.path in ui.selected) { vm.toggle(h.path) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (h.deletable) "Seguro" else "Solo informativo", if (h.deletable) TurboColors.Good else TurboColors.Sky)
                if (h.isDirectory) Pill("Carpeta", TurboColors.Violet)
            }
        }
    }
    if (hidden.any { it.path in ui.selected }) {
        item { Text("Usa «Limpiar» en la pestaña Análisis para eliminar los elementos ocultos seleccionados (se pedirá confirmación).", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun LazyListScope.duplicatesTab(
    ui: CleanerUi, vm: CleanerViewModel, hasMedia: Boolean,
    onGrant: () -> Unit, onPreview: (MediaItem) -> Unit, onTrash: () -> Unit,
) {
    item {
        SectionCard(title = "Fotos y vídeos duplicados", icon = Icons.Default.PhotoLibrary) {
            Text("Se comparan tamaño y contenido (huella SHA-256). Nada se selecciona automáticamente: elige qué enviar a la papelera.", style = MaterialTheme.typography.bodySmall)
            when {
                !hasMedia -> Button(onClick = onGrant) { Text("Permitir acceso a fotos y vídeos") }
                ui.findingDuplicates -> LinearProgressIndicator(progress = { ui.dupProgress }, modifier = Modifier.fillMaxWidth())
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::findDuplicates) { Text(if (ui.groups == null) "Buscar duplicados" else "Buscar de nuevo") }
                    if (!ui.groups.isNullOrEmpty()) OutlinedButton(onClick = vm::selectAllButOldest) { Text("Marcar todos menos el original") }
                }
            }
            ui.groups?.let { g ->
                InfoRow("Grupos", "${g.size}")
                InfoRow("Recuperable", formatBytes(g.sumOf { it.recoverable }))
                if (ui.selectedMedia.isNotEmpty()) {
                    Button(onClick = onTrash) { Text("Enviar ${ui.selectedMedia.size} a la papelera") }
                }
            }
        }
    }
    val groups = ui.groups ?: return
    if (groups.isEmpty()) item { EmptyState("No se encontraron duplicados") }
    items(groups, key = { it.id }) { g ->
        SectionCard(title = "${g.items.size} copias · ${formatBytes(g.recoverable)} recuperables") {
            Pill(if (g.exact) "Idénticos (contenido completo)" else "Probables (archivo muy grande)", if (g.exact) TurboColors.Good else TurboColors.Warn)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(g.items, key = { it.uri.toString() }) { m ->
                    MediaThumb(m, m.uri in ui.selectedMedia, isOldest = m == g.items.first(), onToggle = { vm.toggleMedia(m.uri) }, onPreview = { onPreview(m) })
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(m: MediaItem, selected: Boolean, isOldest: Boolean, onToggle: () -> Unit, onPreview: () -> Unit) {
    Column(Modifier.width(120.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onPreview)) {
            AsyncImage(model = m.uri, contentDescription = m.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            if (m.isVideo) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
            Checkbox(selected, { onToggle() }, modifier = Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(4.dp))
        Text(m.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatBytes(m.size), style = MaterialTheme.typography.labelSmall)
        Text(m.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isOldest) Text("Más antiguo", style = MaterialTheme.typography.labelSmall, color = TurboColors.Good, modifier = Modifier.padding(top = 2.dp))
    }
}
