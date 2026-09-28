package com.pedronveloso.logviewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
  var selectedEntry by remember(source) { mutableStateOf<LogEntry?>(null) }
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
        onRefresh = { refresh++ },
        onCopy = {
          scope.launch {
            clipboard.setClipEntry(
              ClipEntry(ClipData.newPlainText("logs", formatLogEntries(filtered)))
            )
          }
        },
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
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onCopy: () -> Unit,
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
        IconButton(
          onClick = onRefresh,
          modifier =
            Modifier.testTag("refresh_logs").semantics { contentDescription = "Refresh logs" },
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null)
        }
        IconButton(
          onClick = onCopy,
          modifier = Modifier.semantics { contentDescription = "Copy visible logs" },
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null)
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
        else "Previous session: ${selected?.startedAtMillis?.let(::formatSessionDate).orEmpty()} ▾"
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
private fun LogRow(entry: LogEntry, previousTimestamp: Long?, onClick: () -> Unit) {
  val level = LogLevel.fromPriority(entry.priority)
  Column(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
      .clickable(onClickLabel = "Show log details", onClick = onClick)
      .testTag("log_entry_${entry.id}")
      .padding(10.dp)
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Text(
        level.shortLabel,
        color = levelColor(level),
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
      Spacer(Modifier.width(8.dp))
      Text(
        styledTag(
          entry.tag.orEmpty(),
          MaterialTheme.colorScheme.primary,
          MaterialTheme.colorScheme.tertiary,
        ),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (entry.throwableStackTrace != null) {
        Icon(
          Icons.Outlined.ErrorOutline,
          contentDescription = "Has exception details",
          tint = levelColor(level),
          modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(formatTime(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
        formatElapsedTime(previousTimestamp, entry.timestampMillis)?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    if (entry.message.isNotEmpty()) {
      Text(
        entry.message,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

internal fun styledTag(tag: String, classColor: Color, methodColor: Color): AnnotatedString {
  val divider = tag.lastIndexOf('$')
  if (divider <= 0 || divider == tag.lastIndex) return AnnotatedString(tag)
  return buildAnnotatedString {
    withStyle(SpanStyle(color = classColor)) { append(tag.substring(0, divider)) }
    append('$')
    withStyle(SpanStyle(color = methodColor)) { append(tag.substring(divider + 1)) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailsSheet(entry: LogEntry, onDismiss: () -> Unit, onFilterLikeThis: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("log_details_sheet")) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Log details", style = MaterialTheme.typography.titleLarge)
      Text(
        styledTag(
          entry.tag ?: "(no tag)",
          MaterialTheme.colorScheme.primary,
          MaterialTheme.colorScheme.tertiary,
        ),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        "${LogLevel.fromPriority(entry.priority).accessibilityName} · ${formatTime(entry.timestampMillis)}",
        style = MaterialTheme.typography.labelMedium,
      )
      TextButton(
        onClick = onFilterLikeThis,
        enabled = !entry.tag.isNullOrBlank(),
        modifier = Modifier.testTag("filter_logs_like_this"),
      ) {
        Text("Filter logs like this")
      }
      if (entry.message.isNotEmpty()) {
        Text(
          entry.message,
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
      }
      entry.throwableStackTrace?.let {
        Text("Throwable", style = MaterialTheme.typography.titleSmall)
        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
      }
    }
  }
}

@Composable
@ReadOnlyComposable
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

private fun formatTime(timestamp: Long) =
  SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

@Composable
private fun LogViewerPreviewTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
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
        onBack = {},
        onRefresh = {},
        onCopy = {},
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
private fun WarningLogRowPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).padding(12.dp)) {
      LogRow(
        entry =
          LogEntry(
            1,
            "preview",
            1_700_000_000_000L,
            LogLevel.WARN.priority,
            "Network",
            "Slow response from the service",
          ),
        previousTimestamp = null,
        onClick = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun ErrorLogRowPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).padding(12.dp)) {
      LogRow(
        entry =
          LogEntry(
            2,
            "preview",
            1_700_000_000_000L,
            LogLevel.ERROR.priority,
            "Network\$request",
            "Request failed after three attempts.\nThe endpoint did not respond.\nCheck the connection and retry.",
            "java.lang.IllegalStateException: Request failed\n    at Network.request(Network.kt:42)",
          ),
        previousTimestamp = 1_699_999_990_000L,
        onClick = {},
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
