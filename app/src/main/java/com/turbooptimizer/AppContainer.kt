package com.turbooptimizer

import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display
import com.turbooptimizer.apps.AppRepository
import com.turbooptimizer.cleaner.CleanerExecutor
import com.turbooptimizer.cleaner.DuplicateFinder
import com.turbooptimizer.cleaner.StorageScanner
import com.turbooptimizer.core.safety.SafetyGuard
import com.turbooptimizer.core.shizuku.ShizukuManager
import com.turbooptimizer.data.OperationLogRepository
import com.turbooptimizer.data.SettingsRepository
import com.turbooptimizer.device.DeviceInfoProvider
import com.turbooptimizer.game.GameBoostController
import com.turbooptimizer.monitor.FpsMonitor
import com.turbooptimizer.monitor.SystemMonitor
import com.turbooptimizer.monitor.SystemSnapshot
import com.turbooptimizer.optimizer.OptimizationEngine
import com.turbooptimizer.optimizer.RamBoostManager
import com.turbooptimizer.optimizer.UndoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Inyección de dependencias manual: un único grafo por proceso. */
class AppContainer(val app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val log = OperationLogRepository(app)
    val shizuku = ShizukuManager(app)
    val safety = SafetyGuard(app)
    val apps = AppRepository(app, shizuku)
    val monitor = SystemMonitor(app, shizuku)
    val fps = FpsMonitor(shizuku) {
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
    }
    val deviceInfo = DeviceInfoProvider(app)
    val optimizer = OptimizationEngine(app, shizuku, safety, settings, log, apps, monitor)
    val undo = UndoManager(shizuku, settings, log)
    val ramBoost = RamBoostManager(app, shizuku, safety, settings, log, apps, monitor)
    val scanner = StorageScanner(app)
    val duplicates = DuplicateFinder(app)
    val cleaner = CleanerExecutor(app, log)
    val gameBooster = GameBoostController(app, optimizer, undo, log, apps)

    /** Métricas en tiempo real compartidas; solo se calculan mientras alguien las observa. */
    val snapshots: StateFlow<SystemSnapshot?> = monitor.stream(1_000)
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start() {
        shizuku.init()
        appScope.launch { log.load() }
    }
}
