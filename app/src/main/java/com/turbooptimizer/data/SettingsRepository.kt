package com.turbooptimizer.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "turbo_settings")

enum class OptimizationProfile(val title: String, val summary: String) {
    BALANCED(
        "Equilibrado",
        "Optimización moderada: cierra solo procesos en segundo plano de las apps de tu lista negra. Las apps importantes siguen funcionando.",
    ),
    PERFORMANCE(
        "Rendimiento",
        "Detiene las apps de tu lista negra, cierra procesos en segundo plano, prioriza el juego seleccionado y reduce animaciones.",
    ),
    EXTREME(
        "Extremo",
        "Máxima optimización sin root: además restringe el segundo plano, libera caché del sistema y permite desactivar temporalmente apps elegidas. Todo es reversible.",
    ),
}

enum class RecorderPreset(val label: String, val shortSide: Int, val fps: Int, val bitrateMbps: Int) {
    P720_30("720p / 30 FPS", 720, 30, 5),
    P720_60("720p / 60 FPS", 720, 60, 8),
    P1080_30("1080p / 30 FPS", 1080, 30, 10),
    CUSTOM("Personalizado", 0, 0, 0),
}

data class RecorderSettings(
    val preset: RecorderPreset = RecorderPreset.P720_30,
    val shortSide: Int = 720,
    val fps: Int = 30,
    val bitrateMbps: Int = 5,
    val internalAudio: Boolean = true,
    val microphone: Boolean = false,
    val folderUri: String? = null,
)

data class AppSettings(
    val profile: OptimizationProfile = OptimizationProfile.BALANCED,
    /** Lista blanca: apps protegidas que nunca se detienen. */
    val whitelist: Set<String> = emptySet(),
    /** Lista negra: apps que el usuario permite detener. */
    val blacklist: Set<String> = emptySet(),
    val games: Set<String> = emptySet(),
    val selectedGame: String? = null,
    val gameProfile: OptimizationProfile = OptimizationProfile.PERFORMANCE,
    val overlayEnabled: Boolean = true,
    val autoDetectGames: Boolean = false,
    val ramBoostEnabled: Boolean = false,
    val ramBoostIntervalMin: Int = 15,
    val ramBoostThresholdPercent: Int = 20,
    /** Modo simulación: muestra qué se haría sin aplicar cambios. */
    val simulationMode: Boolean = false,
    val showLocalIp: Boolean = false,
    val followSystemTheme: Boolean = false,
    /** Apps desactivadas temporalmente por TurboOptimizer (para restaurarlas). */
    val disabledByApp: Set<String> = emptySet(),
    val recorder: RecorderSettings = RecorderSettings(),
)

class SettingsRepository(private val context: Context) {

