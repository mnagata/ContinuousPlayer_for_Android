package jp.nagu.continuousplayer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.doOnPreDraw
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlnaSavedFolderTest {
    private class FixtureClient : DlnaClient() {
        var server = DlnaServer("uuid:browser-fixture", "Fixture NAS", "http://192.0.2.1/control", "ContentDirectory:1")
        var available = true
        var discoveries = 0
        var duplicateMedia = false
        val requests = mutableListOf<Pair<DlnaServer, String>>()
        override suspend fun discover(): List<DlnaServer> {
            discoveries++
            return if (available) listOf(server) else emptyList()
        }
        override suspend fun browse(server: DlnaServer, objectId: String): List<DlnaEntry> {
            requests += server to objectId
            return when (objectId) {
                "0" -> listOf(DlnaEntry("opaque-folder", "Fixture nested OP ED"))
                "opaque-folder" -> if (duplicateMedia) {
                    listOf(DlnaEntry("child-a", "Child A"), DlnaEntry("child-b", "Child B")) +
                        listOf("media-zero", "media-one").map { id ->
                            DlnaEntry(id, "Sample.mp4", VideoItem(server.controlUrl.replace("control", "$id.mp4"),
                                "Sample.mp4", 100, 0, "video/mp4"))
                        }
                } else listOf(DlnaEntry("media", "Sample OP.mp4", VideoItem(
                    server.controlUrl.replace("control", "Sample.mp4"), "Sample OP.mp4", 100, 0, "video/mp4")))
                else -> error("Unexpected folder: $objectId")
            }
        }
    }

    @Test fun playbackFileFocusUsesObjectIdDespiteDuplicateNamesAndChangedUrls() = withStore { store ->
        val client = FixtureClient().apply { duplicateMedia = true }
        store.save(client.server, listOf(DlnaEntry("0", client.server.name),
            DlnaEntry("opaque-folder", "Fixture nested OP ED")))
        val folder = store.all().single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var browser: DlnaBrowser
            lateinit var configuration: android.content.res.Configuration
            var selected: VideoItem? = null
            scenario.onActivity { activity ->
                configuration = android.content.res.Configuration(activity.resources.configuration)
                val tv = android.content.res.Configuration(configuration).apply {
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK.inv()) or
                        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(tv, activity.resources.displayMetrics)
                browser = DlnaBrowser(activity, client) { media, index -> selected = media[index] }
                browser.openSaved(folder) { fail("Unexpected return") }
            }
            try {
                waitForText("Sample")
                scenario.onActivity {
                    val list = android.view.inspector.WindowInspector.getGlobalWindowViews().single { view ->
                        view.findViewById<android.widget.TextView>(R.id.dlna_title)?.text == "Fixture nested OP ED"
                    }.findViewById<android.widget.ListView>(R.id.dlna_list)
                    list.performItemClick(null, 3, list.adapter.getItemId(3))
                    assertEquals("media-one", selected?.dlnaObjectId)
                    val playbackFolder = requireNotNull(browser.selectedFolder)
                    browser.close()
                    client.server = client.server.copy(controlUrl = "http://192.0.2.2/control")
                    browser.openSaved(playbackFolder.folder, focusMediaId = selected?.dlnaObjectId) {
                        fail("Unexpected return")
                    }
                }
                waitForText("Sample")
                BrowserTestUi.waitUntil {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                        val list = it.findViewById<android.widget.ListView>(R.id.dlna_list)
                        list?.hasFocus() == true && list.selectedItemPosition == 3
                    }
                }
                scenario.onActivity {
                    val list = android.view.inspector.WindowInspector.getGlobalWindowViews().single { view ->
                        view.findViewById<android.widget.TextView>(R.id.dlna_title)?.text == "Fixture nested OP ED"
                    }.findViewById<android.widget.ListView>(R.id.dlna_list)
                    list.performItemClick(list.selectedView, list.selectedItemPosition, list.selectedItemId)
                    assertEquals("media-one", selected?.dlnaObjectId)
                    assertEquals("http://192.0.2.2/media-one.mp4", selected?.uri)
                }
            } finally {
                scenario.onActivity { activity ->
                    browser.close()
                    @Suppress("DEPRECATION")
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                }
            }
        }
    }

    @Test fun selectingVideoKeepsDialogUntilPlayerFrameIsCommitted() = withStore {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val client = FixtureClient()
            lateinit var browser: DlnaBrowser
            lateinit var windowView: android.view.View
            var selected = false
            var playerFrameCommitted = false
            var detached = false
            var detachedAfterPlayerFrame = false
            scenario.onActivity { activity ->
                browser = DlnaBrowser(activity, client) { media, index ->
                    assertTrue("Keep the selection window attached while preparing playback", windowView.isAttachedToWindow)
                    assertEquals("Sample OP.mp4", media[index].displayName)
                    activity.findViewById<android.view.View>(R.id.folder_select_container).visibility = android.view.View.GONE
                    activity.findViewById<android.view.View>(R.id.player_container).visibility = android.view.View.VISIBLE
                    activity.window.decorView.doOnPreDraw { view ->
                        view.viewTreeObserver.registerFrameCommitCallback { playerFrameCommitted = true }
                    }
                    selected = true
                }
                browser.open()
            }
            waitForText(client.server.name)
            BrowserTestUi.tap(client.server.name)
            waitForText("Fixture nested OP ED")
            BrowserTestUi.tap("Fixture nested OP ED")
            waitForText("Sample OP")
            scenario.onActivity { activity ->
                windowView = android.view.inspector.WindowInspector.getGlobalWindowViews().single { view ->
                    view.findViewById<android.widget.TextView>(R.id.dlna_title)?.text?.toString() == "Fixture nested OP ED"
                }
                windowView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: android.view.View) = Unit
                    override fun onViewDetachedFromWindow(view: android.view.View) {
                        detached = true
                        detachedAfterPlayerFrame = playerFrameCommitted &&
                            !activity.findViewById<android.view.View>(R.id.folder_select_container).isShown
                    }
                })
            }
            BrowserTestUi.tap("Sample OP")
            BrowserTestUi.waitUntil { detached }
            assertTrue(selected)
            assertTrue("The home screen must be replaced before the selection window is removed", detachedAfterPlayerFrame)
            scenario.onActivity { browser.close() }
        }
    }

    @Test fun registerNestedFolderThenReopenWithFreshServerAndMediaUrls() = withStore { store ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val client = FixtureClient()
            lateinit var browser: DlnaBrowser
            var selection = emptyList<VideoItem>()
            scenario.onActivity {
                browser = DlnaBrowser(it, client) { media, _ -> selection = media }
                browser.open()
            }
            waitForText(client.server.name)
            BrowserTestUi.tap(client.server.name)
            waitForText("Fixture nested OP ED")
            BrowserTestUi.tap("Fixture nested OP ED")
            waitForText("Sample OP")
            BrowserTestUi.tap(R.string.folder_register)
            BrowserTestUi.waitUntil { store.all().isNotEmpty() }
            val folder = store.all().single()
            assertEquals("opaque-folder", folder.folderId)
            assertEquals(listOf("0", "opaque-folder"), folder.path.map { it.id })
            assertTrue(selection.isEmpty())
            BrowserTestUi.waitUntil { android.view.inspector.WindowInspector.getGlobalWindowViews().none {
                it.findViewById<android.view.View>(R.id.dlna_title) != null
            } }
            scenario.onActivity {
                browser.close()
                client.server = client.server.copy(controlUrl = "http://192.0.2.2/control")
                browser.openSaved(folder) { fail("Unexpected return") }
            }
            waitForText("Sample OP")
            assertEquals(2, client.discoveries)
            assertEquals(client.server to "opaque-folder", client.requests.last())
            BrowserTestUi.tap("Sample OP")
            BrowserTestUi.waitUntil { selection.isNotEmpty() }
            assertEquals("http://192.0.2.2/Sample.mp4", selection.single().uri)
            lateinit var playbackFolder: PlaybackFolder.Dlna
            scenario.onActivity {
                playbackFolder = requireNotNull(browser.selectedFolder)
                // Reopening playback must not depend on the folder remaining registered.
                store.remove(folder.serverId, folder.folderId)
                client.server = client.server.copy(controlUrl = "http://192.0.2.3/control")
                browser.openSaved(playbackFolder.folder) { fail("Unexpected return") }
            }
            waitForText("Sample OP")
            // Reopening keeps the previous rows visible while refreshing the server connection.
            BrowserTestUi.waitUntil { client.requests.lastOrNull() == client.server to "opaque-folder" }
            assertEquals(3, client.discoveries)
            assertEquals(client.server to "opaque-folder", client.requests.last())
            assertEquals(listOf("0", "opaque-folder"), playbackFolder.folder.path.map { it.id })
            scenario.onActivity { browser.close() }
        }
    }

    @Test fun offlineSavedFolderOffersReturnAndRetryWithoutDroppingRegistration() = withStore { store ->
        val client = FixtureClient().apply { available = false }
        store.save(client.server, listOf(DlnaEntry("0", client.server.name), DlnaEntry("opaque-folder", "Fixture nested OP ED")))
        val folder = store.all().single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var browser: DlnaBrowser
            var returned = false
            scenario.onActivity {
                browser = DlnaBrowser(it, client) { _, _ -> fail("Unexpected playback") }
                browser.openSaved(folder) { returned = true }
            }
            waitForText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.dlna_error))
            assertEquals(folder, store.all().single())
            BrowserTestUi.waitForText(R.string.saved_folder_list)
            scenario.onActivity { client.available = true }
            BrowserTestUi.tap(R.string.playback_retry)
            waitForText("Sample OP")
            BrowserTestUi.tap(R.string.usb_parent)
            waitForText("Fixture nested OP ED")
            BrowserTestUi.tap(R.string.saved_folder_list)
            BrowserTestUi.waitUntil { returned }
            assertTrue(returned)
            scenario.onActivity { browser.close() }
        }
    }

    private fun waitForText(title: String) { BrowserTestUi.waitForText(title) }

    private fun withStore(action: (SavedDlnaFolders) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("saved_dlna_folders", Context.MODE_PRIVATE)
        val original = preferences.getString("folders", null)
        try {
            preferences.edit().clear().commit()
            action(SavedDlnaFolders(context))
        } finally {
            preferences.edit().putString("folders", original).commit()
        }
    }
}
