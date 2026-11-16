package com.pedronveloso.logviewer.sample

import com.google.common.truth.Truth.assertThat
import com.pedronveloso.logviewer.LogCapabilities
import com.pedronveloso.logviewer.LogEntry
import com.pedronveloso.logviewer.LogHealth
import com.pedronveloso.logviewer.LogSession
import com.pedronveloso.logviewer.LogSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

class StatusDemoScenarioTest {
  private val capture = FakeCapture()

  @Test
  fun everyScenarioExposesItsStatusRule() {
    assertThat(StatusDemoScenario.NOT_INSTALLED.source(capture).health.value.installed).isFalse()
    assertThat(StatusDemoScenario.NO_SOURCE.source(capture).capabilities.value.hasInMemoryLogs)
      .isFalse()
    assertThat(
        StatusDemoScenario.MEMORY_ONLY.source(capture).capabilities.value.persistsAcrossCrashes,
      )
      .isFalse()
    assertThat(StatusDemoScenario.NO_LIVE_UPDATES.source(capture).capabilities.value.hasLiveUpdates)
      .isFalse()
    assertThat(
        StatusDemoScenario.NO_REDACTION.source(capture).capabilities.value.redactionConfigured,
      )
      .isFalse()
    assertThat(StatusDemoScenario.READ_FAILURE.source(capture).health.value.readError)
      .isEqualTo("Demo read failure")
    assertThat(StatusDemoScenario.WRITE_FAILURE.source(capture).health.value.writeError)
      .isEqualTo("Demo write failure")
    assertThat(StatusDemoScenario.APP_PROVIDED.source(capture).capabilities.value.isLibraryCapture)
      .isFalse()
    assertThat(StatusDemoScenario.APP_PROVIDED.source(capture).health.value.installed).isNull()
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