    private object K {
        val PROFILE = stringPreferencesKey("profile")
        val WHITELIST = stringSetPreferencesKey("whitelist")
        val BLACKLIST = stringSetPreferencesKey("blacklist")
        val GAMES = stringSetPreferencesKey("games")
        val SELECTED_GAME = stringPreferencesKey("selected_game")
        val GAME_PROFILE = stringPreferencesKey("game_profile")
        val OVERLAY = booleanPreferencesKey("overlay")
        val AUTO_DETECT = booleanPreferencesKey("auto_detect")
        val RAM_BOOST = booleanPreferencesKey("ram_boost")
        val RAM_INTERVAL = intPreferencesKey("ram_interval")
        val RAM_THRESHOLD = intPreferencesKey("ram_threshold")
        val SIMULATION = booleanPreferencesKey("simulation")
        val SHOW_IP = booleanPreferencesKey("show_ip")
        val SYSTEM_THEME = booleanPreferencesKey("system_theme")
        val DISABLED = stringSetPreferencesKey("disabled_by_app")
        val REC_PRESET = stringPreferencesKey("rec_preset")
        val REC_SHORT = intPreferencesKey("rec_short")
        val REC_FPS = intPreferencesKey("rec_fps")
        val REC_BITRATE = intPreferencesKey("rec_bitrate")
        val REC_INTERNAL = booleanPreferencesKey("rec_internal")
        val REC_MIC = booleanPreferencesKey("rec_mic")
        val REC_FOLDER = stringPreferencesKey("rec_folder")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::fromPrefs)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(fromPrefs(prefs))
            write(prefs, updated)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        val r = d.recorder
        return AppSettings(
            profile = enumOr(p[K.PROFILE], d.profile),
            whitelist = p[K.WHITELIST] ?: d.whitelist,
            blacklist = p[K.BLACKLIST] ?: d.blacklist,
            games = p[K.GAMES] ?: d.games,
            selectedGame = p[K.SELECTED_GAME],
            gameProfile = enumOr(p[K.GAME_PROFILE], d.gameProfile),
            overlayEnabled = p[K.OVERLAY] ?: d.overlayEnabled,
            autoDetectGames = p[K.AUTO_DETECT] ?: d.autoDetectGames,
            ramBoostEnabled = p[K.RAM_BOOST] ?: d.ramBoostEnabled,
            ramBoostIntervalMin = (p[K.RAM_INTERVAL] ?: d.ramBoostIntervalMin).coerceIn(MIN_RAM_INTERVAL, 120),
            ramBoostThresholdPercent = (p[K.RAM_THRESHOLD] ?: d.ramBoostThresholdPercent).coerceIn(5, 60),
            simulationMode = p[K.SIMULATION] ?: d.simulationMode,
            showLocalIp = p[K.SHOW_IP] ?: d.showLocalIp,
            followSystemTheme = p[K.SYSTEM_THEME] ?: d.followSystemTheme,
            disabledByApp = p[K.DISABLED] ?: d.disabledByApp,
            recorder = RecorderSettings(
                preset = enumOr(p[K.REC_PRESET], r.preset),
                shortSide = p[K.REC_SHORT] ?: r.shortSide,
                fps = p[K.REC_FPS] ?: r.fps,
                bitrateMbps = p[K.REC_BITRATE] ?: r.bitrateMbps,
                internalAudio = p[K.REC_INTERNAL] ?: r.internalAudio,
                microphone = p[K.REC_MIC] ?: r.microphone,
                folderUri = p[K.REC_FOLDER],
            ),
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.PROFILE] = s.profile.name
        p[K.WHITELIST] = s.whitelist
        p[K.BLACKLIST] = s.blacklist
        p[K.GAMES] = s.games
        if (s.selectedGame != null) p[K.SELECTED_GAME] = s.selectedGame else p.remove(K.SELECTED_GAME)
        p[K.GAME_PROFILE] = s.gameProfile.name
        p[K.OVERLAY] = s.overlayEnabled
        p[K.AUTO_DETECT] = s.autoDetectGames
        p[K.RAM_BOOST] = s.ramBoostEnabled
        p[K.RAM_INTERVAL] = s.ramBoostIntervalMin.coerceIn(MIN_RAM_INTERVAL, 120)
        p[K.RAM_THRESHOLD] = s.ramBoostThresholdPercent.coerceIn(5, 60)
        p[K.SIMULATION] = s.simulationMode
        p[K.SHOW_IP] = s.showLocalIp
        p[K.SYSTEM_THEME] = s.followSystemTheme
        p[K.DISABLED] = s.disabledByApp
        p[K.REC_PRESET] = s.recorder.preset.name
        p[K.REC_SHORT] = s.recorder.shortSide
        p[K.REC_FPS] = s.recorder.fps
        p[K.REC_BITRATE] = s.recorder.bitrateMbps
        p[K.REC_INTERNAL] = s.recorder.internalAudio
        p[K.REC_MIC] = s.recorder.microphone
        if (s.recorder.folderUri != null) p[K.REC_FOLDER] = s.recorder.folderUri else p.remove(K.REC_FOLDER)
    }

    companion object {
        /** Intervalo mínimo de RAM Boost: evita limpiezas cada pocos segundos. */
        const val MIN_RAM_INTERVAL = 5
    }
}
