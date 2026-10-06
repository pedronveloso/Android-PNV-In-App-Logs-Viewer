package com.pedronveloso.logviewer.r8test

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No references to app or library classes: tests must not create additional R8 roots. */
@RunWith(AndroidJUnit4::class)
class R8IntegrationTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)

  @Test
  fun captureRedactRecoverFilterAndShareFromOptimizedApp() {
    // The isolated application ID makes clearing this test fixture safe for the regular sample.
    assertTrue(device.executeShellCommand("pm clear $APP").contains("Success"))
    launchSample()
    find(By.text("Generate logs")).click()
    find(By.text("Open logs")).click()
    find(By.text("Generated message 1 access_token=<redacted>"))
    assertFalse(device.hasObject(By.textContains("sample-secret")))
    find(By.desc("Show capture status")).click()
    find(By.text("Crash persistence configured"))
    find(By.desc("Show logs")).click()

    // Restart the process, not just the Activity, so recovery must read the disk journal.
    device.executeShellCommand("am force-stop $APP")
    launchSample()
    find(By.text("Open logs")).click()
    find(By.desc("Select log session, current session")).click()
    find(By.descStartsWith("Select session from ")).click()
    find(By.text("Generated message 1 access_token=<redacted>"))
    find(By.text("Generated warning 1: slow response"))

    // ACTION_SET_TEXT updates the unfocused field without opening the keyboard.
    find(By.clazz("android.widget.EditText")).text = "request failed"
    assertEquals(
      "request failed",
      find(By.clazz("android.widget.EditText").text("request failed")).text,
    )
    find(By.desc("Share visible logs")) // The viewer must remain open after entering the query.
    find(By.text("Generated error 1: request failed"))
    assertTrue(device.wait(Until.gone(By.textContains("Generated message")), TIMEOUT))
    assertTrue(device.wait(Until.gone(By.textContains("Generated warning")), TIMEOUT))
    find(By.desc("Clear filter text")).click()
    find(By.text("Generated warning 1: slow response"))
    device.waitForIdle()
    val errorFilter = By.descStartsWith("Filter Error and above")
    // A partially clipped chip can be in the accessibility tree but not safely clickable.
    val filterRow = find(By.scrollable(true).hasDescendant(By.descStartsWith("Filter ")))
    filterRow.scroll(Direction.RIGHT, 1f)
    // Compose exposes the description on a child; the chip's parent owns the click action.
    find(By.clickable(true).hasDescendant(errorFilter)).click()
    find(By.text("Generated error 1: request failed"))
    assertTrue(device.wait(Until.gone(By.textContains("Generated warning")), TIMEOUT))

    find(By.desc("Share visible logs")).click()
    selectShareReceiver()
    find(By.text("R8 export verified"))
  }

  private fun selectShareReceiver() {
    val receiver = By.text(Pattern.compile("R8\\s+verifier"))
    // Expand the collapsed sheet by dragging its file preview, rather than the background app.
    val preview = find(By.text(Pattern.compile("logs-\\d+\\.txt")))
    val chooser = checkNotNull(preview.applicationPackage)
    val start = preview.visibleCenter
    device.swipe(start.x, start.y, start.x, device.displayHeight / 4, 50)
    repeat(8) {
      device.wait(Until.findObject(receiver), 1_000)?.let {
        it.click()
        return
      }
      device.findObject(By.desc("R8 verifier"))?.let {
        it.click()
        return
      }
      device.findObject(By.text("More"))?.let {
        it.click()
        return@repeat
      }
      for (container in device.findObjects(By.pkg(chooser).scrollable(true))) {
        val direction =
          if (container.className == "android.widget.HorizontalScrollView") Direction.RIGHT
          else Direction.DOWN
        container.scroll(direction, 0.8f)
        device.findObject(receiver)?.let {
          it.click()
          return
        }
      }
    }
    find(receiver).click()
  }

  private fun launchSample() {
    val context = instrumentation.context
    val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP))
    context.startActivity(
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
    find(By.text("Generate logs"))
  }

  private fun find(selector: BySelector): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), TIMEOUT)) {
      "Missing UI element: $selector"
    }

  private companion object {
    const val APP = "com.pedronveloso.logviewer.sample.r8"
    const val TIMEOUT = 10_000L
  }
}
