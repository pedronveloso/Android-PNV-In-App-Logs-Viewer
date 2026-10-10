package com.pedronveloso.logviewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-themed, navigation-agnostic log viewer. Pass one source per installation. */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
public fun LogViewer(source: LogSource, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val capabilities by source.capabilities.collectAsState()
  val liveUpdates = capabilities.hasLiveUpdates && source.changes != null
  val effectiveCapabilities = capabilities.copy(hasLiveUpdates = liveUpdates)
  val health by source.health.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var tab by remember { mutableIntStateOf(0) }
  var sessions by remember(source) { mutableStateOf<List<LogSession>>(emptyList()) }
  var selectedSession by remember(source) { mutableStateOf<String?>(null) }
  var allEntries by remember(source) { mutableStateOf<List<LogEntry>>(emptyList()) }
  var filter by remember { mutableStateOf(LogFilter()) }
  var follow by remember(source) { mutableStateOf(true) }
  var refresh by remember { mutableIntStateOf(0) }
  var error by remember { mutableStateOf<String?>(null) }
  var selectedEntry by remember(source) { mutableStateOf<LogEntry?>(null) }
  val preferences = remember(context) { LogViewerPreferences(context) }
  var wrapLines by remember { mutableStateOf(false) }
  LaunchedEffect(preferences) {
    wrapLines = withContext(Dispatchers.IO) { preferences.wrapLongLines }
  }
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
    source.changes?.sample(250.milliseconds)?.collect { reload() }
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
      LogViewerTopBar(
        showActions = tab == 0,
        canClear = capabilities.canClear && selectedSession != null,
        onBack = onBack,
        canRefresh = !liveUpdates,
        onRefresh = { refresh++ },
        onShare = {
          runCatching { shareLogs(context, formatLogEntries(filtered)) }
            .onFailure { error = it.message ?: "Could not share logs" }
        },
        onClear = {
          selectedSession?.let { sessionId ->
            scope.launch {
              runCatching { source.clear(sessionId) }
                .onSuccess { refresh++ }
                .onFailure { error = it.message ?: "Could not clear logs" }
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      LogViewerTabs(selectedTab = tab, onSelectTab = { tab = it })
      error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
      }
      if (tab == 1) {
        StatusContent(effectiveCapabilities, health, Modifier.fillMaxSize())
      } else {
        Column(Modifier.fillMaxSize()) {
          SessionSelector(
            sessions = sessions,
            selectedSessionId = selectedSession,
            onSelectSession = {
              selectedSession = it.id
              follow = it.isCurrent
            },
          )
          LogSearchField(
            query = filter.query,
            onQueryChange = { filter = filter.copy(query = it) },
          )
          LogFilterRow(
            selectedLevel = filter.minimumLevel,
            warningCount = warningCount,
            errorCount = errorCount,
            follow = follow,
            canFollow = canFollow,
            onSelectLevel = { filter = filter.copy(minimumLevel = it) },
            onFollowChange = { follow = it },
          )
          Text(
            "${filtered.size} of ${allEntries.size} entries",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
          if (filtered.isEmpty()) {
            LogEmptyState(noEntries = allEntries.isEmpty())
          } else {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize().testTag("log_list"),
              contentPadding = PaddingValues(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              itemsIndexed(filtered, key = { _, entry -> "${entry.sessionId}:${entry.id}" }) {
                index,
                entry ->
                LogRow(
                  entry = entry,
                  previousTimestamp = filtered.getOrNull(index - 1)?.timestampMillis,
                  onClick = { selectedEntry = entry },
                )
              }
            }
          }
        }
      }
    }
  }
  selectedEntry?.let { entry ->
    LogDetailsSheet(
      entry = entry,
      wrapLines = wrapLines,
      onWrapLinesChange = {
        wrapLines = it
        preferences.wrapLongLines = it
      },
      onDismiss = { selectedEntry = null },
      onFilterLikeThis = {
        filter = filter.copy(query = entry.tag.orEmpty())
        selectedEntry = null
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewerTopBar(
  showActions: Boolean,
  canClear: Boolean,
  canRefresh: Boolean,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onShare: () -> Unit,
  onClear: () -> Unit,
) {
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
      if (showActions) {
        if (canRefresh) {
          IconButton(
            onClick = onRefresh,
            modifier =
              Modifier.testTag("refresh_logs").semantics { contentDescription = "Refresh logs" },
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
          }
        }
        IconButton(
          onClick = onShare,
          modifier = Modifier.semantics { contentDescription = "Share visible logs" },
        ) {
          Icon(Icons.Default.Share, contentDescription = null)
        }
        if (canClear) {
          IconButton(
            onClick = onClear,
            modifier = Modifier.semantics { contentDescription = "Clear logs in selected session" },
          ) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null)
          }
        }
      }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewerTabs(selectedTab: Int, onSelectTab: (Int) -> Unit) {
  PrimaryTabRow(selectedTabIndex = selectedTab) {
    Tab(
      selected = selectedTab == 0,
      onClick = { onSelectTab(0) },
      text = { Text("Logs") },
      modifier = Modifier.testTag("logs_tab").semantics { contentDescription = "Show logs" },
    )
    Tab(
      selected = selectedTab == 1,
      onClick = { onSelectTab(1) },
      text = { Text("Status") },
      modifier =
        Modifier.testTag("status_tab").semantics { contentDescription = "Show capture status" },
    )
  }
}

@Composable
private fun SessionSelector(
  sessions: List<LogSession>,
  selectedSessionId: String?,
  onSelectSession: (LogSession) -> Unit,
) {
  var sessionMenu by remember { mutableStateOf(false) }
  Box(Modifier.padding(start = 16.dp, top = 8.dp)) {
    val selected = sessions.firstOrNull { it.id == selectedSessionId }
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
        else "Previous session: ${selected?.startedAtMillis?.let(::formatSessionDate).orEmpty()} ▾",
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
              else formatSessionDate(session.startedAtMillis),
            )
          },
          onClick = {
            onSelectSession(session)
            sessionMenu = false
          },
        )
      }
    }
  }
}

