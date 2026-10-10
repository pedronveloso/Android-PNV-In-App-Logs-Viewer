package com.pedronveloso.logviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LogRow(entry: LogEntry, previousTimestamp: Long?, onClick: () -> Unit) {
  val level = LogLevel.fromPriority(entry.priority)
  Column(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
      .clickable(onClickLabel = "Show log details", onClick = onClick)
      .testTag("log_entry_${entry.id}")
      .padding(10.dp)
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Text(
        level.shortLabel,
        color = levelColor(level),
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.width(8.dp))
      Text(
        styledTag(entry.tag.orEmpty()),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (entry.throwableStackTrace != null) {
        Icon(
          Icons.Outlined.ErrorOutline,
          contentDescription = "Has exception details",
          tint = levelColor(level),
          modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(formatTime(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
        formatElapsedTime(previousTimestamp, entry.timestampMillis)?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    if (entry.message.isNotEmpty()) {
      Text(
        entry.message,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

internal fun styledTag(tag: String): AnnotatedString {
  val divider = tag.lastIndexOf('$')
  if (divider <= 0 || divider == tag.lastIndex) return AnnotatedString(tag)
  return buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tag.substring(0, divider)) }
    append("$\n")
    append(tag.substring(divider + 1))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogDetailsSheet(entry: LogEntry, onDismiss: () -> Unit, onFilterLikeThis: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("log_details_sheet")) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Log details", style = MaterialTheme.typography.titleLarge)
      Text(
        styledTag(entry.tag ?: "(no tag)"),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        "${LogLevel.fromPriority(entry.priority).accessibilityName} · ${formatTime(entry.timestampMillis)}",
        style = MaterialTheme.typography.labelMedium,
      )
      TextButton(
        onClick = onFilterLikeThis,
        enabled = !entry.tag.isNullOrBlank(),
        modifier = Modifier.testTag("filter_logs_like_this"),
      ) {
        Text("Filter logs like this")
      }
      if (entry.message.isNotEmpty()) {
        Text(
          entry.message,
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
      }
      entry.throwableStackTrace?.let {
        Text("Throwable", style = MaterialTheme.typography.titleSmall)
        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
      }
    }
  }
}

@Composable
@ReadOnlyComposable
internal fun levelColor(level: LogLevel): Color =
  when (level) {
    LogLevel.VERBOSE,
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.WARN ->
      if (MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f) Color(0xFF8F4700)
      else Color(0xFFFFB74D)
    LogLevel.ERROR ->
      if (MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f) Color(0xFFB00020)
      else Color(0xFFFF9E9E)
    LogLevel.ASSERT -> MaterialTheme.colorScheme.error
  }

internal val LogLevel.accessibilityName: String
  get() =
    if (this == LogLevel.WARN) "Warning" else name.lowercase().replaceFirstChar(Char::uppercaseChar)

internal fun cappedCount(count: Int): String = if (count > 99) "99+" else count.toString()

private fun formatTime(timestamp: Long) =
  SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

@PreviewLightDark
@Composable
private fun WarningLogRowPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).padding(12.dp)) {
      LogRow(
        entry =
          LogEntry(
            1,
            "preview",
            1_700_000_000_000L,
            LogLevel.WARN.priority,
            "Network",
            "Slow response from the service",
          ),
        previousTimestamp = null,
        onClick = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun ErrorLogRowPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).padding(12.dp)) {
      LogRow(
        entry =
          LogEntry(
            2,
            "preview",
            1_700_000_000_000L,
            LogLevel.ERROR.priority,
            "Network\$request",
            "Request failed after three attempts.\nThe endpoint did not respond.\nCheck the connection and retry.",
            "java.lang.IllegalStateException: Request failed\n    at Network.request(Network.kt:42)",
          ),
        previousTimestamp = 1_699_999_990_000L,
        onClick = {},
      )
    }
  }
}
