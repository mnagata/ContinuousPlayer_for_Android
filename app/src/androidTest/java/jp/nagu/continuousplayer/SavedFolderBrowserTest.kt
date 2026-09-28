package jp.nagu.continuousplayer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedFolderBrowserTest {
    @Test fun addingFolderKeepsTheWindowAttachedAndBackRestoresTheSavedList() = withFixture { scenario, folder ->
        lateinit var browser: SafFileBrowser
        lateinit var windowView: android.view.View
        lateinit var titleView: android.widget.TextView
        var detachCount = 0
        var requestedLocal = false
        scenario.onActivity {
            browser = SafFileBrowser(it, onAddFolder = { requestedLocal = true },
                onAddDlna = { fail("Unexpected DLNA discovery") }, onDlnaFolder = { fail("Unexpected saved selection") },
                onSelected = { _, _ -> fail("Unexpected playback") })
            browser.openSavedFolders()
        }
        waitForFolder(folder.title)
        scenario.onActivity { activity ->
            windowView = android.view.inspector.WindowInspector.getGlobalWindowViews().single { view ->
                view.findViewById<android.widget.TextView>(R.id.dlna_title)?.text?.toString() ==
                    activity.getString(R.string.saf_choose_folder)
            }
            titleView = windowView.findViewById(R.id.dlna_title)
            windowView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = Unit
                override fun onViewDetachedFromWindow(view: android.view.View) { detachCount++ }
            })
        }
        repeat(2) {
            BrowserTestUi.tap(R.string.saf_add_folder)
            BrowserTestUi.waitForText(R.string.saved_folder_local)
            scenario.onActivity { activity ->
                assertTrue(windowView.isAttachedToWindow)
                assertSame(titleView, windowView.findViewById(R.id.dlna_title))
                assertEquals(activity.getString(R.string.saf_add_folder), titleView.text.toString())
                assertEquals(0, detachCount)
            }
            BrowserTestUi.tap(R.string.saved_folder_local)
            BrowserTestUi.waitUntil { requestedLocal }
            scenario.onActivity {
                assertTrue(windowView.isAttachedToWindow)
                assertEquals(0, detachCount)
                requestedLocal = false
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val keyDownTime = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                automation.injectInputEvent(android.view.KeyEvent(keyDownTime, android.os.SystemClock.uptimeMillis(),
                    action, android.view.KeyEvent.KEYCODE_BACK, 0), true)
            }
            waitForFolder(folder.title)
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.saf_choose_folder), titleView.text.toString())
                assertEquals(0, detachCount)
            }
        }
        scenario.onActivity { browser.close() }
    }

    @Test fun savedDlnaFolderOpensFromTheSharedListWithoutRequestingSafAccess() = withFixture { scenario, folder ->
        var requestedSaf = false
        var selected: SavedDlnaFolder? = null
        lateinit var browser: SafFileBrowser
        scenario.onActivity {
            browser = SafFileBrowser(it, onAddFolder = { requestedSaf = true }, onAddDlna = { fail("Unexpected DLNA discovery") },
                onDlnaFolder = { chosen -> selected = chosen }, onSelected = { _, _ -> fail("Unexpected local media") })
            browser.open()
        }
        waitForFolder(folder.title)
        BrowserTestUi.tap(folder.title)
        BrowserTestUi.waitUntil { selected != null }
        assertEquals(folder, selected)
        assertFalse(requestedSaf)
        scenario.onActivity { browser.close() }
    }

    @Test fun longPressCanRemoveAnUnavailableDlnaFolderWithoutConnecting() = withFixture { scenario, folder ->
        lateinit var browser: SafFileBrowser
        scenario.onActivity {
            browser = SafFileBrowser(it, onAddFolder = {}, onAddDlna = { fail("Unexpected DLNA discovery") }, onDlnaFolder = { fail("Unexpected connection") },
                onSelected = { _, _ -> fail("Unexpected local media") })
            browser.openSavedFolders()
        }
        waitForFolder(folder.title)
        BrowserTestUi.tap(folder.title, longPress = true)
        BrowserTestUi.tap(R.string.folder_unregister)
        BrowserTestUi.waitUntil { !SavedDlnaFolders(InstrumentationRegistry.getInstrumentation().targetContext)
            .contains(folder.serverId, folder.folderId) }
        assertFalse(SavedDlnaFolders(InstrumentationRegistry.getInstrumentation().targetContext)
            .contains(folder.serverId, folder.folderId))
        scenario.onActivity { browser.close() }
    }

    private fun waitForFolder(title: String) { BrowserTestUi.waitForText(title) }

    private fun withFixture(action: (ActivityScenario<MainActivity>, SavedDlnaFolder) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("saved_dlna_folders", Context.MODE_PRIVATE)
        val original = preferences.getString("folders", null)
        try {
            preferences.edit().clear().commit()
            val store = SavedDlnaFolders(context)
            store.save(DlnaServer("uuid:saved-fixture", "Fixture NAS", "http://192.0.2.1/control", "ContentDirectory:1"),
                listOf(DlnaEntry("0", "Fixture NAS"), DlnaEntry("opaque-id", "Fixture saved OP ED")))
            val folder = store.all().single()
            ActivityScenario.launch(MainActivity::class.java).use { action(it, folder) }
        } finally {
            preferences.edit().putString("folders", original).commit()
        }
    }
}
