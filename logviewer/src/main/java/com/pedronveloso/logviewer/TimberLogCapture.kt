package com.pedronveloso.logviewer

import android.content.Context
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Opt-in Timber capture. Call [install] once from the host Application. With persistence enabled,
 * each accepted emission is synced to internal disk storage before Timber returns. This can slow
 * logging on the calling thread; keep high-volume logs out of critical UI paths.
 */
class TimberLogCapture(
  context: Context,
  private val capacity: Int = 1000,
  persistAcrossCrashes: Boolean = false,
  private val redact: ((String) -> String)? = null,
  private val clock: () -> Long = System::currentTimeMillis,
) : LogSource {
  init {
    require(capacity > 0) { "capacity must be positive" }
  }

  private val lock = Any()
  private val startedAt = clock()
  private val sessionId =
    String.format(Locale.US, "%013d", startedAt) + "-" + UUID.randomUUID().toString().take(8)
  private val memory = ArrayDeque<LogEntry>(capacity)
  private var nextId = 0L
  private var planted = false
  private val journalResult =
    if (persistAcrossCrashes) runCatching { DiskJournal(context.applicationContext, sessionId) }
    else null
  private val journal = journalResult?.getOrNull()
  private var diskWritable = journal != null
  private val revision = MutableStateFlow(0L)
  override val changes: Flow<Unit> = revision.map {}
  private val mutableCapabilities =
    MutableStateFlow(
      LogCapabilities(true, persistAcrossCrashes, true, redact != null, isLibraryCapture = true)
    )
  override val capabilities: StateFlow<LogCapabilities> = mutableCapabilities.asStateFlow()
  private val mutableHealth =
    MutableStateFlow(
      LogHealth(installed = false, writeError = journalResult?.exceptionOrNull()?.message)
    )
  override val health: StateFlow<LogHealth> = mutableHealth.asStateFlow()

  private val tree =
    object : Timber.DebugTree() {
      override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        append(priority, tag, message)
      }
    }

  /** Plant only this tree; unrelated Timber trees remain the host app's responsibility. */
  fun install() =
    synchronized(lock) {
      if (!planted) {
        Timber.plant(tree)
        planted = true
        mutableHealth.value = mutableHealth.value.copy(installed = true)
      }
    }

  fun uninstall() =
    synchronized(lock) {
      if (planted) {
        Timber.uproot(tree)
        planted = false
        mutableHealth.value = mutableHealth.value.copy(installed = false)
      }
    }

  override suspend fun sessions(): List<LogSession> =
    synchronized(lock) {
      val current = LogSession(sessionId, startedAt, true)
      val diskSessions = runCatching {
        journal?.sessions().orEmpty()
      }
        .getOrElse {
          mutableHealth.value =
            mutableHealth.value.copy(readError = it.message ?: "Could not list sessions")
          emptyList()
        }
      listOf(current) + diskSessions.filterNot { it.id == sessionId }
    }

  override suspend fun entries(sessionId: String): List<LogEntry> =
    synchronized(lock) {
      val diskEntries = runCatching {
        journal?.read(sessionId).orEmpty()
      }
        .getOrElse {
          mutableHealth.value =
            mutableHealth.value.copy(readError = it.message ?: "Could not read logs")
          emptyList()
        }
      if (sessionId != this.sessionId) diskEntries
      else (diskEntries + memory).associateBy(LogEntry::id).values.sortedBy(LogEntry::id)
    }

  override suspend fun clear(sessionId: String) =
    synchronized(lock) {
      if (sessionId == this.sessionId) memory.clear()
      runCatching { journal?.clear(sessionId) }
        .onFailure {
          mutableHealth.value =
            mutableHealth.value.copy(writeError = it.message ?: "Could not clear logs")
        }
      revision.value += 1
    }

  private fun append(priority: Int, tag: String?, message: String) {
    fun safeRedact(value: String): String = runCatching {
      (redact?.invoke(value) ?: value)
    }
      .getOrDefault("<redaction failed>")
    val redactedMessage = safeRedact(message)
    val entryMessage =
      if (redactedMessage.length > MAX_MESSAGE_CHARS)
        redactedMessage.take(MAX_MESSAGE_CHARS) + "\n<truncated>"
      else redactedMessage
    val entryTag = tag?.let(::safeRedact)?.take(MAX_TAG_CHARS)
    synchronized(lock) {
      val entry = LogEntry(nextId++, sessionId, clock(), priority, entryTag, entryMessage)
      if (memory.size == capacity) memory.removeFirst()
      memory.addLast(entry)
      if (diskWritable) {
        runCatching { journal?.write(entry) }
          .onSuccess {
            mutableHealth.value =
              mutableHealth.value.copy(lastDiskWriteMillis = entry.timestampMillis)
          }
          .onFailure {
            diskWritable = false
            mutableHealth.value =
              mutableHealth.value.copy(writeError = it.message ?: "Could not write logs")
          }
      }
      revision.value += 1
    }
  }

  private companion object {
    const val MAX_MESSAGE_CHARS = 12_000
    const val MAX_TAG_CHARS = 256
  }
}
