package com.pedronveloso.logviewer.sample

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pedronveloso.logviewer.LogExport
import com.pedronveloso.logviewer.LogViewerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LOCAL_SEND_PACKAGE = "org.localsend.localsend_app"

/** Refresh on resume so installing/removing LocalSend while the sample is open is reflected. */
@Composable
internal fun rememberLocalSendIcon(): ImageBitmap? {
  val context = LocalContext.current
  var icon by remember(context) { mutableStateOf<ImageBitmap?>(null) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { icon = localSendIcon(context) }
  return icon
}

private fun localSendIcon(context: Context): ImageBitmap? {
  val manager = context.packageManager
  val intent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(LOCAL_SEND_PACKAGE)
  if (manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return null
  return try {
    manager.getApplicationIcon(LOCAL_SEND_PACKAGE).toBitmap().asImageBitmap()
  } catch (_: PackageManager.NameNotFoundException) {
    null
  }
}

internal fun sampleViewerOptions(
  context: Context,
  settings: SampleViewerSettings,
  localSendIcon: ImageBitmap?,
): LogViewerOptions {
  val builder =
    LogViewerOptions.Builder()
      .showShareButton(settings.showShareButton)
      .showClearButton(settings.showClearButton)
  if (!settings.showLocalSendButton || localSendIcon == null) return builder.build()
  val manager = context.packageManager
  return builder
    .exportAction(
      contentDescription = "Send logs with LocalSend",
      icon = { Image(localSendIcon, contentDescription = null, modifier = Modifier.size(24.dp)) },
      onExport = { export ->
        // Recheck at click time: LocalSend may have been removed since the last resume.
        val send = localSendIntent(export)
        check(manager.resolveActivity(send, PackageManager.MATCH_DEFAULT_ONLY) != null) {
          "LocalSend is no longer installed"
        }
        withContext(Dispatchers.Main.immediate) { context.startActivity(send) }
      },
    )
    .build()
}

internal fun localSendIntent(export: LogExport): Intent =
  Intent(Intent.ACTION_SEND).apply {
    setPackage(LOCAL_SEND_PACKAGE)
    type = export.mimeType
    putExtra(Intent.EXTRA_STREAM, export.uri)
    clipData = ClipData.newRawUri("logs", export.uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
