package com.pedronveloso.logviewer

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewerExportTest {
  @org.junit.Before
  fun resetProviderPaths() {
    // FileProvider caches paths by authority, but Robolectric gives each test a new cacheDir.
    val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
    cache.isAccessible = true
    (cache.get(null) as MutableMap<*, *>).clear()
  }

  @Test
  fun `share grants read access to provider content`() {
    val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    val exports = File(context.cacheDir, "pnv-logviewer-exports")
    exports.deleteRecursively()
    shareLogs(context, "redacted log")

    val chooser = Shadows.shadowOf(context).nextStartedActivity
    assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
    val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(send.type).isEqualTo("text/plain")
    assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
    assertThat(uri.authority).isEqualTo("${context.packageName}.pnvlogviewer.fileprovider")
    assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    assertThat(
        context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() },
      )
      .isEqualTo("redacted log")
  }

  @Test
  fun exitExportsPreserveRawDiagnosticsAndGrantProviderAccess() =
    kotlinx.coroutines.runBlocking {
      val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
      val history =
        ProcessExitHistory(
          context,
          ExitReason.DEFAULT_ALERTS,
          16,
          { "redacted" },
          kotlinx.coroutines.Dispatchers.Unconfined,
        ) { _, _ ->
          emptyList()
        }
      val bytes = "secret ANR dump".encodeToByteArray()
      history.traceReader = { _, _, _ -> bytes.inputStream() }
      val exit =
        ProcessExit(
          1_000,
          7,
          ExitReason.Anr,
          6,
          "redacted",
          100,
          0,
          0,
          "app",
          hasTrace = true,
          traceKind = TraceKind.AnrDump,
        )
      fun shared(expectedType: String): ByteArray {
        val chooser = Shadows.shadowOf(context).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.type).isEqualTo(expectedType)
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
      }
      shareExitTrace(context, history, exit)
      assertThat(shared("text/plain")).isEqualTo(bytes)
      shareExitDetails(context, history, exit)
      assertThat(shared("text/plain").decodeToString()).endsWith("Trace\nsecret ANR dump")
      val binary = byteArrayOf(0, 1, -1, 127)
      history.traceReader = { _, _, _ -> binary.inputStream() }
      shareExitTrace(context, history, exit.copy(traceKind = TraceKind.Tombstone))
      assertThat(shared("application/octet-stream")).isEqualTo(binary)
      shareExitDetails(context, history, exit.copy(traceKind = TraceKind.Tombstone))
      assertThat(shared("text/plain").decodeToString()).doesNotContain("Trace\n")
    }

  @Test
  fun detailsIncludeAllLinkedSessionEntriesAndPreserveTheirContent() =
    kotlinx.coroutines.runBlocking {
      val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
      val history =
        ProcessExitHistory(
          context,
          ExitReason.DEFAULT_ALERTS,
          16,
          { "redacted" },
          kotlinx.coroutines.Dispatchers.Unconfined,
        ) { _, _ ->
          emptyList()
        }
      history.traceReader = { _, _, _ -> "raw ANR".byteInputStream() }
      val exit =
        ProcessExit(
          1,
          1,
          ExitReason.Anr,
          6,
          null,
          100,
          0,
          0,
          "app",
          hasTrace = true,
          traceKind = TraceKind.AnrDump,
          sessionId = "linked",
        )
      val entries =
        listOf(
          LogEntry(1, "linked", 1, 2, "Tag", "verbose message"),
          LogEntry(2, "linked", 2, 6, "Tag", "custom source message", "exception trace"),
        )
      var requested: String? = null
      var fail = false
      var retained = entries
      val source =
        object : LogSource {
          override val changes: kotlinx.coroutines.flow.Flow<Unit>? = null
          override val capabilities =
            kotlinx.coroutines.flow.MutableStateFlow(LogCapabilities(true, true, false, true))
          override val health = kotlinx.coroutines.flow.MutableStateFlow(LogHealth())

          override suspend fun sessions() =
            listOf(LogSession("other", 2, true), LogSession("linked", 1, false))

          override suspend fun entries(sessionId: String): List<LogEntry> {
            requested = sessionId
            if (fail) error("Could not read session")
            return retained
          }

          override suspend fun clear(sessionId: String) = Unit
        }
      fun shared(): String {
        val chooser = Shadows.shadowOf(context).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        return context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
      }
      shareExitDetails(context, history, exit, source)
      assertThat(requested).isEqualTo("linked")
      assertThat(shared()).endsWith("Trace\nraw ANR\n\nSession logs\n" + formatLogEntries(entries))
      requested = null
      shareExitDetails(context, history, exit)
      assertThat(shared()).doesNotContain("Session logs\n")
      assertThat(requested).isNull()
      retained = emptyList()
      shareExitDetails(context, history, exit, source)
      assertThat(shared()).endsWith("Session logs\nNo retained log entries.")
      fail = true
      assertThat(
          runCatching { shareExitDetails(context, history, exit, source) }
            .exceptionOrNull()
            ?.message,
        )
        .isEqualTo("Could not read session")
      assertThat(Shadows.shadowOf(context).nextStartedActivity).isNull()
    }

  @Test
  fun exportsAreUniqueAndFailedWritesAreRemoved() {
    val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    val directory = File(context.cacheDir, "pnv-logviewer-exports")
    directory.deleteRecursively()
    val first = exportFile(context, "anr-20261114-120000.txt") { it.write("first".toByteArray()) }
    val second = exportFile(context, "anr-20261114-120000.txt") { it.write("second".toByteArray()) }
    assertThat(first).isNotEqualTo(second)
    assertThat(first.readText()).isEqualTo("first")
    assertThat(first.name).startsWith("anr-20261114-120000-")
    assertThat(first.extension).isEqualTo("txt")
    assertThat(
        runCatching {
          exportFile(context, "broken.txt") {
            it.write(1)
            error("failed")
          }
        }
          .isFailure,
      )
      .isTrue()
    assertThat(directory.listFiles()!!.toList()).containsExactly(first, second)
    first.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
    exportFile(context, "new.txt") { it.write(2) }
    assertThat(first.exists()).isFalse()
    assertThat(second.readText()).isEqualTo("second")
  }

  @Test
  fun concurrentExportsNeverOverwriteOneAnother() {
    val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    File(context.cacheDir, "pnv-logviewer-exports").deleteRecursively()
    val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
      val files =
        (1..8)
          .map { index ->
            executor.submit<File> {
              exportFile(context, "exit-same.txt") { it.write(index.toString().toByteArray()) }
            }
          }
          .map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
      assertThat(files.toSet()).hasSize(8)
      assertThat(files.map { it.readText() })
        .containsExactlyElementsIn((1..8).map { it.toString() })
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun cancelledOrMissingTraceDoesNotLaunchChooser() =
    kotlinx.coroutines.runBlocking {
      val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
      val history =
        ProcessExitHistory(
          context,
          ExitReason.DEFAULT_ALERTS,
          16,
          null,
          kotlinx.coroutines.Dispatchers.Unconfined,
        ) { _, _ ->
          emptyList()
        }
      val exit =
        ProcessExit(
          1,
          1,
          ExitReason.Anr,
          6,
          null,
          100,
          0,
          0,
          "app",
          hasTrace = true,
          traceKind = TraceKind.AnrDump,
        )
      history.traceReader = { _, _, _ -> null }
      assertThat(runCatching { shareExitTrace(context, history, exit) }.exceptionOrNull())
        .isInstanceOf(IllegalStateException::class.java)
      history.traceReader = { _, _, _ ->
        throw kotlinx.coroutines.CancellationException("cancelled")
      }
      assertThat(runCatching { shareExitTrace(context, history, exit) }.exceptionOrNull())
        .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
      assertThat(runCatching { shareExitDetails(context, history, exit) }.exceptionOrNull())
        .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
      assertThat(Shadows.shadowOf(context).nextStartedActivity).isNull()
    }
}
