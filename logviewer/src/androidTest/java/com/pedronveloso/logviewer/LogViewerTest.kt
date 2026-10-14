package com.pedronveloso.logviewer

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
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
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
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
  fun clearSearchButtonAppearsOnlyForTextAndRestoresEntries() {
    val source = FakeSource()
    source.records =
      listOf(
        LogEntry(0, "current", 0, Log.DEBUG, "Worker", "Started"),
        LogEntry(1, "current", 1, Log.ERROR, "Network", "Request failed"),
      )
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("Request failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("clear_log_search").assertDoesNotExist()
    compose.onNodeWithTag("log_search").performTextInput("network")
    compose.onNodeWithContentDescription("Clear filter text").assertExists()
    compose.onNodeWithText("Started").assertDoesNotExist()
    compose.onNodeWithTag("clear_log_search").performClick()
    compose.onNodeWithTag("clear_log_search").assertDoesNotExist()
    compose.onNodeWithText("Started").assertExists()
  }

  @Test
  fun elapsedTimeFollowsVisibleEntries() {
    val source = FakeSource()
    source.records =
      listOf(
        LogEntry(0, "current", 0, Log.DEBUG, "Hidden", "First"),
        LogEntry(1, "current", 10_100, Log.DEBUG, "Shown", "Second"),
        LogEntry(2, "current", 11_100, Log.DEBUG, "Shown", "Third"),
      )
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("+10s").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_search").performTextInput("Shown")
    compose.onNodeWithText("+10s").assertDoesNotExist()
    compose.onNodeWithText("+1s").assertExists()
  }

  @Test
  fun detailsSheetShowsThrowableAndFiltersByTag() {
    val source = FakeSource()
    source.records =
      listOf(
        LogEntry(
          0,
          "current",
          0,
          Log.ERROR,
          "MyClass\$someFunction",
          "Failed",
          "IllegalStateException: boom",
        ),
        LogEntry(1, "current", 1, Log.DEBUG, "Other", "Unrelated"),
      )
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Has exception details").assertExists()
    compose.onNodeWithText("IllegalStateException: boom").assertDoesNotExist()
    compose.onNodeWithTag("log_entry_0").performClick()
    compose.onNodeWithTag("log_details_sheet").assertExists()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    compose.onNodeWithTag("filter_logs_like_this").performClick()
    compose.onNodeWithText("Unrelated").assertDoesNotExist()
    compose.onNodeWithText("Failed").assertExists()
  }

  @Test
  fun statusIdentifiesExternalPersistenceAsDeclared() {
    val source = FakeSource()
    source.capabilityState.value = LogCapabilities(true, true, false, true)
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.onNodeWithTag("status_tab").performClick()
    compose
      .onNodeWithText(
        "Persistence is declared by the host app and cannot be verified by this viewer.",
      )
      .assertExists()
    compose.onNodeWithText("Live updates unavailable").assertExists()
  }

  @Test
  fun statusCardsExposeHealthyAndActionNeededStates() {
    compose.setContent {
      MaterialTheme {
        StatusContent(
          LogCapabilities(true, true, false, true, isLibraryCapture = true),
          LogHealth(installed = true),
        )
      }
    }
    compose
      .onNodeWithText("Crash persistence configured")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Healthy"))
    compose
      .onNodeWithText("Live updates unavailable")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Action needed"))
  }

  @Test
  fun followPausesWhenUserScrollsAwayFromNewEntries() {
    val source = FakeSource()
    source.records =
      (0 until 100).map {
        LogEntry(it.toLong(), "current", it.toLong(), Log.DEBUG, "Loop", "Line $it")
      }
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
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

  @Test
  fun warningAndErrorCountsAreSessionWideAndCapped() {
    val source = FakeSource()
    source.sessionList = listOf(LogSession("current", 0, true), LogSession("previous", 1, false))
    source.recordsBySession["previous"] =
      listOf(
        LogEntry(1, "previous", 0, Log.WARN, "Other", "Previous warning"),
        LogEntry(2, "previous", 0, Log.ERROR, "Other", "Previous error"),
        LogEntry(3, "previous", 0, Log.ERROR, "Other", "Another error"),
        LogEntry(4, "previous", 0, Log.ASSERT, "Other", "Assertion"),
      )
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.onNodeWithText("W 0", useUnmergedTree = true).assertExists()
    compose.onNodeWithText("E 0", useUnmergedTree = true).assertExists()

    source.records =
      (0 until 99).map {
        LogEntry(it.toLong(), "current", 0, Log.WARN, "Batch", "Warning $it")
      } + LogEntry(100, "current", 0, Log.ERROR, "Batch", "Error")
    source.notifyChanged()
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("W 99", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("E 1", useUnmergedTree = true).assertExists()

    source.records = source.records + LogEntry(101, "current", 0, Log.WARN, "Batch", "More")
    source.notifyChanged()
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("W 99+", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_search").performTextInput("no match")
    compose.onNodeWithText("W 99+", useUnmergedTree = true).assertExists()
    compose.onNodeWithTag("level_ERROR").performClick()
    compose.onNodeWithText("E 1", useUnmergedTree = true).assertExists()

    compose.onNodeWithTag("session_selector").performClick()
    compose.onNodeWithTag("session_previous").performClick()
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("W 1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("E 2", useUnmergedTree = true).assertExists()
  }

  @Test
  fun controlsExposeAccessibleActionsAndFollowState() {
    val source = FakeSource()
    source.records = listOf(LogEntry(1, "current", 0, Log.WARN, "Worker", "Started"))
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.waitUntil(3_000) {
      compose.onAllNodesWithText("Started").fetchSemanticsNodes().isNotEmpty()
    }
    listOf(
        "Go back",
        "Share visible logs",
        "Clear logs in selected session",
        "Show logs",
        "Show capture status",
        "Select log session, current session",
        "Filter Warning and above, 1 warning entry",
        "Follow new logs and scroll to the newest entry",
      )
      .forEach { compose.onNodeWithContentDescription(it).assertExists() }
    compose.onNodeWithContentDescription("Refresh logs").assertDoesNotExist()
    compose.onNodeWithContentDescription("Copy visible logs").assertDoesNotExist()
    compose
      .onNodeWithTag("follow_logs")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
    compose.onNodeWithTag("follow_logs").performClick()
    compose
      .onNodeWithTag("follow_logs")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
  }

  @Test
  fun refreshIsOfferedOnlyWithoutLiveUpdates() {
    val source = FakeSource()
    source.changeFlow = null
    source.capabilityState.value = LogCapabilities(true, false, false, true)
    source.records = listOf(LogEntry(1, "current", 0, Log.WARN, "Worker", "Started"))
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.onNodeWithContentDescription("Refresh logs").assertExists()
  }

  @Test
  fun wrappableTextOnlyWrapsWhenEnabled() {
    // Needs real font metrics, so it lives here rather than in the Robolectric tests.
    val text = "java.lang.IllegalStateException: " + "boom".repeat(200)
    var wrap by mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        Box(Modifier.width(200.dp)) { WrappableText(text, TextStyle.Default, wrap = wrap) }
      }
    }
    val unwrapped = compose.onNodeWithText(text).fetchSemanticsNode().size.height
    wrap = true
    compose.waitForIdle()
    val wrapped = compose.onNodeWithText(text).fetchSemanticsNode().size.height
    assertTrue("wrapped $wrapped should exceed unwrapped $unwrapped", wrapped > unwrapped)
  }

  @Test
  fun refreshIsOfferedWhenChangesFlowIsNullEvenIfLiveUpdatesClaimed() {
    val source = FakeSource()
    source.changeFlow = null
    source.capabilityState.value = LogCapabilities(true, false, true, true)
    source.records = listOf(LogEntry(1, "current", 0, Log.WARN, "Worker", "Started"))
    compose.setContent { MaterialTheme { PhoneViewer(source) } }
    compose.onNodeWithContentDescription("Refresh logs").assertExists()
  }

  @Test
  fun availableWidthChoosesInlineDetailsOrPhoneSheet() {
    val expanded = hasExpandedWindow()
    val source = FakeSource()
    source.records =
      listOf(
        LogEntry(0, "current", 0, Log.WARN, "Worker", "Warning", "Tablet trace"),
      )
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(3000) {
      compose
        .onAllNodesWithText(if (expanded) "Warning 1" else "W 1", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose.onNodeWithTag("level_VERBOSE").assertTextContains(if (expanded) "Verbose" else "V")
    if (expanded) compose.onNodeWithText("Select a log entry").assertExists()
    compose.onNodeWithTag("follow_logs").assertIsSelected()
    compose.onNodeWithTag("log_entry_0").performClick()
    compose.onNodeWithTag("follow_logs").assertIsNotSelected()
    if (expanded) {
      compose.onNodeWithTag("log_entry_0").assertIsSelected()
      compose.onNodeWithTag("log_details_sheet").assertDoesNotExist()
    } else {
      compose.onNodeWithTag("log_details_sheet").assertExists()
    }
    compose.onNodeWithText("Tablet trace").assertExists()
  }

  @Test
  fun resizingPreservesDetailsInTheAvailablePresentation() {
    val expanded = hasExpandedWindow()
    val initialWidth = if (expanded) 1000.dp else 400.dp
    var width by mutableStateOf(initialWidth)
    val source = FakeSource()
    source.records =
      listOf(
        LogEntry(0, "current", 0, Log.ERROR, null, "Failure", "Resize trace"),
      )
    compose.setContent {
      MaterialTheme { Box(Modifier.width(width)) { LogViewer(source, onBack = {}) } }
    }
    compose.waitUntil(3000) {
      compose.onAllNodesWithText("Failure").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_entry_0").performClick()
    compose.runOnIdle { width = if (expanded) 500.dp else 320.dp }
    compose.onNodeWithTag("log_details_sheet").assertExists()
    compose.onNodeWithText("Resize trace").assertExists()
    compose.runOnIdle { width = initialWidth }
    if (expanded) {
      compose.onNodeWithTag("log_details_sheet").assertDoesNotExist()
      compose.onNodeWithTag("log_details_pane").assertExists()
      compose.onNodeWithTag("log_entry_0").assertIsSelected()
    } else {
      compose.onNodeWithTag("log_details_sheet").assertExists()
    }
    compose.onNodeWithText("Resize trace").assertExists()
  }

  private fun hasExpandedWindow() =
    ApplicationProvider.getApplicationContext<Context>().resources.configuration.screenWidthDp >=
      840

  @Composable
  private fun PhoneViewer(source: LogSource) {
    Box(Modifier.width(420.dp)) { LogViewer(source, onBack = {}) }
  }

  private class FakeSource : LogSource {
    var records: List<LogEntry> = emptyList()
    var sessionList = listOf(LogSession("current", 0, true))
    val recordsBySession = mutableMapOf<String, List<LogEntry>>()
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var changeFlow: Flow<Unit>? = events
    override val changes: Flow<Unit>?
      get() = changeFlow

    fun notifyChanged() {
      events.tryEmit(Unit)
    }

    val capabilityState = MutableStateFlow(LogCapabilities(true, false, true, true))
    override val capabilities: StateFlow<LogCapabilities> = capabilityState
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth(installed = true))

    override suspend fun sessions() = sessionList

    override suspend fun entries(sessionId: String) = recordsBySession[sessionId] ?: records

    override suspend fun clear(sessionId: String) {
      records = emptyList()
      events.tryEmit(Unit)
    }
  }
}
