package com.turbooptimizer.core.diagnostics

/**
 * Oculta datos sensibles antes de que un mensaje entre en el registro o en Logcat.
 * Se aplica siempre, a todos los mensajes, en [Diagnostics.record].
 */
object Redactor {

    const val MAX_LENGTH = 500

    /** Cabeceras de autorización: «Bearer abc…». */
    private val BEARER = Regex("""(?i)\bbearer\s+\S+""")

    /** Pares clave=valor o clave: valor con nombres sensibles. */
    private val SECRET_PAIR = Regex(
        """(?i)\b(password|passwd|pwd|pass|passphrase|token|secret|api[_-]?key|apikey|authorization|auth|cookie|session|credential|pin)\b(\s*[=:]\s*)(\S+)""",
    )

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** Cadenas hexadecimales largas (hashes, claves, identificadores). */
    private val HEX_TOKEN = Regex("""\b[0-9a-fA-F]{32,}\b""")

    /** Cadenas largas alfanuméricas con letras y dígitos mezclados (tokens en base64/base64url). */
    private val MIXED_TOKEN = Regex("""\b(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9_\-]{40,}\b""")

    fun clean(text: String): String {
        var out = BEARER.replace(text, "Bearer [oculto]")
        out = SECRET_PAIR.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}[oculto]" }
        out = EMAIL.replace(out, "[correo oculto]")
        out = HEX_TOKEN.replace(out, "[oculto]")
        out = MIXED_TOKEN.replace(out, "[oculto]")
        return if (out.length > MAX_LENGTH) out.take(MAX_LENGTH) + "…" else out
    }
}
