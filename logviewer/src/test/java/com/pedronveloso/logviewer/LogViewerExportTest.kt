package com.pedronveloso.logviewer

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewerExportTest {
  @Test
  fun `share grants read access to provider content`() {
    val context = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    val exports = File(context.cacheDir, "pnv-logviewer-exports")
    exports.deleteRecursively()
    shareLogs(context, "redacted log")

    val chooser = Shadows.shadowOf(context).nextStartedActivity
    assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
    val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(send.type).isEqualTo("text/plain")
    assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
    assertThat(uri.authority).isEqualTo("${context.packageName}.pnvlogviewer.fileprovider")
    assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    assertThat(
        context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() },
      )
      .isEqualTo("redacted log")
  }
}
