package jp.nagu.continuousplayer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlnaSavedFolderTest {
    private class FixtureClient : DlnaClient() {
        var server = DlnaServer("uuid:browser-fixture", "Fixture NAS", "http://192.0.2.1/control", "ContentDirectory:1")
        var available = true
        var discoveries = 0
        val requests = mutableListOf<Pair<DlnaServer, String>>()
        override suspend fun discover(): List<DlnaServer> {
            discoveries++
            return if (available) listOf(server) else emptyList()
        }
        override suspend fun browse(server: DlnaServer, objectId: String): List<DlnaEntry> {
            requests += server to objectId
            return when (objectId) {
                "0" -> listOf(DlnaEntry("opaque-folder", "Fixture nested OP ED"))
                "opaque-folder" -> listOf(DlnaEntry("media", "Sample OP.mp4", VideoItem(
                    server.controlUrl.replace("control", "Sample.mp4"), "Sample OP.mp4", 100, 0, "video/mp4")))
                else -> error("Unexpected folder: $objectId")
            }
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
            waitForText("Sample OP.mp4")
            BrowserTestUi.tap(R.string.folder_register)
            BrowserTestUi.waitUntil { store.all().isNotEmpty() }
            val folder = store.all().single()
            assertEquals("opaque-folder", folder.folderId)
            assertEquals(listOf("0", "opaque-folder"), folder.path.map { it.id })
            BrowserTestUi.waitForText(R.string.folder_unregister)
            scenario.onActivity {
                browser.close()
                client.server = client.server.copy(controlUrl = "http://192.0.2.2/control")
                browser.openSaved(folder) { fail("Unexpected return") }
            }
            waitForText("Sample OP.mp4")
            assertEquals(2, client.discoveries)
            assertEquals(client.server to "opaque-folder", client.requests.last())
            BrowserTestUi.tap("Sample OP.mp4")
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
            waitForText("Sample OP.mp4")
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
            waitForText("Sample OP.mp4")
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
