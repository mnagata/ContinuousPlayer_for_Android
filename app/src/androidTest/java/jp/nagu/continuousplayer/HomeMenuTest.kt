package jp.nagu.continuousplayer

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeMenuTest {
    @Test fun homeShowsOnlyFolderSelection() = withHome { scenario, activity ->
        scenario.onActivity {
            assertEquals("ANIME OPENINGS / ENDINGS", it.findViewById<View>(R.id.folder_select_container).accessibilityPaneTitle)
            val buttons = descendants(it.findViewById(R.id.folder_select_container)).filterIsInstance<Button>().filter { button -> button.isShown }
            assertEquals(listOf("動画フォルダーを選ぶ"), buttons.map { button -> button.text.toString() })
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
        saveScreenshot(activity, "home")
    }

    @Test fun keyboardCanSelectFoldersWithNoOtherHomeAction() = withHome { scenario, _ ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var wasInTouchMode = false
        scenario.onActivity { wasInTouchMode = it.window.decorView.isInTouchMode }
        instrumentation.setInTouchMode(false)
        try {
            scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).requestFocus() }
            keyStroke(KeyEvent.KEYCODE_DPAD_DOWN)
            keyStroke(KeyEvent.KEYCODE_TAB)
            scenario.onActivity {
                assertTrue(it.findViewById<View>(R.id.btn_select_folder).hasFocus())
                assertFalse(it.isFinishing)
            }
            keyStroke(KeyEvent.KEYCODE_ENTER)
            BrowserTestUi.waitUntil {
                WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(R.id.dlna_title) != null }
            }
            assertFalse(hasExitMenu())
            keyStroke(KeyEvent.KEYCODE_ESCAPE)
            BrowserTestUi.waitUntil {
                WindowInspector.getGlobalWindowViews().none { it.findViewById<View>(R.id.dlna_title) != null }
            }
            scenario.onActivity { assertFalse(it.isFinishing) }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    @Test fun backgroundReturnsToHomeWithoutExitMenu() = withHome { scenario, _ ->
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity {
            assertFalse(it.isFinishing)
            assertTrue(it.findViewById<View>(R.id.folder_select_container).isShown)
            assertFalse(hasExitMenu())
        }
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
