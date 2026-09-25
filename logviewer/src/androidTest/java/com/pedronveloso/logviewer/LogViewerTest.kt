package com.pedronveloso.logviewer

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class LogViewerTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun searchAndSeverityFilterVisibleEntries() {
    val source = FakeSource()
    source.records =
      listOf(
        LogEntry(0, "current", 0, Log.DEBUG, "Worker", "Started"),
        LogEntry(1, "current", 1, Log.ERROR, "Network", "Request failed"),
      )
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("Request failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_search").performTextInput("network")
    compose.onNodeWithText("Request failed").assertExists()
    compose.onNodeWithText("Started").assertDoesNotExist()
    compose.onNodeWithTag("level_ERROR").performClick()
    compose.onNodeWithText("Request failed").assertExists()
  }

  @Test
  fun statusIdentifiesExternalPersistenceAsDeclared() {
    val source = FakeSource()
    source.capabilityState.value = LogCapabilities(true, true, false, true)
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.onNodeWithTag("status_tab").performClick()
    compose
      .onNodeWithText(
        "Persistence is declared by the host app and cannot be verified by this viewer."
      )
      .assertExists()
    compose.onNodeWithText("Live updates unavailable").assertExists()
  }

  @Test
  fun followPausesWhenUserScrollsAwayFromNewEntries() {
    val source = FakeSource()
    source.records =
      (0 until 100).map {
        LogEntry(it.toLong(), "current", it.toLong(), Log.DEBUG, "Loop", "Line $it")
      }
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("Line 99").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("follow_logs").assertIsSelected()
    compose.onNodeWithTag("log_list").performTouchInput { swipeDown() }
    compose.waitUntil(3_000) {
      runCatching {
          compose.onNodeWithTag("follow_logs").assertIsNotSelected()
          true
        }
        .getOrDefault(false)
    }
  }

  private class FakeSource : LogSource {
    var records: List<LogEntry> = emptyList()
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = events
    val capabilityState = MutableStateFlow(LogCapabilities(true, false, true, true))
    override val capabilities: StateFlow<LogCapabilities> = capabilityState
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth(installed = true))

    override suspend fun sessions() = listOf(LogSession("current", 0, true))

    override suspend fun entries(sessionId: String) = records

    override suspend fun clear(sessionId: String) {
      records = emptyList()
      events.tryEmit(Unit)
    }
  }
}
