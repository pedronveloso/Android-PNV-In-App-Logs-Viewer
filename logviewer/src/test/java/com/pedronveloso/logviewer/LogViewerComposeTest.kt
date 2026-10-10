package com.pedronveloso.logviewer

import android.content.Context
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewerComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun detailsSheetShowsSeparateTrace() {
    val source = FakeSource(canClear = true)
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Has exception details").assertExists()
    compose.onNodeWithTag("log_entry_0").performClick()
    compose.onNodeWithTag("log_details_sheet").assertExists()
    compose.onNodeWithText("IllegalStateException: boom").assertExists()
  }

  @Test
  fun wrapToggleDefaultsOffAndPersists() {
    val prefs = prefs()
    prefs.edit().clear().commit()
    openDetails()
    compose.onNodeWithTag("wrap_lines_toggle").assertIsOff()
    compose.onNodeWithTag("wrap_lines_toggle").performClick()
    compose.onNodeWithTag("wrap_lines_toggle").assertIsOn()
    assertThat(prefs.getBoolean(LogViewerPreferences.KEY_WRAP, false)).isTrue()
  }

  @Test
  fun storedWrapPreferenceIsRestored() {
    prefs().edit().putBoolean(LogViewerPreferences.KEY_WRAP, true).commit()
    openDetails()
    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag("wrap_lines_toggle").fetchSemanticsNodes().any {
        SemanticsProperties.ToggleableState in it.config &&
          it.config[SemanticsProperties.ToggleableState] == ToggleableState.On
      }
    }
  }

  private fun prefs() =
    ApplicationProvider.getApplicationContext<Context>()
      .getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)

  private fun openDetails() {
    compose.setContent { MaterialTheme { LogViewer(FakeSource(canClear = true), onBack = {}) } }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_entry_0").performClick()
  }

  @Test
  fun readOnlySourceHidesClearControl() {
    val source = FakeSource(canClear = false)
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Clear logs in selected session").assertDoesNotExist()
  }

  private class FakeSource(canClear: Boolean) : LogSource {
    override val changes: Flow<Unit>? = null
    override val capabilities: StateFlow<LogCapabilities> =
      MutableStateFlow(LogCapabilities(true, false, false, true, canClear = canClear))
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth())

    override suspend fun sessions() = listOf(LogSession("current", 0, true))

    override suspend fun entries(sessionId: String) =
      listOf(
        LogEntry(0, sessionId, 0, Log.ERROR, "Worker", "Failed", "IllegalStateException: boom")
      )

    override suspend fun clear(sessionId: String) = Unit
  }
}
