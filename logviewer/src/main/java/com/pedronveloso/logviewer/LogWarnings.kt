package com.pedronveloso.logviewer

/** Categories of outstanding diagnostics. More categories may be added in future releases. */
public enum class WarningType {
  /** Process exits matching the configured alert reasons that have not been acknowledged. */
  PendingProcessExit,
}

/** A nonzero count for one category of outstanding diagnostics. */
@ConsistentCopyVisibility
public data class LogWarning
internal constructor(public val type: WarningType, public val count: Int)

/**
 * A snapshot of diagnostics needing attention, independent of their underlying data source. This
 * does not count WARN/ERROR log lines or optional capture configuration recommendations. Loading,
 * unsupported and failed exit-history reads currently contribute no warnings; inspect
 * [ProcessExitHistory.state] when that distinction matters.
 */
@ConsistentCopyVisibility
public data class LogWarnings internal constructor(public val warnings: List<LogWarning>) {
  public val totalCount: Int = warnings.sumOf { it.count }
  public val hasWarnings: Boolean = totalCount > 0
}

internal val NoLogWarnings = LogWarnings(emptyList())

internal fun ProcessExitState.warningSummary(): LogWarnings {
  val count = pendingExits.size
  return if (count == 0) NoLogWarnings
  else LogWarnings(listOf(LogWarning(WarningType.PendingProcessExit, count)))
}
