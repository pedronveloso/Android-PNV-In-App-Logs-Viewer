package com.pedronveloso.logviewer

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Opt-in Timber capture. Call [install] once from the host Application. With persistence enabled,
 * startup emissions are buffered until disk storage is ready. Later emissions are synced before
 * Timber returns, which can slow logging on the calling thread.
 */
class TimberLogCapture
internal constructor(
  context: Context,
  private val capacity: Int,
  private val persistAcrossCrashes: Boolean,
  private val redact: ((String) -> String)?,
  private val clock: () -> Long,
  private val ioDispatcher: CoroutineDispatcher,
  private val maxPendingBytes: Int,
  private val createJournal: suspend (Context, String) -> DiskJournal,
) : LogSource {
  constructor(
    context: Context,
    capacity: Int = 1000,
    persistAcrossCrashes: Boolean = false,
    redact: ((String) -> String)? = null,
    clock: () -> Long = System::currentTimeMillis,
  ) : this(
    context,
    capacity,
    persistAcrossCrashes,
    redact,
    clock,
    Dispatchers.IO,
    MAX_PENDING_BYTES,
    { appContext, id -> DiskJournal(appContext, id) },
  )

  init {
    require(capacity > 0) { "capacity must be positive" }
    require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
  }

  private val appContext = context.applicationContext
  private val lock = Any()
  private val startedAt = clock()
  private val sessionId =
    String.format(Locale.US, "%013d", startedAt) + "-" + UUID.randomUUID().toString().take(8)
  private val memory = ArrayDeque<LogEntry>(capacity)
  private val pending = ArrayDeque<PendingEntry>()
  private var pendingBytes = 0
  private var nextId = 0L
  private var planted = false
  private var journal: DiskJournal? = null
  private val initialization = CompletableDeferred<Unit>()
  private val revision = MutableStateFlow(0L)
  override val changes: Flow<Unit> = revision.map {}
  private val mutableCapabilities =
    MutableStateFlow(
      LogCapabilities(true, persistAcrossCrashes, true, redact != null, isLibraryCapture = true)
    )
  override val capabilities: StateFlow<LogCapabilities> = mutableCapabilities.asStateFlow()
  private val mutableHealth = MutableStateFlow(LogHealth(installed = false))
  override val health: StateFlow<LogHealth> = mutableHealth.asStateFlow()

  private val tree =
    object : Timber.DebugTree() {
      override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        append(priority, tag, message, t)
      }
    }

  init {
    if (persistAcrossCrashes) {
      CoroutineScope(SupervisorJob() + ioDispatcher).launch { initializeJournal() }
    } else {
      initialization.complete(Unit)
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

  override suspend fun sessions(): List<LogSession> {
    initialization.await()
    return withContext(ioDispatcher) {
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
    }
  }

  override suspend fun entries(sessionId: String): List<LogEntry> {
    initialization.await()
    return withContext(ioDispatcher) {
      synchronized(lock) {
        val diskEntries = runCatching {
          journal?.read(sessionId).orEmpty()
        }
          .getOrElse {
            mutableHealth.value =
              mutableHealth.value.copy(readError = it.message ?: "Could not read logs")
            emptyList()
          }
        if (sessionId != this@TimberLogCapture.sessionId) diskEntries
        else (diskEntries + memory).associateBy(LogEntry::id).values.sortedBy(LogEntry::id)
      }
    }
  }

  override suspend fun clear(sessionId: String) {
    initialization.await()
    withContext(ioDispatcher) {
      synchronized(lock) {
        if (sessionId == this@TimberLogCapture.sessionId) memory.clear()
        runCatching { journal?.clear(sessionId) }
          .onFailure {
            mutableHealth.value =
              mutableHealth.value.copy(writeError = it.message ?: "Could not clear logs")
          }
        revision.value += 1
      }
    }
  }

  private suspend fun initializeJournal() {
    val initialized =
      try {
        createJournal(appContext, sessionId)
      } catch (failure: Exception) {
        synchronized(lock) {
          pending.clear()
          pendingBytes = 0
          mutableHealth.value =
            mutableHealth.value.copy(writeError = failure.message ?: "Could not prepare logs")
          initialization.complete(Unit)
        }
        return
      }
    var flushed = 0
    while (flushed < MAX_BACKGROUND_FLUSH_ENTRIES) {
      val next =
        synchronized(lock) {
          if (pending.isEmpty()) {
            journal = initialized
            initialization.complete(Unit)
            null
          } else {
            pending.removeFirst().also { pendingBytes -= it.bytes }
          }
        } ?: return
      try {
        initialized.write(next.entry)
      } catch (failure: Exception) {
        synchronized(lock) {
          pending.clear()
          pendingBytes = 0
          mutableHealth.value =
            mutableHealth.value.copy(writeError = failure.message ?: "Could not write logs")
          initialization.complete(Unit)
        }
        return
      }
      synchronized(lock) {
        mutableHealth.value =
          mutableHealth.value.copy(lastDiskWriteMillis = next.entry.timestampMillis)
      }
      flushed++
    }
    synchronized(lock) {
      while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        pendingBytes -= next.bytes
        try {
          initialized.write(next.entry)
        } catch (failure: Exception) {
          pending.clear()
          pendingBytes = 0
          mutableHealth.value =
            mutableHealth.value.copy(writeError = failure.message ?: "Could not write logs")
          initialization.complete(Unit)
          return
        }
        mutableHealth.value =
          mutableHealth.value.copy(lastDiskWriteMillis = next.entry.timestampMillis)
      }
      journal = initialized
      initialization.complete(Unit)
    }
  }

  private fun append(priority: Int, tag: String?, message: String, throwable: Throwable?) {
    fun safeRedact(value: String): String = runCatching {
      (redact?.invoke(value) ?: value)
    }
      .getOrDefault("<redaction failed>")
    fun truncate(value: String): String =
      if (value.length > MAX_MESSAGE_CHARS) value.take(MAX_MESSAGE_CHARS) + "\n<truncated>"
      else value
    val stackTrace = throwable?.let {
      StringWriter(256).also { writer -> it.printStackTrace(PrintWriter(writer)) }.toString()
    }
    val separateTrace = stackTrace?.takeIf { message == it || message.endsWith("\n$it") }
    val plainMessage =
      when {
        separateTrace == null -> message
        message == separateTrace -> ""
        message.endsWith("\n$separateTrace") -> message.removeSuffix("\n$separateTrace")
        else -> message
      }
    val entryMessage = truncate(safeRedact(plainMessage))
    val entryTag = tag?.let(::safeRedact)?.take(MAX_TAG_CHARS)
    val entryStackTrace = separateTrace?.let(::safeRedact)?.trimEnd()?.let(::truncate)
    synchronized(lock) {
      val entry =
        LogEntry(nextId++, sessionId, clock(), priority, entryTag, entryMessage, entryStackTrace)
      if (memory.size == capacity) memory.removeFirst()
      memory.addLast(entry)
      val activeJournal = journal
      if (activeJournal != null) {
        runCatching { activeJournal.write(entry) }
          .onSuccess {
            mutableHealth.value =
              mutableHealth.value.copy(lastDiskWriteMillis = entry.timestampMillis)
          }
          .onFailure {
            journal = null
            mutableHealth.value =
              mutableHealth.value.copy(writeError = it.message ?: "Could not write logs")
          }
      } else if (persistAcrossCrashes && !initialization.isCompleted) {
        val bytes = DiskJournal.encodedRecordSize(entry)
        pending.addLast(PendingEntry(entry, bytes))
        pendingBytes += bytes
        var discarded = false
        while (pendingBytes > maxPendingBytes) {
          pendingBytes -= pending.removeFirst().bytes
          discarded = true
        }
        if (discarded) {
          mutableHealth.value =
            mutableHealth.value.copy(
              writeError = "Startup log buffer filled; older entries were not persisted"
            )
        }
      }
      revision.value += 1
    }
  }

  private companion object {
    const val MAX_BACKGROUND_FLUSH_ENTRIES = 64
    const val MAX_PENDING_BYTES = 2 * 1024 * 1024
    const val MAX_MESSAGE_CHARS = 12_000
    const val MAX_TAG_CHARS = 256
  }

  private data class PendingEntry(val entry: LogEntry, val bytes: Int)
}
