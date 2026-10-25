package jp.nagu.continuousplayer

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class KeyboardPlaybackTest {
    @Test fun spaceAndEnterToggleTheSameSession() = withPlayback { scenario, player ->
        for (key in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            focusPlayer(scenario)
            stroke(key)
            BrowserTestUi.waitUntil { player.isPlaying }
            stroke(key)
            BrowserTestUi.waitUntil { !player.playWhenReady }
            scenario.onActivity {
                assertSame(player, it.findViewById<PlayerView>(R.id.player_view).player)
                assertEquals(1, player.currentMediaItemIndex)
                assertTrue(player.currentPosition in 30_000..34_000)
            }
        }
    }

    @Test fun shiftArrowsSeekWithoutChangingFileOrPauseIntent() = withPlayback { scenario, player ->
        stroke(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON)
        BrowserTestUi.waitUntil { player.currentPosition in 19_750..20_250 }
        stroke(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        BrowserTestUi.waitUntil { player.currentPosition in 29_750..30_250 }
        scenario.onActivity { assertEquals(1, player.currentMediaItemIndex); assertFalse(player.playWhenReady); player.play() }
        BrowserTestUi.waitUntil { player.isPlaying }
        stroke(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        BrowserTestUi.waitUntil { player.currentPosition in 40_000..42_000 }
        scenario.onActivity { assertEquals(1, player.currentMediaItemIndex); assertTrue(player.playWhenReady) }
    }

    @Test fun unmodifiedArrowsKeepTheirFileAndSeekAssignments() = withPlayback { scenario, player ->
        stroke(KeyEvent.KEYCODE_DPAD_UP)
        BrowserTestUi.waitUntil { player.currentPosition in 19_750..20_250 }
        stroke(KeyEvent.KEYCODE_DPAD_DOWN)
        BrowserTestUi.waitUntil { player.currentPosition in 29_750..30_250 }
        stroke(KeyEvent.KEYCODE_DPAD_LEFT)
        BrowserTestUi.waitUntil { player.currentMediaItemIndex == 0 && player.playbackState == Player.STATE_READY }
        scenario.onActivity { assertEquals(0L, player.currentPosition); assertFalse(player.playWhenReady) }
        stroke(KeyEvent.KEYCODE_DPAD_RIGHT)
        BrowserTestUi.waitUntil { player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_READY }
        scenario.onActivity { assertEquals(0L, player.currentPosition); assertFalse(player.playWhenReady) }
    }

    @Test fun televisionPauseConsumesRepeatsAndKeyUpAfterFocusMoves() = withPlayback { scenario, player ->
        lateinit var original: Configuration
        scenario.onActivity {
            original = Configuration(it.resources.configuration)
            val tv = Configuration(original).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(tv, it.resources.displayMetrics)
        }
        try {
            for (key in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
                scenario.onActivity { player.play() }
                BrowserTestUi.waitUntil { player.isPlaying }
                focusPlayer(scenario)
                val down = SystemClock.uptimeMillis()
                event(key, KeyEvent.ACTION_DOWN, down)
                BrowserTestUi.waitUntil { !player.playWhenReady }
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus()) }
                event(key, KeyEvent.ACTION_DOWN, down, repeat = 1)
                event(key, KeyEvent.ACTION_DOWN, down, repeat = 2)
                event(key, KeyEvent.ACTION_UP, down)
                scenario.onActivity { assertFalse("Key-up must not resume the focused button", player.playWhenReady) }
                // A separate press belongs to the button and must still work.
                val resumeDown = SystemClock.uptimeMillis()
                event(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, resumeDown)
                event(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, resumeDown)
                try {
                    BrowserTestUi.waitUntil { player.isPlaying }
                } catch (failure: AssertionError) {
                    scenario.onActivity {
                        val presses = MainActivity::class.java.getDeclaredField("playbackKeyPresses")
                            .apply { isAccessible = true }.get(it)
                        throw AssertionError("Paused by $key at $down, resume at $resumeDown: focus=${it.currentFocus}, " +
                            "windowFocus=${it.window.decorView.hasWindowFocus()}, touch=${it.window.decorView.isInTouchMode}, " +
                            "intent=${player.playWhenReady}, state=${player.playbackState}, presses=$presses", failure)
                    }
                }
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.player_view).hasFocus()) }
            }
        } finally {
            scenario.onActivity {
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(original, it.resources.displayMetrics)
            }
        }
    }

    @Test fun holdingSeekOrSkipExecutesOnlyOnce() = withPlayback { scenario, player ->
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT)) {
            scenario.onActivity { player.seekTo(0, 30_000) }
            BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY && player.currentMediaItemIndex == 0 }
            held(key)
            BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY }
            scenario.onActivity { assertEquals(1, player.currentMediaItemIndex); assertEquals(0L, player.currentPosition) }
        }
        for ((key, modifiers) in listOf(KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.META_SHIFT_ON,
            KeyEvent.KEYCODE_MEDIA_REWIND to 0, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to 0)) {
            scenario.onActivity { player.seekTo(1, 30_000) }
            BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY && player.currentPosition == 30_000L }
            held(key, modifiers)
            val expected = if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 40_000L else 20_000L
            BrowserTestUi.waitUntil { player.currentPosition == expected }
            scenario.onActivity { assertEquals(1, player.currentMediaItemIndex); assertFalse(player.playWhenReady) }
        }
    }

    @Test fun hEndsPausedSessionAndShowsHome() = checkHome(playing = false)
    @Test fun hEndsPlayingSessionAndShowsHome() = checkHome(playing = true)

    private fun checkHome(playing: Boolean) = withPlayback { scenario, player ->
        if (playing) { scenario.onActivity { player.play() }; BrowserTestUi.waitUntil { player.isPlaying } }
        held(KeyEvent.KEYCODE_H)
        scenario.onActivity {
            assertNull(it.findViewById<PlayerView>(R.id.player_view).player)
            assertFalse(ViewModelProvider(it)[PlayerViewModel::class.java].isPlayerScreen)
            assertTrue(it.findViewById<View>(R.id.folder_select_container).isShown)
            assertFalse(it.isFinishing)
            assertEquals(1, WindowInspector.getGlobalWindowViews().count { view -> view.isShown })
        }
    }

    @Test fun reservedModifierCombinationsDoNotRunPlaybackShortcuts() = withPlayback { scenario, player ->
        for (modifier in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_ALT_ON, KeyEvent.META_META_ON)) {
            for (key in listOf(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                // Meta combinations belong to Android (for example Meta+H). Test only the
                // app's dispatch here, without asking system policy to execute that shortcut.
                if (modifier == KeyEvent.META_META_ON) {
                    val down = SystemClock.uptimeMillis()
                    scenario.onActivity { activity ->
                        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                            activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), action,
                                key, 0, modifier, -1, 0, 0, InputDevice.SOURCE_KEYBOARD))
                        }
                    }
                } else stroke(key, modifier)
            }
        }
        stroke(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        scenario.onActivity {
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(30_000L, player.currentPosition)
            assertFalse(player.playWhenReady)
            assertTrue(ViewModelProvider(it)[PlayerViewModel::class.java].isPlayerScreen)
            assertTrue(it.window.decorView.hasWindowFocus())
        }
    }

    @Test fun textEditorKeepsLettersSpaceDeletionAndArrows() = withPlayback { scenario, player ->
        lateinit var editor: EditText
        scenario.onActivity {
            editor = EditText(it).apply {
                setSingleLine(); showSoftInputOnFocus = false; setText("prefix"); setSelection(length())
            }
            it.findViewById<ViewGroup>(R.id.player_container).addView(editor, ViewGroup.LayoutParams(400, 100))
            assertTrue(editor.requestFocus())
        }
        for (key in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL)) stroke(key)
        scenario.onActivity { assertEquals("prefixioh", editor.text.toString()) }
        stroke(KeyEvent.KEYCODE_DPAD_LEFT)
        scenario.onActivity { assertEquals(editor.length() - 1, editor.selectionStart) }
        stroke(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON)
        stroke(KeyEvent.KEYCODE_ENTER)
        scenario.onActivity {
            assertEquals(30_000L, player.currentPosition); assertEquals(1, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
            assertTrue(ViewModelProvider(it)[PlayerViewModel::class.java].isPlayerScreen)
        }
    }

    @Test fun focusedButtonsKeepArrowEnterAndSpacePriority() = withPlayback { scenario, player ->
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_play_pause).requestFocus() }
        stroke(KeyEvent.KEYCODE_DPAD_LEFT)
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_rewind).hasFocus()); assertEquals(30_000L, player.currentPosition) }
        stroke(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        scenario.onActivity { assertEquals(30_000L, player.currentPosition); assertEquals(1, player.currentMediaItemIndex) }
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_next).requestFocus() }
        held(KeyEvent.KEYCODE_ENTER)
        BrowserTestUi.waitUntil { player.currentMediaItemIndex == 2 }
        scenario.onActivity { assertFalse(player.playWhenReady); it.findViewById<View>(R.id.btn_overlay_rewind).requestFocus() }
        stroke(KeyEvent.KEYCODE_SPACE)
        scenario.onActivity { assertFalse("Space on a component must not toggle background playback", player.playWhenReady) }
    }

    @Test fun tabAndShiftTabNavigateVisibleControls() = withPlayback { scenario, player ->
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_home).requestFocus() }
        stroke(KeyEvent.KEYCODE_TAB)
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_select_folder).hasFocus()) }
        stroke(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_home).hasFocus()); assertFalse(player.playWhenReady) }
    }

    private fun focusPlayer(scenario: ActivityScenario<MainActivity>) = scenario.onActivity {
        assertTrue(it.findViewById<View>(R.id.player_view).requestFocus())
        assertTrue(it.findViewById<View>(R.id.player_view).hasFocus())
    }

    private fun stroke(key: Int, modifiers: Int = 0) {
        val down = SystemClock.uptimeMillis()
        event(key, KeyEvent.ACTION_DOWN, down, modifiers = modifiers)
        event(key, KeyEvent.ACTION_UP, down, modifiers = modifiers)
    }

    private fun held(key: Int, modifiers: Int = 0) {
        val down = SystemClock.uptimeMillis()
        event(key, KeyEvent.ACTION_DOWN, down, modifiers = modifiers)
        event(key, KeyEvent.ACTION_DOWN, down, repeat = 1, modifiers = modifiers)
        event(key, KeyEvent.ACTION_DOWN, down, repeat = 2, modifiers = modifiers)
        event(key, KeyEvent.ACTION_UP, down, modifiers = modifiers)
    }

    private fun event(key: Int, action: Int, down: Long, repeat: Int = 0, modifiers: Int = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
            action, key, repeat, modifiers, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
        instrumentation.waitForIdleSync()
    }

    private fun withPlayback(action: (ActivityScenario<MainActivity>, Player) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d09-keyboard-fixture").apply { deleteRecursively(); mkdirs() }
        val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val dataSize = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        val videos = (1..3).map { index ->
            val file = File(directory, "D09 Episode $index.wav").apply { writeBytes(wave) }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), "audio/wav")
        }
        var wasInTouchMode = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: PlayerView
                scenario.onActivity { view = it.findViewById(R.id.player_view); wasInTouchMode = view.isInTouchMode }
                BrowserTestUi.waitUntil { view.rootView.hasWindowFocus() && view.rootView.isLaidOut }
                scenario.onActivity {
                    ViewModelProvider(it)[PlayerViewModel::class.java].apply {
                        this.videos = videos; playbackFolder = PlaybackFolder.Usb(directory, directory)
                    }
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(it, videos, 1)
                }
                BrowserTestUi.waitUntil { view.player?.isPlaying == true && view.rootView.hasWindowFocus() }
                val player = view.player!!
                scenario.onActivity { player.seekTo(1, 30_000); player.pause() }
                BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY && view.rootView.findViewById<View>(R.id.pause_overlay).isShown }
                instrumentation.setInTouchMode(false)
                focusPlayer(scenario)
                action(scenario, player)
            }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
            directory.deleteRecursively()
        }
    }
}
