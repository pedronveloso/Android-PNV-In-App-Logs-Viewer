package com.pedronveloso.logviewer

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val UI_WAIT_TIMEOUT_MILLIS = 5_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessExitsComposeTest {
  @get:Rule val compose = createComposeRule()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val providerCache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
    providerCache.isAccessible = true
    (providerCache.get(null) as MutableMap<*, *>).clear()
    context
      .getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  private fun history(vararg exits: RawExit) =
    ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) { _, _
      ->
      exits.toList()
    }

  @Test
  fun statusTabWarnsUntilTheExitIsDismissed() {
    val history = history(RawExit(1_000, 42, 13, "MemoryLimiter:AnonSwap", 100, 0, 0, "app"))
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = history) }
    }
    compose.onNodeWithTag("status_tab_warning", useUnmergedTree = true).assertExists()
    compose.onNodeWithTag("status_tab").performClick()
    // The section sits below the capture cards, past the first screen of the lazy list.
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("dismiss_exits"))
    compose.onNodeWithTag("dismiss_exits").performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose
        .onAllNodesWithTag("status_tab_warning", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isEmpty()
    }
    assertThat(history.state.value.pendingExits).isEmpty()
  }

  @Test
  fun replacingOrRemovingHistoryClearsPreviousExitState() {
    val first = history(RawExit(1_000, 42, 4, null, 100, 0, 0, "app"))
    val second = history()
    val selected = androidx.compose.runtime.mutableStateOf<ProcessExitHistory?>(first)
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = selected.value) }
    }
    compose.onNodeWithTag("status_tab_warning", useUnmergedTree = true).assertExists()
    compose.runOnIdle { selected.value = second }
    compose.onNodeWithTag("status_tab_warning", useUnmergedTree = true).assertDoesNotExist()
    compose.runOnIdle { selected.value = first }
    compose.onNodeWithTag("status_tab_warning", useUnmergedTree = true).assertExists()
    compose.runOnIdle { selected.value = null }
    compose.onNodeWithTag("status_tab_warning", useUnmergedTree = true).assertDoesNotExist()
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNodeWithText("Previous exits").assertDoesNotExist()
  }

  @Test
  fun exitsThatAreNotFlaggedDoNotWarn() {
    val history = history(RawExit(1_000, 42, 10, "remove task", 400, 0, 0, "app"))
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = history) }
    }
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNode(hasScrollAction()).performScrollToNode(hasText("Previous exits"))
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("exit_card_42"))
    compose
      .onNodeWithTag("exit_card_42")
      .assert(
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
          androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
          "Previous exit",
        ),
      )
    assertThat(
        compose
          .onAllNodesWithTag("status_tab_warning", useUnmergedTree = true)
          .fetchSemanticsNodes(),
      )
      .isEmpty()
  }

  private fun openExitSheet(history: ProcessExitHistory, pid: Int) {
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = history) }
    }
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("exit_card_$pid"))
    compose.onNodeWithTag("exit_card_$pid").performClick()
    compose.onNodeWithTag("exit_details_sheet").assertExists()
  }

  @Test
  fun tappingAnExitOpensItsDetailsWithTraceSharingOnlyWhenKept() {
    val history =
      history(
        RawExit(2_000, 2, 6, "Input dispatching timed out", 100, 0, 0, "app", hasTrace = true),
        RawExit(1_000, 1, 10, "remove task", 400, 0, 0, "app"),
      )
    openExitSheet(history, pid = 2)
    compose.onNodeWithText("Input dispatching timed out").assertExists()
    compose.onNodeWithTag("share_exit_trace").assertExists()
    compose.onNodeWithTag("share_exit_details").assertExists()
  }

  @Test
  fun anExitWithoutATraceOffersOnlyDetails() {
    openExitSheet(history(RawExit(1_000, 1, 10, "remove task", 400, 0, 0, "app")), pid = 1)
    compose.onNodeWithTag("share_exit_details").assertExists()
    compose.onNodeWithTag("share_exit_trace").assertDoesNotExist()
  }

  @Test
  fun openSessionLogsJumpsToThatSession() {
    val tag = encodeStateSummary("0001790399000-3f2a9c1b", null)
    openExitSheet(
      history(
        RawExit(1_000, 3, 13, "MemoryLimiter:AnonSwap", 100, 0, 0, "app", stateSummary = tag),
      ),
      pid = 3,
    )
    // The sheet is taller than the test screen; tapping off-screen would land on the scrim.
    compose.onNodeWithTag("open_exit_session").performScrollTo().performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose
        .onAllNodesWithText("entry in old", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose.onNodeWithTag("exit_details_sheet").assertDoesNotExist()
  }

  private fun openLinkedExit() {
    openExitSheet(
      history(
        RawExit(
          1_000,
          3,
          4,
          null,
          100,
          0,
          0,
          "app",
          stateSummary = encodeStateSummary("0001790399000-3f2a9c1b", null),
        ),
      ),
      3,
    )
    compose.onNodeWithTag("share_exit_details").performScrollTo().performClick()
    compose.onNodeWithTag("include_session_logs_dialog").assertExists()
  }

  private fun sharedDetails(): String {
    val application =
      org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) { application.peekNextStartedActivity() != null }
    val chooser = application.nextStartedActivity
    val send =
      chooser.getParcelableExtra(
        android.content.Intent.EXTRA_INTENT,
        android.content.Intent::class.java,
      )!!
    val uri =
      send.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
    return context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
  }

  @Test
  fun includingLogsSharesTheLinkedSessionRatherThanTheSelectedSession() {
    openLinkedExit()
    compose.onNodeWithText("Include logs").performClick()
    val text = sharedDetails()
    assertThat(text).contains("Session logs\n")
    assertThat(text).contains("entry in old")
    compose.onNodeWithTag("include_session_logs_dialog").assertDoesNotExist()
  }

  @Test
  fun detailsOnlyDoesNotIncludeLogsAndAsksAgainNextTime() {
    openLinkedExit()
    compose.onNodeWithText("Details only").performClick()
    assertThat(sharedDetails()).doesNotContain("Session logs\n")
    compose.onNodeWithTag("share_exit_details").performScrollTo().performClick()
    compose.onNodeWithTag("include_session_logs_dialog").assertExists()
  }

  @Test
  fun backDismissesThePromptWithoutSharing() {
    openLinkedExit()
    compose.runOnIdle {
      (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
        .onBackPressedDispatcher
        .onBackPressed()
    }
    compose.onNodeWithTag("include_session_logs_dialog").assertDoesNotExist()
    compose.onNodeWithTag("exit_details_sheet").assertExists()
    assertThat(
        org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
          .nextStartedActivity,
      )
      .isNull()
  }

  @Test
  fun sharingWithoutRetainedSessionSkipsThePrompt() {
    openExitSheet(
      history(
        RawExit(
          1_000,
          1,
          4,
          null,
          100,
          0,
          0,
          "app",
          stateSummary = encodeStateSummary("0001790399000-aaaaaaaa", null),
        ),
      ),
      1,
    )
    compose.onNodeWithTag("open_exit_session").assertDoesNotExist()
    compose.onNodeWithTag("share_exit_details").performScrollTo().performClick()
    assertThat(sharedDetails()).doesNotContain("Session logs\n")
    compose.onNodeWithTag("include_session_logs_dialog").assertDoesNotExist()
  }

  @Test
  fun changingHistoryClearsAnOpenPrompt() {
    val first =
      history(
        RawExit(
          1_000,
          3,
          4,
          null,
          100,
          0,
          0,
          "app",
          stateSummary = encodeStateSummary("0001790399000-3f2a9c1b", null),
        ),
      )
    val selected = androidx.compose.runtime.mutableStateOf(first)
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = selected.value) }
    }
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("exit_card_3"))
    compose.onNodeWithTag("exit_card_3").performClick()
    compose.onNodeWithTag("share_exit_details").performScrollTo().performClick()
    compose.onNodeWithTag("include_session_logs_dialog").assertExists()
    val next = history()
    compose.runOnIdle { selected.value = next }
    compose.onNodeWithTag("include_session_logs_dialog").assertDoesNotExist()
    compose.onNodeWithTag("exit_details_sheet").assertDoesNotExist()
  }

  private object EmptySource : LogSource {
    override val changes: Flow<Unit>? = null
    override val capabilities: StateFlow<LogCapabilities> =
      MutableStateFlow(LogCapabilities(true, true, false, true))
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth())

    override suspend fun sessions() =
      listOf(LogSession("current", 2, true), LogSession("0001790399000-3f2a9c1b", 1, false))

    override suspend fun entries(sessionId: String) =
      if (sessionId == "0001790399000-3f2a9c1b")
        listOf(LogEntry(0, "0001790399000-3f2a9c1b", 1, 4, "Tag", "entry in old"))
      else emptyList()

    override suspend fun clear(sessionId: String) = Unit
  }
}
