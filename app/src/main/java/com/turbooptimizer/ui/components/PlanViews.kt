package com.turbooptimizer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbooptimizer.optimizer.OptimizationPlan
import com.turbooptimizer.optimizer.OptimizationResult
import com.turbooptimizer.optimizer.PlannedAction
import com.turbooptimizer.optimizer.Risk
import com.turbooptimizer.ui.theme.TurboColors
import com.turbooptimizer.util.formatBytes

/** Fila de una acción del plan: muestra EXACTAMENTE qué se va a modificar y el comando. */
@Composable
fun PlanActionRow(action: PlannedAction, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(action.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (action.requiresShizuku) Pill("Shizuku", MaterialTheme.colorScheme.secondary)
                    Pill(if (action.reversible) "Reversible" else "No requiere deshacer", if (action.reversible) TurboColors.Good else TurboColors.Sky)
                    if (action.risk == Risk.MEDIUM) Pill(action.risk.label, TurboColors.Warn)
                }
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 48.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(action.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommandBox(action.commandText)
            }
        }
    }
}

@Composable
fun PlanList(plan: OptimizationPlan, selected: Set<String>, shizukuReady: Boolean, onToggle: (String) -> Unit) {
    Column {
        plan.notes.forEach { note ->
            Text("• $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (plan.actions.isEmpty()) EmptyState("No hay nada que optimizar con este perfil ahora mismo.")
        plan.actions.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlanActionRow(a, a.id in selected, enabled = !a.requiresShizuku || shizukuReady) { onToggle(a.id) }
        }
        Text(
            "Toca una acción para ver qué hace y el comando exacto que se ejecutará.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Diálogo con el plan completo para aprobar antes de ejecutar (Game Booster). */
@Composable
fun PlanPreviewDialog(
    title: String,
    plan: OptimizationPlan,
    selected: Set<String>,
    shizukuReady: Boolean,
    simulate: Boolean,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    if (simulate) Text("Modo simulación activo: no se aplicará ningún cambio.", color = TurboColors.Warn, style = MaterialTheme.typography.bodySmall)
                    plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(plan.actions, key = { it.id }) { a ->
                    PlanActionRow(a, a.id in selected, !a.requiresShizuku || shizukuReady) { onToggle(a.id) }
                }
                if (plan.actions.isEmpty()) item { Text("No se aplicará ninguna acción; solo se abrirá el juego con el monitor.") }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (simulate) "Simular y jugar" else "Aplicar (${selected.size}) y jugar", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun ResultSummary(result: OptimizationResult, onUndo: (() -> Unit)?) {
    val delta = result.ramAfter - result.ramBefore
    SectionCard(
        title = if (result.simulated) "Simulación completada" else "Optimización aplicada",
        icon = Icons.Default.CheckCircle,
        accent = if (result.simulated) TurboColors.Warn else TurboColors.Good,
    ) {
        InfoRow("RAM disponible antes", formatBytes(result.ramBefore))
        InfoRow("RAM disponible después", formatBytes(result.ramAfter))
        InfoRow("Diferencia", (if (delta >= 0) "+" else "−") + formatBytes(kotlin.math.abs(delta)))
        Text(
            "Android reutiliza la memoria libre automáticamente; la diferencia puede ser pequeña y es normal.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.outcomes.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (o.success) Icons.Default.CheckCircle else Icons.Default.Error, null,
                    tint = if (o.success) TurboColors.Good else TurboColors.Bad,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("${o.action.title}: ${o.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onUndo != null && result.undoAvailable) {
            TextButton(onClick = onUndo) { Text("Deshacer los cambios reversibles") }
        }
    }
}
