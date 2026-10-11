package com.pedronveloso.logviewer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp

@Composable
internal fun StatusContent(
  capabilities: LogCapabilities,
  health: LogHealth,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Text(
        when {
          !capabilities.hasInMemoryLogs && !capabilities.persistsAcrossCrashes -> "No log source"
          capabilities.isLibraryCapture -> "Library capture"
          else -> "App-provided source"
        },
        style = MaterialTheme.typography.titleMedium,
      )
    }
    items(statusMessages(capabilities, health)) { message -> StatusCard(message) }
    health.lastDiskWriteMillis?.let { last ->
      item {
        Text(
          "Last disk write: ${formatSessionDate(last)}",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun StatusCard(message: StatusMessage) {
  val statusColor =
    if (message.isProblem) {
      if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFF8F5A00)
      else Color(0xFFFFC46B)
    } else MaterialTheme.colorScheme.primary
  Card(
    modifier =
      Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        stateDescription = if (message.isProblem) "Action needed" else "Healthy"
      },
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector =
            if (message.isProblem) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
          contentDescription = null,
          tint = statusColor,
        )
        Spacer(Modifier.width(8.dp))
        Text(message.title, color = statusColor, style = MaterialTheme.typography.titleSmall)
      }
      Text(
        message.detail,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 32.dp, top = 4.dp),
      )
    }
  }
}

@Composable
private fun StatusPreviewTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
  ) {
    Surface(color = MaterialTheme.colorScheme.background) { content() }
  }
}

@PreviewLightDark
@Composable
private fun HealthyStatusPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health = LogHealth(installed = true, lastDiskWriteMillis = 1_700_000_000_000),
      modifier = Modifier.height(500.dp),
    )
  }
}

@PreviewLightDark
@Composable
private fun ConfigurationWarningsPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, false, false, false, isLibraryCapture = true),
      health = LogHealth(installed = false),
      modifier = Modifier.height(650.dp),
    )
  }
}

@PreviewLightDark
@Composable
private fun FailureStatusPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health =
        LogHealth(
          installed = true,
          readError = "Demo read failure",
          writeError = "Demo write failure",
        ),
      modifier = Modifier.height(500.dp),
    )
  }
}

@PreviewLightDark
@Composable
private fun ExternalSourcePreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true),
      health = LogHealth(),
      modifier = Modifier.height(500.dp),
    )
  }
}
