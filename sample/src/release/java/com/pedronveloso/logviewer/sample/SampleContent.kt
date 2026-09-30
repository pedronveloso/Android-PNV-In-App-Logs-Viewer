package com.pedronveloso.logviewer.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SampleContent(modifier: Modifier = Modifier) {
  Text(
    "The log viewer is included only in this sample's debug build.",
    modifier = modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
  )
}
