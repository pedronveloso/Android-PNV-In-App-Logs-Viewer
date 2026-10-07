package com.pedronveloso.logviewer.r8test

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView

/** Reads the actual URI grant across apps, rather than bypassing the provider with test access. */
class ShareReceiverActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val result = runCatching {
      @Suppress("DEPRECATION") // The test app supports API 26.
      val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
      check(uri.authority == "com.pedronveloso.logviewer.sample.r8.pnvlogviewer.fileprovider")
      check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      check(intent.clipData?.getItemAt(0)?.uri == uri)
      val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
      check("Generated error 1: request failed" in text)
      check("Request 1 failed" in text)
      check("Generated warning" !in text)
      check("Generated message" !in text)
      check("sample-secret" !in text)
      "R8 export verified"
    }
      .getOrElse { "R8 export failed: $it" }
    setContentView(TextView(this).apply { text = result })
  }
}
