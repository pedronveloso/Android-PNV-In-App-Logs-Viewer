package com.pedronveloso.logviewer

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1200dp-h1000dp-mdpi")
class LogViewerAdaptiveTest {
  @get:Rule val compose = createComposeRule()

  @Before
  fun clearPreferences() {
    preferences().edit().clear().commit()
  }

  @Test
  fun layoutUsesAvailableWidthAtBothBoundaries() {
    var width by mutableStateOf(599.dp)
    val source = FakeSource()
    compose.setContent {
      MaterialTheme { Box(Modifier.width(width).height(800.dp)) { LogViewer(source, {}) } }
    }
    awaitEntry()
    compose.onNodeWithTag("level_VERBOSE").assertTextContains("V")
    compose.onNodeWithTag("log_details_pane").assertDoesNotExist()
    compose.runOnIdle { width = 600.dp }
    compose.onNodeWithTag("level_VERBOSE").assertTextContains("Verbose")
    compose.onNodeWithTag("level_WARN").assertTextContains("Warning 1")
    compose.onNodeWithTag("log_details_pane").assertDoesNotExist()
    compose.runOnIdle { width = 839.dp }
    compose.onNodeWithTag("log_details_pane").assertDoesNotExist()
    compose.runOnIdle { width = 840.dp }
    compose.onNodeWithTag("log_details_pane").assertExists()
    compose.onNodeWithText("Select a log entry").assertExists()
    compose.onNodeWithTag("log_details_sheet").assertDoesNotExist()
  }

  @Test
  fun selectingEntryPausesFollowAndShowsSharedDetails() {
    showViewer()
    compose.onNodeWithTag("follow_logs").assertIsSelected()
    selectEntry()
    compose.onNodeWithTag("follow_logs").assertIsNotSelected()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    compose.onNodeWithText("Since previous visible entry: +1s").assertExists()
    compose.onNodeWithTag("log_details_sheet").assertDoesNotExist()
    compose.onNodeWithTag("wrap_lines_toggle").assertIsOff().performClick()
    compose.onNodeWithTag("wrap_lines_toggle").assertIsOn()
    assertThat(preferences().getBoolean(LogViewerPreferences.KEY_WRAP, false)).isTrue()
    compose.onNodeWithTag("filter_logs_like_this").performClick()
    compose.onNodeWithTag("log_search").assertTextContains("Worker")
    compose.onNodeWithTag("log_entry_0").assertDoesNotExist()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
  }

  @Test
  fun filteringPinsSelectionAndOmitsHiddenElapsedTime() {
    showViewer()
    selectEntry()
    compose.onNodeWithTag("log_search").performTextReplacement("Other")
    compose.onNodeWithText("Selected entry is hidden by filters.").assertExists()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    compose.onNodeWithText("Since previous visible entry: +1s").assertDoesNotExist()
    compose.onNodeWithTag("clear_log_search").performClick()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
    compose.onNodeWithTag("level_ERROR").performClick()
    compose.onNodeWithText("Selected entry is hidden by filters.").assertExists()
    compose.onNodeWithTag("level_VERBOSE").performClick()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
    compose.onNodeWithText("Since previous visible entry: +1s").assertExists()
  }

  @Test
  fun liveSnapshotsUpdateSelectedEntryWithoutFollowingOrReplacingIt() {
    val source = showViewer()
    selectEntry()
    source.records =
      source.records.map { if (it.id == 1L) it.copy(message = "Updated warning") else it } +
        LogEntry(3, "current", 3000, Log.ERROR, "New", "New arrival")
    source.notifyChanged()
    awaitText("4 of 4 entries")
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
    compose.onNodeWithTag("log_entry_1").assertTextContains("Updated warning")
    compose.onNodeWithTag("follow_logs").assertIsNotSelected()
    compose.onNodeWithTag("follow_logs").performClick()
    compose.onNodeWithTag("follow_logs").assertIsSelected()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    source.records = source.records.filterNot { it.id == 1L }
    source.notifyChanged()
    awaitText("Select a log entry")
    compose.onNodeWithText("IllegalStateException: boom").assertDoesNotExist()
  }

