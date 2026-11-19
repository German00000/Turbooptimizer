package com.turbooptimizer.util

import android.content.Context
import com.turbooptimizer.core.diagnostics.Diagnostics

/**
 * Registra los permisos especiales concedidos o denegados. Se llama al volver a la app (tras el diálogo
 * de permisos o desde Ajustes) y solo escribe cuando algo cambia respecto a la comprobación anterior.
 */
object PermissionDiagnostics {

    private const val TAG = "Permisos"
    private var last: Map<String, Boolean>? = null

    fun check(ctx: Context) {
        val now = runCatching {
            linkedMapOf(
                "Acceso de uso" to Permissions.hasUsageAccess(ctx),
                "Mostrar sobre otras apps" to Permissions.canDrawOverlays(ctx),
                "Notificaciones" to Permissions.hasNotifications(ctx),
                "Micrófono" to Permissions.hasMicrophone(ctx),
                "Multimedia" to Permissions.hasMediaAccess(ctx),
                "Acceso a todos los archivos" to Permissions.hasAllFilesAccess(),
            )
        }.getOrElse {
            Diagnostics.w(TAG, "No se pudo comprobar los permisos", it)
            return
        }
        val previous = last
        now.forEach { (name, granted) ->
            if (previous == null || previous[name] != granted) {
                if (granted) Diagnostics.i(TAG, "Permiso concedido: $name")
                else Diagnostics.w(TAG, "Permiso denegado o no concedido: $name")
            }
        }
        last = now
    }
}
