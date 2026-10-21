package app.taskbaba.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.ErrorText
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.components.StatusDot
import app.taskbaba.app.ui.components.StatusTone
import app.taskbaba.app.ui.components.ToggleRow
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.ai.AiModule
import app.taskbaba.core.ai.AiRoute
import app.taskbaba.core.ai.AiSettings
import app.taskbaba.core.ai.EndpointHealth
import app.taskbaba.core.ai.InferenceResult
import app.taskbaba.core.drive.DriveFile
import app.taskbaba.core.drive.FileCheck
import app.taskbaba.core.drive.ModelCatalog
import app.taskbaba.core.drive.ModelFileState
import app.taskbaba.core.model.AiMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AiScreen(module: AiModule) {
    val settings by module.settings.state.collectAsState()
    val status by module.router.status.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            "AI & Model",
            "Baba's jokes can be rewritten by your own model running in Google Colab. Without it he uses his built-in lines.",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusCard(module, status.label, when {
                    status.route == AiRoute.CLOUD -> StatusTone.OK
                    settings.mode == AiMode.BUILT_IN -> StatusTone.NEUTRAL
                    else -> StatusTone.WARN
                })
                ModeCard(module, settings)
                EndpointCard(module, settings)
                LimitsCard(module, settings)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ModelCard(module, settings)
                DriveCard(module, settings)
                NotebookCard()
            }
        }
    }
}

@Composable
private fun StatusCard(module: AiModule, label: String, tone: StatusTone) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("Status")
        StatusDot(tone, label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (checking) "Checking…" else "Check connection", enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    checkResult = when (val h = module.checkConnection()) {
                        is EndpointHealth.Healthy -> "Reachable: the health check answered in ${h.latencyMs} ms."
                        is EndpointHealth.Down -> h.message
                        EndpointHealth.NotConfigured -> "Enter a valid endpoint URL first."
                        EndpointHealth.Unknown -> "Not checked."
                    }
                    checking = false
                }
            })
            SecondaryButton(if (testing) "Asking Baba…" else "Test inference", enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    testResult = when (val r = module.testInference()) {
                        is InferenceResult.Success -> true to "“${r.text}” (${r.latencyMs} ms)"
                        is InferenceResult.Failure -> false to r.message
                    }
                    testing = false
                }
            })
        }
        checkResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }
        testResult?.let { (ok, msg) ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = if (ok) Baba.colors.text else Baba.colors.danger)
        }
        Text(
            "The test sends a fixed sample sentence with no tasks or personal data.",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
    }
}

@Composable
private fun ModeCard(module: AiModule, settings: AiSettings) {
    var pendingMode by remember { mutableStateOf<AiMode?>(null) }
    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("AI mode")
        AiMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (mode != AiMode.BUILT_IN && !settings.cloudConsentGiven) pendingMode = mode else module.setMode(mode)
                }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.mode == mode,
                    onClick = { if (mode != AiMode.BUILT_IN && !settings.cloudConsentGiven) pendingMode = mode else module.setMode(mode) },
                    colors = RadioButtonDefaults.colors(selectedColor = Baba.colors.saffron),
                )
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                    Text(mode.blurb, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                }
            }
        }
        HorizontalDivider(color = Baba.colors.cardBorder)
        SectionTitle("Privacy")
        if (settings.cloudConsentGiven) {
            ToggleRow(
                "Share task titles", settings.shareTaskTitles,
                { v -> module.settings.update { it.copy(shareTaskTitles = v) } },
                subtitle = "Off: titles are replaced with \"this task\" before anything leaves your Mac.",
            )
            ToggleRow(
                "Share task categories", settings.shareCategories,
                { v -> module.settings.update { it.copy(shareCategories = v) } },
                subtitle = "Work, Personal, Health…",
            )
            QuietButton("Revoke consent and switch to built-in", onClick = { module.revokeConsent() })
        } else {
            Text(
                "No consent given: nothing is sent to any model.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
        }
    }

    pendingMode?.let { mode ->
        var shareTitles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("Send monk lines to your Colab model?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To rewrite a reminder, Task Baba sends your Colab server:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "• the built-in line it would have shown\n• the event type (e.g. task overdue, water reminder)\n" +
                            "• small counts such as postponements or tasks done today\n• the task category, and the title only if you allow it",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Never sent: descriptions, notes, deadlines, your task list, health history, notification contents, or Drive data.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The request goes over HTTPS to the endpoint you configure (a Cloudflare quick tunnel to your Colab session). " +
                            "Google and Cloudflare operate that infrastructure.",
                        style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(shareTitles, { shareTitles = it }, colors = CheckboxDefaults.colors(checkedColor = Baba.colors.saffron))
                        Text("Also share task titles", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                PrimaryButton("I agree", onClick = {
                    module.settings.update { it.copy(shareTaskTitles = shareTitles) }
                    module.setMode(mode, consent = true)
                    pendingMode = null
                })
            },
            dismissButton = { SecondaryButton("Cancel", onClick = { pendingMode = null }) },
        )
    }
}

