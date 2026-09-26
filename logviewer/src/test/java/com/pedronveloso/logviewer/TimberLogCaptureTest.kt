package com.pedronveloso.logviewer

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class TimberLogCaptureTest {
  private lateinit var context: Context
  private lateinit var directory: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    directory = File(context.noBackupFilesDir, "pnv-logviewer")
    directory.deleteRecursively()
    Timber.uprootAll()
  }

  @After
  fun tearDown() {
    Timber.uprootAll()
    directory.deleteRecursively()
  }

  @Test
  fun `bounded memory keeps latest emissions and redacts before storage`() = runBlocking {
    val capture =
      TimberLogCapture(context, capacity = 2, redact = { it.replace("secret", "hidden") })
    capture.install()
    repeat(3) { Timber.tag("Key").d("secret %d", it) }

    val entries = capture.entries(capture.sessions().first().id)
    assertThat(entries.map(LogEntry::id)).containsExactly(1L, 2L).inOrder()
    assertThat(entries.map(LogEntry::message)).containsExactly("hidden 1", "hidden 2").inOrder()
    assertThat(entries.map(LogEntry::tag)).containsExactly("Key", "Key")
    Unit
  }

  @Test
  fun `standard policy redacts tags messages and throwable text before persistence`() =
    runBlocking {
      val capture =
        TimberLogCapture(
          context,
          persistAcrossCrashes = true,
          clock = { 1000L },
          redact = StandardLogRedactor::redact,
        )
      capture.install()
      Timber.tag("person@example.com")
        .e(
          IllegalStateException("password=hunter2"),
          "Visited https://example.com/private?token=secret123",
        )
      capture.uninstall()

      assertThat(capture.capabilities.value.redactionConfigured).isTrue()
      val restarted = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 2000L })
      val previous = restarted.sessions().single { !it.isCurrent }
      val stored = restarted.entries(previous.id).single()
      assertThat(stored.tag).isEqualTo("<redacted>")
      assertThat(stored.message).contains("https://example.com/<redacted>")
      assertThat(stored.message).contains("password=<redacted>")
      val exported = formatLogEntries(listOf(stored))
      assertThat(exported).doesNotContain("person@example.com")
      assertThat(exported).doesNotContain("secret123")
      assertThat(exported).doesNotContain("hunter2")
    }

  @Test
  fun `capture without a callback keeps existing opt in behavior`() = runBlocking {
    val capture = TimberLogCapture(context)
    capture.install()
    Timber.tag("Private").d("password=hunter2")

    assertThat(capture.capabilities.value.redactionConfigured).isFalse()
    assertThat(capture.entries(capture.sessions().first().id).single().message)
      .isEqualTo("password=hunter2")
  }

  @Test
  fun `concurrent emissions have stable unique IDs`() = runBlocking {
    val capture = TimberLogCapture(context, capacity = 4000)
    capture.install()
    val start = CountDownLatch(1)
    val writers =
      (0 until 8).map { writer ->
        thread {
          start.await()
          repeat(250) { Timber.d("writer %d entry %d", writer, it) }
        }
      }
    start.countDown()
    writers.forEach(Thread::join)

    val ids = capture.entries(capture.sessions().first().id).map(LogEntry::id)
    assertThat(ids).hasSize(2000)
    assertThat(ids.toSet()).hasSize(2000)
    assertThat(ids).isInStrictOrder()
  }

  @Test
  fun `disk entries survive a new capture after process restart`() = runBlocking {
    val first = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 1000L })
    first.install()
    Timber.tag("CrashTest").e(IllegalStateException("boom"), "Before crash")
    first.uninstall()

    val restarted = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 2000L })
    val previous = restarted.sessions().single { !it.isCurrent }
    val restored = restarted.entries(previous.id).single()
    assertThat(restored.tag).isEqualTo("CrashTest")
    assertThat(restored.priority).isEqualTo(Log.ERROR)
    assertThat(restored.message).contains("Before crash")
    assertThat(restored.message).contains("java.lang.IllegalStateException: boom")
    assertThat(formatLogEntries(listOf(restored)).split("java.lang.IllegalStateException: boom"))
      .hasSize(2)
  }

  @Test
  fun `incomplete tail record is ignored after restart`() = runBlocking {
    val first = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 3000L })
    first.install()
    Timber.i("Complete record")
    first.uninstall()
    val oldId = first.sessions().first().id
    RandomAccessFile(File(directory, "s-$oldId.0"), "rw").use {
      it.seek(it.length())
      it.writeInt(100)
      it.writeInt(0)
      it.write(byteArrayOf(1, 2, 3))
    }

    val restarted = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 4000L })
    assertThat(restarted.entries(oldId).map(LogEntry::message)).containsExactly("Complete record")
    Unit
  }

  @Test
  fun `disk segments and retained sessions stay bounded`() = runBlocking {
    repeat(4) { session ->
      val capture =
        TimberLogCapture(context, persistAcrossCrashes = true, clock = { (session + 1) * 1000L })
      capture.install()
      repeat(160) { Timber.d("%s", "x".repeat(10_000)) }
      capture.uninstall()
    }
    val current = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 5000L })
    assertThat(current.sessions()).hasSize(3)
    assertThat(
        directory.listFiles().orEmpty().filter { it.name.endsWith(".0") || it.name.endsWith(".1") }
      )
      .hasSize(5)
    assertThat(directory.listFiles().orEmpty().all { it.length() <= 1024 * 1024 }).isTrue()
  }

  @Test
  fun `clear removes selected session from memory and disk`() = runBlocking {
    val capture = TimberLogCapture(context, persistAcrossCrashes = true)
    capture.install()
    Timber.d("Clear me")
    val sessionId = capture.sessions().first().id
    capture.clear(sessionId)
    assertThat(capture.entries(sessionId)).isEmpty()
    assertThat(directory.listFiles().orEmpty().filter { it.name.contains(sessionId) }).isEmpty()
  }

  @Test
  fun `disk write failure keeps in-memory capture available`() = runBlocking {
    val capture = TimberLogCapture(context, persistAcrossCrashes = true)
    capture.install()
    directory.deleteRecursively()
    directory.writeText("blocked")

    Timber.w("Still captured")

    val current = capture.sessions().first().id
    assertThat(capture.entries(current).map(LogEntry::message)).containsExactly("Still captured")
    assertThat(capture.health.value.writeError).isNotNull()
  }
}
