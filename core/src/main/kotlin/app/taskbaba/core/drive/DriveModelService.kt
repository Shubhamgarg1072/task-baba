package app.taskbaba.core.drive

import app.taskbaba.core.ai.AiSettings
import app.taskbaba.core.data.PersistedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing Drive operations: connect, pick the model folder, verify. Storage only, never inference. */
class DriveModelService(
    private val settings: PersistedState<AiSettings>,
    private val auth: GoogleOAuth,
    private val client: DriveClient,
    private val openBrowser: (String) -> Unit,
) {
    private val _report = MutableStateFlow(initialReport())
    val report: StateFlow<DriveModelReport> = _report.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val isConnected: Boolean get() = auth.isConnected

    suspend fun connect(clientId: String, clientSecret: String?): Result<Unit> = guarded {
        auth.authorize(clientId.trim(), clientSecret?.trim()?.takeIf { it.isNotEmpty() }, openBrowser)
        settings.update { it.copy(driveClientId = clientId.trim()) }
        refreshInternal()
    }

    suspend fun disconnect() {
        auth.disconnect()
        settings.update { it.copy(driveFolderId = null, driveFolderName = null) }
        _report.value = DriveModelReport(ModelFileState.NOT_CONNECTED)
    }

    suspend fun listFolders(nameContains: String? = null): Result<List<DriveFile>> = try {
        Result.success(client.listFolders(nameContains))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        handleAuthError(e)
        Result.failure(e)
    }

    suspend fun selectFolder(folder: DriveFile): Result<Unit> = guarded {
        settings.update { it.copy(driveFolderId = folder.id, driveFolderName = folder.name) }
        refreshInternal()
    }

    fun selectModel(spec: ModelSpec) {
        settings.update { it.copy(modelId = spec.id) }
    }

    suspend fun refresh(): Result<Unit> = guarded { refreshInternal() }

    private suspend fun refreshInternal() {
        if (!auth.isConnected) { _report.value = DriveModelReport(ModelFileState.NOT_CONNECTED); return }
        val s = settings.value
        val folderId = s.driveFolderId ?: run { _report.value = DriveModelReport(ModelFileState.FOLDER_NOT_SELECTED); return }
        val folder = client.getFile(folderId)
        if (folder == null || folder.trashed) { _report.value = DriveModelReport(ModelFileState.FOLDER_MISSING); return }
        val children = client.listChildren(folderId)
        val backupFolder = children.firstOrNull { it.isFolder && it.name == "backup" }
        val backups = backupFolder?.let { client.listChildren(it.id) }.orEmpty()
        _report.value = DriveModelVerifier.report(ModelCatalog.byId(s.modelId), children, backups)
    }

    private suspend fun guarded(block: suspend () -> Unit): Result<Unit> {
        _busy.value = true
        return try {
            block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleAuthError(e)
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    private fun handleAuthError(e: Exception) {
        _report.value = when {
            e is DriveAuthException && (e.reason == DriveAuthException.Reason.EXPIRED || e.reason == DriveAuthException.Reason.NOT_CONNECTED) ->
                DriveModelReport(if (auth.isConnected) ModelFileState.AUTH_EXPIRED else ModelFileState.NOT_CONNECTED, message = e.message)
            e is DriveAuthException && e.reason == DriveAuthException.Reason.DENIED ->
                DriveModelReport(ModelFileState.NOT_CONNECTED, message = e.message)
            else -> _report.value.copy(state = if (_report.value.state == ModelFileState.NOT_CONNECTED) ModelFileState.NOT_CONNECTED else ModelFileState.ERROR, message = e.message)
        }
    }

    private fun initialReport() = when {
        !auth.isConnected -> DriveModelReport(ModelFileState.NOT_CONNECTED)
        settings.value.driveFolderId == null -> DriveModelReport(ModelFileState.FOLDER_NOT_SELECTED)
        else -> DriveModelReport(ModelFileState.NOT_CHECKED)
    }
}