@Composable
private fun EndpointCard(module: AiModule, settings: AiSettings) {
    var url by remember(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(module.hasApiKey) }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    val urlError = if (url.isBlank()) null else AiSettings.validateEndpoint(url)

    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("Colab endpoint")
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() }, singleLine = true,
            label = { Text("Endpoint URL") }, placeholder = { Text("https://….trycloudflare.com") },
            isError = urlError != null, modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(listOfNotNull(urlError))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save URL", enabled = urlError == null && url.trimEnd('/') != settings.endpoint, onClick = {
                module.settings.update { it.copy(endpoint = url.trimEnd('/')) }
            })
            if (settings.endpoint.isNotEmpty()) SecondaryButton("Clear", onClick = {
                url = ""
                module.settings.update { it.copy(endpoint = "") }
            })
        }
        OutlinedTextField(
            value = key, onValueChange = { key = it }, singleLine = true,
            label = { Text(if (hasKey) "API key (saved in Keychain; enter to replace)" else "API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Save key", enabled = key.isNotBlank(), onClick = {
                module.setApiKey(key)
                    .onSuccess { key = ""; hasKey = true; keyMessage = "Saved to your Keychain." }
                    .onFailure { keyMessage = it.message ?: "Couldn't save the key." }
            })
            if (hasKey) SecondaryButton("Remove key", danger = true, onClick = {
                module.clearApiKey(); hasKey = false; keyMessage = "Key removed."
            })
        }
        keyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }
        Text(
            "Each Colab session prints a new URL and key. Quick-tunnel URLs change whenever the notebook restarts.",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
    }
}

@Composable
private fun LimitsCard(module: AiModule, settings: AiSettings) {
    val n = settings.normalized()
    var tokens by remember(n.maxTokens) { mutableFloatStateOf(n.maxTokens.toFloat()) }
    var timeout by remember(n.timeoutSeconds) { mutableFloatStateOf(n.timeoutSeconds.toFloat()) }
    var temp by remember(n.temperature) { mutableFloatStateOf(n.temperature.toFloat()) }
    val sliderColors = SliderDefaults.colors(thumbColor = Baba.colors.saffron, activeTrackColor = Baba.colors.saffron)

    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("Generation limits")
        LabeledSlider("Max reply length", "${tokens.roundToInt()} tokens") {
            Slider(tokens, { tokens = it }, valueRange = 16f..AiSettings.MAX_TOKENS_CAP.toFloat(), colors = sliderColors,
                onValueChangeFinished = { module.settings.update { it.copy(maxTokens = tokens.roundToInt()) } })
        }
        LabeledSlider("Timeout", "${timeout.roundToInt()} s") {
            Slider(timeout, { timeout = it }, valueRange = 2f..AiSettings.TIMEOUT_CAP_SECONDS.toFloat(), colors = sliderColors,
                onValueChangeFinished = { module.settings.update { it.copy(timeoutSeconds = timeout.roundToInt()) } })
        }
        LabeledSlider("Creativity", "%.1f".format(temp)) {
            Slider(temp, { temp = it }, valueRange = 0f..1.5f, colors = sliderColors,
                onValueChangeFinished = { module.settings.update { it.copy(temperature = (temp * 10).roundToInt() / 10.0) } })
        }
        Text(
            "Reminders never wait for the model. If it doesn't answer within the timeout, Baba uses his built-in line.",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
        }
        slider()
    }
}

