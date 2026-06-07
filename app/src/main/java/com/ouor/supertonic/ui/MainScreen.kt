package com.ouor.supertonic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (state.stage) {
                MainViewModel.Stage.READY -> ReadyContent(state, vm)
                MainViewModel.Stage.LOADING -> CenteredStatus(loading = true, text = "Loading models…")
                MainViewModel.Stage.CHECKING -> CenteredStatus(loading = true, text = "Checking…")
                // Download / error states render as a modal dialog over an empty background.
                else -> Unit
            }
        }
    }

    // First-run model download is shown as a (non-dismissable) modal.
    when (state.stage) {
        MainViewModel.Stage.NEEDS_DOWNLOAD -> DownloadDialog(onDownload = vm::startDownload)

        MainViewModel.Stage.DOWNLOADING -> DownloadingDialog(
            file = state.downloadingFile,
            downloaded = state.downloadedBytes,
            total = state.totalBytes,
        )

        MainViewModel.Stage.ERROR -> ErrorDialog(
            message = state.errorMessage ?: "Something went wrong",
            onRetry = vm::startDownload,
        )

        else -> Unit
    }
}

// --- non-ready states -------------------------------------------------- //

@Composable
private fun CenteredStatus(loading: Boolean, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private val NonDismissable = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

@Composable
private fun DownloadDialog(onDownload: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = NonDismissable,
        icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
        title = { Text("Get the voice models") },
        text = {
            Text("About 398 MB is downloaded once and stored on your device for fully offline synthesis.")
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("Download") }
        },
    )
}

@Composable
private fun DownloadingDialog(file: String, downloaded: Long, total: Long) {
    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    AlertDialog(
        onDismissRequest = {},
        properties = NonDismissable,
        title = { Text("Downloading models…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(fraction * 100).roundToInt()}%  •  ${mb(downloaded)} / ${mb(total)} MB",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    file,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ErrorDialog(message: String, onRetry: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = NonDismissable,
        title = { Text("Download failed") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text("Retry") }
        },
    )
}

// --- ready content ----------------------------------------------------- //

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(state: MainViewModel.UiState, vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Text input with a clear-all button at the bottom-right.
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.text,
                onValueChange = vm::onTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Text") },
                minLines = 6,
                placeholder = { Text("Type something to speak…") },
            )
            if (state.text.isNotEmpty()) {
                IconButton(
                    onClick = { vm.onTextChange("") },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear text")
                }
            }
        }

        // Voice · Save · Play on one row
        val saveLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(state.format.mimeType)
        ) { uri -> uri?.let(vm::save) }
        val busyPlaying = state.isBusy && !state.isPlaying

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledDropdown(
                label = "Voice",
                options = state.voices,
                selected = state.voiceName,
                optionLabel = { it },
                onSelect = vm::onVoiceChange,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { saveLauncher.launch(state.format.fileName(vm.suggestedBaseName())) },
                enabled = !state.isBusy,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = "Save (${state.format.label})")
            }
            FilledIconButton(
                onClick = { if (state.isPlaying) vm.stopPlayback() else vm.playPreview() },
                enabled = !busyPlaying,
                modifier = Modifier.size(56.dp),
            ) {
                when {
                    busyPlaying -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    state.isPlaying -> Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
            }
        }

        // Advanced settings (collapsible)
        ExpandableCard("추가 설정", Icons.Outlined.Tune) {
            LabeledDropdown(
                label = "Output device",
                options = listOf<Int?>(null) + state.devices.map { it.id },
                selected = state.selectedDeviceId,
                optionLabel = { id -> deviceLabel(state, id) },
                onSelect = vm::onDeviceChange,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledDropdown(
                label = "Save format",
                options = state.formats,
                selected = state.format,
                optionLabel = { it.label },
                onSelect = vm::onFormatChange,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledDropdown(
                label = "Language",
                options = state.languages,
                selected = state.lang,
                optionLabel = { langLabel(it) },
                onSelect = vm::onLangChange,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderRow(
                label = "Quality",
                valueText = "${state.totalStep} steps",
                value = state.totalStep.toFloat(),
                valueRange = 5f..12f,
                steps = 6,
                onChange = { vm.onStepChange(it.roundToInt()) },
            )
            SliderRow(
                label = "Speed",
                valueText = "${"%.2f".format(state.speed)}×",
                value = state.speed,
                valueRange = 0.7f..2.0f,
                steps = 0,
                onChange = vm::onSpeedChange,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

// --- building blocks --------------------------------------------------- //

@Composable
private fun ExpandableCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon, contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = valueRange, steps = steps)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Show the language-agnostic code "na" as "자동"; other codes as-is. */
private fun langLabel(code: String): String = if (code == "na") "자동" else code

/** Friendly output-device label, avoiding redundant "Earpiece · Earpiece" for built-ins. */
private fun deviceLabel(state: MainViewModel.UiState, id: Int?): String {
    if (id == null) return "System default"
    val d = state.devices.firstOrNull { it.id == id } ?: return "Unknown"
    return if (d.name == d.typeLabel) d.name else "${d.name} · ${d.typeLabel}"
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_000_000.0)
