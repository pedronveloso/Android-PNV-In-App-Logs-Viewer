package com.pedronveloso.logviewer.sample

import com.google.common.truth.Truth.assertThat
import com.pedronveloso.logviewer.LogCapabilities
import com.pedronveloso.logviewer.LogEntry
import com.pedronveloso.logviewer.LogHealth
import com.pedronveloso.logviewer.LogSession
import com.pedronveloso.logviewer.LogSource
import com.pedronveloso.logviewer.statusMessages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

class StatusDemoScenarioTest {
  private val capture = FakeCapture()

  @Test
  fun everyScenarioExposesItsStatusRule() {
    val expected =
      mapOf(
        StatusDemoScenario.REAL to "Crash persistence configured",
        StatusDemoScenario.NOT_INSTALLED to "Capture is not installed",
        StatusDemoScenario.NO_SOURCE to "No log source configured",
        StatusDemoScenario.MEMORY_ONLY to "Logs live only in memory",
        StatusDemoScenario.NO_LIVE_UPDATES to "Live updates unavailable",
        StatusDemoScenario.NO_REDACTION to "Redaction is not configured",
        StatusDemoScenario.READ_FAILURE to "Reading logs failed",
        StatusDemoScenario.WRITE_FAILURE to "Persistent writes failed",
        StatusDemoScenario.APP_PROVIDED to "Crash persistence configured",
      )
    assertThat(expected.keys).containsExactlyElementsIn(StatusDemoScenario.entries)
    expected.forEach { (scenario, title) ->
      val source = scenario.source(capture)
      assertThat(statusMessages(source.capabilities.value, source.health.value).map { it.title })
        .contains(title)
    }
    assertThat(
        statusMessages(
            StatusDemoScenario.APP_PROVIDED.capabilities(capture.capabilities.value),
            StatusDemoScenario.APP_PROVIDED.health(capture.health.value),
          )
          .single { it.title == "Crash persistence configured" }
          .detail
      )
      .contains("declared by the host app")
  }

  @Test
  fun simulationsDoNotChangeTheRealCapture() {
    assertThat(StatusDemoScenario.REAL.source(capture)).isSameInstanceAs(capture)
    assertThat(StatusDemoScenario.NO_LIVE_UPDATES.source(capture).changes).isNull()
    assertThat(StatusDemoScenario.NO_SOURCE.source(capture).capabilities.value.canClear).isFalse()
    StatusDemoScenario.entries.forEach { it.source(capture) }
    assertThat(capture.capabilities.value).isEqualTo(LogCapabilities(true, true, true, true, true))
    assertThat(capture.health.value).isEqualTo(LogHealth(installed = true))
  }

  private class FakeCapture : LogSource {
    override val capabilities: StateFlow<LogCapabilities> =
      MutableStateFlow(LogCapabilities(true, true, true, true, isLibraryCapture = true))
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth(installed = true))
    override val changes: Flow<Unit> = MutableStateFlow(Unit)

    override suspend fun sessions(): List<LogSession> = emptyList()

    override suspend fun entries(sessionId: String): List<LogEntry> = emptyList()

    override suspend fun clear(sessionId: String) = Unit
  }
}
