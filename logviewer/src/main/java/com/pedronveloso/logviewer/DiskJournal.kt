package com.pedronveloso.logviewer

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32

/** Two bounded segments per session. A checksum and length discard an interrupted last record. */
internal interface Journal {
  fun write(entry: LogEntry)

  fun read(sessionId: String): List<LogEntry>

  fun sessions(): List<LogSession>

  fun clear(sessionId: String)
}

internal class DiskJournal(context: Context, private val currentId: String) : Journal {
  private val directory = File(context.noBackupFilesDir, "pnv-logviewer")

  init {
    check(directory.isDirectory || directory.mkdirs()) { "Could not create log directory" }
    check(file(currentId, 0).exists() || file(currentId, 0).createNewFile()) {
      "Could not create log file"
    }
    prune()
  }

  override fun write(entry: LogEntry) {
    val payload = encode(entry)
    check(payload.size <= MAX_RECORD_BYTES) { "Log entry exceeds the disk record limit" }
    val current = file(currentId, 0)
    if (current.length() + payload.size + HEADER_BYTES > SEGMENT_BYTES) {
      file(currentId, 1).delete()
      check(!current.exists() || current.renameTo(file(currentId, 1))) {
        "Could not rotate log file"
      }
    }
    RandomAccessFile(current, "rw").use { output ->
      output.seek(output.length())
      output.writeInt(payload.size)
      output.writeInt(CRC32().apply { update(payload) }.value.toInt())
      output.write(payload)
      output.fd.sync()
    }
  }

  override fun read(sessionId: String): List<LogEntry> =
    if (VALID_ID.matches(sessionId))
      listOf(file(sessionId, 1), file(sessionId, 0)).flatMap { readFile(it, sessionId) }
    else emptyList()

  override fun sessions(): List<LogSession> {
    val ids =
      directory
        .listFiles()
        .orEmpty()
        .mapNotNull { SESSION_FILE.matchEntire(it.name)?.groupValues?.get(1) }
        .distinct()
        .sortedDescending()
    return ids.mapNotNull { id ->
      id.substringBefore('-').toLongOrNull()?.let { LogSession(id, it, id == currentId) }
    }
  }

  override fun clear(sessionId: String) {
    require(VALID_ID.matches(sessionId)) { "Invalid session ID" }
    listOf(file(sessionId, 0), file(sessionId, 1)).forEach {
      check(!it.exists() || it.delete()) { "Could not delete log file" }
    }
  }

  private fun prune() {
    sessions().drop(MAX_SESSIONS).forEach { clear(it.id) }
  }

  private fun file(id: String, segment: Int) = File(directory, "s-$id.$segment")

  private fun readFile(file: File, sessionId: String): List<LogEntry> {
    if (!file.isFile) return emptyList()
    val entries = ArrayList<LogEntry>()
    RandomAccessFile(file, "r").use { input ->
      while (input.filePointer + HEADER_BYTES <= input.length()) {
        val size = input.readInt()
        val checksum = input.readInt()
        if (size !in 1..MAX_RECORD_BYTES || input.filePointer + size > input.length()) break
        val payload = ByteArray(size)
        input.readFully(payload)
        if (CRC32().apply { update(payload) }.value.toInt() != checksum) break
        val decoded = runCatching { decode(payload, sessionId) }.getOrNull() ?: break
        entries.add(decoded)
      }
    }
    return entries
  }

  private fun decode(payload: ByteArray, sessionId: String): LogEntry {
    val parts = String(payload, StandardCharsets.UTF_8).split('\t')
    require(parts.size == 5 || parts.size == 6)
    fun field(value: String) = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    val tag = field(parts[3]).ifBlank { null }
    return LogEntry(
      parts[0].toLong(),
      sessionId,
      parts[1].toLong(),
      parts[2].toInt(),
      tag,
      field(parts[4]),
      parts.getOrNull(5)?.let(::field)?.ifBlank { null },
    )
  }

  companion object {
    internal fun encodedRecordSize(entry: LogEntry): Int {
      fun fieldSize(value: String): Int =
        ((value.toByteArray(StandardCharsets.UTF_8).size + 2) / 3) * 4
      return HEADER_BYTES +
        entry.id.toString().length +
        entry.timestampMillis.toString().length +
        entry.priority.toString().length +
        fieldSize(entry.tag.orEmpty()) +
        fieldSize(entry.message) +
        fieldSize(entry.throwableStackTrace.orEmpty()) +
        5 // tab separators
    }

    private fun encode(entry: LogEntry): ByteArray {
      val encoder = Base64.getEncoder()
      fun field(value: String) = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
      return listOf(
          entry.id,
          entry.timestampMillis,
          entry.priority,
          field(entry.tag.orEmpty()),
          field(entry.message),
          field(entry.throwableStackTrace.orEmpty()),
        )
        .joinToString("\t")
        .toByteArray(StandardCharsets.UTF_8)
    }

    private val VALID_ID = Regex("\\d{13}-[0-9a-f]{8}")
    private val SESSION_FILE = Regex("s-(\\d{13}-[0-9a-f]{8})\\.[01]")
    private const val HEADER_BYTES = 8
    private const val MAX_RECORD_BYTES = 128 * 1024
    private const val SEGMENT_BYTES = 1024 * 1024
    private const val MAX_SESSIONS = 3
  }
}
