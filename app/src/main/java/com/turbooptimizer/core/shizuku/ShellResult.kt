package com.turbooptimizer.core.shizuku

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {

    val isSuccess: Boolean get() = exitCode == 0

    /** Mensaje corto y legible para mostrar en la UI o en el registro. */
    val message: String
        get() = when {
            isSuccess -> stdout.trim().lineSequence().firstOrNull()?.take(160)?.ifBlank { "OK" } ?: "OK"
            else -> stderr.ifBlank { stdout }.trim().take(200).ifBlank { "Error (código $exitCode)" }
        }

    companion object {
        const val CODE_UNAVAILABLE = -4
        const val CODE_DENIED = -2
        const val CODE_TIMEOUT = -3

        fun unavailable(msg: String = "Shizuku no está disponible") = ShellResult(CODE_UNAVAILABLE, "", msg)

        fun encode(r: ShellResult): String = "${r.exitCode}\n${r.stdout.length}\n${r.stdout}${r.stderr}"

        fun decode(s: String?): ShellResult {
            if (s == null) return ShellResult(-1, "", "Sin respuesta del servicio")
            val first = s.indexOf('\n')
            val second = if (first >= 0) s.indexOf('\n', first + 1) else -1
            if (first < 0 || second < 0) return ShellResult(-1, "", s)
            val code = s.substring(0, first).toIntOrNull() ?: -1
            val len = s.substring(first + 1, second).toIntOrNull() ?: 0
            val body = s.substring(second + 1)
            val end = len.coerceIn(0, body.length)
            return ShellResult(code, body.substring(0, end), body.substring(end))
        }
    }
}
