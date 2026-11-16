package com.pedronveloso.logviewer

import android.net.Uri
import androidx.compose.runtime.Composable
import java.io.File

/**
 * The same UTF-8 text export used by Share: the selected session's visible, filtered entries.
 * [file] is app-private cache storage; copy it if it must outlive cache eviction. Exports older
 * than a day are removed on the next export. [uri] is a FileProvider content URI; grant read access
 * when passing it to another app. Caller-provided log content is exported as supplied.
 */
public class LogExport internal constructor(public val file: File, public val uri: Uri) {
  public val mimeType: String = "text/plain"
}

/**
 * Immutable viewer configuration. Build in the UI so icon and callback can use the current host.
 */
public class LogViewerOptions
private constructor(
  internal val showShareButton: Boolean,
  internal val showClearButton: Boolean,
  internal val exportAction: ExportAction?,
) {
  public class Builder {
    private var showShareButton = true
    private var showClearButton = true
    private var exportAction: ExportAction? = null

    /** Controls the built-in log Share button. Exit diagnostic sharing is separate. */
    public fun showShareButton(show: Boolean): Builder = apply { showShareButton = show }

    /** Controls Clear; a read-only source always hides it, even when true. */
    public fun showClearButton(show: Boolean): Builder = apply { showClearButton = show }

    /**
     * Adds one toolbar button beside Share and Clear. [icon] supplies its content; the viewer
     * supplies the accessible [contentDescription] and click handling. Replaces any previous
     * action. The viewer prepares the file off the main thread, then invokes [onExport] on the UI
     * coroutine. Suspend for uploads and move blocking work to an IO dispatcher. Repeated taps are
     * disabled until completion; failures appear in the viewer. Leaving the viewer cancels the
     * operation.
     */
    public fun exportAction(
      contentDescription: String,
      icon: @Composable () -> Unit,
      onExport: suspend (LogExport) -> Unit,
    ): Builder = apply {
      require(contentDescription.isNotBlank()) { "contentDescription must not be blank" }
      exportAction = ExportAction(contentDescription, icon, onExport)
    }

    public fun build(): LogViewerOptions =
      LogViewerOptions(showShareButton, showClearButton, exportAction)
  }

  internal companion object {
    val Default = Builder().build()
  }
}

internal class ExportAction(
  val contentDescription: String,
  val icon: @Composable () -> Unit,
  val onExport: suspend (LogExport) -> Unit,
)
