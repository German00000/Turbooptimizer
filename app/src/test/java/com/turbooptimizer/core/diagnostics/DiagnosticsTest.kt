package com.turbooptimizer.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsTest {

    @Before fun setUp() = Diagnostics.clear()

    @Test fun redactsPasswordsAndTokens() {
        val out = Redactor.clean("login password=hunter2 token: abc123 user=german")
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("password=[oculto]"))
        assertTrue(out.contains("user=german"))
    }

    @Test fun redactsBearerAuthorization() {
        val out = Redactor.clean("Authorization: Bearer eyJhbGciOi.secret")
        assertFalse(out.contains("eyJhbGciOi"))
    }

    @Test fun redactsEmailAddresses() {
        assertEquals("contacto [correo oculto] fin", Redactor.clean("contacto persona@example.com fin"))
    }

    @Test fun redactsLongHexAndMixedTokens() {
        val hex = "0123456789abcdef0123456789abcdef"
        val mixed = "aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY5zA7b"
        assertEquals("id [oculto]", Redactor.clean("id $hex"))
        assertEquals("id [oculto]", Redactor.clean("id $mixed"))
    }

    @Test fun keepsPackageNamesAndCommands() {
        val text = "dumpsys SurfaceFlinger --latency com.example.game/com.example.game.MainActivity"
        assertEquals(text, Redactor.clean(text))
        assertEquals("pm disable-user --user 0 moe.shizuku.privileged.api", Redactor.clean("pm disable-user --user 0 moe.shizuku.privileged.api"))
    }

    @Test fun truncatesLongMessages() {
        val out = Redactor.clean("z".repeat(2_000)) // 'z' no es hexadecimal ni lleva dígitos: no se oculta
        assertEquals(Redactor.MAX_LENGTH + 1, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun keepsOnlyTheLatestEntriesWhenFull() {
        repeat(Diagnostics.CAPACITY + 5) { Diagnostics.i("Test", "línea $it") }
        val entries = Diagnostics.entries.value
        assertEquals(Diagnostics.CAPACITY, entries.size)
        assertEquals("línea 5", entries.first().message)
        assertEquals("línea ${Diagnostics.CAPACITY + 4}", entries.last().message)
    }

    @Test fun recordsLevelComponentAndSanitizedMessage() {
        Diagnostics.w("FpsMonitor", "fallo con token=sup3rsecreto")
        val e = Diagnostics.entries.value.single()
        assertEquals(DiagLevel.WARN, e.level)
        assertEquals("FpsMonitor", e.component)
        assertEquals("fallo con token=[oculto]", e.message)
        assertTrue(e.format().contains("W [FpsMonitor] fallo con token=[oculto]"))
    }

    @Test fun exportKeepsHeaderAndChronologicalOrder() {
        Diagnostics.i("Shizuku", "primero")
        Diagnostics.e("FpsMonitor", "segundo")
        val text = Diagnostics.export(Diagnostics.entries.value, listOf("cabecera"))
        val lines = text.lines()
        assertEquals("cabecera", lines[0])
        assertTrue(lines[1].endsWith("I [Shizuku] primero"))
        assertTrue(lines[2].endsWith("E [FpsMonitor] segundo"))
    }

    @Test fun clearEmptiesTheLog() {
        Diagnostics.d("Test", "algo")
        Diagnostics.clear()
        assertTrue(Diagnostics.entries.value.isEmpty())
    }
}