  @Test
  fun resizingTransfersSelectionAndPreservesSearchSeverityAndWrapPreference() {
    var width by mutableStateOf(1000.dp)
    val source = FakeSource()
    compose.setContent {
      MaterialTheme { Box(Modifier.width(width).height(800.dp)) { LogViewer(source, {}) } }
    }
    awaitEntry()
    compose.onNodeWithTag("log_search").performTextReplacement("Worker")
    compose.onNodeWithTag("level_WARN").performClick()
    selectEntry()
    compose.onNodeWithTag("wrap_lines_toggle").performClick()
    compose.runOnIdle { width = 500.dp }
    compose.onNodeWithTag("log_details_pane").assertDoesNotExist()
    compose.onNodeWithTag("log_details_sheet").assertExists()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    compose.onNodeWithTag("wrap_lines_toggle").assertIsOn()
    compose.runOnIdle { width = 1000.dp }
    compose.onNodeWithTag("log_details_sheet").assertDoesNotExist()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
    compose.onNodeWithTag("log_search").assertTextContains("Worker")
    compose.onNodeWithTag("level_WARN").assertIsSelected()
    compose.onNodeWithTag("wrap_lines_toggle").assertIsOn()
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNodeWithTag("log_details_pane").assertDoesNotExist()
    compose.onNodeWithTag("logs_tab").performClick()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
  }

  @Test
  fun sessionAndSourceChangesClearSelectionEvenWhenIdsMatch() {
    var source by mutableStateOf(FakeSource())
    compose.setContent { MaterialTheme { LogViewer(source, {}) } }
    awaitEntry()
    selectEntry()
    compose.onNodeWithTag("session_selector").performClick()
    compose.onNodeWithTag("session_previous").performClick()
    awaitText("1 of 1 entries")
    compose.onNodeWithText("Select a log entry").assertExists()
    compose.onNodeWithTag("log_entry_1").performClick()
    compose.onNodeWithText("Previous trace").assertExists()
    compose.runOnIdle { source = FakeSource() }
    awaitText("3 of 3 entries")
    compose.onNodeWithText("Select a log entry").assertExists()
  }

  @Test
  fun failedRefreshAndClearRetainSelectionAndSuccessfulClearRemovesIt() {
    val source = FakeSource(live = false)
    showViewer(source)
    selectEntry()
    source.failRead = true
    compose.onNodeWithTag("refresh_logs").performClick()
    awaitText("Read failed")
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    source.failRead = false
    source.failClear = true
    compose.onNodeWithContentDescription("Clear logs in selected session").performClick()
    awaitText("Clear failed")
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
    source.failClear = false
    compose.onNodeWithContentDescription("Clear logs in selected session").performClick()
    awaitText("No log entries captured yet.")
    compose.onNodeWithText("Select a log entry").assertExists()
  }

  @Test
  fun fullWordCountsStaySessionWideAndReadOnlySourceHidesClear() {
    val source = FakeSource(canClear = false)
    source.records =
      (0..100).map {
        LogEntry(it.toLong(), "current", it.toLong(), Log.WARN, "Worker", "Warning $it")
      }
    showViewer(source, waitForDefaultEntry = false)
    awaitText("Warning 99+")
    compose.onNodeWithTag("log_search").performTextReplacement("missing")
    compose.onNodeWithTag("level_WARN").assertTextContains("Warning 99+")
    compose.onNodeWithTag("level_ERROR").assertTextContains("Error 0")
    compose.onNodeWithText("No entries match this filter.").assertExists()
    compose.onNodeWithContentDescription("Clear logs in selected session").assertDoesNotExist()
  }

