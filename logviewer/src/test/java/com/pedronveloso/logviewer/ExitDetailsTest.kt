package com.pedronveloso.logviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExitDetailsTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context
      .getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  private fun exit(reason: ExitReason, code: Int, status: Int = 0) =
    ProcessExit(1_000, 7, reason, code, null, 100, 0, 0, "app", status = status)

  @Test
  fun stateSummaryRoundTripsTheSessionAndNote() {
    val decoded = decodeStateSummary(encodeStateSummary("0001790399000-3f2a9c1b", "model: Voz"))
    assertThat(decoded).isEqualTo(StateSummary("0001790399000-3f2a9c1b", "model: Voz", null))
    assertThat(decodeStateSummary(encodeStateSummary("0001790399000-3f2a9c1b", null)))
      .isEqualTo(StateSummary("0001790399000-3f2a9c1b", null, null))
    assertThat(decodeStateSummary(null)).isEqualTo(StateSummary(null, null, null))
  }

  @Test
  fun stateSummaryFitsAndroidsLimitWithoutSplittingCharacters() {
    val bytes = encodeStateSummary("0001790399000-3f2a9c1b", "é".repeat(200))
    assertThat(bytes.size).isAtMost(MAX_STATE_SUMMARY_BYTES)
    val note = decodeStateSummary(bytes).note!!
    assertThat(note).isNotEmpty()
    assertThat(note.all { it == 'é' }).isTrue()
  }

  @Test
  fun anotherWritersSummaryShowsAsTextOrHex() {
    assertThat(decodeStateSummary("level=4".encodeToByteArray()).foreign).isEqualTo("level=4")
    assertThat(decodeStateSummary(byteArrayOf(0x00, 0x7f, 0xff.toByte())).foreign)
      .isEqualTo("00 7f ff")
  }

  @Test
  fun malformedLibraryTagsAreForeignSummaries() {
    for (id in listOf("", "../secret", "s1", "0001790399000-ABCDEF12")) {
      val text = "pnvlv:1:$id\nnote"
      assertThat(decodeStateSummary(text.encodeToByteArray()))
        .isEqualTo(StateSummary(null, null, text))
    }
  }

  @Test
  @Config(sdk = [30])
  fun nativeCrashOnApi30RetainsAnrTextClassification() {
    val history =
      ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) {
        _,
        _ ->
        listOf(RawExit(1, 1, 5, null, 100, 0, 0, "app", hasTrace = true))
      }
    assertThat((history.state.value as ProcessExitState.Loaded).exits.single().traceKind)
      .isEqualTo(TraceKind.AnrDump)
  }

  @Test
  fun notesAreRedactedOnceBeforeThePlatformWrite() {
    val capture = TimberLogCapture.Builder(context).redact { "[$it]" }.build()
    var written: ByteArray? = null
    capture.summaryWriter = { _, bytes -> written = bytes }
    capture.setExitNote("secret")
    capture.install()
    try {
      assertThat(decodeStateSummary(written).note).isEqualTo("[secret]")
      val history =
        ProcessExitHistory(
          context,
          ExitReason.DEFAULT_ALERTS,
          16,
          { "[$it]" },
          Dispatchers.Unconfined,
        ) { _, _ ->
          listOf(RawExit(1, 1, 4, null, 100, 0, 0, "app", stateSummary = written))
        }
      assertThat((history.state.value as ProcessExitState.Loaded).exits.single().appNote)
        .isEqualTo("[secret]")
    } finally {
      capture.uninstall()
    }
  }

  @Test
  @Suppress("DEPRECATION")
  fun legacyConstructorAndDisabledTrackingNeverWriteSummaries() {
    val captures =
      listOf(
        TimberLogCapture(context),
        TimberLogCapture.Builder(context).processExits(false).linkExitsToSessions(true).build(),
        TimberLogCapture.Builder(context).linkExitsToSessions(true).processExits(false).build(),
      )
    captures.forEach { capture ->
      var writes = 0
      capture.summaryWriter = { _, _ -> writes++ }
      capture.install()
      try {
        capture.setExitNote("note")
        assertThat(writes).isEqualTo(0)
      } finally {
        capture.uninstall()
      }
    }
  }

  @Test
  fun statusNamesSignalsAndExitCodes() {
    assertThat(exit(ExitReason.MemoryLimit, 17, status = 9).statusText)
      .isEqualTo("Signal 9 (SIGKILL)")
    assertThat(exit(ExitReason.NativeCrash, 5, status = 11).statusText)
      .isEqualTo("Signal 11 (SIGSEGV)")
    assertThat(exit(ExitReason.Signaled, 2, status = 30).statusText).isEqualTo("Signal 30")
    assertThat(exit(ExitReason.ExitSelf, 1, status = 3).statusText).isEqualTo("Exit code 3")
    assertThat(exit(ExitReason.MemoryLimit, 17).statusText).isNull()
    assertThat(exit(ExitReason.Crash, 4).statusText).isNull()
  }

  @Test
  fun anrTypesHaveLabels() {
    assertThat(AnrDetails(2, 5_000, true, 1).typeLabel).isEqualTo("Input dispatch")
    assertThat(AnrDetails(10, 5_000, true, 1).typeLabel).isEqualTo("Application start")
    assertThat(AnrDetails(99, 5_000, true, 1).typeLabel).isEqualTo("Other")
  }

  @Test
  fun detailsTextListsEveryFieldAndAppendsTheTrace() {
    val anr =
      exit(ExitReason.Anr, 6)
        .copy(
          hasTrace = true,
          traceKind = TraceKind.AnrDump,
          anr = AnrDetails(2, 5_000, true, 41),
          sessionId = "0001790399000-3f2a9c1b",
          appNote = "model: Voz",
        )
    val text = anr.detailsText(trace = "\"main\" prio=5")
    assertThat(text).contains("Reason: ANR (code 6)")
    assertThat(text).contains("ANR timeout: 5 s")
    assertThat(text).contains("User could see it: Yes")
    assertThat(text).contains("Log session: 0001790399000-3f2a9c1b")
    assertThat(text).contains("App note: model: Voz")
    assertThat(text).contains("Trace: ANR thread dump")
    assertThat(text).endsWith("Trace\n\"main\" prio=5")
    assertThat(exit(ExitReason.Crash, 4).detailRows()).contains("Trace" to "None kept")
  }

  @Test
  @Config(sdk = [31, 35])
  fun historyDecodesTheTagAndTraceKind() {
    val tag = encodeStateSummary("0001790399000-3f2a9c1b", "secret note")
    val history =
      ProcessExitHistory(
        context,
        ExitReason.DEFAULT_ALERTS,
        16,
        { it.replace("secret", "***") },
        Dispatchers.Unconfined,
      ) { _, _ ->
        listOf(
          RawExit(2, 2, 5, null, 100, 0, 0, "app", status = 11, hasTrace = true),
          RawExit(1, 1, 6, null, 100, 0, 0, "app", hasTrace = true, stateSummary = tag),
        )
      }
    val exits = (history.state.value as ProcessExitState.Loaded).exits
    assertThat(exits[0].traceKind).isEqualTo(TraceKind.Tombstone)
    assertThat(exits[1].traceKind).isEqualTo(TraceKind.AnrDump)
    assertThat(exits[1].sessionId).isEqualTo("0001790399000-3f2a9c1b")
    assertThat(exits[1].appNote).isEqualTo("secret note")
  }

  @Test
  fun openTraceAsksForThatPidAndTimestamp() {
    val history =
      ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) {
        _,
        _ ->
        emptyList()
      }
    var asked: Pair<Int, Long>? = null
    history.traceReader = { _, pid, timestamp ->
      asked = pid to timestamp
      ByteArrayInputStream("dump".encodeToByteArray())
    }
    val withTrace = exit(ExitReason.Anr, 6).copy(hasTrace = true)
    val read = runBlocking { history.openTrace(withTrace)?.use { it.readBytes().decodeToString() } }
    assertThat(read).isEqualTo("dump")
    assertThat(asked).isEqualTo(7 to 1_000L)
    assertThat(runBlocking { history.openTrace(exit(ExitReason.Anr, 6)) }).isNull()
  }

  @Test
  fun captureTagsItsProcessOnInstallUnlessLinkingIsOff() {
    val written = mutableListOf<ByteArray>()
    val linked = TimberLogCapture.Builder(context).build()
    linked.summaryWriter = { _, bytes -> written += bytes }
    linked.install()
    try {
      linked.setExitNote("model: Voz")
      assertThat(written).hasSize(2)
      val decoded = decodeStateSummary(written.last())
      assertThat(decoded.sessionId).isEqualTo(runBlocking { linked.sessions().first().id })
      assertThat(decoded.note).isEqualTo("model: Voz")
    } finally {
      linked.uninstall()
    }

    val unlinked = TimberLogCapture.Builder(context).linkExitsToSessions(false).build()
    unlinked.summaryWriter = { _, bytes -> written += bytes }
    unlinked.install()
    try {
      unlinked.setExitNote("ignored")
      assertThat(written).hasSize(2)
    } finally {
      unlinked.uninstall()
    }
  }

  @Test
  fun slowNoteRedactorDoesNotHoldTheCaptureLock() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    val capture =
      TimberLogCapture.Builder(context)
        .redact {
          entered.countDown()
          check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
          "redacted"
        }
        .build()
    try {
      val writer = executor.submit { capture.setExitNote("secret") }
      assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
      val reader = executor.submit<List<LogSession>> { runBlocking { capture.sessions() } }
      assertThat(reader.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotEmpty()
      release.countDown()
      writer.get(5, java.util.concurrent.TimeUnit.SECONDS)
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun failedNoteRedactionAndForeignSummariesFailClosed() {
    val capture = TimberLogCapture.Builder(context).redact { error("failed") }.build()
    var written: ByteArray? = null
    capture.summaryWriter = { _, bytes -> written = bytes }
    capture.setExitNote("secret")
    capture.install()
    try {
      assertThat(decodeStateSummary(written).note).isEqualTo("<redaction failed>")
    } finally {
      capture.uninstall()
    }
    val history =
      ProcessExitHistory(
        context,
        ExitReason.DEFAULT_ALERTS,
        16,
        { error("failed") },
        Dispatchers.Unconfined,
      ) { _, _ ->
        listOf(
          RawExit(
            1,
            1,
            4,
            null,
            100,
            0,
            0,
            "app",
            stateSummary = "foreign secret".encodeToByteArray(),
          ),
        )
      }
    assertThat((history.state.value as ProcessExitState.Loaded).exits.single().stateSummary)
      .isEqualTo("<redaction failed>")
  }

  @Test
  fun exportNamesAreSortableAndRelativeTimesRead() {
    assertThat(exit(ExitReason.Anr, 6).exportFileName("anr", "txt"))
      .matches("anr-\\d{8}-\\d{6}\\.txt")
    assertThat(relativeTime(0, 30_000)).isEqualTo("just now")
    assertThat(relativeTime(0, 5 * 60_000)).isEqualTo("5 min ago")
    assertThat(relativeTime(0, 3 * 3_600_000)).isEqualTo("3 h ago")
    assertThat(relativeTime(0, 3 * 86_400_000)).isEqualTo("3 days ago")
  }
}
