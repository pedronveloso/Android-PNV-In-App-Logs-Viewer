package com.pedronveloso.logviewer

import androidx.core.content.FileProvider

/** A distinct component name prevents manifest merging with the host's own FileProvider. */
internal class LogViewerFileProvider : FileProvider()
