package com.pedronveloso.logviewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-themed, navigation-agnostic log viewer. Pass one source per installation. */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun LogViewer(source: LogSource, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val capabilities by source.capabilities.collectAsState()
  val liveUpdates = capabilities.hasLiveUpdates && source.changes != null
  val effectiveCapabilities = capabilities.copy(hasLiveUpdates = liveUpdates)
  val health by source.health.collectAsState()
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  var tab by remember { mutableIntStateOf(0) }
  var sessions by remember(source) { mutableStateOf<List<LogSession>>(emptyList()) }
  var selectedSession by remember(source) { mutableStateOf<String?>(null) }
  var allEntries by remember(source) { mutableStateOf<List<LogEntry>>(emptyList()) }
  var filter by remember { mutableStateOf(LogFilter()) }
  var follow by remember(source) { mutableStateOf(true) }
  var refresh by remember { mutableIntStateOf(0) }
  var error by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val isDragged by listState.interactionSource.collectIsDraggedAsState()
  val filtered = remember(allEntries, filter) { allEntries.filter { it.matches(filter) } }
  val warningCount =
    remember(allEntries) { allEntries.count { it.priority == LogLevel.WARN.priority } }
  val errorCount =
    remember(allEntries) { allEntries.count { it.priority == LogLevel.ERROR.priority } }
  val canFollow =
    liveUpdates && sessions.firstOrNull { it.id == selectedSession }?.isCurrent == true

  LaunchedEffect(source, selectedSession, refresh) {
    suspend fun reload() {
      runCatching {
        withContext(Dispatchers.IO) {
          val available = source.sessions()
          val chosen =
            selectedSession?.takeIf { id -> available.any { it.id == id } }
              ?: available.firstOrNull { it.isCurrent }?.id
              ?: available.firstOrNull()?.id
          Triple(available, chosen, chosen?.let { source.entries(it) }.orEmpty())
        }
      }
        .onSuccess { (available, chosen, entries) ->
          sessions = available
          if (selectedSession != chosen) selectedSession = chosen
          allEntries = entries
          error = null
        }
        .onFailure {
          if (it is CancellationException) throw it
          error = it.message ?: "Could not load logs"
        }
    }
    reload()
    source.changes?.sample(250)?.collect { reload() }
  }

  LaunchedEffect(listState, isDragged) {
    if (isDragged)
      snapshotFlow { listState.firstVisibleItemIndex }
        .collect {
          val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
          if (lastVisible != null && lastVisible < listState.layoutInfo.totalItemsCount - 2)
            follow = false
        }
  }
  LaunchedEffect(selectedSession, allEntries.lastOrNull()?.id, follow) {
    if (follow && filtered.isNotEmpty()) listState.animateScrollToItem(filtered.lastIndex)
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text("Logs") },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "Go back" },
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
          }
        },
        actions = {
          if (tab == 0) {
            IconButton(
              onClick = { refresh++ },
              modifier =
                Modifier.testTag("refresh_logs").semantics { contentDescription = "Refresh logs" },
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null)
            }
            IconButton(
              onClick = {
                scope.launch {
                  clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("logs", formatLogEntries(filtered)))
                  )
                }
              },
              modifier = Modifier.semantics { contentDescription = "Copy visible logs" },
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
            IconButton(
              onClick = {
                runCatching { shareLogs(context, formatLogEntries(filtered)) }
                  .onFailure { error = it.message ?: "Could not share logs" }
              },
              modifier = Modifier.semantics { contentDescription = "Share visible logs" },
            ) {
              Icon(Icons.Default.Share, contentDescription = null)
            }
            if (capabilities.canClear && selectedSession != null) {
              IconButton(
                onClick = {
                  scope.launch {
                    runCatching { source.clear(selectedSession!!) }
                      .onSuccess { refresh++ }
                      .onFailure { error = it.message ?: "Could not clear logs" }
                  }
                },
                modifier =
                  Modifier.semantics { contentDescription = "Clear logs in selected session" },
              ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
              }
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      PrimaryTabRow(selectedTabIndex = tab) {
        Tab(
          selected = tab == 0,
          onClick = { tab = 0 },
          text = { Text("Logs") },
          modifier = Modifier.testTag("logs_tab").semantics { contentDescription = "Show logs" },
        )
        Tab(
          selected = tab == 1,
          onClick = { tab = 1 },
          text = { Text("Status") },
          modifier =
            Modifier.testTag("status_tab").semantics { contentDescription = "Show capture status" },
        )
      }
      error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
      }
      if (tab == 1) {
        StatusContent(effectiveCapabilities, health, Modifier.fillMaxSize())
      } else {
        Column(Modifier.fillMaxSize()) {
          var sessionMenu by remember { mutableStateOf(false) }
          Box(Modifier.padding(start = 16.dp, top = 8.dp)) {
            val selected = sessions.firstOrNull { it.id == selectedSession }
            TextButton(
              onClick = { sessionMenu = true },
              modifier =
                Modifier.testTag("session_selector").semantics {
                  contentDescription =
                    when {
                      selected == null -> "Select log session"
                      selected.isCurrent -> "Select log session, current session"
                      else ->
                        "Select log session, previous session from ${formatSessionDate(selected.startedAtMillis)}"
                    }
                },
            ) {
              Text(
                if (selected?.isCurrent == true) "Current session ▾"
                else
                  "Previous session: ${selected?.startedAtMillis?.let(::formatSessionDate).orEmpty()} ▾"
              )
            }
            DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
              sessions.forEach { session ->
                DropdownMenuItem(
                  modifier =
                    Modifier.testTag("session_${session.id}").semantics {
                      contentDescription =
                        if (session.isCurrent) "Select current session"
                        else "Select session from ${formatSessionDate(session.startedAtMillis)}"
                    },
                  text = {
                    Text(
                      if (session.isCurrent) "Current session"
                      else formatSessionDate(session.startedAtMillis)
                    )
                  },
                  onClick = {
                    selectedSession = session.id
                    sessionMenu = false
                    follow = session.isCurrent
                  },
                )
              }
            }
          }
          OutlinedTextField(
            value = filter.query,
            onValueChange = { filter = filter.copy(query = it) },
            label = { Text("Filter by tag or message") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("log_search"),
          )
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              Modifier.weight(1f).horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              LogLevel.entries.forEach { level ->
                val count =
                  when (level) {
                    LogLevel.WARN -> warningCount
                    LogLevel.ERROR -> errorCount
                    else -> null
                  }
                FilterChip(
                  selected = filter.minimumLevel == level,
                  onClick = { filter = filter.copy(minimumLevel = level) },
                  label = {
                    Text(
                      if (count == null) level.shortLabel
                      else "${level.shortLabel} ${cappedCount(count)}",
                      color =
                        if (level == LogLevel.WARN || level == LogLevel.ERROR) levelColor(level)
                        else Color.Unspecified,
                    )
                  },
                  modifier =
                    Modifier.testTag("level_${level.name}").semantics {
                      contentDescription =
                        "Filter ${level.accessibilityName} and above" +
                          if (count == null) ""
                          else
                            ", ${cappedCount(count)} ${level.accessibilityName.lowercase()} ${if (count == 1) "entry" else "entries"}"
                    },
                )
              }
            }
            FilterChip(
              selected = follow,
              onClick = { follow = !follow },
              enabled = canFollow,
              label = { Text("Follow") },
              leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, contentDescription = null) },
              modifier =
                Modifier.padding(start = 8.dp).testTag("follow_logs").semantics {
                  contentDescription = "Follow new logs and scroll to the newest entry"
                  stateDescription =
                    if (!canFollow) "Unavailable for this session or log source"
                    else if (follow) "On" else "Off"
                },
            )
          }
          Text(
            "${filtered.size} of ${allEntries.size} entries",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
          if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                if (allEntries.isEmpty()) "No log entries captured yet."
                else "No entries match this filter."
              )
            }
          } else {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize().testTag("log_list"),
              contentPadding = PaddingValues(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              items(filtered, key = { "${it.sessionId}:${it.id}" }) { LogRow(it) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LogRow(entry: LogEntry) {
  var expanded by remember(entry.sessionId, entry.id) { mutableStateOf(false) }
  val level = LogLevel.fromPriority(entry.priority)
  Column(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
      .clickable(onClickLabel = if (expanded) "Collapse log entry" else "Expand log entry") {
        expanded = !expanded
      }
      .padding(10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        level.shortLabel,
        color = levelColor(level),
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.width(8.dp))
      Text(
        entry.tag.orEmpty(),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(formatTime(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
    }
    Text(
      entry.message,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      maxLines = if (expanded) Int.MAX_VALUE else 6,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun levelColor(level: LogLevel): Color =
  when (level) {
    LogLevel.VERBOSE,
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.WARN ->
      if (MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f) Color(0xFF8F4700)
      else Color(0xFFFFB74D)
    LogLevel.ERROR ->
      if (MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f) Color(0xFFB00020)
      else Color(0xFFFF9E9E)
    LogLevel.ASSERT -> MaterialTheme.colorScheme.error
  }

private val LogLevel.accessibilityName: String
  get() =
    if (this == LogLevel.WARN) "Warning" else name.lowercase().replaceFirstChar(Char::uppercaseChar)

private fun cappedCount(count: Int): String = if (count > 99) "99+" else count.toString()

@Composable
private fun StatusContent(
  capabilities: LogCapabilities,
  health: LogHealth,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Text(
        if (capabilities.isLibraryCapture) "Library capture" else "App-provided source",
        style = MaterialTheme.typography.titleMedium,
      )
    }
    items(statusMessages(capabilities, health)) { message ->
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Text(
            message.title,
            color =
              if (message.isProblem) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
          )
          Text(message.detail, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
    health.lastDiskWriteMillis?.let { last ->
      item {
        Text(
          "Last disk write: ${formatSessionDate(last)}",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private fun formatTime(timestamp: Long) =
  SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

private fun formatSessionDate(timestamp: Long) =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

private fun shareLogs(context: Context, text: String) {
  val directory = File(context.cacheDir, "pnv-logviewer-exports")
  check(directory.isDirectory || directory.mkdirs()) { "Could not prepare export" }
  val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
  directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
  val file = File(directory, "logs-${System.currentTimeMillis()}.txt")
  file.writeText(text)
  val uri =
    FileProvider.getUriForFile(context, "${context.packageName}.pnvlogviewer.fileprovider", file)
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      clipData = ClipData.newRawUri("logs", uri)
    }
  context.startActivity(Intent.createChooser(intent, "Share logs"))
}
