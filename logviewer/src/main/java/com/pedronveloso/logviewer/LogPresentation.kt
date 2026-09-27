package com.pedronveloso.logviewer

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val priority: Int, val shortLabel: String) {
  VERBOSE(Log.VERBOSE, "V"),
  DEBUG(Log.DEBUG, "D"),
  INFO(Log.INFO, "I"),
  WARN(Log.WARN, "W"),
  ERROR(Log.ERROR, "E"),
  ASSERT(Log.ASSERT, "A");

  companion object {
    fun fromPriority(priority: Int): LogLevel =
      entries.firstOrNull { it.priority == priority } ?: DEBUG
  }
}

data class LogFilter(val minimumLevel: LogLevel = LogLevel.VERBOSE, val query: String = "")

fun LogEntry.matches(filter: LogFilter): Boolean =
  priority >= filter.minimumLevel.priority &&
    (filter.query.isBlank() ||
      message.contains(filter.query, ignoreCase = true) ||
      tag?.contains(filter.query, ignoreCase = true) == true)

/** Render only the selected entries, without appending a throwable a second time. */
fun formatLogEntries(entries: List<LogEntry>): String {
  val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  return entries.joinToString("\n\n") { entry ->
    buildString {
      append(date.format(Date(entry.timestampMillis)))
      append(' ')
      append(LogLevel.fromPriority(entry.priority).shortLabel)
      entry.tag?.takeIf(String::isNotBlank)?.let {
        append('/')
        append(it)
      }
      append('\n')
      append(entry.message)
    }
  }
}

internal fun formatSessionDate(timestamp: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

data class StatusMessage(val title: String, val detail: String, val isProblem: Boolean)

/** Pure status rules shared by the UI and tests. */
fun statusMessages(capabilities: LogCapabilities, health: LogHealth): List<StatusMessage> =
  buildList {
    if (health.installed == false) {
      add(
        StatusMessage(
          "Capture is not installed",
          "Call capture.install() during app startup.",
          true,
        )
      )
    }
    if (!capabilities.hasInMemoryLogs && !capabilities.persistsAcrossCrashes) {
      add(
        StatusMessage(
          "No log source configured",
          "Connect an in-memory or persistent Timber log source.",
          true,
        )
      )
    }
    if (capabilities.hasInMemoryLogs && !capabilities.persistsAcrossCrashes) {
      add(
        StatusMessage(
          "Logs live only in memory",
          "Enable persistent capture to keep logs after a crash or process restart.",
          true,
        )
      )
    }
    if (!capabilities.hasLiveUpdates) {
      add(
        StatusMessage(
          "Live updates unavailable",
          "Provide a change flow to enable Follow; use Refresh until then.",
          true,
        )
      )
    }
    if (!capabilities.redactionConfigured) {
      add(
        StatusMessage(
          "Redaction is not configured",
          "Redact sensitive values before logs are stored or shared.",
          true,
        )
      )
    }
    health.readError?.let { add(StatusMessage("Reading logs failed", it, true)) }
    health.writeError?.let {
      add(StatusMessage("Persistent writes failed", "$it. In-memory capture continues.", true))
    }
    if (capabilities.persistsAcrossCrashes && health.writeError == null) {
      add(
        StatusMessage(
          "Crash persistence configured",
          if (capabilities.isLibraryCapture)
            "The library writes each accepted entry to app-private storage before Timber returns."
          else "Persistence is declared by the host app and cannot be verified by this viewer.",
          false,
        )
      )
    }
    if (capabilities.hasLiveUpdates) {
      add(
        StatusMessage(
          "Live updates available",
          "Follow can track new entries while this screen is open.",
          false,
        )
      )
    }
  }
