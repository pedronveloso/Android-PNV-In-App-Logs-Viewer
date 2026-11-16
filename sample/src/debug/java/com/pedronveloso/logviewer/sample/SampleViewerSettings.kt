package com.pedronveloso.logviewer.sample

import androidx.compose.runtime.saveable.listSaver

internal data class SampleViewerSettings(
  val showShareButton: Boolean = true,
  val showClearButton: Boolean = true,
  val showLocalSendButton: Boolean = true,
  val showProcessExits: Boolean = true,
) {
  companion object {
    val Saver =
      listSaver<SampleViewerSettings, Boolean>(
        save = {
          listOf(
            it.showShareButton,
            it.showClearButton,
            it.showLocalSendButton,
            it.showProcessExits,
          )
        },
        restore = { SampleViewerSettings(it[0], it[1], it[2], it[3]) },
      )
  }
}
