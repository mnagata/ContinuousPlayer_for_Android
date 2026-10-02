package jp.nagu.continuousplayer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DlnaSelectionDialogTest {
    @Test
    fun registrationActionRemainsReachableInPortraitAndLandscape() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: DlnaSelectionDialog
            var registered = false
            lateinit var content: DlnaDialogContent
            scenario.onActivity { activity ->
                content = fixtureContent().copy(onRegister = {
                    registered = !registered
                    content = content.copy(registerLabel = if (registered) R.string.folder_unregister else R.string.folder_register)
                    dialog.updateContent(content)
                })
                dialog = DlnaSelectionDialog(activity, content)
                dialog.show()
            }
            for ((orientation, config) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
            )) {
                scenario.onActivity { it.requestedOrientation = orientation }
                awaitOrientation(scenario, config)
                scenario.onActivity { dialog.refreshForConfiguration() }
                val before = registered
                tap(dialog, R.id.dlna_register)
                assertEquals(!before, registered)
                scenario.onActivity {
                    assertTrue(dialog.isShowing)
                    val register = dialog.findViewById<android.widget.Button>(R.id.dlna_register)
                    assertEquals(it.getString(if (registered) R.string.folder_unregister else R.string.folder_register),
                        register.text.toString())
                    assertTrue(register.width > 0)
                    assertTrue(dialog.findViewById<View>(R.id.dlna_refresh).isShown)
                    assertTrue(dialog.findViewById<View>(R.id.dlna_close).isShown)
                }
            }
            scenario.onActivity { dialog.dismiss() }
        }
    }

    @Test
    fun safContentUsesTheSharedDesignAcrossOrientationChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: DlnaSelectionDialog
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                dialog = DlnaSelectionDialog(activity, fixtureContent().copy(
                    label = R.string.saf_dialog_label, hint = R.string.dlna_touch_hint,
                    title = "SAF コレクション",
                    upLabel = R.string.saf_folder_list))
                dialog.show()
            }
            for ((orientation, config, name) in listOf(
                Triple(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT, "saf-portrait.png"),
                Triple(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE, "saf-landscape.png")
            )) {
                scenario.onActivity { it.requestedOrientation = orientation }
                awaitOrientation(scenario, config)
                scenario.onActivity {
                    dialog.refreshForConfiguration()
                    assertEquals("フォルダー / SAF", dialog.findViewById<android.widget.TextView>(R.id.dlna_label).text.toString())
                    val up = dialog.findViewById<android.widget.Button>(R.id.dlna_up)
                    assertEquals("", up.text.toString())
                    assertNotNull(up.compoundDrawablesRelative[0])
                    assertEquals("↑ 保存済みフォルダーへ", up.contentDescription.toString())
                    assertEquals(6, dialog.findViewById<ListView>(R.id.dlna_list).count)
                }
                idle()
                scenario.onActivity {
                    val up = dialog.findViewById<View>(R.id.dlna_up)
                    val refresh = dialog.findViewById<View>(R.id.dlna_refresh)
                    val close = dialog.findViewById<View>(R.id.dlna_close)
                    val title = dialog.findViewById<View>(R.id.dlna_title)
                    assertSame(up.parent, refresh.parent)
                    assertSame(up.parent, close.parent)
                    assertEquals(up.top, refresh.top)
                    assertEquals(up.top, close.top)
                    assertTrue(up.right <= close.left)
                    assertTrue(close.right <= title.left)
                    assertTrue(title.right <= refresh.left)
                    assertEquals(View.GONE, dialog.findViewById<View>(R.id.dlna_bottom_actions).visibility)
                }
                screenshot(name)
            }
            scenario.onActivity { dialog.dismiss() }
        }
    }

    @Test
    fun directoryLoadingKeepsListVisibleAndCancelAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: DlnaSelectionDialog
            var selected = false
            var closed = false
            scenario.onActivity { activity ->
                dialog = fixture(activity, onClose = { closed = true }, onSelected = { selected = true })
                dialog.show()
            }
            idle()
            scenario.onActivity {
                val list = dialog.findViewById<ListView>(R.id.dlna_list)
                val adapter = list.adapter
                val position = list.firstVisiblePosition
                val title = dialog.findViewById<android.widget.TextView>(R.id.dlna_title)
                assertTrue(dialog.keepFileListWhileLoading())
                assertSame(adapter, list.adapter)
                assertEquals(position, list.firstVisiblePosition)
                assertEquals("アニメ OP / ED", title.text.toString())
                assertTrue(list.isShown)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.dlna_progress).visibility)
                assertFalse(list.isEnabled)
                assertFalse(dialog.findViewById<View>(R.id.dlna_up).isEnabled)
                assertFalse(dialog.findViewById<View>(R.id.dlna_refresh).isEnabled)
                assertTrue(dialog.findViewById<View>(R.id.dlna_close).isEnabled)
                list.performItemClick(list.getChildAt(0), 0, 0)
                assertFalse(selected)

                // Rebuilding the layout during a request must not reactivate stale rows.
                dialog.refreshForConfiguration()
                assertFalse(dialog.findViewById<ListView>(R.id.dlna_list).isEnabled)
                dialog.updateContent(fixtureContent().copy(title = "子フォルダー"))
                assertEquals("子フォルダー", dialog.findViewById<android.widget.TextView>(R.id.dlna_title).text.toString())
                assertTrue(dialog.findViewById<ListView>(R.id.dlna_list).isEnabled)
                assertTrue(dialog.findViewById<View>(R.id.dlna_up).isEnabled)

                assertTrue(dialog.keepFileListWhileLoading())
                dialog.updateContent(DlnaDialogContent(title = "接続エラー", message = "再試行できます",
                    onRefresh = {}, onClose = {}))
                assertTrue(dialog.findViewById<View>(R.id.dlna_refresh).isEnabled)
                assertFalse(dialog.keepFileListWhileLoading())
                dialog.updateContent(fixtureContent(onClose = { closed = true }))
                assertTrue(dialog.keepFileListWhileLoading())
            }
            tap(dialog, R.id.dlna_close)
            assertTrue(closed)
            assertFalse(dialog.isShowing)
        }
    }

    @Test
    fun navigationLoadingAndErrorsKeepTheSameWindowAttached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: DlnaSelectionDialog
            lateinit var originalWindow: android.view.Window
            lateinit var originalTitle: View
            var detached = 0
            var dismissed = 0
            var wentUp = false
            scenario.onActivity { activity ->
                dialog = fixture(activity)
                dialog.setOnDismissListener { dismissed++ }
                dialog.show()
                originalWindow = requireNotNull(dialog.window)
                originalTitle = dialog.findViewById(R.id.dlna_title)
                originalWindow.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) { detached++ }
                })
            }
            idle()
            val states = listOf(
                DlnaDialogContent(title = "読み込み", loading = true, onClose = {}),
                fixtureContent().copy(title = "子フォルダー"),
                DlnaDialogContent(title = "接続エラー", message = "再試行できます", onRefresh = {}, onClose = {}),
                fixtureContent().copy(title = "子フォルダー", onUp = {
                    wentUp = true
                    dialog.updateContent(fixtureContent().copy(title = "親フォルダー"))
                }))
            for (state in states) {
                scenario.onActivity { dialog.updateContent(state) }
                idle()
                scenario.onActivity {
                    assertTrue(dialog.isShowing)
                    assertSame(originalWindow, dialog.window)
                    assertSame(originalTitle, dialog.findViewById(R.id.dlna_title))
                    assertTrue(originalWindow.decorView.isAttachedToWindow)
                    assertEquals(0, detached)
                    assertEquals(0, dismissed)
                }
            }
            sendKey(KeyEvent.KEYCODE_BACK)
            scenario.onActivity {
                assertTrue(wentUp)
                assertTrue(dialog.isShowing)
                assertEquals("親フォルダー", dialog.findViewById<android.widget.TextView>(R.id.dlna_title).text.toString())
                assertEquals(0, detached)
                assertEquals(0, dismissed)
                dialog.dismiss()
            }
        }
    }

    @Test
    fun repeatedDialogsHaveFinalSizeFromTheirFirstFrame() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var previousSize: Pair<Int, Int>? = null
            // Cover the loading-to-list replacement as well as opening a list again.
            for (loading in listOf(true, false, false)) {
                val firstDraw = CountDownLatch(1)
                var firstSize: Pair<Int, Int>? = null
                lateinit var dialog: DlnaSelectionDialog
                scenario.onActivity { activity ->
                    dialog = if (loading) DlnaSelectionDialog(activity, DlnaDialogContent(
                        title = "NAS / DLNA", message = "読み込んでいます…", loading = true, onClose = {}))
                    else fixture(activity)
                    dialog.show()
                    val window = requireNotNull(dialog.window)
                    // show() must already have fixed dimensions, before OnShow is dispatched.
                    assertTrue(window.attributes.width > 0)
                    assertTrue(window.attributes.height > 0)
                    val decor = window.decorView
                    decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            firstSize = decor.width to decor.height
                            decor.viewTreeObserver.removeOnPreDrawListener(this)
                            firstDraw.countDown()
                            return true
                        }
                    })
                }
                assertTrue("The first dialog frame was not drawn", firstDraw.await(5, TimeUnit.SECONDS))
                idle()
                SystemClock.sleep(350)
                scenario.onActivity {
                    val decor = requireNotNull(dialog.window).decorView
                    val settledSize = decor.width to decor.height
                    assertEquals("Dialog grew after its first frame", firstSize, settledSize)
                    if (previousSize != null) assertEquals("Loading/list dialog sizes differ", previousSize, settledSize)
                    previousSize = settledSize
                    dialog.dismiss()
                }
            }
        }
    }

    @Test
    fun directionKeysSelectMediaAndKeepParentNavigationSeparate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: DlnaSelectionDialog
            var selected = -1
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            scenario.onActivity { activity ->
                dialog = fixture(activity, initialSelection = 1, onSelected = { selected = it })
                dialog.show()
            }
            idle()
            scenario.onActivity {
                dialog.findViewById<ListView>(R.id.dlna_list).apply {
                    requestFocusFromTouch()
                    setSelection(0)
                }
            }
            sendKey(KeyEvent.KEYCODE_DPAD_UP)
            scenario.onActivity { assertSame(dialog.findViewById<View>(R.id.dlna_up), dialog.currentFocus) }
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { assertSame(dialog.findViewById<View>(R.id.dlna_close), dialog.currentFocus) }
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { assertSame(dialog.findViewById<View>(R.id.dlna_refresh), dialog.currentFocus) }
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { assertSame(dialog.findViewById<View>(R.id.dlna_list), dialog.currentFocus) }
            scenario.onActivity {
                dialog.findViewById<ListView>(R.id.dlna_list).apply {
                    requestFocusFromTouch()
                    setSelection(1)
                }
            }
            idle()
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity {
                val list = dialog.findViewById<ListView>(R.id.dlna_list)
                assertEquals(2, list.selectedItemPosition)
                assertSame(list, dialog.currentFocus)
            }
            screenshot("dlna-portrait.png")
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
            idle()
            assertEquals(2, selected)
            scenario.onActivity { dialog.dismiss() }
        }
    }

    @Test
    fun backNavigatesToParentAndCloseExitsBrowser() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var wentUp = false
            var closed = false
            lateinit var dialog: DlnaSelectionDialog
            scenario.onActivity { activity ->
                dialog = fixture(activity, onUp = { wentUp = true }, onClose = { closed = true })
                dialog.show()
            }
            sendKey(KeyEvent.KEYCODE_BACK)
            assertTrue(wentUp)
            assertFalse(closed)
            scenario.onActivity {
                assertTrue(dialog.isShowing)
                dialog.updateContent(fixtureContent(onUp = { wentUp = false }, onClose = { closed = true }))
            }
            tap(dialog, R.id.dlna_close)
            assertTrue(closed)
            assertTrue(wentUp) // Explicit Close does not invoke parent navigation.
        }
    }

    @Test
    fun landscapeKeepsActionsReachableAndLongTitlesSelectable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            lateinit var dialog: DlnaSelectionDialog
            var selected = -1
            scenario.onActivity { activity ->
                dialog = fixture(activity, initialSelection = 2, onSelected = { selected = it })
                dialog.show()
            }
            idle()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            scenario.onActivity {
                dialog.refreshForConfiguration()
            }
            idle()
            scenario.onActivity {
                dialog.findViewById<ListView>(R.id.dlna_list).apply {
                    requestFocusFromTouch()
                    setSelection(2)
                    assertTrue("A complete selection row must fit in landscape", height >= getChildAt(0).height)
                }
            }
            idle()
            screenshot("dlna-landscape.png")
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
            idle()
            assertEquals(2, selected)
            tap(dialog, R.id.dlna_close)
        }
    }

    private fun fixture(activity: MainActivity, initialSelection: Int = 0, onUp: () -> Unit = {}, onClose: () -> Unit = {},
                        onSelected: (Int) -> Unit = {}) = DlnaSelectionDialog(activity,
        fixtureContent(initialSelection, onUp, onClose, onSelected))

    private fun fixtureContent(initialSelection: Int = 0, onUp: () -> Unit = {}, onClose: () -> Unit = {},
                               onSelected: (Int) -> Unit = {}) = DlnaDialogContent(
        title = "アニメ OP / ED",
        fileSelection = true,
        rows = listOf(
            DlnaRow("2026年 秋アニメ", R.drawable.ic_folder_open),
            DlnaRow("お気に入り", R.drawable.ic_folder_open),
            DlnaRow("長い作品名のオープニングコレクション 第2期 ノンクレジット版 OP2.mp4", R.drawable.ic_play),
            DlnaRow("作品 ED2.mp4", R.drawable.ic_play),
            DlnaRow("作品 OP.flac", R.drawable.ic_dlna_audio),
            DlnaRow("作品 ED.flac", R.drawable.ic_dlna_audio)),
        initialSelection = initialSelection,
        onUp = onUp, onRefresh = {}, onClose = onClose, onSelected = onSelected)

    private fun sendKey(key: Int) {
        idle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            automation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, key,
                0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD), true)
        }
        idle()
    }

    private fun tap(dialog: DlnaSelectionDialog, id: Int) {
        idle()
        val point = IntArray(2)
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = dialog.findViewById<View>(id)
                ready = view.isShown && view.width > 0 && view.height > 0 &&
                    dialog.window?.decorView?.hasWindowFocus() == true
                if (ready) {
                    view.getLocationOnScreen(point)
                    point[0] += view.width / 2
                    point[1] += view.height / 2
                }
            }
            if (!ready) SystemClock.sleep(16)
        }
        assertTrue("Dialog action must be laid out and ready for input", ready)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                point[0].toFloat(), point[1].toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        idle()
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                ready = activity.resources.configuration.orientation == orientation &&
                    (if (orientation == Configuration.ORIENTATION_LANDSCAPE) content.width > content.height
                    else content.height > content.width)
            }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("Orientation did not settle", ready)
        idle()
    }

    private fun screenshot(name: String) {
        idle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
