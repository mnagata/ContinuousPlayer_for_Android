package jp.nagu.continuousplayer

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Use UiAutomation directly: the bundled Espresso cannot inject input on API 36.1. */
internal object BrowserTestUi {
    fun waitUntil(condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Browser action did not complete")
    }

    fun waitForText(resource: Int) = waitForText(text(resource))

    fun waitForText(title: String): Rect {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title).orEmpty()
            val node = nodes.firstOrNull { it.text?.toString() == title && it.isVisibleToUser }
            if (node != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) return bounds
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Visible text not found: $title")
    }

    fun tap(resource: Int) = tap(text(resource))

    fun tap(title: String, longPress: Boolean = false) {
        val bounds = waitForText(title)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP && longPress) SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Touch could not be injected", automation.injectInputEvent(event, true))
            event.recycle()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun text(resource: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
