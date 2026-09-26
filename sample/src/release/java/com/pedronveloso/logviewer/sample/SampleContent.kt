package com.pedronveloso.logviewer.sample

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SampleContent(modifier: Modifier = Modifier) {
  Text("The log viewer is included only in this sample's debug build.", modifier = modifier)
}
