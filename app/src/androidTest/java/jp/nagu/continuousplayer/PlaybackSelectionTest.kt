package jp.nagu.continuousplayer

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSelectionTest {
    @Test fun playingCancelAfterChangingFolderRestoresSession() = checkCancel(paused = false)

    @Test fun pausedCancelAfterChangingFolderKeepsPositionAndPause() = checkCancel(paused = true)

    private fun checkCancel(paused: Boolean) = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused)
        select(scenario, fixture.directory.name, "Other folder")
        waitForTitle("Other folder")
        scenario.onActivity {
            val model = ViewModelProvider(it)[PlayerViewModel::class.java]
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            assertEquals(1, original.currentMediaItemIndex)
            assertSame(fixture.playlist, model.videos)
            assertEquals(fixture.folder, model.playbackFolder)
            assertEquals(View.GONE, it.findViewById<View>(R.id.folder_select_container).visibility)
        }
        cancel(scenario, "Other folder")
        BrowserTestUi.waitUntil {
            fixture.playerView.player === original && original.playWhenReady == !paused &&
                (!paused || fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown)
        }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertEquals(1, original.currentMediaItemIndex)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_500.0)
            assertEquals(!paused, original.playWhenReady)
            assertEquals(2, original.mediaItemCount)
            assertEquals(fixture.files[1].toURI().path, original.currentMediaItem?.localConfiguration?.uri?.path)
            assertEquals(if (paused) View.VISIBLE else View.GONE,
                it.findViewById<View>(R.id.pause_overlay).visibility)
        }
    }

    @Test fun savedListTransferDoesNotResumeUntilCancelled() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        action(scenario, fixture.directory.name, R.id.dlna_up)
        waitForTitle(fixture.root.name)
        action(scenario, fixture.root.name, R.id.dlna_up)
        waitForTitle(R.string.saf_choose_folder)
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
        }
        cancel(scenario, InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.saf_choose_folder))
        BrowserTestUi.waitUntil { original.playWhenReady }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertEquals(fixture.folder, ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_500.0)
        }
    }

    @Test fun sameFileSelectionStartsNewSessionAtZero() = checkNewSelection("D01 ED", 1)

    @Test fun differentFileSelectionStartsNewSessionAtZero() = checkNewSelection("D01 OP", 0)

    @Test fun cancelDlnaDiscoveryAfterSwitchingSourcesRestoresOriginalSession() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        action(scenario, fixture.directory.name, R.id.dlna_up)
        waitForTitle(fixture.root.name)
        action(scenario, fixture.root.name, R.id.dlna_up)
        waitForTitle(R.string.saf_choose_folder)
        action(scenario, context.getString(R.string.saf_choose_folder), R.id.dlna_refresh)
        waitForTitle(R.string.saf_add_folder)
        scenario.onActivity {
            val list = window(context.getString(R.string.saf_add_folder))!!.findViewById<ListView>(R.id.dlna_list)
            val index = (0 until list.count).single {
                (list.adapter.getItem(it) as DlnaRow).title == context.getString(R.string.dlna_select)
            }
            list.performItemClick(null, index, list.adapter.getItemId(index))
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            window(context.getString(R.string.dlna_dialog_label))!!.findViewById<View>(R.id.dlna_close).performClick()
        }
        BrowserTestUi.waitUntil { original.playWhenReady }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertSame(fixture.playlist, ViewModelProvider(it)[PlayerViewModel::class.java].videos)
            assertEquals(fixture.folder, ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder)
            assertFalse(browserShowing())
        }
    }

    private fun checkNewSelection(title: String, index: Int) = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = true)
        select(scenario, fixture.directory.name, title)
        BrowserTestUi.waitUntil {
            fixture.playerView.player?.let { it !== original && it.isPlaying } == true && !browserShowing()
        }
        scenario.onActivity {
            val replacement = fixture.playerView.player!!
            assertNotSame(original, replacement)
            assertEquals(index, replacement.currentMediaItemIndex)
            assertTrue(replacement.currentPosition < 5_000)
            assertTrue(replacement.playWhenReady)
            assertEquals(2, replacement.mediaItemCount)
        }
    }

    @Test fun interruptionWhileSelectingPreventsResumeOnReturnAndCancel() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { assertFalse(original.playWhenReady) }
        cancel(scenario, fixture.directory.name)
        BrowserTestUi.waitUntil { fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_000.0)
        }
        // The restored manual-pause state must also survive a later stop/start.
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { assertFalse(original.playWhenReady) }
    }

    @Test fun lostLocalFileIsNotResumedFromCachedData() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        assertTrue(fixture.files[1].delete())
        cancel(scenario, fixture.directory.name)
        waitForTitle(R.string.playback_source_unavailable)
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_000.0)
        }
    }

    @Test fun homeEndsRetainedSessionAndClosesSelection() = withPlayback { scenario, fixture ->
        openSelection(scenario, fixture, paused = false)
        scenario.onActivity { it.findViewById<ImageButton>(R.id.btn_overlay_home).performClick() }
        scenario.onActivity {
            assertNull(fixture.playerView.player)
            assertFalse(ViewModelProvider(it)[PlayerViewModel::class.java].isPlayerScreen)
            assertEquals(View.VISIBLE, it.findViewById<View>(R.id.folder_select_container).visibility)
            assertFalse(browserShowing())
        }
    }

    @Test fun mediaPlayCannotStartPlaybackBehindSelection() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        scenario.onActivity {
            // MediaSession issues player commands independently of Activity key dispatch.
            original.play()
            assertFalse(original.playWhenReady)
        }
        cancel(scenario, fixture.directory.name)
        BrowserTestUi.waitUntil { original.playWhenReady }
    }

    @Test fun televisionBackCallbackRetainsCurrentFileAndRestoresOnCancel() = withPlayback { scenario, fixture ->
        val original = fixture.playerView.player!!
        lateinit var configuration: Configuration
        scenario.onActivity {
            original.seekTo(1, 30_000)
            configuration = Configuration(it.resources.configuration)
            val tv = Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(tv, it.resources.displayMetrics)
            // Exercise the Activity's TV Back callback without relying on the phone AVD's OS routing.
            it.onBackPressedDispatcher.onBackPressed()
        }
        try {
            BrowserTestUi.waitUntil {
                window(fixture.directory.name)?.findViewById<ListView>(R.id.dlna_list)?.selectedItemPosition == 2
            }
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertFalse(original.playWhenReady)
                assertEquals("D01 ED", (window(fixture.directory.name)!!.findViewById<ListView>(R.id.dlna_list)
                    .selectedItem as DlnaRow).title)
            }
            cancel(scenario, fixture.directory.name)
            BrowserTestUi.waitUntil { original.playWhenReady }
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertEquals(1, original.currentMediaItemIndex)
                assertEquals(30_000.0, original.currentPosition.toDouble(), 1_500.0)
            }
        } finally {
            scenario.onActivity {
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
            }
        }
    }

    @Test fun escapeCancelsWholeSelectionFromNestedFolder() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        select(scenario, fixture.directory.name, "Other folder")
        waitForTitle("Other folder")
        keyStroke(KeyEvent.KEYCODE_ESCAPE)
        BrowserTestUi.waitUntil { original.playWhenReady && !browserShowing() }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertEquals(fixture.folder, ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder)
            assertEquals(1, original.currentMediaItemIndex)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_500.0)
        }
    }

    @Test fun backInsideSelectionMovesToParentWithoutResuming() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        select(scenario, fixture.directory.name, "Other folder")
        waitForTitle("Other folder")
        keyStroke(KeyEvent.KEYCODE_BACK)
        waitForTitle(fixture.directory.name)
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
        }
        cancel(scenario, fixture.directory.name)
        BrowserTestUi.waitUntil { original.playWhenReady }
    }

    @Test fun oDoesNotOpenAnotherSelectionBehindTheBrowser() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        select(scenario, fixture.directory.name, "Other folder")
        waitForTitle("Other folder")
        keyStroke(KeyEvent.KEYCODE_O)
        scenario.onActivity {
            assertNotNull(window("Other folder"))
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
        }
        cancel(scenario, "Other folder")
        BrowserTestUi.waitUntil { original.playWhenReady }
    }

    @Test fun escapeClosesOnlyMediaInformation() = withPlayback { scenario, fixture ->
        val original = fixture.playerView.player!!
        scenario.onActivity { it.findViewById<ImageButton>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().any {
            it.findViewById<View>(R.id.info_close) != null
        } }
        keyStroke(KeyEvent.KEYCODE_ESCAPE)
        BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().none {
            it.findViewById<View>(R.id.info_close) != null
        } }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(browserShowing())
            assertTrue(original.playWhenReady)
        }
    }

    @Test fun escapeRepeatDoesNotCancelTheListOpenedByThatPress() = withPlayback { scenario, fixture ->
        val original = fixture.playerView.player!!
        val down = SystemClock.uptimeMillis()
        keyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN, down)
        waitForTitle(fixture.directory.name)
        keyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN, down, repeat = 1)
        keyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_UP, down)
        scenario.onActivity {
            assertNotNull(window(fixture.directory.name))
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
        }
        keyStroke(KeyEvent.KEYCODE_ESCAPE)
        BrowserTestUi.waitUntil { original.playWhenReady && !browserShowing() }
    }

    @Test fun modifiedOIsNotTheFileListShortcut() = withPlayback { scenario, fixture ->
        val original = fixture.playerView.player!!
        keyStroke(KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON)
        scenario.onActivity {
            assertFalse(browserShowing())
            assertSame(original, fixture.playerView.player)
            assertTrue(original.playWhenReady)
        }
    }

    private fun keyStroke(code: Int, modifiers: Int = 0) {
        val down = SystemClock.uptimeMillis()
        keyEvent(code, KeyEvent.ACTION_DOWN, down, modifiers = modifiers)
        keyEvent(code, KeyEvent.ACTION_UP, down, modifiers = modifiers)
    }

    private fun keyEvent(code: Int, action: Int, down: Long, repeat: Int = 0, modifiers: Int = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
            action, code, repeat, modifiers, -1, 0, 0,
            if (code == KeyEvent.KEYCODE_BACK) InputDevice.SOURCE_DPAD else InputDevice.SOURCE_KEYBOARD), true))
        instrumentation.waitForIdleSync()
    }

    private fun openSelection(scenario: ActivityScenario<MainActivity>, fixture: Fixture, paused: Boolean): Player {
        val original = fixture.playerView.player!!
        scenario.onActivity {
            original.seekTo(1, 30_000)
            if (paused) original.pause()
        }
        BrowserTestUi.waitUntil { original.currentMediaItemIndex == 1 && original.playbackState == Player.STATE_READY }
        scenario.onActivity { it.findViewById<ImageButton>(R.id.btn_overlay_select_folder).performClick() }
        waitForTitle(fixture.directory.name)
        BrowserTestUi.waitUntil { window(fixture.directory.name)?.findViewById<ListView>(R.id.dlna_list)?.count == 3 }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
        }
        return original
    }

    private data class Fixture(val root: File, val directory: File, val files: List<File>,
        val playerView: PlayerView, val playlist: List<VideoItem>, val folder: PlaybackFolder.Usb)

    private fun withPlayback(action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "d01-selection-fixture").apply { deleteRecursively(); mkdirs() }
        val directory = File(root, "D01 playback folder").apply { mkdirs() }
        File(directory, "Other folder").mkdirs()
        val dataSize = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        val files = listOf("D01 OP.wav", "D01 ED.wav").map { File(directory, it).apply { writeBytes(wave) } }
        val stores = listOf("saved_usb_folders", "usb_browser", "last_playback_folder").associateWith {
            context.getSharedPreferences(it, Context.MODE_PRIVATE)
        }
        val originals = stores.mapValues { it.value.all.toMap() }
        try {
            SavedUsbFolders(context).save(root, root)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var playerView: PlayerView
                val folder = PlaybackFolder.Usb(root, directory)
                scenario.onActivity {
                    playerView = it.findViewById(R.id.player_view)
                    ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder = folder
                    it.findViewById<ImageButton>(R.id.btn_overlay_select_folder).performClick()
                }
                select(scenario, directory.name, "D01 OP")
                BrowserTestUi.waitUntil { playerView.player?.isPlaying == true && !browserShowing() }
                lateinit var playlist: List<VideoItem>
                scenario.onActivity { playlist = ViewModelProvider(it)[PlayerViewModel::class.java].videos }
                action(scenario, Fixture(root, directory, files, playerView, playlist, folder))
            }
        } finally {
            stores.forEach { (name, store) ->
                val editor = store.edit().clear()
                originals.getValue(name).forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                editor.commit()
            }
            root.deleteRecursively()
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, folder: String, title: String) {
        BrowserTestUi.waitUntil { window(folder)?.findViewById<ListView>(R.id.dlna_list)?.adapter?.let { adapter ->
            (0 until adapter.count).any { (adapter.getItem(it) as DlnaRow).title == title }
        } == true }
        scenario.onActivity {
            val list = window(folder)!!.findViewById<ListView>(R.id.dlna_list)
            val index = (0 until list.count).single { (list.adapter.getItem(it) as DlnaRow).title == title }
            list.performItemClick(null, index, list.adapter.getItemId(index))
        }
    }

    private fun action(scenario: ActivityScenario<MainActivity>, folder: String, id: Int) {
        scenario.onActivity { window(folder)!!.findViewById<View>(id).performClick() }
    }

    private fun cancel(scenario: ActivityScenario<MainActivity>, folder: String) = action(scenario, folder, R.id.dlna_close)

    private fun window(title: String) = WindowInspector.getGlobalWindowViews().singleOrNull {
        it.findViewById<TextView>(R.id.dlna_title)?.text == title
    }

    private fun waitForTitle(resource: Int) = waitForTitle(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource))

    private fun waitForTitle(title: String) = BrowserTestUi.waitUntil {
        window(title) != null || WindowInspector.getGlobalWindowViews().any {
            it.findViewById<TextView>(android.R.id.message)?.text == title
        }
    }

    private fun browserShowing() = WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(R.id.dlna_list) != null }
}
