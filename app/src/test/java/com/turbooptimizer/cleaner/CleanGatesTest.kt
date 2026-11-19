package com.turbooptimizer.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix 1 (multimedia nunca se borra localmente) y fix 2 (una ruta bloqueada nunca se borra). */
class CleanGatesTest {

    @Test fun blockedPathIsSkippedAndNeverLookedUpOrDeleted() {
        var lookups = 0
        val gate = CleanGates.decide<String>("Carpeta estándar de Android", isMediaFile = false) {
            lookups++
            "content://media/1"
        }
        assertTrue(gate is CleanGate.Skip)
        assertEquals("Carpeta estándar de Android", (gate as CleanGate.Skip).reason)
        assertEquals(0, lookups)
    }

    @Test fun blockedMediaIsSkippedAndNotSentToTrash() {
        var lookups = 0
        val gate = CleanGates.decide<String>("Fuera del almacenamiento compartido", isMediaFile = true) {
            lookups++
            "content://media/1"
        }
        assertTrue(gate is CleanGate.Skip)
        assertEquals(0, lookups)
    }

    @Test fun mediaWithoutValidUriIsSkippedAndNeverDeleted() {
        val gate = CleanGates.decide<String>(blockedReason = null, isMediaFile = true) { null }
        assertTrue(gate is CleanGate.Skip)
        assertFalse(gate == CleanGate.DeleteLocally)
        assertTrue((gate as CleanGate.Skip).reason.contains("no se ha borrado"))
    }

    @Test fun mediaWithValidUriGoesToTrashNotDelete() {
        val gate = CleanGates.decide(blockedReason = null, isMediaFile = true) { "content://media/external/images/42" }
        assertTrue(gate is CleanGate.SendToTrash)
        assertEquals("content://media/external/images/42", (gate as CleanGate.SendToTrash).uri)
    }

    @Test fun mediaNeverYieldsDeleteLocallyForAnyLookupResult() {
        for (lookup in listOf<String?>(null, "", "content://media/1")) {
            val gate = CleanGates.decide(blockedReason = null, isMediaFile = true) { lookup }
            assertFalse("lookup=$lookup", gate == CleanGate.DeleteLocally)
        }
    }

    @Test fun allowedNonMediaIsDeletableLocallyWithoutMediaStoreLookup() {
        var lookups = 0
        val gate = CleanGates.decide<String>(blockedReason = null, isMediaFile = false) {
            lookups++
            null
        }
        assertEquals(CleanGate.DeleteLocally, gate)
        assertEquals(0, lookups)
    }
}
