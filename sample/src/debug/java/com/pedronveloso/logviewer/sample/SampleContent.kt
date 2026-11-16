package com.pedronveloso.logviewer.sample

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedronveloso.logviewer.LogViewer
import timber.log.Timber

@Composable
fun SampleContent(modifier: Modifier = Modifier) {
  var showViewer by rememberSaveable { mutableStateOf(false) }
  var count by remember { mutableIntStateOf(0) }
  var scenario by rememberSaveable { mutableStateOf(StatusDemoScenario.REAL) }
  var settings by
    rememberSaveable(stateSaver = SampleViewerSettings.Saver) {
      mutableStateOf(SampleViewerSettings())
    }
  val warnings by SampleApplication.capture.warnings.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val localSendIcon = rememberLocalSendIcon()
  val viewerOptions =
    remember(context, settings, localSendIcon) {
      sampleViewerOptions(context, settings, localSendIcon)
    }
  val returnHome = { showViewer = false }
  BackHandler(enabled = showViewer, onBack = returnHome)
  if (showViewer) {
    LogViewer(
      source = remember(scenario) { scenario.source(SampleApplication.capture) },
      onBack = returnHome,
      modifier = modifier,
      // Demo scenarios wrap the capture, so hand its exit history over explicitly.
      processExits = SampleApplication.capture.processExits.takeIf { settings.showProcessExits },
      options = viewerOptions,
    )
  } else {
    SampleHome(
      scenario = scenario,
      onScenarioChange = { scenario = it },
      onGenerateLogs = {
        val batch = ++count
        Timber.tag("Sample").d("Generated message %d access_token=sample-secret", batch)
        Timber.tag("Sample").w("Generated warning %d: slow response", batch)
        Timber.tag("Sample\$generateLogs")
          .e(
            IllegalStateException("Request $batch failed"),
            "Generated error %d: request failed",
            batch,
          )
      },
      onCrash = {
        Timber.tag("Sample").w("About to crash; this entry should survive restart")
        error("Intentional sample crash")
      },
      onOpenLogs = { showViewer = true },
      modifier = modifier,
      warningCount = warnings.totalCount,
      settings = settings,
      onSettingsChange = { settings = it },
      localSendAvailable = localSendIcon != null,
    )
  }
}

@Composable
private fun SampleHome(
  scenario: StatusDemoScenario,
  onScenarioChange: (StatusDemoScenario) -> Unit,
  onGenerateLogs: () -> Unit,
  onCrash: () -> Unit,
  onOpenLogs: () -> Unit,
  modifier: Modifier = Modifier,
  warningCount: Int = 0,
  settings: SampleViewerSettings = SampleViewerSettings(),
  onSettingsChange: (SampleViewerSettings) -> Unit = {},
  localSendAvailable: Boolean = false,
) {
  var scenarioMenu by remember { mutableStateOf(false) }
  Column(
    modifier
      .fillMaxSize()
      .safeDrawingPadding()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Log viewer sample", style = MaterialTheme.typography.titleLarge)
    Button(onClick = onGenerateLogs) { Text("Generate logs") }
    Box {
      TextButton(onClick = { scenarioMenu = true }) { Text("Status demo: ${scenario.label} ▾") }
      DropdownMenu(expanded = scenarioMenu, onDismissRequest = { scenarioMenu = false }) {
        StatusDemoScenario.entries.forEach { option ->
          DropdownMenuItem(
            text = { Text(option.label) },
            onClick = {
              onScenarioChange(option)
              scenarioMenu = false
            },
          )
        }
      }
    }
    if (scenario != StatusDemoScenario.REAL) {
      Text(
        "This Status scenario is simulated. Your captured logs and settings are unchanged.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Text("Log screen options", style = MaterialTheme.typography.titleMedium)
    Column {
      ViewerOptionToggle(
        title = "Show Share button",
        checked = settings.showShareButton,
        onCheckedChange = { onSettingsChange(settings.copy(showShareButton = it)) },
      )
      ViewerOptionToggle(
        title = "Show Clear logs button",
        checked = settings.showClearButton,
        onCheckedChange = { onSettingsChange(settings.copy(showClearButton = it)) },
      )
      ViewerOptionToggle(
        title = "Show LocalSend button",
        checked = settings.showLocalSendButton,
        onCheckedChange = { onSettingsChange(settings.copy(showLocalSendButton = it)) },
        enabled = localSendAvailable,
        supportingText =
          if (localSendAvailable) null else "Install LocalSend to enable this action.",
      )
      ViewerOptionToggle(
        title = "Show process exit history",
        checked = settings.showProcessExits,
        onCheckedChange = { onSettingsChange(settings.copy(showProcessExits = it)) },
      )
    }
    Button(onClick = onOpenLogs) {
      Text(if (warningCount > 0) "Open logs ($warningCount warnings)" else "Open logs")
    }
    Button(onClick = onCrash) { Text("Crash app to test recovery") }
    Text("After a crash, reopen the app and choose the previous session in Logs.")
  }
}

@Composable
private fun ViewerOptionToggle(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  supportingText: String? = null,
) {
  Row(
    modifier
      .fillMaxWidth()
      .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onCheckedChange,
      )
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        title,
        color =
          if (enabled) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (supportingText != null) {
        Text(
          supportingText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}

@PreviewLightDark
@Composable
private fun RealCaptureSamplePreview() {
  SampleTheme {
    SampleHome(StatusDemoScenario.REAL, {}, {}, {}, {}, Modifier.height(600.dp))
  }
}

@PreviewLightDark
@Composable
private fun SimulatedStatusSamplePreview() {
  SampleTheme {
    SampleHome(StatusDemoScenario.WRITE_FAILURE, {}, {}, {}, {}, Modifier.height(600.dp))
  }
}
