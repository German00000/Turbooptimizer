package com.turbooptimizer.core.shizuku

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Se ejecuta DENTRO del proceso que crea Shizuku (uid shell/ADB), no en el proceso de la app.
 * Shizuku lo instancia por reflexión con el constructor vacío.
 *
 * Seguridad:
 *  - Revalida cada comando con [CommandPolicy] (defensa en profundidad).
 *  - Ejecuta el binario por ruta absoluta en /system/bin con ProcessBuilder (sin shell).
 *  - Limita el tamaño de salida y el tiempo de ejecución.
 */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: Array<String>?, timeoutMs: Long): String {
        val cmd = command?.toList().orEmpty()
        val verdict = CommandPolicy.check(cmd)
        if (verdict is CommandPolicy.Verdict.Denied) {
            return ShellResult.encode(ShellResult(ShellResult.CODE_DENIED, "", "Denegado por la política de seguridad: ${verdict.reason}"))
        }
        return try {
            val argv = listOf("/system/bin/${cmd[0]}") + cmd.drop(1)
            val process = ProcessBuilder(argv).start()
            process.outputStream.close()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = thread(name = "shell-out") { readLimited(process.inputStream, out) }
            val tErr = thread(name = "shell-err") { readLimited(process.errorStream, err) }
            val finished = process.waitFor(timeoutMs.coerceIn(500L, 30_000L), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            tOut.join(1_000)
            tErr.join(1_000)
            val result = if (finished) {
                ShellResult(process.exitValue(), out.toString(), err.toString())
            } else {
                ShellResult(ShellResult.CODE_TIMEOUT, out.toString(), "Tiempo de espera agotado")
            }
            ShellResult.encode(result)
        } catch (t: Throwable) {
            ShellResult.encode(ShellResult(-1, "", t.toString()))
        }
    }

    private fun readLimited(stream: InputStream, sb: StringBuilder) {
        try {
            stream.bufferedReader().use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sb.length < MAX_OUTPUT) sb.append(buf, 0, minOf(n, MAX_OUTPUT - sb.length))
                }
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val MAX_OUTPUT = 400_000 // < 1 MB de límite de Binder
    }
}
