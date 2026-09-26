package com.pedronveloso.logviewer.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pedronveloso.logviewer.LogViewer
import timber.log.Timber

@Composable
fun SampleContent(modifier: Modifier = Modifier) {
  var showViewer by remember { mutableStateOf(false) }
  var count by remember { mutableIntStateOf(0) }
  if (showViewer) {
    LogViewer(
      source = SampleApplication.capture,
      onBack = { showViewer = false },
      modifier = modifier,
    )
  } else {
    Column(
      modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Log viewer sample")
      Button(
        onClick = { Timber.tag("Sample").d("Generated message %d token=sample-secret", ++count) }
      ) {
        Text("Generate a log")
      }
      Button(
        onClick = {
          Timber.tag("Sample").w("About to crash; this entry should survive restart")
          error("Intentional sample crash")
        }
      ) {
        Text("Crash app to test recovery")
      }
      Button(onClick = { showViewer = true }) { Text("Open logs") }
      Text("After a crash, reopen the app and choose the previous session in Logs.")
    }
  }
}
