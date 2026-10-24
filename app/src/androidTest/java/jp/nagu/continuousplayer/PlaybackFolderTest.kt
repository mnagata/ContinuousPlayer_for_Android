package jp.nagu.continuousplayer

import android.widget.ImageButton
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
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
class PlaybackFolderTest {
    @Test fun televisionBackDuringPlaybackReopensPlaylistDirectory() = checkPlaybackSelectionEntry(television = true, paused = false)

    @Test fun televisionBackWhilePausedReopensPlaylistDirectory() = checkPlaybackSelectionEntry(television = true, paused = true)

    @Test fun televisionBackAfterTrackChangesFocusesTheCurrentFile() =
        checkPlaybackSelectionEntry(television = true, paused = false, advanceToNext = true)

    @Test fun phoneBackDuringPlaybackReopensPlaylistDirectory() = checkPlaybackSelectionEntry(television = false, paused = false)

    @Test fun phoneBackWhilePausedReopensPlaylistDirectory() = checkPlaybackSelectionEntry(television = false, paused = true)

    @Test fun phoneBackAfterTrackChangesFocusesTheCurrentFile() =
        checkPlaybackSelectionEntry(television = false, paused = false, advanceToNext = true)

    @Test fun escapeDuringPlaybackReopensPlaylistDirectory() =
        checkPlaybackSelectionEntry(television = false, paused = false, keyCode = KeyEvent.KEYCODE_ESCAPE)

    @Test fun escapeWhilePausedReopensPlaylistDirectory() =
        checkPlaybackSelectionEntry(television = false, paused = true, keyCode = KeyEvent.KEYCODE_ESCAPE)

    @Test fun oDuringPlaybackReopensPlaylistDirectory() =
        checkPlaybackSelectionEntry(television = false, paused = false, keyCode = KeyEvent.KEYCODE_O)

    @Test fun oWhilePausedReopensPlaylistDirectory() =
        checkPlaybackSelectionEntry(television = false, paused = true, keyCode = KeyEvent.KEYCODE_O)

    @Test fun homeBackUsesTheSystemNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var content: View
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it; content = it.findViewById(android.R.id.content) }
            BrowserTestUi.waitUntil { content.hasWindowFocus() }
            sendKey(KeyEvent.KEYCODE_BACK)
            BrowserTestUi.waitUntil {
                activity.lifecycle.currentState in listOf(Lifecycle.State.CREATED, Lifecycle.State.DESTROYED)
            }
            // A root launcher activity may be backgrounded instead of destroyed by Android.
            if (scenario.state != Lifecycle.State.DESTROYED) {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { assertTrue(it.onBackPressedDispatcher.hasEnabledCallbacks()) }
            }
        }
    }

    private fun checkPlaybackSelectionEntry(television: Boolean, paused: Boolean, advanceToNext: Boolean = false,
        keyCode: Int = KeyEvent.KEYCODE_BACK) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "playback-back-fixture").apply { mkdirs() }
        val directory = File(root, "Current playlist folder").apply { mkdirs() }
        val media = File(directory, "Current OP.wav")
        // A real playable PCM fixture keeps the player active without network or codec dependencies.
        val dataSize = 8_000 * 2 * 60
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
        media.writeBytes(wave.array())
        File(directory, "Current ED.wav").writeBytes(wave.array())
        // Media rows follow folders, beyond the first visible page.
        repeat(40) { File(directory, "Subfolder %02d".format(it)).mkdirs() }
        val preferences = listOf("usb_browser", "last_playback_folder").associateWith {
            context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE)
        }
        val originals = preferences.mapValues { it.value.all.toMap() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var playerView: androidx.media3.ui.PlayerView
                lateinit var originalConfiguration: Configuration
                lateinit var activityView: View
                scenario.onActivity { activity ->
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activityView = activity.findViewById(android.R.id.content)
                }
                // A previous landscape test can leave a pending system configuration update,
                // which would overwrite this phone emulator's simulated TV mode.
                BrowserTestUi.waitUntil {
                    activityView.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                        activityView.height > activityView.width
                }
                scenario.onActivity { activity ->
                    playerView = activity.findViewById(R.id.player_view)
                    originalConfiguration = Configuration(activity.resources.configuration)
                    simulateDeviceMode(activity, television)
                }
                try {
                    scenario.onActivity { activity ->
                        ViewModelProvider(activity)[PlayerViewModel::class.java].playbackFolder = PlaybackFolder.Usb(root, directory)
                        activity.findViewById<ImageButton>(R.id.btn_overlay_select_folder).performClick()
                    }
                    BrowserTestUi.waitForText(directory.name)
                    BrowserTestUi.waitUntil {
                        android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                            it.findViewById<android.widget.ListView>(R.id.dlna_list)?.count == 42
                        }
                    }
                    scenario.onActivity {
                        val list = android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                            it.findViewById<android.widget.TextView>(R.id.dlna_title)?.text == directory.name
                        }.findViewById<android.widget.ListView>(R.id.dlna_list)
                        list.setSelectionFromTop(40, 0)
                    }
                    BrowserTestUi.tap("Current OP")
                    BrowserTestUi.waitUntil { playerView.player?.isPlaying == true }
                    // File selection stays attached until the first player-screen frame commits.
                    BrowserTestUi.waitUntil {
                        android.view.inspector.WindowInspector.getGlobalWindowViews().none {
                            it.findViewById<View>(R.id.dlna_list) != null
                        }
                    }
                    if (advanceToNext) {
                        scenario.onActivity { playerView.player!!.seekToNextMediaItem() }
                        BrowserTestUi.waitUntil {
                            playerView.player?.currentMediaItemIndex == 1 && playerView.player?.isPlaying == true
                        }
                    }
                    scenario.onActivity { activity ->
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.player_container).visibility)
                        // Entering immersive playback can deliver the phone's actual mode again.
                        simulateDeviceMode(activity, television)
                        playerView.player!!.seekTo(30_000)
                        if (paused) playerView.player!!.pause()
                    }
                    if (paused) BrowserTestUi.waitUntil {
                        playerView.rootView.findViewById<View>(R.id.pause_overlay).visibility == View.VISIBLE
                    }
                    BrowserTestUi.waitUntil { playerView.rootView.hasWindowFocus() }
                    scenario.onActivity { activity ->
                        simulateDeviceMode(activity, television)
                        assertTrue(ViewModelProvider(activity)[PlayerViewModel::class.java].isPlayerScreen)
                        assertEquals("The simulated device mode must still match before Back",
                            if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL,
                            activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
                    }
                    val originalPlayer = playerView.player!!
                    var originalPosition = 0L
                    scenario.onActivity { originalPosition = originalPlayer.currentPosition }
                    sendKey(keyCode)
                    // API 36 dispatches Back through the system; wait for the selection window.
                    try {
                        BrowserTestUi.waitForText(directory.name)
                    } catch (error: AssertionError) {
                        var details = ""
                        scenario.onActivity { activity ->
                            val titles = android.view.inspector.WindowInspector.getGlobalWindowViews().map {
                                "${it.findViewById<android.widget.TextView>(R.id.dlna_title)?.text}" +
                                    "(shown=${it.isShown}, focus=${it.hasWindowFocus()})"
                            }
                            details = "player=${playerView.player != null}, playing=${playerView.player?.playWhenReady}, " +
                                "screen=${ViewModelProvider(activity)[PlayerViewModel::class.java].isPlayerScreen}, " +
                                "mode=${activity.resources.configuration.uiMode}, windows=$titles"
                        }
                        throw AssertionError("Selection key $keyCode did not show the playback folder: $details", error)
                    }
                    val expectedTitle = if (advanceToNext) "Current ED" else "Current OP"
                    val expectedRow = if (advanceToNext) 41 else 40
                    BrowserTestUi.waitForText(expectedTitle)
                    BrowserTestUi.waitUntil {
                        android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                            val list = it.findViewById<android.widget.ListView>(R.id.dlna_list)
                            list != null && expectedRow in list.firstVisiblePosition..list.lastVisiblePosition &&
                                (!television || list.hasFocus() && list.selectedItemPosition == expectedRow)
                        }
                    }
                    scenario.onActivity {
                        assertSame(originalPlayer, playerView.player)
                        assertFalse(originalPlayer.playWhenReady)
                        val window = android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                            it.findViewById<android.widget.TextView>(R.id.dlna_title)?.text == directory.name
                        }
                        val list = window.findViewById<android.widget.ListView>(R.id.dlna_list)
                        assertTrue(list.isShown)
                        if (television) {
                            assertTrue(list.hasFocus())
                            assertEquals(expectedRow, list.selectedItemPosition)
                            assertEquals(expectedTitle, (list.selectedItem as DlnaRow).title)
                        }
                        assertTrue(expectedRow in list.firstVisiblePosition..list.lastVisiblePosition)
                        assertEquals(View.GONE, window.findViewById<View>(R.id.dlna_register).visibility)
                        window.findViewById<android.widget.Button>(R.id.dlna_close).performClick()
                    }
                    BrowserTestUi.waitUntil {
                        originalPlayer.playWhenReady == !paused &&
                            (!paused || playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown)
                    }
                    scenario.onActivity { activity ->
                        assertFalse(activity.isFinishing)
                        assertTrue(ViewModelProvider(activity)[PlayerViewModel::class.java].isPlayerScreen)
                        assertSame(originalPlayer, playerView.player)
                        assertEquals(!paused, originalPlayer.playWhenReady)
                        assertEquals(if (advanceToNext) 1 else 0, originalPlayer.currentMediaItemIndex)
                        assertEquals(originalPosition.toDouble(), originalPlayer.currentPosition.toDouble(), 1_500.0)
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.folder_select_container).visibility)
                        assertTrue(android.view.inspector.WindowInspector.getGlobalWindowViews().none {
                            it.findViewById<View>(R.id.dlna_list) != null
                        })
                    }
                } finally {
                    scenario.onActivity { activity ->
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(originalConfiguration, activity.resources.displayMetrics)
                    }
                }
            }
        } finally {
            preferences.forEach { (name, store) ->
                val editor = store.edit().clear()
                originals.getValue(name).forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                } }
                editor.commit()
            }
            root.deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    private fun simulateDeviceMode(activity: MainActivity, television: Boolean) {
        val configuration = Configuration(activity.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
            if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
    }

    private fun sendKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
                action, keyCode, 0, 0, -1, 0, 0,
                if (keyCode == KeyEvent.KEYCODE_BACK) InputDevice.SOURCE_DPAD else InputDevice.SOURCE_KEYBOARD), true))
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun pauseFolderButtonReopensPlaylistDirectoryAfterActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "playback-folder-fixture").apply { mkdirs() }
        val directory = File(root, "Current OP ED folder").apply { mkdirs() }
        File(directory, "Current OP.mp4").writeBytes(byteArrayOf(1))
        val preferences = context.getSharedPreferences("usb_browser", android.content.Context.MODE_PRIVATE)
        val originalRoot = preferences.getString("root", null)
        val originalDirectory = preferences.getString("directory", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder =
                        PlaybackFolder.Usb(root, directory)
                }
                scenario.recreate()
                scenario.onActivity {
                    it.findViewById<ImageButton>(R.id.btn_overlay_select_folder).performClick()
                }
                BrowserTestUi.waitForText("Current OP ED folder")
                BrowserTestUi.waitForText("Current OP")
            }
        } finally {
            preferences.edit().putString("root", originalRoot)
                .putString("directory", originalDirectory).commit()
            root.deleteRecursively()
        }
    }
}
