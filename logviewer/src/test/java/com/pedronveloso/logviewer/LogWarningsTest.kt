package com.pedronveloso.logviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogWarningsTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun clearAcknowledgement() {
    context
      .getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  @Test
  fun warningsReactToRefreshAcknowledgementAndReadFailure() = runBlocking {
    var exits = listOf(raw(10, 4), raw(9, 6), raw(8, 10))
    var fail = false
    val history =
      ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) {
        _,
        _ ->
        check(!fail) { "read failed" }
        exits
      }
    val snapshots = mutableListOf<LogWarnings>()
    val collector =
      launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        history.warnings.toList(snapshots)
      }
    try {
      assertThat(history.warnings.value.hasWarnings).isTrue()
      assertThat(history.warnings.value.totalCount).isEqualTo(2)
      assertThat(history.warnings.value.warnings)
        .containsExactly(LogWarning(WarningType.PendingProcessExit, 2))
      history.acknowledge()
      assertThat(history.warnings.value.hasWarnings).isFalse()
      history.refresh()
      assertThat(history.warnings.value.warnings).isEmpty()
      exits = listOf(raw(11, 5)) + exits
      history.refresh()
      assertThat(history.warnings.value.totalCount).isEqualTo(1)
      fail = true
      history.refresh()
      assertThat(history.state.value).isInstanceOf(ProcessExitState.Failed::class.java)
      assertThat(history.warnings.value.totalCount).isEqualTo(0)
      assertThat(snapshots.map { it.totalCount }).containsExactly(2, 0, 1, 0).inOrder()
    } finally {
      collector.cancel()
    }
  }

  @Test
  fun loadingStartsEmptyAndRespectsCustomAlertReasons() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val history =
      ProcessExitHistory(context, setOf(ExitReason.LowMemory), 16, null, dispatcher) { _, _ ->
        listOf(raw(3, 3), raw(2, 4), raw(1, 6))
      }
    assertThat(history.state.value).isEqualTo(ProcessExitState.Loading)
    assertThat(history.warnings.value.hasWarnings).isFalse()
    testScheduler.runCurrent()
    assertThat(history.warnings.value.totalCount).isEqualTo(1)
  }

  @Test
  @Config(sdk = [28])
  fun unsupportedPlatformHasNoWarnings() {
    val history =
      ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) {
        _,
        _ ->
        error("must not read")
      }
    assertThat(history.warnings.value).isEqualTo(NoLogWarnings)
  }

  @Test
  fun disabledTrackingStillProvidesNonNullStateFlowWithoutInstallingCapture() {
    val capture = TimberLogCapture.Builder(context).processExits(false).build()
    assertThat(capture.warnings.value.hasWarnings).isFalse()
    assertThat(capture.warnings.value.warnings).isEmpty()
    assertThat(capture.health.value.installed).isFalse()
  }

  private fun raw(timestamp: Long, reason: Int) =
    RawExit(timestamp, 1, reason, null, 100, 0, 0, "app")
}
