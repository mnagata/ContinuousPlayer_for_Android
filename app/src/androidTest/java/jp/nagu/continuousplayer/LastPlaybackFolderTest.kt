package jp.nagu.continuousplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastPlaybackFolderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)
    private var original: Map<String, *> = emptyMap<String, Any>()

    @Before fun isolateSelection() {
        original = preferences.all
        preferences.edit().clear().commit()
    }

    @After fun restoreSelection() {
        val editor = preferences.edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    @Test fun safChildRestoresItsGrantedTreeAfterSortingChanges() {
        val tree = tree("root/with opaque id")
        LastPlaybackFolder(context).save(PlaybackFolder.Saf(listOf(
            SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree, "root/with opaque id"), "Root"),
            SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree, "child"), "Child"))))
        // Re-create the store and change the order: a persisted row number would fail.
        val restored = LastPlaybackFolder(context)
        assertEquals(2, restored.initialSelection(listOf(tree("first"), tree("second"), tree), emptyList()))
        assertEquals(1, restored.initialSelection(listOf(tree("second"), tree, tree("first")), emptyList()))
        assertEquals(0, restored.initialSelection(listOf(tree("first")), emptyList()))
    }

    @Test fun singleSafTreeReopensAtThePlayedChildAndRestoresEachLevel() {
        val saved = context.getSharedPreferences("saved_dlna_folders", Context.MODE_PRIVATE)
        val originalFolders = saved.getString("folders", null)
        val tree = ScrollFixtureProvider.tree
        try {
            saved.edit().clear().commit()
            context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test",
                ScrollFixtureGrantActivity::class.java.name).putExtra("target", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            BrowserTestUi.waitUntil {
                runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .isSuccess
            }
            assertEquals(listOf(tree), context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var browser: SafFileBrowser
                var selectedMedia: VideoItem? = null
                scenario.onActivity { activity ->
                    browser = SafFileBrowser(activity, {}, {}, {}) { media, index ->
                        selectedMedia = media[index]
                        ViewModelProvider(activity)[PlayerViewModel::class.java].playbackFolder = browser.selectedFolder
                    }
                    browser.open()
                }
                BrowserTestUi.waitForText("Season 00")
                selectRow(scenario, "Scroll fixture", 24)
                BrowserTestUi.waitForText("Collection 00")
                selectRow(scenario, "Season 24", 27)
                BrowserTestUi.waitForText("Sample")
                BrowserTestUi.tap("Sample")
                BrowserTestUi.waitUntil { browser.selectedFolder?.path?.size == 3 }
                lateinit var configuration: android.content.res.Configuration
                scenario.onActivity { activity ->
                    configuration = android.content.res.Configuration(activity.resources.configuration)
                    val tv = android.content.res.Configuration(configuration).apply {
                        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK.inv()) or
                            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                    }
                    @Suppress("DEPRECATION")
                    activity.resources.updateConfiguration(tv, activity.resources.displayMetrics)
                    val folder = requireNotNull(browser.selectedFolder)
                    browser.close()
                    browser.openFolder(folder, Uri.parse(requireNotNull(selectedMedia).uri))
                }
                try {
                    BrowserTestUi.waitForText("Sample")
                    BrowserTestUi.waitUntil {
                        WindowInspector.getGlobalWindowViews().any {
                            val list = it.findViewById<ListView>(R.id.dlna_list)
                            list?.hasFocus() == true && list.selectedItemPosition == 2
                        }
                    }
                    scenario.onActivity {
                        val list = WindowInspector.getGlobalWindowViews().single { view ->
                            view.findViewById<TextView>(R.id.dlna_title)?.text == "Collection 27"
                        }.findViewById<ListView>(R.id.dlna_list)
                        assertEquals("Sample", (list.selectedItem as DlnaRow).title)
                        assertTrue(2 in list.firstVisiblePosition..list.lastVisiblePosition)
                    }
                } finally {
                    scenario.onActivity { activity ->
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    }
                }
                scenario.onActivity { browser.close(); it.finishAndRemoveTask() }
            }
            assertTrue(preferences.getStringSet("saf_path", emptySet()).orEmpty().any { it.contains("season-24") })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<android.widget.Button>(R.id.btn_select_folder).performClick() }
                BrowserTestUi.waitForText("Season 24")
                assertFirstRow(scenario, "Scroll fixture", "Season 24")
                BrowserTestUi.tap("Season 24")
                BrowserTestUi.waitForText("Collection 27")
                assertFirstRow(scenario, "Season 24", "Collection 27")
            }
        } finally {
            context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }?.let {
                context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            saved.edit().putString("folders", originalFolders).commit()
        }
    }

    @Test fun safCurrentFileUsesUriWithDuplicateNamesAndScrollsBeyondFolders() {
        val tree = ScrollFixtureProvider.tree
        val alreadyGranted = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test",
            ScrollFixtureGrantActivity::class.java.name).putExtra("target", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        BrowserTestUi.waitUntil {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .isSuccess
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var browser: SafFileBrowser
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
                    browser = SafFileBrowser(activity, {}, {}, {}) { media, index -> selected = media[index] }
                }
                val title = "SAF current-file fixture"
                val folder = PlaybackFolder.Saf(listOf(SafFolder(
                    DocumentsContract.buildDocumentUriUsingTree(tree, "focus-fixture"), title)))
                try {
                    for ((id, expectedRow) in listOf("media-two" to 41, "media-one" to 40, "missing" to 0)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "focus-fixture/$id")
                        scenario.onActivity {
                            browser.close()
                            selected = null
                            browser.openFolder(folder, uri)
                        }
                        BrowserTestUi.waitUntil {
                            WindowInspector.getGlobalWindowViews().any {
                                val list = it.findViewById<ListView>(R.id.dlna_list)
                                it.findViewById<TextView>(R.id.dlna_title)?.text == title &&
                                    list?.hasFocus() == true && list.count == 42 && list.selectedItemPosition == expectedRow
                            }
                        }
                        scenario.onActivity {
                            val list = list(title)
                            assertTrue(expectedRow in list.firstVisiblePosition..list.lastVisiblePosition)
                            assertNull("Opening a browser must not choose a media file", selected)
                            if (expectedRow > 0) {
                                assertEquals("Duplicate", (list.selectedItem as DlnaRow).title)
                                list.performItemClick(list.selectedView, expectedRow, list.selectedItemId)
                                assertEquals(uri.toString(), selected?.uri)
                            } else assertEquals(R.drawable.ic_folder_open, (list.selectedItem as DlnaRow).icon)
                        }
                    }
                } finally {
                    scenario.onActivity { activity ->
                        browser.close()
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    }
                }
            }
        } finally {
            if (!alreadyGranted) context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test fun explicitParentSelectionTakesPriorityAndNoPlaybackKeepsThePreviousFolder() {
        val first = tree("first")
        val last = tree("last")
        val store = LastPlaybackFolder(context)
        store.save(PlaybackFolder.Saf(listOf(SafFolder(
            DocumentsContract.buildDocumentUriUsingTree(last, "last"), "Last"))))
        store.save(null)
        assertEquals(1, store.initialSelection(listOf(first, last), emptyList()))
        assertEquals(0, store.initialSelection(listOf(first, last), emptyList(), first))
        store.save(PlaybackFolder.Usb(File("/usb"), File("/usb/videos")))
        assertEquals(0, store.initialSelection(listOf(first, last), emptyList()))
    }

    @Test fun dlnaChildSelectsTheClosestRegisteredAncestorOnTheSameServer() {
        val root = folder("server", "0")
        val parent = folder("server", "0", "parent")
        val child = folder("server", "0", "parent", "child")
        val otherServer = folder("other", "0", "parent", "child")
        LastPlaybackFolder(context).save(PlaybackFolder.Dlna(child))
        val restored = LastPlaybackFolder(context)
        val roots = listOf(tree("local"))
        assertEquals(3, restored.initialSelection(roots, listOf(otherServer, root, parent)))
        assertEquals(2, restored.initialSelection(roots, listOf(parent, child, otherServer)))
        assertEquals(0, restored.initialSelection(roots, listOf(otherServer)))
    }

    @Test fun usbPlaybackSelectsTheClosestRegisteredFolderOnTheSameDrive() {
        val root = File("/storage/usb-one")
        val parent = SavedUsbFolder(root.path, "$root/videos")
        val child = SavedUsbFolder(root.path, "$root/videos/season")
        val other = SavedUsbFolder("/storage/usb-two", "/storage/usb-two/videos/season")
        LastPlaybackFolder(context).save(PlaybackFolder.Usb(root, File(root, "videos/season/OP")))
        assertEquals(3, LastPlaybackFolder(context).initialSelection(listOf(tree("local")), emptyList(),
            usb = listOf(other, parent, child)))
        assertEquals(0, LastPlaybackFolder(context).initialSelection(emptyList(), emptyList(), usb = listOf(other)))
    }

    @Test fun exitingAndRelaunchingScrollsTheSavedListToThePlaybackFolder() {
        val saved = context.getSharedPreferences("saved_dlna_folders", Context.MODE_PRIVATE)
        val originalFolders = saved.getString("folders", null)
        val folders = SavedDlnaFolders(context)
        try {
            saved.edit().clear().commit()
            val server = DlnaServer("selection-fixture", "Selection NAS", "http://192.0.2.1/control", "ContentDirectory:1")
            repeat(40) { index ->
                folders.save(server, listOf(DlnaEntry("0", server.name),
                    DlnaEntry("folder-$index", "Folder %02d".format(index))))
            }
            val played = folders.all().single { it.folderId == "folder-24" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[PlayerViewModel::class.java].playbackFolder = PlaybackFolder.Dlna(played)
                    activity.finishAndRemoveTask()
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.findViewById<android.widget.Button>(R.id.btn_select_folder).performClick() }
                BrowserTestUi.waitForText(played.title)
                scenario.onActivity { activity ->
                    assertNull(ViewModelProvider(activity)[PlayerViewModel::class.java].playbackFolder)
                    val window = WindowInspector.getGlobalWindowViews().single {
                        it.findViewById<TextView>(R.id.dlna_title)?.text?.toString() == activity.getString(R.string.saf_choose_folder)
                    }
                    val list = window.findViewById<ListView>(R.id.dlna_list)
                    assertEquals(played.title, list.getChildAt(0).findViewById<TextView>(R.id.dlna_row_title).text.toString())
                    assertTrue("The restored item should be well below the top of the list", list.firstVisiblePosition >= 24)
                }
            }
        } finally {
            saved.edit().putString("folders", originalFolders).commit()
        }
    }

    private fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri("selection.saf.fixture", id)
    private fun folder(server: String, vararg ids: String) =
        SavedDlnaFolder(server, "NAS", ids.map { SavedDlnaPath(it, it) })

    private fun selectRow(scenario: ActivityScenario<MainActivity>, title: String, index: Int) {
        scenario.onActivity {
            val list = list(title)
            list.performItemClick(list.getChildAt(0), index, list.adapter.getItemId(index))
        }
    }

    private fun assertFirstRow(scenario: ActivityScenario<MainActivity>, title: String, row: String) {
        scenario.onActivity { assertEquals(row, list(title).getChildAt(0).findViewById<TextView>(R.id.dlna_row_title).text.toString()) }
    }

    private fun list(title: String): ListView = WindowInspector.getGlobalWindowViews().single {
        it.findViewById<TextView>(R.id.dlna_title)?.text?.toString() == title
    }.findViewById(R.id.dlna_list)
}
