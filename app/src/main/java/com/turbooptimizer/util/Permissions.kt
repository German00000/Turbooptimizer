package com.turbooptimizer.util

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Comprobaciones y accesos directos a los permisos especiales que concede el usuario. */
object Permissions {

    fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(ctx: Context): Boolean {
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        val mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) granted(ctx, Manifest.permission.PACKAGE_USAGE_STATS)
        else mode == AppOpsManager.MODE_ALLOWED
    }

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun hasAllFilesAccess() = Environment.isExternalStorageManager()

    fun hasNotifications(ctx: Context) = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun hasMicrophone(ctx: Context) = granted(ctx, Manifest.permission.RECORD_AUDIO)

    /** Acceso completo o parcial (Android 14+: "Seleccionar fotos y vídeos"). */
    fun hasMediaAccess(ctx: Context): Boolean {
        val full = granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) && granted(ctx, Manifest.permission.READ_MEDIA_VIDEO)
        val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return full || partial
    }

    fun mediaPermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }.toTypedArray()

    fun usageAccessIntent(ctx: Context) =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun overlayIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun allFilesIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun appDetailsIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    /** Abre una pantalla de Ajustes con alternativas si el fabricante no la implementa. */
    fun open(ctx: Context, intent: Intent, fallback: Intent? = null) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            try {
                val alt = fallback ?: Intent(intent.action)
                ctx.startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                ctx.startActivity(appDetailsIntent(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: SecurityException) {
            ctx.startActivity(appDetailsIntent(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
