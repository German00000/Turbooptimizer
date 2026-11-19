package com.turbooptimizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.turbooptimizer.data.AppSettings
import com.turbooptimizer.util.PermissionDiagnostics
import com.turbooptimizer.game.GameSessionState
import com.turbooptimizer.ui.navigation.TurboNavHost
import com.turbooptimizer.ui.theme.TurboTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // obligatorio en Android 15+
        val container = (application as TurboApp).container
        // Restaura la detección automática de juegos desde primer plano (requisito de Android 14+).
        lifecycleScope.launch {
            if (container.settings.current().autoDetectGames && !GameSessionState.watching.value) {
                container.gameBooster.setWatching(true)
            }
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = if (settings.followSystemTheme) isSystemInDarkTheme() else true
            TurboTheme(darkTheme = dark) {
                TurboNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // El usuario puede volver de Shizuku o de Ajustes con permisos nuevos.
        (application as TurboApp).container.shizuku.refresh()
        // Registra los permisos que cambiaron (concedidos o denegados) durante el diálogo o en Ajustes.
        PermissionDiagnostics.check(this)
    }
}
