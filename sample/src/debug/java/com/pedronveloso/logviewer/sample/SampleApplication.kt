package com.pedronveloso.logviewer.sample

import android.app.Application
import com.pedronveloso.logviewer.TimberLogCapture
import timber.log.Timber

class SampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    capture =
      TimberLogCapture(
        this,
        persistAcrossCrashes = true,
        redact = { text ->
          Regex("token=[^\\s]+", RegexOption.IGNORE_CASE).replace(text, "token=<redacted>")
        },
      )
    Timber.plant(Timber.DebugTree())
    capture.install()
    Timber.i("Sample started. Open Logs to inspect this session.")
  }

  companion object {
    lateinit var capture: TimberLogCapture
      private set
  }
}
