package com.pedronveloso.logviewer.sample

import com.pedronveloso.logviewer.LogCapabilities
import com.pedronveloso.logviewer.LogEntry
import com.pedronveloso.logviewer.LogHealth
import com.pedronveloso.logviewer.LogSession
import com.pedronveloso.logviewer.LogSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class StatusDemoScenario(val label: String) {
  REAL("Real capture"),
  NOT_INSTALLED("Capture not installed"),
  NO_SOURCE("No source configured"),
  MEMORY_ONLY("Memory only"),
  NO_LIVE_UPDATES("No live updates"),
  NO_REDACTION("No redaction"),
  READ_FAILURE("Read failure"),
  WRITE_FAILURE("Write failure"),
  APP_PROVIDED("App-provided source");

  fun source(capture: LogSource): LogSource =
    if (this == REAL) capture else ScenarioLogSource(capture, this)

  fun capabilities(base: LogCapabilities): LogCapabilities =
    when (this) {
      NO_SOURCE ->
        base.copy(
          hasInMemoryLogs = false,
          persistsAcrossCrashes = false,
          hasLiveUpdates = false,
          isLibraryCapture = false,
          canClear = false,
        )
      MEMORY_ONLY -> base.copy(persistsAcrossCrashes = false)
      NO_LIVE_UPDATES -> base.copy(hasLiveUpdates = false)
      NO_REDACTION -> base.copy(redactionConfigured = false)
      APP_PROVIDED -> base.copy(isLibraryCapture = false)
      else -> base
    }

  fun health(base: LogHealth): LogHealth =
    when (this) {
      NOT_INSTALLED -> base.copy(installed = false)
      READ_FAILURE -> base.copy(readError = "Demo read failure")
      WRITE_FAILURE -> base.copy(writeError = "Demo write failure")
      APP_PROVIDED -> base.copy(installed = null)
      else -> base
    }
}

private class ScenarioLogSource(
  private val capture: LogSource,
  private val scenario: StatusDemoScenario,
) : LogSource by capture {
  override val capabilities: StateFlow<LogCapabilities> =
    MutableStateFlow(scenario.capabilities(capture.capabilities.value))
  override val health: StateFlow<LogHealth> =
    MutableStateFlow(scenario.health(capture.health.value))
  override val changes: Flow<Unit>? =
    if (scenario == StatusDemoScenario.NO_SOURCE || scenario == StatusDemoScenario.NO_LIVE_UPDATES)
      null
    else capture.changes

  override suspend fun sessions(): List<LogSession> =
    if (scenario == StatusDemoScenario.NO_SOURCE) emptyList() else capture.sessions()

  override suspend fun entries(sessionId: String): List<LogEntry> =
    if (scenario == StatusDemoScenario.NO_SOURCE) emptyList() else capture.entries(sessionId)

  override suspend fun clear(sessionId: String) {
    if (scenario != StatusDemoScenario.NO_SOURCE) capture.clear(sessionId)
  }
}
