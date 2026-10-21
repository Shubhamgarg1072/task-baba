package app.taskbaba.core.drive

enum class ModelFileState(val label: String) {
    NOT_CONNECTED("Google Drive not connected"),
    NOT_CHECKED("Not checked yet"),
    FOLDER_NOT_SELECTED("Choose the Drive folder the notebook uses"),
    FOLDER_MISSING("Folder not found (deleted or no access)"),
    MODEL_MISSING("Model not in Drive yet; run the Colab notebook"),
    INCOMPLETE("Upload incomplete (size doesn't match)"),
    CHECKSUM_PENDING("Size matches; Drive hasn't computed the SHA-256 yet"),
    CHECKSUM_MISMATCH("Checksum mismatch: file is corrupt or a different build"),
    VERIFIED("Verified"),
    AUTH_EXPIRED("Google sign-in expired; connect again"),
    ERROR("Couldn't check Google Drive"),
}

data class FileCheck(
    val spec: ModelSpec,
    val file: DriveFile?,
    val state: ModelFileState,
    val isBackup: Boolean,
)

data class DriveModelReport(
    val state: ModelFileState,
    val active: FileCheck? = null,
    val backups: List<FileCheck> = emptyList(),
    /** An in-progress `.part` download written by the notebook. */
    val partialFile: DriveFile? = null,
    val otherModels: List<FileCheck> = emptyList(),
    val message: String? = null,
)

/**
 * Pure verification of Drive metadata against the pinned catalog. Expected layout (written by the
 * Colab notebook): `<folder>/<model>.gguf` (active), `<folder>/backup/<model>.gguf` (rollback copy)
 * and `<folder>/<model>.gguf.part` while a download is in progress.
 */
object DriveModelVerifier {

    fun check(spec: ModelSpec, file: DriveFile?, isBackup: Boolean = false): FileCheck {
        val state = when {
            file == null || file.trashed -> ModelFileState.MODEL_MISSING
            file.sizeBytes != spec.sizeBytes -> ModelFileState.INCOMPLETE
            file.sha256Checksum.isNullOrBlank() -> ModelFileState.CHECKSUM_PENDING
            !file.sha256Checksum.equals(spec.sha256, ignoreCase = true) -> ModelFileState.CHECKSUM_MISMATCH
            else -> ModelFileState.VERIFIED
        }
        return FileCheck(spec, file, state, isBackup)
    }

    fun report(selected: ModelSpec, folderFiles: List<DriveFile>, backupFiles: List<DriveFile>): DriveModelReport {
        val visible = folderFiles.filterNot { it.trashed }
        val active = check(selected, visible.firstOrNull { it.name == selected.fileName })
        val partial = visible.firstOrNull { it.name == selected.fileName + ".part" }
        val backups = backupFiles.filterNot { it.trashed }.mapNotNull { f ->
            ModelCatalog.byFileName(f.name)?.let { check(it, f, isBackup = true) }
        }
        val others = visible.filter { it.name != selected.fileName }.mapNotNull { f ->
            ModelCatalog.byFileName(f.name)?.let { check(it, f) }
        }
        val message = when {
            active.state == ModelFileState.MODEL_MISSING && partial != null ->
                "A download is in progress in Colab (${partial.sizeBytes ?: 0} of ${selected.sizeBytes} bytes)."
            active.state == ModelFileState.CHECKSUM_MISMATCH && backups.any { it.state == ModelFileState.VERIFIED } ->
                "A verified backup exists. Use the notebook's \"Restore previous model\" cell."
            else -> null
        }
        return DriveModelReport(active.state, active, backups, partial, others, message)
    }
}
