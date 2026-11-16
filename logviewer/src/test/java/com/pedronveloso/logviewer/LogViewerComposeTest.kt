package com.pedronveloso.logviewer

import android.content.Context
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val UI_WAIT_TIMEOUT_MILLIS = 5_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewerComposeTest {
  @get:Rule val compose = createComposeRule()

  @Before
  fun resetProviderPaths() {
    // FileProvider retains paths by authority; Robolectric gives each test a new cacheDir.
    val cache = FileProvider::class.java.getDeclaredField("sCache")
    cache.isAccessible = true
    (cache.get(null) as MutableMap<*, *>).clear()
  }

  @Test
  fun detailsSheetShowsSeparateTrace() {
    val source = FakeSource(canClear = true)
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
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
    prefs.edit(commit = true) { clear() }
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
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
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
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_entry_0").performClick()
  }

  @Test
  fun readOnlySourceHidesClearControl() {
    val source = FakeSource(canClear = false)
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}) } }
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Clear logs in selected session").assertDoesNotExist()
  }

  @Test
  fun toolbarBuilderHidesBuiltInButtonsAndUsesCaptureDefaults() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val capture =
      TimberLogCapture.Builder(context)
        .processExits(false)
        .viewerOptions(
          LogViewerOptions.Builder().showShareButton(false).showClearButton(false).build(),
        )
        .build()
    compose.setContent { MaterialTheme { LogViewer(capture, onBack = {}) } }
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Share visible logs").assertDoesNotExist()
    compose.onNodeWithContentDescription("Clear logs in selected session").assertDoesNotExist()
  }

  @Test
  fun customExportGetsFilteredFileAndDisablesRepeatedTapsWhileSuspended() {
    val source = FakeSource(canClear = true)
    val exported = AtomicReference<Pair<String, String>?>()
    val release = CompletableDeferred<Unit>()
    val options =
      LogViewerOptions.Builder()
        .exportAction("Send to support", { androidx.compose.material3.Text("S") }) { export ->
          withContext(Dispatchers.IO) {
            exported.set(export.file.readText() to export.mimeType)
          }
          release.await()
        }
        .build()
    compose.setContent { MaterialTheme { LogViewer(source, onBack = {}, options = options) } }
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("log_search").performTextInput("missing")
    compose.onNodeWithContentDescription("Send to support").performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) { exported.get() != null }
    assertThat(exported.get()).isEqualTo("" to "text/plain")
    compose.onNodeWithContentDescription("Send to support").assertIsNotEnabled()
    compose.onNodeWithContentDescription("Share visible logs").assertIsNotEnabled()
    release.complete(Unit)
    compose.waitForIdle()
  }

  @Test
  fun customExportFailureAppearsInViewerAndCanBeRetried() {
    val source = FakeSource(true)
    var attempts = 0
    val options =
      LogViewerOptions.Builder()
        .exportAction("Upload logs", { androidx.compose.material3.Text("U") }) {
          attempts++
          error("Upload unavailable")
        }
        .build()
    compose.setContent {
      MaterialTheme { LogViewer(source, onBack = {}, options = options) }
    }
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Upload logs").performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText("Upload unavailable").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Upload logs").performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) { attempts == 2 }
    compose.onNodeWithText("Status").performClick()
    compose.onNodeWithContentDescription("Upload logs").assertDoesNotExist()
  }

  private class FakeSource(canClear: Boolean) : LogSource {
    override val changes: Flow<Unit>? = null
    override val capabilities: StateFlow<LogCapabilities> =
      MutableStateFlow(LogCapabilities(true, false, false, true, canClear = canClear))
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth())

    override suspend fun sessions() = listOf(LogSession("current", 0, true))

    override suspend fun entries(sessionId: String) =
      listOf(
        LogEntry(0, sessionId, 0, Log.ERROR, "Worker", "Failed", "IllegalStateException: boom"),
      )

    override suspend fun clear(sessionId: String) = Unit
  }
}
