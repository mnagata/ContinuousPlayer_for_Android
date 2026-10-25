package jp.nagu.continuousplayer

import android.app.ActivityManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeMenuTest {
    @Test fun homeUsesCommonNamesAndExitAppearsOnlyAfterMore() = withHome { scenario, activity ->
        scenario.onActivity {
            assertEquals("ホーム", it.findViewById<View>(R.id.folder_select_container).accessibilityPaneTitle)
            val buttons = descendants(it.findViewById(R.id.folder_select_container)).filterIsInstance<Button>().filter { button -> button.isShown }
            assertEquals(listOf("OP / EDを選ぶ", "その他"), buttons.map { button -> button.text.toString() })
            buttons.forEach { button ->
                assertTrue(button.width <= it.findViewById<View>(R.id.folder_select_container).width)
                assertTrue("Home label is clipped", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            }
            val brand = descendants(it.findViewById(R.id.folder_select_container)).filterIsInstance<TextView>()
                .single { view -> view.text.toString() == it.getString(R.string.app_name) }
            assertTrue("App name is clipped", brand.layout.getLineWidth(0) <=
                brand.width - brand.compoundPaddingLeft - brand.compoundPaddingRight + 1)
            assertFalse(hasExitMenu())
        }
        showMore(scenario)
        scenario.onActivity { assertFalse(it.isFinishing); assertFalse(ViewModelProvider(it)[PlayerViewModel::class.java].isPlayerScreen) }
        saveScreenshot(activity, "home-more")
        keyStroke(KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { !hasExitMenu() }
        saveScreenshot(activity, "home")
    }

    @Test fun backFromMoreOnlyDismissesMenu() = checkCancel(KeyEvent.KEYCODE_BACK)

    @Test fun escapeFromMoreOnlyDismissesMenu() = checkCancel(KeyEvent.KEYCODE_ESCAPE)

    private fun checkCancel(key: Int) = withHome { scenario, activity ->
        showMore(scenario)
        keyStroke(key)
        BrowserTestUi.waitUntil { !hasExitMenu() }
        scenario.onActivity {
            assertFalse(it.isFinishing)
            assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
            if (!it.window.decorView.isInTouchMode) assertTrue(it.findViewById<View>(R.id.btn_home_more).hasFocus())
        }
    }

    @Test fun touchExitFromMoreRemovesTheTask() = withHome { scenario, activity ->
        showMore(scenario)
        val taskId = activity.taskId
        assertTrue(taskId > 0)
        BrowserTestUi.tap(R.string.exit_app)
        waitForExit(activity, taskId)
    }

    @Test fun televisionDecisionCanOpenMoreAndExit() = withHome { scenario, activity ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var wasInTouchMode = false
        lateinit var previous: Configuration
        scenario.onActivity {
            wasInTouchMode = it.window.decorView.isInTouchMode
            previous = Configuration(it.resources.configuration)
            val tv = Configuration(previous).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(tv, it.resources.displayMetrics)
        }
        instrumentation.setInTouchMode(false)
        try {
            scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).requestFocus() }
            keyStroke(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_home_more).hasFocus()) }
            keyStroke(KeyEvent.KEYCODE_DPAD_CENTER)
            BrowserTestUi.waitUntil { hasExitMenu() }
            keyStroke(KeyEvent.KEYCODE_DPAD_DOWN)
            val taskId = activity.taskId
            assertTrue(taskId > 0)
            keyStroke(KeyEvent.KEYCODE_DPAD_CENTER)
            waitForExit(activity, taskId)
        } finally {
            @Suppress("DEPRECATION")
            instrumentation.runOnMainSync { activity.resources.updateConfiguration(previous, activity.resources.displayMetrics) }
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    @Test fun tabAndEnterOpenMoreWithoutExiting() = withHome { scenario, activity ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var wasInTouchMode = false
        scenario.onActivity { wasInTouchMode = it.window.decorView.isInTouchMode }
        instrumentation.setInTouchMode(false)
        try {
            scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).requestFocus() }
            keyStroke(KeyEvent.KEYCODE_TAB)
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_home_more).hasFocus()) }
            keyStroke(KeyEvent.KEYCODE_ENTER)
            BrowserTestUi.waitUntil { hasExitMenu() }
            scenario.onActivity { assertFalse(it.isFinishing) }
            keyStroke(KeyEvent.KEYCODE_BACK)
            BrowserTestUi.waitUntil { !hasExitMenu() }
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_home_more).hasFocus()) }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    @Test fun backgroundDismissesMoreWithoutEndingHome() = withHome { scenario, activity ->
        showMore(scenario)
        scenario.moveToState(Lifecycle.State.CREATED)
        BrowserTestUi.waitUntil { !hasExitMenu() }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity {
            assertFalse(it.isFinishing)
            assertTrue(it.findViewById<View>(R.id.folder_select_container).isShown)
        }
    }

    private fun waitForExit(activity: MainActivity, taskId: Int) {
        val manager = activity.getSystemService(ActivityManager::class.java)
        BrowserTestUi.waitUntil { activity.isFinishing && manager.appTasks.none { it.taskInfo.taskId == taskId } }
    }

    private fun showMore(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            val more = it.findViewById<View>(R.id.btn_home_more)
            more.requestRectangleOnScreen(Rect(0, 0, more.width, more.height), true)
        }
        BrowserTestUi.tap(R.string.home_more)
        BrowserTestUi.waitUntil { hasExitMenu() }
    }

    private fun hasExitMenu(): Boolean = WindowInspector.getGlobalWindowViews().any { root ->
        descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "アプリを終了" }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun keyStroke(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        val source = if (code in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE))
            InputDevice.SOURCE_KEYBOARD else InputDevice.SOURCE_DPAD
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
                action, code, 0, 0, -1, 0, 0, source), true))
        }
        instrumentation.waitForIdleSync()
    }

    private fun saveScreenshot(activity: MainActivity, name: String) {
        SystemClock.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = activity.resources.configuration
        File(activity.cacheDir, "d08-$name-${config.screenWidthDp}dp-font${config.fontScale}.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun withHome(action: (ActivityScenario<MainActivity>, MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            BrowserTestUi.waitUntil { activity.window.decorView.hasWindowFocus() && activity.window.decorView.isLaidOut }
            action(scenario, activity)
        }
    }
}
