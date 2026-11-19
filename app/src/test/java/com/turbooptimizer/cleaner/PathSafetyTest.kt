package com.turbooptimizer.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fix 2: la validación de ruta se aplica a todo elemento. Las cachés propias son la única excepción
 * y dependen de la RUTA real (no de la categoría que declara quien llama).
 */
class PathSafetyTest {

    private val root = "/storage/emulated/0"
    private val appCache = "/data/user/0/com.turbooptimizer/cache"

    private fun reason(path: String, ownCaches: List<String> = listOf(appCache)) =
        PathSafety.deletableReason(path, ownCaches, root)

    @Test fun appCacheDirectoryItselfIsAllowed() {
        assertNull(reason(appCache))
    }

    @Test fun pathInsideAppCacheIsAllowed() {
        assertNull(reason("$appCache/thumbs/x.tmp"))
    }

    @Test fun siblingDirectorySharingCachePrefixIsNotAppCache() {
        assertNotNull(reason("$appCache-backup/x"))
    }

    @Test fun appCacheItemWithoutOwnCacheRegisteredIsBlocked() {
        // Un elemento de caché no puede saltarse la validación: sin caché propia reconocida, se bloquea.
        assertEquals("Fuera del almacenamiento compartido", reason(appCache, ownCaches = emptyList()))
    }

    @Test fun pathOutsideSharedStorageIsBlocked() {
        assertEquals("Fuera del almacenamiento compartido", reason("/data/data/other.app/files/x"))
    }

    @Test fun storageRootIsBlocked() {
        assertEquals("Raíz del almacenamiento", reason(root))
    }

    @Test fun androidDataAndObbAreBlocked() {
        assertNotNull(reason("$root/Android/data/com.example/files/save.dat"))
        assertNotNull(reason("$root/Android/obb/com.example/main.obb"))
    }

    @Test fun standardTopLevelFoldersAreBlocked() {
        assertNotNull(reason("$root/DCIM"))
        assertNotNull(reason("$root/Download"))
        assertNotNull(reason("$root/Pictures"))
    }

    @Test fun nestedStandardFolderIsBlocked() {
        assertNotNull(reason("$root/DCIM/Camera"))
    }

    @Test fun fileInsideDownloadsIsAllowedAsItemForReview() {
        assertNull(reason("$root/Download/old-installer.zip"))
    }

    @Test fun ordinaryFolderIsAllowed() {
        assertNull(reason("$root/Projects/old-build"))
    }
}
