package jp.nagu.continuousplayer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedFolderBrowserTest {
    @Test fun televisionAddFolderRoutesSafUsbAndDlnaSeparately() = withFixture { scenario, folder ->
        lateinit var browser: SafFileBrowser
        lateinit var originalConfiguration: android.content.res.Configuration
        val requests = mutableListOf<String>()
        try {
            scenario.onActivity { activity ->
                originalConfiguration = android.content.res.Configuration(activity.resources.configuration)
                setUiMode(activity, android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
                browser = SafFileBrowser(activity, onAddFolder = { requests += "saf" },
                    onAddDlna = { requests += "dlna" }, onDlnaFolder = { fail("Unexpected saved selection") },
                    onAddUsb = { requests += "usb" }, onSelected = { _, _ -> fail("Unexpected playback") })
                browser.openSavedFolders()
            }
            waitForFolder(folder.title)
            BrowserTestUi.tap(R.string.saf_add_folder)
            BrowserTestUi.waitForText(R.string.saved_folder_local)
            BrowserTestUi.waitForText(R.string.usb_choose_drive)
            BrowserTestUi.waitForText(R.string.dlna_select)
            BrowserTestUi.tap(R.string.saved_folder_local)
            BrowserTestUi.waitUntil { requests.size == 1 }
            assertEquals(listOf("saf"), requests)
            // Cancelling SAF or USB must leave the source chooser available.
            BrowserTestUi.tap(R.string.usb_choose_drive)
            BrowserTestUi.waitUntil { requests.size == 2 }
            assertEquals(listOf("saf", "usb"), requests)
            BrowserTestUi.tap(R.string.dlna_select)
            BrowserTestUi.waitUntil { requests.size == 3 }
            assertEquals(listOf("saf", "usb", "dlna"), requests)
        } finally {
            scenario.onActivity {
                browser.close()
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(originalConfiguration, it.resources.displayMetrics)
            }
        }
    }

    @Test fun televisionSafSelectionLaunchesDocumentTreeAndCancelKeepsSourceChooser() = withFixture { scenario, folder ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(
            android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        lateinit var originalConfiguration: android.content.res.Configuration
        try {
            scenario.onActivity { activity ->
                originalConfiguration = android.content.res.Configuration(activity.resources.configuration)
                setUiMode(activity, android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
                activity.findViewById<android.widget.Button>(R.id.btn_select_folder).performClick()
            }
            waitForFolder(folder.title)
            BrowserTestUi.tap(R.string.saf_add_folder)
            BrowserTestUi.tap(R.string.saved_folder_local)
            BrowserTestUi.waitUntil { monitor.hits == 1 }
            BrowserTestUi.waitForText(R.string.saved_folder_local)
            BrowserTestUi.waitForText(R.string.usb_choose_drive)
            BrowserTestUi.tap(R.string.dlna_close)
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario.onActivity {
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(originalConfiguration, it.resources.displayMetrics)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setUiMode(activity: MainActivity, type: Int) {
        val configuration = android.content.res.Configuration(activity.resources.configuration)
        configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK.inv()) or type
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
    }

    private class DelayedDlnaClient(private val folder: SavedDlnaFolder) : DlnaClient() {
        val ready = CompletableDeferred<Unit>()
        @Volatile var searching = false
        @Volatile var cancelled = false
        override suspend fun discover(): List<DlnaServer> {
            searching = true
            try {
                ready.await()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
            return listOf(DlnaServer(folder.serverId, folder.serverName, "http://192.0.2.1/control", "ContentDirectory:1"))
        }
        override suspend fun browse(server: DlnaServer, objectId: String): List<DlnaEntry> =
            if (objectId == "0") listOf(DlnaEntry(folder.folderId, folder.title)) else listOf(
                DlnaEntry("media", "Sample OP.mp4", VideoItem("http://192.0.2.1/Sample.mp4", "Sample OP.mp4", 100, 0, "video/mp4")))
    }

    @Test fun savedDlnaSelectionAndReturnReuseTheSameWindowWithoutLoadingLayout() = withFixture { scenario, folder ->
        val client = DelayedDlnaClient(folder)
        lateinit var saf: SafFileBrowser
        lateinit var dlna: DlnaBrowser
        lateinit var windowView: android.view.View
        lateinit var titleView: android.widget.TextView
        var detachCount = 0
        scenario.onActivity { activity ->
            dlna = DlnaBrowser(activity, client) { _, _ -> fail("Unexpected playback") }
            saf = SafFileBrowser(activity, onAddFolder = {}, onAddDlna = {},
                onDlnaFolder = { chosen ->
                    dlna.openSaved(chosen, saf.takeDialog()) { saf.openSavedFolders(dlna.takeDialog()) }
                }, onSelected = { _, _ -> fail("Unexpected playback") })
            saf.openSavedFolders()
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
        BrowserTestUi.tap(folder.title)
        BrowserTestUi.waitUntil { client.searching }
        scenario.onActivity { activity ->
            assertTrue(windowView.isAttachedToWindow)
            assertEquals(activity.getString(R.string.saf_choose_folder), titleView.text.toString())
            assertEquals(0, detachCount)
            assertEquals(android.view.View.GONE, windowView.findViewById<android.view.View>(R.id.dlna_label).visibility)
            assertEquals(android.view.View.GONE, windowView.findViewById<android.view.View>(R.id.dlna_progress).visibility)
            assertFalse(windowView.findViewById<android.widget.ListView>(R.id.dlna_list).isEnabled)
            assertEquals(1, android.view.inspector.WindowInspector.getGlobalWindowViews().count {
                it.findViewById<android.view.View>(R.id.dlna_title) != null
            })
        }
        BrowserTestUi.waitUntil { windowView.findViewById<android.view.View>(R.id.dlna_pending_progress).isShown }
        scenario.onActivity { activity ->
            assertEquals(activity.getString(R.string.saf_choose_folder), titleView.text.toString())
            assertTrue(windowView.findViewById<android.widget.ListView>(R.id.dlna_list).isShown)
            assertEquals(0, detachCount)
            client.ready.complete(Unit)
        }
        waitForFolder("Sample OP")
        scenario.onActivity {
            assertTrue(windowView.isAttachedToWindow)
            assertSame(titleView, windowView.findViewById(R.id.dlna_title))
            assertEquals(folder.title, titleView.text.toString())
            assertEquals(0, detachCount)
            assertEquals(android.view.View.GONE, windowView.findViewById<android.view.View>(R.id.dlna_pending_progress).visibility)
        }
        BrowserTestUi.tap(R.string.usb_parent)
        waitForFolder(folder.serverName)
        BrowserTestUi.tap(R.string.saved_folder_list)
        BrowserTestUi.waitForText(R.string.saf_choose_folder)
        scenario.onActivity {
            assertSame(titleView, windowView.findViewById(R.id.dlna_title))
            assertEquals(0, detachCount)
            assertTrue(windowView.findViewById<android.widget.ListView>(R.id.dlna_list).isEnabled)
            saf.close()
            dlna.close()
        }
    }

    @Test fun closingTransferredSavedListCancelsDlnaDiscovery() = withFixture { scenario, folder ->
        val client = DelayedDlnaClient(folder)
        lateinit var saf: SafFileBrowser
        lateinit var dlna: DlnaBrowser
        scenario.onActivity { activity ->
            dlna = DlnaBrowser(activity, client) { _, _ -> fail("Unexpected playback") }
            saf = SafFileBrowser(activity, onAddFolder = {}, onAddDlna = {},
                onDlnaFolder = { chosen ->
                    dlna.openSaved(chosen, saf.takeDialog()) { saf.openSavedFolders(dlna.takeDialog()) }
                }, onSelected = { _, _ -> fail("Unexpected playback") })
            saf.openSavedFolders()
        }
        waitForFolder(folder.title)
        BrowserTestUi.tap(folder.title)
        BrowserTestUi.waitUntil { client.searching }
        BrowserTestUi.waitUntil {
            android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                it.findViewById<android.view.View>(R.id.dlna_pending_progress)?.isShown == true
            }
        }
        BrowserTestUi.tap(R.string.dlna_close)
        BrowserTestUi.waitUntil { client.cancelled }
        scenario.onActivity {
            assertTrue(android.view.inspector.WindowInspector.getGlobalWindowViews().none {
                it.findViewById<android.view.View>(R.id.dlna_title) != null
            })
            saf.close()
            dlna.close()
        }
    }

    @Test fun openingSafFromSavedListDoesNotShowTheLoadingLayout() = withFixture { scenario, folder ->
        lateinit var browser: SafFileBrowser
        lateinit var windowView: android.view.View
        var detachCount = 0
        scenario.onActivity {
            browser = SafFileBrowser(it, onAddFolder = {}, onAddDlna = {}, onDlnaFolder = {},
                onSelected = { _, _ -> fail("Unexpected playback") })
            browser.openSavedFolders()
        }
        waitForFolder(folder.title)
        scenario.onActivity { activity ->
            windowView = android.view.inspector.WindowInspector.getGlobalWindowViews().single { view ->
                view.findViewById<android.widget.TextView>(R.id.dlna_title)?.text?.toString() ==
                    activity.getString(R.string.saf_choose_folder)
            }
            windowView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = Unit
                override fun onViewDetachedFromWindow(view: android.view.View) { detachCount++ }
            })
            val list = windowView.findViewById<android.widget.ListView>(R.id.dlna_list)
            val adapter = list.adapter
            val title = windowView.findViewById<android.widget.TextView>(R.id.dlna_title)
            // Follow the same open(treeUri) path as tapping a saved SAF folder.
            // An unavailable provider also verifies that failure restores the error actions.
            browser.open(android.provider.DocumentsContract.buildTreeDocumentUri("unavailable.saf.fixture", "root"))
            assertEquals(activity.getString(R.string.saf_choose_folder), title.text.toString())
            assertEquals(android.view.View.GONE, windowView.findViewById<android.view.View>(R.id.dlna_progress).visibility)
            assertTrue(list.isShown)
            assertSame(adapter, list.adapter)
            assertFalse(list.isEnabled)
            assertFalse(windowView.findViewById<android.view.View>(R.id.dlna_refresh).isEnabled)
            assertTrue(windowView.findViewById<android.view.View>(R.id.dlna_close).isEnabled)
            assertEquals(0, detachCount)
        }
        BrowserTestUi.waitForText(R.string.saf_read_error)
        scenario.onActivity {
            assertTrue(windowView.isAttachedToWindow)
            assertEquals(0, detachCount)
            assertTrue(windowView.findViewById<android.view.View>(R.id.dlna_refresh).isEnabled)
            assertTrue(windowView.findViewById<android.view.View>(R.id.dlna_up).isEnabled)
            browser.close()
        }
    }

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
        var connectionRequests = 0
        scenario.onActivity {
            browser = SafFileBrowser(it, onAddFolder = {}, onAddDlna = { fail("Unexpected DLNA discovery") }, onDlnaFolder = { connectionRequests++ },
                onSelected = { _, _ -> fail("Unexpected local media") })
            browser.openSavedFolders()
        }
        waitForFolder(folder.title)
        BrowserTestUi.tap(folder.title, longPress = true)
        scenario.onActivity { assertEquals("Long press must not request a connection", 0, connectionRequests) }
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
