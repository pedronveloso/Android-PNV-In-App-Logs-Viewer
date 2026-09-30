package com.pedronveloso.logviewer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One log emission. [message] can contain trace text when its formatting differs from
 * [throwableStackTrace].
 */
public data class LogEntry(
  public val id: Long,
  public val sessionId: String,
  public val timestampMillis: Long,
  public val priority: Int,
  public val tag: String?,
  public val message: String,
  public val throwableStackTrace: String? = null,
)

public data class LogSession(
  public val id: String,
  public val startedAtMillis: Long,
  public val isCurrent: Boolean,
)

/** For external sources, these are claims supplied by the host app. */
public data class LogCapabilities(
  public val hasInMemoryLogs: Boolean,
  public val persistsAcrossCrashes: Boolean,
  public val hasLiveUpdates: Boolean,
  public val redactionConfigured: Boolean,
  public val isLibraryCapture: Boolean = false,
  public val canClear: Boolean = true,
)

/** Observable integration health. Null [installed] means the host did not provide this signal. */
public data class LogHealth(
  public val installed: Boolean? = null,
  public val readError: String? = null,
  public val writeError: String? = null,
  public val lastDiskWriteMillis: Long? = null,
  public val startupEntriesDropped: Boolean = false,
)

/**
 * Adapt an existing Timber store by implementing this interface. Return immutable snapshots; the
 * viewer does filtering itself. A null [changes] means that the viewer offers manual refresh.
 */
public interface LogSource {
  public val changes: Flow<Unit>?
  public val capabilities: StateFlow<LogCapabilities>
  public val health: StateFlow<LogHealth>

  public suspend fun sessions(): List<LogSession>

  public suspend fun entries(sessionId: String): List<LogEntry>

  public suspend fun clear(sessionId: String)
}
