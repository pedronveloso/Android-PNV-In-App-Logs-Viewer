package com.pedronveloso.logviewer

import java.net.URI

/**
 * Reusable, opt-in redaction for captured logs. Pattern matching cannot recognize every sensitive
 * value, so callers should avoid logging secrets and may supply a stricter policy when needed.
 */
object StandardLogRedactor {
  private const val REDACTED = "<redacted>"
  private val url = Regex("https?://[^\\s)\"'<>,;]+", RegexOption.IGNORE_CASE)
  private val credentialAssignment =
    Regex(
      """(?i)\b(authorization|proxy-authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|password|passwd|secret|client[_-]?secret|session[_-]?id)\b(\s*[:=]\s*)("[^"]*"|'[^']*'|(?:Bearer|Basic)\s+[^\s,;}\]]+|[^\s,;}\]]+)"""
    )
  private val bearerOrBasic = Regex("""(?i)\b(bearer|basic)\s+[a-z0-9._~+/\-=]+""")
  private val email = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
  private val uuid =
    Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b""")

  fun redact(value: String): String =
    uuid.replace(
      email.replace(
        bearerOrBasic.replace(
          credentialAssignment.replace(url.replace(value) { sanitizeUrl(it.value) }) {
            it.groupValues[1] + it.groupValues[2] + REDACTED
          }
        ) {
          it.groupValues[1] + " " + REDACTED
        }
      ) {
        REDACTED
      }
    ) {
      REDACTED
    }

  private fun sanitizeUrl(value: String): String {
    return try {
      val uri = URI(value)
      val scheme = uri.scheme?.lowercase() ?: return "<no-scheme>"
      val host = uri.host ?: return "<no-host>"
      val hostWithPort = if (uri.port != -1) "$host:${uri.port}" else host
      "$scheme://$hostWithPort/$REDACTED"
    } catch (_: Exception) {
      "<malformed-url>"
    }
  }
}