@Composable
private fun LogSearchField(query: String, onQueryChange: (String) -> Unit) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    label = { Text("Filter by tag or message") },
    trailingIcon =
      if (query.isNotEmpty()) {
        {
          TextButton(
            onClick = { onQueryChange("") },
            modifier =
              Modifier.testTag("clear_log_search").semantics {
                contentDescription = "Clear filter text"
              },
          ) {
            Text("Clear")
          }
        }
      } else null,
    singleLine = true,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("log_search"),
  )
}

@Composable
private fun LogFilterRow(
  selectedLevel: LogLevel,
  warningCount: Int,
  errorCount: Int,
  follow: Boolean,
  canFollow: Boolean,
  onSelectLevel: (LogLevel) -> Unit,
  onFollowChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier.weight(1f).horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      LogLevel.entries.forEach { level ->
        val count =
          when (level) {
            LogLevel.WARN -> warningCount
            LogLevel.ERROR -> errorCount
            else -> null
          }
        FilterChip(
          selected = selectedLevel == level,
          onClick = { onSelectLevel(level) },
          label = {
            Text(
              if (count == null) level.shortLabel else "${level.shortLabel} ${cappedCount(count)}",
              maxLines = 1,
              softWrap = false,
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
      onClick = { onFollowChange(!follow) },
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
}

@Composable
private fun LogEmptyState(noEntries: Boolean) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(if (noEntries) "No log entries captured yet." else "No entries match this filter.")
  }
}

@Composable
internal fun LogViewerPreviewTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
  ) {
    Surface(color = MaterialTheme.colorScheme.background) { content() }
  }
}

@PreviewLightDark
@Composable
private fun LogViewerTopBarPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) {
      LogViewerTopBar(
        showActions = true,
        canClear = true,
        canRefresh = true,
        onBack = {},
        onRefresh = {},
        onShare = {},
        onClear = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun LogViewerTabsPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) { LogViewerTabs(selectedTab = 0, onSelectTab = {}) }
  }
}

@PreviewLightDark
@Composable
private fun SessionSelectorPreview() {
  LogViewerPreviewTheme {
    SessionSelector(
      sessions = listOf(LogSession("current", 1_700_000_000_000L, true)),
      selectedSessionId = "current",
      onSelectSession = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun LogSearchFieldPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) { LogSearchField(query = "network", onQueryChange = {}) }
  }
}

@PreviewLightDark
@Composable
private fun LogFilterRowPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) {
      LogFilterRow(
        selectedLevel = LogLevel.WARN,
        warningCount = 12,
        errorCount = 3,
        follow = true,
        canFollow = true,
        onSelectLevel = {},
        onFollowChange = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun NoEntriesPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).height(180.dp)) { LogEmptyState(noEntries = true) }
  }
}

@PreviewLightDark
@Composable
private fun NoMatchingEntriesPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).height(180.dp)) { LogEmptyState(noEntries = false) }
  }
}

internal fun shareLogs(context: Context, text: String) {
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