@Composable
private fun ModelCard(module: AiModule, settings: AiSettings) {
    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("Model")
        Text(
            "The model file lives in your Google Drive and runs in Colab. Nothing is downloaded to this Mac.",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
        ModelCatalog.all.forEach { spec ->
            Row(
                Modifier.fillMaxWidth().clickable { module.drive.selectModel(spec) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = settings.modelId == spec.id, onClick = { module.drive.selectModel(spec) },
                    colors = RadioButtonDefaults.colors(selectedColor = Baba.colors.saffron),
                )
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(spec.displayName, style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                    Text("${spec.sizeLabel} · ${spec.license} · ${spec.repo}", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    Text(spec.notes, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    SelectionContainer {
                        Text(
                            "revision ${spec.revision.take(12)} · sha256 ${spec.sha256.take(16)}…",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Baba.colors.textMuted,
                        )
                    }
                }
            }
        }
        Text(
            "Pick the same model in the notebook's first cell. The model's own license applies; it is not covered by Task Baba's MIT license.",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
    }
}

@Composable
private fun DriveCard(module: AiModule, settings: AiSettings) {
    val scope = rememberCoroutineScope()
    val report by module.drive.report.collectAsState()
    val busy by module.drive.busy.collectAsState()
    var clientId by remember(settings.driveClientId) { mutableStateOf(settings.driveClientId) }
    var clientSecret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var folderQuery by remember { mutableStateOf("models") }
    var folders by remember { mutableStateOf<List<DriveFile>?>(null) }
    val connected = report.state != ModelFileState.NOT_CONNECTED && module.drive.isConnected

    LaunchedEffect(connected) { if (connected) module.drive.refresh() }

    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("Google Drive storage")
        StatusDot(
            when (report.state) {
                ModelFileState.VERIFIED -> StatusTone.OK
                ModelFileState.NOT_CONNECTED, ModelFileState.NOT_CHECKED -> StatusTone.NEUTRAL
                ModelFileState.CHECKSUM_PENDING, ModelFileState.INCOMPLETE, ModelFileState.FOLDER_NOT_SELECTED, ModelFileState.MODEL_MISSING -> StatusTone.WARN
                else -> StatusTone.ERROR
            },
            if (busy) "Working…" else report.state.label,
        )
        report.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }

        if (!module.drive.isConnected) {
            Text(
                "Optional: connect Drive so Task Baba can confirm the model the notebook stored is complete and untampered. " +
                    "It uses read-only metadata access (drive.metadata.readonly) and never opens file contents.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
            Text(
                "Setup: in Google Cloud Console create a project, enable the Drive API, configure the OAuth consent screen " +
                    "(External, add yourself as a test user) and create an OAuth client ID of type \"Desktop app\". Paste it below.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
            OutlinedTextField(clientId, { clientId = it.trim() }, singleLine = true, label = { Text("OAuth client ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                clientSecret, { clientSecret = it.trim() }, singleLine = true,
                label = { Text("Client secret (if Google issued one; stored in Keychain)") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(if (busy) "Waiting for browser…" else "Connect Google Drive", enabled = !busy && clientId.isNotBlank(), onClick = {
                error = null
                scope.launch {
                    module.drive.connect(clientId, clientSecret).onFailure { error = it.message }
                    clientSecret = ""
                }
            })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Folder: ${settings.driveFolderName ?: "not chosen"}",
                    style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.weight(1f),
                )
                SecondaryButton("Verify again", enabled = !busy, onClick = { scope.launch { module.drive.refresh().onFailure { error = it.message } } })
                SecondaryButton("Disconnect", danger = true, onClick = { scope.launch { module.drive.disconnect(); folders = null } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(folderQuery, { folderQuery = it }, singleLine = true, label = { Text("Find folder by name") }, modifier = Modifier.weight(1f))
                SecondaryButton("Search", onClick = {
                    scope.launch { module.drive.listFolders(folderQuery).onSuccess { folders = it }.onFailure { error = it.message } }
                })
            }
            folders?.let { list ->
                if (list.isEmpty()) Text("No folders found. The notebook creates MyDrive/TaskBaba/models.", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                list.take(12).forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.weight(1f))
                        f.modifiedTime?.let { Text(it.take(10), style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }
                        Spacer(Modifier.width(8.dp))
                        QuietButton(if (settings.driveFolderId == f.id) "Selected" else "Use", enabled = settings.driveFolderId != f.id, onClick = {
                            scope.launch { module.drive.selectFolder(f).onFailure { error = it.message }; folders = null }
                        })
                    }
                }
            }
            val rows = listOfNotNull(report.active) + report.backups + report.otherModels
            if (rows.isNotEmpty()) {
                HorizontalDivider(color = Baba.colors.cardBorder)
                rows.forEach { FileRow(it) }
            }
        }
        ErrorText(listOfNotNull(error))
    }
}

@Composable
private fun FileRow(check: FileCheck) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (check.isBackup) "backup/" else "") + check.spec.fileName,
                style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text,
            )
            val size = check.file?.sizeBytes?.let { "%.2f GB".format(it / 1e9) } ?: "—"
            Text(
                "${check.spec.displayName} · rev ${check.spec.revision.take(7)} · ${check.spec.license} · $size",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
        }
        StatusDot(
            when (check.state) {
                ModelFileState.VERIFIED -> StatusTone.OK
                ModelFileState.CHECKSUM_PENDING, ModelFileState.INCOMPLETE, ModelFileState.MODEL_MISSING -> StatusTone.WARN
                else -> StatusTone.ERROR
            },
            check.state.label, Modifier.widthIn(max = 220.dp),
        )
    }
}

@Composable
private fun NotebookCard() {
    BabaCard(Modifier.fillMaxWidth()) {
        SectionTitle("Running the model in Colab")
        Text(
            "1. Open cloud/colab/TaskBaba_Colab_Server.ipynb from this project in Google Colab and choose a GPU runtime (T4 is enough).\n" +
                "2. Run the cells in order. The first run downloads the model into MyDrive/TaskBaba/models and checks its SHA-256; later runs reuse it.\n" +
                "3. The last cell prints an https URL and an API key. Paste both into the Colab endpoint card, then press Check connection.\n" +
                "4. When the Colab session ends (idle timeout or ~12 h limit), Baba quietly returns to his built-in lines until you run it again.",
            style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text,
        )
        Text(
            "Google Drive only stores the file; it cannot run a model. Colab does the computing while the notebook is open.",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
    }
}
