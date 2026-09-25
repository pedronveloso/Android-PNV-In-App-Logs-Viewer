package com.pedronveloso.logviewer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One rendered Timber emission. [message] already includes Timber's throwable stack trace. */
data class LogEntry(
  val id: Long,
  val sessionId: String,
  val timestampMillis: Long,
  val priority: Int,
  val tag: String?,
  val message: String,
)

data class LogSession(
  val id: String,
  val startedAtMillis: Long,
  val isCurrent: Boolean,
)

/** For external sources, these are claims supplied by the host app. */
data class LogCapabilities(
  val hasInMemoryLogs: Boolean,
  val persistsAcrossCrashes: Boolean,
  val hasLiveUpdates: Boolean,
  val redactionConfigured: Boolean,
  val isLibraryCapture: Boolean = false,
  val canClear: Boolean = true,
)

/** Observable integration health. Null [installed] means the host did not provide this signal. */
data class LogHealth(
  val installed: Boolean? = null,
  val readError: String? = null,
  val writeError: String? = null,
  val lastDiskWriteMillis: Long? = null,
)

/**
 * Adapt an existing Timber store by implementing this interface. Return immutable snapshots; the
 * viewer does filtering itself. A null [changes] means that the viewer offers manual refresh.
 */
interface LogSource {
  val changes: Flow<Unit>?
  val capabilities: StateFlow<LogCapabilities>
  val health: StateFlow<LogHealth>

  suspend fun sessions(): List<LogSession>

  suspend fun entries(sessionId: String): List<LogEntry>

  suspend fun clear(sessionId: String)
}
