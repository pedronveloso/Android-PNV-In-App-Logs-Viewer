package com.pedronveloso.logviewer

import android.util.Log
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogPresentationTest {
  private val entry = LogEntry(1, "session", 0, Log.WARN, "Network", "Connection failed")

  @Test
  fun `filter checks minimum priority and query in tag or message`() {
    assertThat(entry.matches(LogFilter(LogLevel.ERROR))).isFalse()
    assertThat(entry.matches(LogFilter(LogLevel.WARN, "network"))).isTrue()
    assertThat(entry.matches(LogFilter(LogLevel.DEBUG, "FAILED"))).isTrue()
    assertThat(entry.matches(LogFilter(query = "missing"))).isFalse()
  }

  @Test
  fun `elapsed time uses whole units and omits unavailable or backward intervals`() {
    assertThat(formatElapsedTime(null, 300)).isNull()
    assertThat(formatElapsedTime(600, 300)).isNull()
    assertThat(formatElapsedTime(300, 300)).isEqualTo("+0 ms")
    assertThat(formatElapsedTime(300, 600)).isEqualTo("+300 ms")
    assertThat(formatElapsedTime(0, 999)).isEqualTo("+999 ms")
    assertThat(formatElapsedTime(0, 1_000)).isEqualTo("+1s")
    assertThat(formatElapsedTime(0, 10_100)).isEqualTo("+10s")
    assertThat(formatElapsedTime(0, 60_000)).isEqualTo("+1m")
    assertThat(formatElapsedTime(0, 3_600_000)).isEqualTo("+1h")
    assertThat(formatElapsedTime(0, 86_400_000)).isEqualTo("+1d")
  }

  @Test
  fun `class is bold and method follows the dollar sign on a new line`() {
    val tag = styledTag("Outer\$Inner\$someFunction")
    assertThat(tag.text).isEqualTo("Outer\$Inner\$\nsomeFunction")
    assertThat(tag.spanStyles).hasSize(1)
    assertThat(tag.spanStyles[0].item.fontWeight).isEqualTo(FontWeight.Bold)
    assertThat(tag.spanStyles[0].start).isEqualTo(0)
    assertThat(tag.spanStyles[0].end).isEqualTo("Outer\$Inner".length)
    assertThat(styledTag("PlainTag").spanStyles).isEmpty()
    assertThat(styledTag("PlainTag").text).isEqualTo("PlainTag")
  }

  @Test
  fun `break anywhere adds zero width breaks without altering visible text`() {
    val broken = breakAnywhere("ab\ncd😀")
    assertThat(broken.replace("\u200B", "")).isEqualTo("ab\ncd😀")
    assertThat(broken).startsWith("a\u200Bb\u200B\nc\u200Bd\u200B")
    assertThat(broken).doesNotContain("\n\u200B")
    assertThat(broken).doesNotContain("\uD83D\u200B")
  }

  @Test
  fun `export includes separate throwable once and preserves legacy messages`() {
    val trace = "java.lang.IllegalStateException: boom\n at Sample.run(Sample.kt:1)"
    val newEntry = entry.copy(message = "Failed", throwableStackTrace = trace)
    val legacyEntry = entry.copy(message = "Failed\n$trace")
    assertThat(formatLogEntries(listOf(newEntry)).split(trace)).hasSize(2)
    assertThat(formatLogEntries(listOf(legacyEntry)).split(trace)).hasSize(2)
  }

  @Test
  fun `memory only and no live updates produce actionable guidance`() {
    val messages =
      statusMessages(
        LogCapabilities(true, false, false, false),
        LogHealth(),
      )
    assertThat(messages.map(StatusMessage::title))
      .containsAtLeast(
        "Logs live only in memory",
        "Live updates unavailable",
        "Redaction is not configured",
      )
  }

  @Test
  fun `external persistence is identified as declared and write failures are visible`() {
    val capabilities = LogCapabilities(true, true, true, true)
    assertThat(
        statusMessages(capabilities, LogHealth())
          .single { it.title == "Crash persistence configured" }
          .detail,
      )
      .contains("declared by the host app")
    assertThat(
        statusMessages(capabilities, LogHealth(writeError = "Storage full"))
          .map(StatusMessage::title),
      )
      .contains("Persistent writes failed")
  }

  @Test
  fun `startup loss stays visible alongside working persistence`() {
    val messages =
      statusMessages(
        LogCapabilities(true, true, true, true, isLibraryCapture = true),
        LogHealth(installed = true, lastDiskWriteMillis = 123, startupEntriesDropped = true),
      )
    assertThat(messages.map(StatusMessage::title))
      .containsAtLeast("Startup logs were dropped", "Crash persistence configured")
    assertThat(messages.single { it.title == "Crash persistence configured" }.detail)
      .contains("buffers early entries")
  }
}
