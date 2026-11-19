package com.turbooptimizer.cleaner

/** Decisión sobre un elemento ANTES de tocar el sistema de archivos. */
sealed interface CleanGate<out T> {
    /** No se hace nada con el elemento; [reason] se muestra al usuario. */
    data class Skip(val reason: String) : CleanGate<Nothing>

    /** Foto, vídeo o audio: se envía a la papelera del sistema mediante [uri]. */
    data class SendToTrash<out T>(val uri: T) : CleanGate<T>

    /** Elemento no multimedia y permitido: puede eliminarse localmente. */
    data object DeleteLocally : CleanGate<Nothing>
}

/** Reglas puras de seguridad del borrado (sin dependencias de Android). */
object CleanGates {

    /**
     * Reglas, en este orden:
     *  1. Si la ruta está bloqueada ([blockedReason] != null) → siempre Skip. Aplica a todas las categorías.
     *  2. Foto/vídeo/audio → nunca DeleteLocally. Solo SendToTrash si [lookupTrashUri] devuelve un URI válido;
     *     si no, Skip (no se borra).
     *  3. Resto de archivos permitidos → DeleteLocally.
     *
     * @param blockedReason resultado de [PathSafety.checkDeletable] (null = permitido)
     * @param isMediaFile el elemento es un archivo multimedia
     * @param lookupTrashUri consulta MediaStore; solo se invoca si el elemento está permitido y es multimedia
     */
    fun <T : Any> decide(
        blockedReason: String?,
        isMediaFile: Boolean,
        lookupTrashUri: () -> T?,
    ): CleanGate<T> = when {
        blockedReason != null -> CleanGate.Skip(blockedReason)
        isMediaFile -> {
            val uri = lookupTrashUri()
            if (uri != null) CleanGate.SendToTrash(uri)
            else CleanGate.Skip("no está indexado en la galería, no se puede enviar a la papelera; no se ha borrado")
        }
        else -> CleanGate.DeleteLocally
    }
}
