package jp.nagu.continuousplayer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsbFolderBrowserTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun useFolderRegistersWithoutPlayingAndSavedListReopensAndUnregistersIt() = withFixture { root, directory ->
        var reopened = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var usb: UsbFileBrowser
            lateinit var saved: SafFileBrowser
            scenario.onActivity { activity ->
                usb = UsbFileBrowser(activity, onFolderSelected = { saved.openSavedFolders(usb.takeDialog()) }) { _, _ ->
                    fail("Selecting a folder must not start playback")
                }
                saved = SafFileBrowser(activity, onAddFolder = {}, onAddDlna = {}, onDlnaFolder = {},
                    onUsbFolder = { folder ->
                        reopened = true
                        usb.openSaved(folder.folder, saved.takeDialog()) {
                            saved.openSavedFolders(usb.takeDialog())
                        }
                    },
                    onSelected = { _, _ -> fail("Unexpected SAF playback") })
                usb.openFolder(PlaybackFolder.Usb(root, directory), forRegistration = true)
            }
            BrowserTestUi.waitForText("Sample OP")
            lateinit var registrationWindow: android.view.View
            scenario.onActivity { registrationWindow = sharedWindow(directory.name) }
            pressUseFolderWithRemote(scenario)
            BrowserTestUi.waitForText(R.string.saf_choose_folder)
            scenario.onActivity { assertSame(registrationWindow, sharedWindow(context.getString(R.string.saf_choose_folder))) }
            assertEquals(SavedUsbFolder(root.canonicalPath, directory.canonicalPath),
                SavedUsbFolders(context).all().single())
            BrowserTestUi.tap(directory.name)
            BrowserTestUi.waitUntil { reopened }
            BrowserTestUi.waitForText("Sample OP")
            scenario.onActivity {
                val view = sharedWindow(directory.name)
                assertTrue(view.findViewById<android.widget.ListView>(R.id.dlna_list).isShown)
                assertEquals(android.view.View.GONE, view.findViewById<android.view.View>(R.id.dlna_register).visibility)
            }
            BrowserTestUi.tap(R.string.saved_folder_list)
            BrowserTestUi.waitForText(R.string.saf_choose_folder)
            // Re-registering the same directory must not add a second bookmark.
            SavedUsbFolders(context).save(root, directory)
            assertEquals(1, SavedUsbFolders(context).all().size)
            BrowserTestUi.tap(directory.name, longPress = true)
            BrowserTestUi.tap(R.string.folder_unregister)
            BrowserTestUi.waitUntil { SavedUsbFolders(context).all().isEmpty() }
            assertTrue(File(directory, "Sample OP.mp4").exists())
            scenario.onActivity { usb.close(); saved.close() }
        }
    }

    @Test fun emptyFolderCanBeRegisteredAndReopenedAfterRelaunch() = withFixture { root, directory ->
        File(directory, "Sample OP.mp4").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                UsbFileBrowser(activity) { _, _ -> fail("Unexpected playback") }
                    .openFolder(PlaybackFolder.Usb(root, directory), forRegistration = true)
            }
            BrowserTestUi.tap(R.string.usb_use_folder)
            BrowserTestUi.waitUntil { SavedUsbFolders(context).all().size == 1 }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var saved: SafFileBrowser
            scenario.onActivity { activity ->
                saved = SafFileBrowser(activity, onAddFolder = { fail("Should open the saved list") },
                    onAddDlna = {}, onDlnaFolder = {}, onSelected = { _, _ -> fail("Unexpected playback") })
                saved.open()
            }
            BrowserTestUi.waitForText(directory.name)
            BrowserTestUi.waitForText("${context.getString(R.string.saved_folder_usb)} · ${directory.canonicalPath}")
            scenario.onActivity { saved.close() }
        }
    }

    @Test fun offlineBookmarksRemainRemovableAndInvalidPathsAreRejected() = withFixture { root, directory ->
        val store = SavedUsbFolders(context)
        store.save(root, directory)
        directory.deleteRecursively()
        val folder = SavedUsbFolders(context).all().single()
        assertEquals(directory.canonicalPath, folder.directoryPath)
        SavedUsbFolders(context).remove(folder)
        assertTrue(store.all().isEmpty())
        try {
            store.save(root, File(root.parentFile, "outside-root"))
            fail("A folder outside the drive must be rejected")
        } catch (_: IllegalArgumentException) { }
        val preferences = context.getSharedPreferences("saved_usb_folders", Context.MODE_PRIVATE)
        for (json in listOf("not JSON", "null", "[null]", "[{}]",
            "[{\"rootPath\":\"/usb\",\"directoryPath\":\"/usb-other/videos\"}]")) {
            preferences.edit().putString("folders", json).commit()
            assertTrue(store.all().isEmpty())
        }
    }

    @Test fun registeredUsbUsesSharedWindowForNavigationAndPlayback() = withFixture { root, directory ->
        val child = File(directory, "Child folder.mp4").apply { mkdirs() }
        File(child, "Child OP.mp4").writeBytes(byteArrayOf(1))
        File(directory, "Sample ED.mp4").writeBytes(byteArrayOf(1))
        File(directory, "._Sample OP.mp4").writeBytes(byteArrayOf(1))
        SavedUsbFolders(context).save(root, directory)
        var played: List<VideoItem>? = null
        var playedIndex = -1
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var browser: UsbFileBrowser
            lateinit var window: android.view.View
            scenario.onActivity { activity ->
                browser = UsbFileBrowser(activity) { items, index -> played = items; playedIndex = index }
                browser.openFolder(PlaybackFolder.Usb(root, directory))
            }
            BrowserTestUi.waitForText("Sample OP")
            scenario.onActivity { window = sharedWindow(directory.name) }
            BrowserTestUi.tap(child.name)
            BrowserTestUi.waitForText("Child OP")
            scenario.onActivity { assertSame(window, sharedWindow(child.name)) }
            BrowserTestUi.tap(R.string.usb_parent)
            BrowserTestUi.waitForText("Sample ED")
            scenario.onActivity { assertSame(window, sharedWindow(directory.name)) }
            BrowserTestUi.tap("Sample ED")
            BrowserTestUi.waitUntil { played != null }
            assertEquals(listOf("Sample OP.mp4", "Sample ED.mp4"), played!!.map { it.displayName })
            assertEquals(1, playedIndex)
            assertEquals(PlaybackFolder.Usb(root, directory), browser.selectedFolder)
            scenario.onActivity { browser.close() }
        }
    }

    @Test fun registrationUsesSharedWindowForNavigationRefreshAndDriveList() = withFixture { root, directory ->
        val child = File(directory, "Empty child").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var browser: UsbFileBrowser
            lateinit var window: android.view.View
            scenario.onActivity { activity ->
                browser = UsbFileBrowser(activity) { _, _ -> fail("Unexpected playback") }
                browser.openFolder(PlaybackFolder.Usb(root, directory), forRegistration = true)
            }
            BrowserTestUi.waitForText("Sample OP")
            scenario.onActivity {
                window = sharedWindow(directory.name)
                val top = window.findViewById<android.view.View>(R.id.dlna_top_actions)
                assertSame(top, window.findViewById<android.view.View>(R.id.dlna_close).parent)
                assertSame(top, window.findViewById<android.view.View>(R.id.dlna_refresh).parent)
                assertTrue(window.findViewById<android.view.View>(R.id.dlna_register).isShown)
            }
            BrowserTestUi.tap(child.name)
            BrowserTestUi.waitForText(R.string.usb_empty)
            scenario.onActivity { assertSame(window, sharedWindow(child.name)) }
            File(child, "New OP.mp4").writeBytes(byteArrayOf(1))
            BrowserTestUi.tap(R.string.usb_refresh)
            BrowserTestUi.waitForText("New OP")
            scenario.onActivity { assertSame(window, sharedWindow(child.name)) }
            BrowserTestUi.tap(R.string.usb_parent)
            BrowserTestUi.waitForText("Sample OP")
            scenario.onActivity {
                assertSame(window, sharedWindow(directory.name))
                // Touch mode scrolls to the restored child without showing a D-pad selection.
                assertEquals(0, window.findViewById<android.widget.ListView>(R.id.dlna_list).firstVisiblePosition)
            }
            BrowserTestUi.tap(R.string.usb_parent)
            BrowserTestUi.waitForText(root.name)
            BrowserTestUi.tap(R.string.usb_parent)
            BrowserTestUi.waitForText(R.string.usb_choose_drive)
            scenario.onActivity {
                assertSame(window, sharedWindow(context.getString(R.string.usb_choose_drive)))
                assertEquals(android.view.View.GONE, window.findViewById<android.view.View>(R.id.dlna_register).visibility)
            }
            BrowserTestUi.tap(R.string.usb_refresh)
            BrowserTestUi.waitForText(R.string.usb_choose_drive)
            scenario.onActivity { assertSame(window, sharedWindow(context.getString(R.string.usb_choose_drive))) }
            BrowserTestUi.tap(R.string.file_browser_cancel)
            BrowserTestUi.waitUntil { !window.isAttachedToWindow }
            scenario.onActivity { browser.close() }
        }
    }

    @Test fun registrationReadErrorAndRetryKeepSharedWindow() = withFixture { root, directory ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var browser: UsbFileBrowser
            lateinit var window: android.view.View
            scenario.onActivity { activity ->
                browser = UsbFileBrowser(activity) { _, _ -> fail("Unexpected playback") }
                browser.openFolder(PlaybackFolder.Usb(root, directory), forRegistration = true)
            }
            BrowserTestUi.waitForText("Sample OP")
            scenario.onActivity { window = sharedWindow(directory.name) }
            directory.deleteRecursively()
            BrowserTestUi.tap(R.string.usb_refresh)
            BrowserTestUi.waitUntil {
                window.findViewById<android.widget.TextView>(R.id.dlna_message).text
                    .contains(context.getString(R.string.usb_read_error))
            }
            scenario.onActivity {
                assertSame(window, sharedWindow(directory.name))
                assertEquals(android.view.View.GONE, window.findViewById<android.view.View>(R.id.dlna_register).visibility)
            }
            directory.mkdirs()
            File(directory, "Restored OP.mp4").writeBytes(byteArrayOf(1))
            BrowserTestUi.tap(R.string.usb_refresh)
            BrowserTestUi.waitForText("Restored OP")
            scenario.onActivity {
                assertSame(window, sharedWindow(directory.name))
                assertTrue(window.findViewById<android.view.View>(R.id.dlna_register).isShown)
                browser.close()
            }
        }
    }

    private fun sharedWindow(title: String) = android.view.inspector.WindowInspector.getGlobalWindowViews().single {
        it.findViewById<android.widget.TextView>(R.id.dlna_title)?.text?.toString() == title
    }

    private fun withFixture(action: (File, File) -> Unit) {
        val stores = listOf("saved_usb_folders", "saved_dlna_folders", "usb_browser", "last_playback_folder")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val originals = stores.mapValues { it.value.all.toMap() }
        val root = File(context.cacheDir, "usb-folder-fixture").apply { mkdirs() }
        val directory = File(root, "USB OP ED folder").apply { mkdirs() }
        File(directory, "Sample OP.mp4").writeBytes(byteArrayOf(1))
        try {
            stores.values.forEach { it.edit().clear().commit() }
            action(root, directory)
        } finally {
            for ((name, preferences) in stores) {
                val editor = preferences.edit().clear()
                for ((key, value) in originals.getValue(name)) when (value) {
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                editor.commit()
            }
            root.deleteRecursively()
        }
    }

    private fun pressUseFolderWithRemote(scenario: ActivityScenario<MainActivity>) {
        BrowserTestUi.waitForText(R.string.usb_use_folder)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        // A preceding touch leaves Android in touch mode, where buttons refuse requestFocus().
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val event = android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
                action, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0)
            event.source = android.view.InputDevice.SOURCE_DPAD
            assertTrue(automation.injectInputEvent(event, true))
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .single { it.findViewById<android.widget.Button>(R.id.dlna_register)?.let { button ->
                    button.isShown && button.text == activity.getString(R.string.usb_use_folder)
                } == true }
            val list = window.findViewById<android.widget.ListView>(R.id.dlna_list)
            assertTrue(list.requestFocus())
            list.setSelection(list.count - 1)
        }
        instrumentation.waitForIdleSync()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val event = android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
                action, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0)
            event.source = android.view.InputDevice.SOURCE_DPAD
            assertTrue(automation.injectInputEvent(event, true))
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val button = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .mapNotNull { it.findViewById<android.widget.Button>(R.id.dlna_register) }
                .single { it.isShown && it.text == activity.getString(R.string.usb_use_folder) }
            assertTrue("D-pad down from the last row must reach the registration button", button.hasFocus())
        }
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val event = android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
                action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0)
            event.source = android.view.InputDevice.SOURCE_DPAD
            assertTrue(automation.injectInputEvent(event, true))
        }
    }
}
