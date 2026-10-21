package app.taskbaba.core.integrations

import app.taskbaba.core.data.KvStore
import app.taskbaba.core.data.PersistedState
import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.platform.ClockProvider
import app.taskbaba.core.platform.Notifier
import app.taskbaba.core.platform.SecretStore
import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.CoroutineScope

/** Service endpoints, overridable so tests can point adapters at local mock servers. */
data class IntegrationEndpoints(
    val slackApi: String = "https://slack.com/api",
    val microsoftLogin: String = "https://login.microsoftonline.com",
    val microsoftGraph: String = "https://graph.microsoft.com/v1.0",
)

class IntegrationsModule(
    db: TaskBabaDatabase,
    kv: KvStore,
    private val secrets: SecretStore,
    notifier: Notifier,
    taskCreator: FollowUpTaskCreator,
    settingsProvider: () -> AppSettings,
    scope: CoroutineScope,
    val openBrowser: (String) -> Unit,
    clock: ClockProvider = ClockProvider.SYSTEM,
    endpoints: IntegrationEndpoints = IntegrationEndpoints(),
    http: HttpSupport = HttpSupport(),
) {
    val settings: PersistedState<IntegrationSettings> = kv.state(SETTINGS_KEY, IntegrationSettings.serializer()) { IntegrationSettings() }

    private val configAccess = object : SourceConfigAccess {
        override fun read(sourceId: String) = settings.value.sourceConfig[sourceId].orEmpty()
        override fun write(sourceId: String, values: Map<String, String>?) {
            settings.update { s ->
                s.copy(sourceConfig = if (values == null) s.sourceConfig - sourceId else s.sourceConfig + (sourceId to values))
            }
        }
    }

    private val now = { clock.clock().instant() }

    val jira = JiraSource(secrets, configAccess, http, now)
    val slack = SlackSource(secrets, configAccess, http, endpoints.slackApi, now)
    val microsoft = GraphSource(secrets, configAccess, http, endpoints.microsoftLogin, endpoints.microsoftGraph, now)

    /** Real, supported adapters. */
    val sources: List<NotificationSource> = listOf(microsoft, slack, jira)

    /** Sources that are listed with an honest explanation of why they aren't supported. */
    val unsupported: List<UnsupportedSource> = UnsupportedSource.all()

    val inbox = InboxService(db, settings, sources, notifier, taskCreator, settingsProvider, scope, openBrowser, clock)

    fun start() = inbox.start()

    /** Disconnects every source and deletes all stored notification data. */
    suspend fun disconnectAll(deleteData: Boolean) {
        sources.forEach { it.disconnect() }
        if (deleteData) inbox.deleteAll()
    }

    companion object { const val SETTINGS_KEY = "integrations" }
}
