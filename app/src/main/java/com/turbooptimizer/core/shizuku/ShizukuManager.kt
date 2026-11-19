package com.turbooptimizer.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.turbooptimizer.BuildConfig
import com.turbooptimizer.core.diagnostics.Diagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    /** 🔴 Shizuku no está instalado ni hay servicio compatible (Sui). */
    NOT_INSTALLED,
    /** 🟡 Instalado pero el servicio no está iniciado. */
    NOT_RUNNING,
    /** 🟡 Iniciado pero TurboOptimizer no tiene permiso. */
    NO_PERMISSION,
    /** 🔴 Versión demasiado antigua. */
    UNSUPPORTED,
    /** 🟢 Funcionando y con permiso. */
    READY,
}

data class ShizukuState(
    val status: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val installed: Boolean = false,
    val managerVersion: String? = null,
    val serverVersion: Int? = null,
    val uid: Int? = null,
    val error: String? = null,
) {
    /** Shizuku iniciado con ADB (2000) o root (0). */
    val modeLabel: String?
        get() = when (uid) {
            0 -> "root"
            2000 -> "ADB (shell)"
            null -> null
            else -> "uid $uid"
        }
}

/**
 * Detecta el estado de Shizuku, gestiona el permiso y el UserService privilegiado.
 * Toda ejecución pasa por [CommandPolicy].
 */
class ShizukuManager(private val context: Context) {

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()
    val isReady: Boolean get() = _state.value.status == ShizukuStatus.READY

    @Volatile private var service: IShellService? = null
    private var pending: CompletableDeferred<IShellService>? = null
    private var lastLoggedStatus: ShizukuStatus? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = IShellService.Stub.asInterface(binder)
                service = s
                pending?.complete(s)
                pending = null
                Diagnostics.i(TAG, "Servicio privilegiado conectado")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Diagnostics.w(TAG, "Servicio privilegiado desconectado")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Diagnostics.i(TAG, "Binder de Shizuku recibido")
        refresh()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Diagnostics.w(TAG, "Binder de Shizuku perdido (Shizuku se ha detenido)")
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) Diagnostics.i(TAG, "Permiso de Shizuku concedido (petición $requestCode)")
        else Diagnostics.w(TAG, "Permiso de Shizuku denegado (petición $requestCode)")
        refresh()
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        val managerVersion = runCatching {
            context.packageManager.getPackageInfo(CommandPolicy.SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0)).versionName
        }.getOrNull()
        val installed = managerVersion != null
        val newState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState(if (installed) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED)
                Shizuku.isPreV11() -> ShizukuState(ShizukuStatus.UNSUPPORTED, error = "Versión de Shizuku demasiado antigua (< 11)")
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuState(ShizukuStatus.NO_PERMISSION, serverVersion = Shizuku.getVersion(), uid = Shizuku.getUid())
                else -> ShizukuState(ShizukuStatus.READY, serverVersion = Shizuku.getVersion(), uid = Shizuku.getUid())
            }
        } catch (t: Throwable) {
            ShizukuState(if (installed) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED, error = t.message)
        }
        val finalState = newState.copy(installed = installed || newState.status != ShizukuStatus.NOT_INSTALLED, managerVersion = managerVersion)
        logIfChanged(finalState)
        _state.value = finalState
    }

    /** Registra solo los cambios de estado, para no repetir la misma línea en cada comprobación. */
    private fun logIfChanged(s: ShizukuState) {
        val previous = lastLoggedStatus
        if (previous == s.status) return
        lastLoggedStatus = s.status
        val detail = listOfNotNull(
            s.modeLabel?.let { "modo $it" },
            s.serverVersion?.let { "servidor v$it" },
            s.managerVersion?.let { "app $it" },
            s.error?.let { "error: $it" },
        ).joinToString(", ")
        val message = "Estado de Shizuku: ${previous?.name ?: "inicio"} → ${s.status.name}" + if (detail.isEmpty()) "" else " ($detail)"
        if (s.status == ShizukuStatus.READY) Diagnostics.i(TAG, message) else Diagnostics.w(TAG, message)
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Diagnostics.i(TAG, "Solicitud de permiso de Shizuku enviada")
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "No se pudo solicitar el permiso de Shizuku", t)
        }
        refresh()
    }

    fun openShizukuApp(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(CommandPolicy.SHIZUKU_PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private suspend fun obtainService(): IShellService? = withContext(Dispatchers.Main) {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return@withContext it }
        if (!isReady) return@withContext null
        val deferred = pending ?: CompletableDeferred<IShellService>().also {
            pending = it
            try {
                Shizuku.bindUserService(serviceArgs, connection)
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "bindUserService falló", t)
                pending = null
                return@withContext null
            }
        }
        val result = withTimeoutOrNull(10_000) { deferred.await() }
        if (result == null) {
            Diagnostics.w(TAG, "Tiempo de espera agotado al conectar con el servicio de Shizuku (10 s)")
            pending = null
        }
        result
    }

    /**
     * Ejecuta un comando de la lista blanca. Nunca lanza excepciones: devuelve un [ShellResult].
     * Cada comando se registra con su código de salida y su duración (sin volcar la salida completa).
     */
    suspend fun exec(cmd: List<String>, timeoutMs: Long = 10_000): ShellResult = withContext(Dispatchers.IO) {
        val text = ShellCommands.display(cmd)
        when (val verdict = CommandPolicy.check(cmd)) {
            is CommandPolicy.Verdict.Denied -> {
                Diagnostics.w(TAG, "Comando bloqueado por la lista blanca: $text (${verdict.reason})")
                return@withContext ShellResult(ShellResult.CODE_DENIED, "", verdict.reason)
            }
            is CommandPolicy.Verdict.Allowed -> Unit
        }
        if (!isReady) {
            Diagnostics.w(TAG, "Comando omitido, Shizuku no está listo: $text")
            return@withContext ShellResult.unavailable()
        }
        repeat(2) { attempt ->
            val s = obtainService() ?: run {
                Diagnostics.w(TAG, "Comando omitido, sin conexión con el servicio: $text")
                return@withContext ShellResult.unavailable("No se pudo conectar con el servicio de Shizuku")
            }
            val startNs = System.nanoTime()
            try {
                val result = ShellResult.decode(s.exec(cmd.toTypedArray(), timeoutMs))
                val ms = (System.nanoTime() - startNs) / 1_000_000
                if (result.isSuccess) Diagnostics.i(TAG, "Comando: $text → código 0 (${ms} ms)")
                else Diagnostics.w(TAG, "Comando: $text → código ${result.exitCode} (${ms} ms): ${result.message}")
                return@withContext result
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "Fallo ejecutando $text (intento ${attempt + 1} de 2)", t)
                service = null
            }
        }
        ShellResult(-1, "", "El servicio de Shizuku se desconectó")
    }

    companion object {
        private const val TAG = "ShizukuManager"
        const val PERMISSION_REQUEST_CODE = 4242
    }
}
