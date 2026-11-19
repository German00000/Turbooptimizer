package com.turbooptimizer.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Fix 3: si el recorrido alcanza la profundidad máxima o el límite de entradas, el directorio se trata
 * como potencialmente personal (bloquea el borrado recursivo). Solo usa directorios temporales.
 */
class DirectoryInspectionTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, content: String = "x"): File =
        File(tmp.root, path).apply { parentFile.mkdirs(); writeText(content) }

    private fun dir(path: String): File = File(tmp.root, path).apply { mkdirs() }

    @Test fun cleanTreeIsCompleteNotPersonalAndMeasured() {
        file("a/notes/readme", "abc")
        file("a/b/data", "de")
        val i = PathSafety.inspectDirectory(tmp.root)
        assertTrue(i.complete)
        assertFalse(i.personal)
        assertEquals(2, i.files)
        assertEquals(5L, i.size)
    }

    @Test fun personalFileAtDepthWithinLimitIsDetected() {
        file("a/b/c/photo.jpg")
        assertTrue(PathSafety.containsPersonal(tmp.root))
    }

    @Test fun personalFileAtExactlyTheDepthLimitIsDetected() {
        file("a/b/photo.jpg")
        assertTrue(PathSafety.containsPersonal(tmp.root, maxDepth = 2))
    }

    @Test fun depthLimitReachedWithContentIsTreatedAsPersonal() {
        // a (1) → b (2) → c (3): c supera maxDepth=2 y tiene contenido que no se puede verificar.
        file("a/b/c/readme")
        val i = PathSafety.inspectDirectory(tmp.root, maxDepth = 2)
        assertFalse(i.complete)
        assertTrue(i.personal)
        assertTrue(PathSafety.containsPersonal(tmp.root, maxDepth = 2))
    }

    @Test fun emptyDirectoryBeyondDepthLimitIsNotTreatedAsIncomplete() {
        dir("a/b/c")
        val i = PathSafety.inspectDirectory(tmp.root, maxDepth = 2)
        assertTrue(i.complete)
        assertFalse(i.personal)
    }

    @Test fun exceedingEntryLimitIsTreatedAsPersonal() {
        for (n in 1..6) file("d/f$n.log")
        val i = PathSafety.inspectDirectory(tmp.root, maxEntries = 5)
        assertFalse(i.complete)
        assertTrue(i.personal)
    }

    @Test fun exactlyAtEntryLimitIsStillComplete() {
        for (n in 1..5) file("f$n.log")
        val i = PathSafety.inspectDirectory(tmp.root, maxEntries = 5)
        assertTrue(i.complete)
        assertFalse(i.personal)
        assertEquals(5, i.files)
    }

    @Test fun missingDirectoryIsTreatedAsPersonal() {
        val i = PathSafety.inspectDirectory(File(tmp.root, "no-existe"))
        assertFalse(i.complete)
        assertTrue(i.personal)
    }

    @Test fun singlePersonalFileIsPersonalAndComplete() {
        val i = PathSafety.inspectDirectory(file("solo.jpg"))
        assertTrue(i.complete)
        assertTrue(i.personal)
    }

    @Test fun symlinkLoopTerminatesAndIsTreatedAsIncomplete() {
        file("a/x.log")
        Files.createSymbolicLink(File(tmp.root, "a/loop").toPath(), tmp.root.toPath())
        val i = PathSafety.inspectDirectory(tmp.root, maxDepth = 3)
        assertFalse(i.complete)
        assertTrue(i.personal)
    }

    @Test fun defaultLimitsAreTheDocumentedOnes() {
        assertEquals(50_000, PathSafety.MAX_SCAN_ENTRIES)
        assertEquals(16, PathSafety.MAX_SCAN_DEPTH)
    }
}