  @Test
  fun resumingFollowScrollsToNewestMatchingEntryAndResizeKeepsPausedPosition() {
    var width by mutableStateOf(1000.dp)
    val source = FakeSource()
    source.records =
      (0..99).map {
        LogEntry(it.toLong(), "current", it.toLong(), Log.DEBUG, "Loop", "Line $it")
      }
    compose.setContent {
      MaterialTheme { Box(Modifier.width(width).height(800.dp)) { LogViewer(source, {}) } }
    }
    awaitText("100 of 100 entries")
    compose.onNodeWithTag("log_list").performScrollToIndex(90)
    compose.onNodeWithTag("log_entry_90").performClick()
    compose.onNodeWithTag("follow_logs").assertIsNotSelected()
    compose.onNodeWithTag("log_list").performScrollToIndex(20)
    compose.runOnIdle { width = 839.dp }
    compose.onNodeWithTag("log_details_sheet").assertExists()
    compose.runOnIdle { width = 1000.dp }
    compose.onNodeWithTag("log_entry_20").assertExists()
    compose.onNodeWithTag("follow_logs").performClick()
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag("log_entry_99").fetchSemanticsNodes().isNotEmpty()
    }
    // The inspected entry is pinned even after Follow scrolls its row offscreen.
    compose.onNodeWithText("Line 90").assertExists()
  }

  @Test
  fun activityStateRestorationKeepsSelectionFiltersAndPausedFollow() {
    val restoration = StateRestorationTester(compose)
    val source = FakeSource()
    restoration.setContent { MaterialTheme { LogViewer(source, {}) } }
    awaitEntry()
    compose.onNodeWithTag("log_search").performTextReplacement("Worker")
    compose.onNodeWithTag("level_WARN").performClick()
    selectEntry()
    restoration.emulateSavedInstanceStateRestore()
    awaitEntry()
    compose.onNodeWithTag("log_entry_1").assertIsSelected()
    compose.onNodeWithTag("log_search").assertTextContains("Worker")
    compose.onNodeWithTag("level_WARN").assertIsSelected()
    compose.onNodeWithTag("follow_logs").assertIsNotSelected()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
  }

  private fun showViewer(
    source: FakeSource = FakeSource(),
    waitForDefaultEntry: Boolean = true,
  ): FakeSource {
    compose.setContent { MaterialTheme { LogViewer(source, {}) } }
    if (waitForDefaultEntry) awaitEntry()
    return source
  }

  private fun selectEntry() = compose.onNodeWithTag("log_entry_1").performClick()

  private fun awaitEntry() {
    compose.waitUntil(5000) {
      compose.onAllNodesWithTag("log_entry_1").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun awaitText(text: String) {
    compose.waitUntil(5000) {
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun preferences() =
    ApplicationProvider.getApplicationContext<Context>()
      .getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)

  private class FakeSource(live: Boolean = true, canClear: Boolean = true) : LogSource {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes = if (live) events else null
    override val capabilities =
      MutableStateFlow(LogCapabilities(true, false, live, true, canClear = canClear))
    override val health = MutableStateFlow(LogHealth())
    var failRead = false
    var failClear = false
    var records =
      listOf(
        LogEntry(0, "current", 0, Log.DEBUG, "Other", "Started"),
        LogEntry(1, "current", 1000, Log.WARN, "Worker", "Warning", "IllegalStateException: boom"),
        LogEntry(2, "current", 2000, Log.ERROR, "Other", "Failed"),
      )

    override suspend fun sessions() =
      listOf(
        LogSession("current", 0, true),
        LogSession("previous", 1, false),
      )

    override suspend fun entries(sessionId: String): List<LogEntry> {
      check(!failRead) { "Read failed" }
      return if (sessionId == "current") records
      else listOf(LogEntry(1, "previous", 0, Log.ERROR, null, "Previous", "Previous trace"))
    }

    override suspend fun clear(sessionId: String) {
      check(!failClear) { "Clear failed" }
      records = emptyList()
    }

    fun notifyChanged() {
      events.tryEmit(Unit)
    }
  }
}
