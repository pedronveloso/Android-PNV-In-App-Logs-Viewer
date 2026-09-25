package com.pedronveloso.logviewer

import android.util.Log
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
          .detail
      )
      .contains("declared by the host app")
    assertThat(
        statusMessages(capabilities, LogHealth(writeError = "Storage full"))
          .map(StatusMessage::title)
      )
      .contains("Persistent writes failed")
  }
}
