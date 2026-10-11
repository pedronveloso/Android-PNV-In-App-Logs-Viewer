package com.pedronveloso.logviewer

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import java.text.BreakIterator

private val WrapMarkerSize = 14.dp
private val WrapMarkerGap = 4.dp

/**
 * Monospace-friendly text that either soft-wraps with a return marker before each wrapped line, or
 * stays on single lines and scrolls horizontally.
 */
@Composable
internal fun WrappableText(
  text: String,
  style: TextStyle,
  wrap: Boolean,
  modifier: Modifier = Modifier,
) {
  if (!wrap) {
    Text(
      text,
      style = style,
      softWrap = false,
      modifier = modifier.horizontalScroll(rememberScrollState()),
    )
    return
  }
  // Zero-width break opportunities after every character make the line breaker wrap exactly at
  // the edge, mid-word if needed, instead of waiting for whitespace.
  val breakable = remember(text) { breakAnywhere(text) }
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val painter = rememberVectorPainter(KeyboardReturnIcon)
  val tint = MaterialTheme.colorScheme.primary
  Text(
    breakable,
    style = style,
    onTextLayout = { layout = it },
    modifier =
      modifier
        // Expose the original text, without the break characters, to accessibility services.
        .clearAndSetSemantics { this.text = AnnotatedString(text) }
        .drawBehind {
          val result = layout ?: return@drawBehind
          val size = WrapMarkerSize.toPx()
          // Drawn in the sheet's own side padding, left of the text, so the text is not indented.
          val x = -(WrapMarkerSize + WrapMarkerGap).toPx()
          // Mark each line that continues a soft-wrapped one. A hard newline already reads as a
          // line break, so those lines get no marker.
          val lineEnds = List(result.lineCount, result::getLineEnd)
          for (line in continuationLines(breakable, lineEnds)) {
            val y = (result.getLineTop(line) + result.getLineBottom(line)) / 2f - size / 2f
            translate(x, y) {
              scale(scaleX = -1f, scaleY = 1f, pivot = Offset(size / 2f, size / 2f)) {
                with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
              }
            }
          }
        },
  )
}

private const val ZERO_WIDTH_SPACE = '\u200B'

/**
 * Inserts a zero-width break opportunity between user-perceived characters, so base characters stay
 * with their combining marks and surrogate pairs stay whole.
 */
internal fun breakAnywhere(text: String): String =
  buildString(text.length * 2) {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      append(text, start, end)
      if (text[end - 1] != '\n') append(ZERO_WIDTH_SPACE)
      start = end
      end = iterator.next()
    }
  }

/**
 * Returns the indices of lines that continue a soft-wrapped line. A line following a hard newline
 * already reads as a line break, so it is not included. [lineEnds] holds each line's end offset.
 */
internal fun continuationLines(text: String, lineEnds: List<Int>): List<Int> =
  (1 until lineEnds.size).filter { line ->
    val previousEnd = lineEnds[line - 1]
    previousEnd <= 0 || text[previousEnd - 1] != '\n'
  }

/** Stores viewer UI preferences only; never log content. */
internal class LogViewerPreferences(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  var wrapLongLines: Boolean
    get() = prefs.getBoolean(KEY_WRAP, false)
    set(value) = prefs.edit { putBoolean(KEY_WRAP, value) }

  internal companion object {
    const val FILE = "pnv_logviewer"
    const val KEY_WRAP = "wrap_long_lines"
  }
}

// Material Symbols Outlined "keyboard_return", 24dp.
internal val KeyboardReturnIcon: ImageVector by lazy {
  ImageVector.Builder(
      name = "keyboard_return",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .apply {
      path(
        fill = SolidColor(Color.Black),
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Bevel,
        pathFillType = PathFillType.NonZero,
      ) {
        moveTo(9f, 18f)
        lineTo(3f, 12f)
        lineTo(9f, 6f)
        lineToRelative(1.4f, 1.4f)
        lineTo(6.8f, 11f)
        horizontalLineTo(19f)
        verticalLineTo(7f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(6f)
        horizontalLineTo(6.8f)
        lineToRelative(3.6f, 3.6f)
        lineTo(9f, 18f)
        close()
      }
    }
    .build()
}
