package jp.nagu.continuousplayer

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModalPlaybackTest {
    @Test fun modernControllerCannotMutateBehindPlayingInformation() = checkModern(Modal.INFO, playing = true)
    @Test fun modernControllerCannotMutateBehindPausedInformation() = checkModern(Modal.INFO, playing = false)
    @Test fun modernControllerCannotMutateBehindPlayingFileList() = checkModern(Modal.FILES, playing = true)
    @Test fun modernControllerCannotMutateBehindPausedFileList() = checkModern(Modal.FILES, playing = false)

    private fun checkModern(modal: Modal, playing: Boolean) = withPlayback { scenario, fixture ->
        val remote = connect(scenario, fixture)
        prepareIntent(scenario, fixture, playing)
        scenario.onActivity {
            open(it, modal)
            assertFalse(fixture.player.playWhenReady)
            // Issue commands immediately, without waiting for the client's permission
            // notification; authorization must also hold at the session boundary.
            sendModernMutations(remote, fixture)
        }
        awaitModal(modal)
        awaitBlocked(remote)
        assertRetained(scenario, fixture)
        close(scenario, modal) { sendModernMutations(remote, fixture) }
        BrowserTestUi.waitUntil { fixture.player.playWhenReady == playing && remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        scenario.onActivity {
            assertSame(fixture.player, it.findViewById<PlayerView>(R.id.player_view).player)
            assertEquals(1, fixture.player.currentMediaItemIndex)
            assertEquals(30_000.0, fixture.player.currentPosition.toDouble(), 1_500.0)
            fixture.player.pause()
            remote.seekTo(20_000)
        }
        BrowserTestUi.waitUntil { fixture.player.currentPosition == 20_000L }
        scenario.onActivity { remote.seekToNextMediaItem() }
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 2 }
        scenario.onActivity { remote.play() }
        BrowserTestUi.waitUntil { fixture.player.isPlaying }
    }

    @Test fun controllerConnectingWhileModalIsOpenStartsWithReadOnlyCommands() = withPlayback { scenario, fixture ->
        for (modal in Modal.entries) {
            scenario.onActivity { open(it, modal) }
            awaitModal(modal)
            val remote = connect(scenario, fixture)
            awaitBlocked(remote)
            scenario.onActivity {
                assertTrue(remote.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
                assertEquals(3, remote.mediaItemCount)
                sendModernMutations(remote, fixture)
            }
            assertRetained(scenario, fixture)
            close(scenario, modal)
            BrowserTestUi.waitUntil { remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        }
    }

    @Test fun platformControllerAndMediaButtonsCannotMutateEitherModal() = withPlayback { scenario, fixture ->
        val platform = android.media.session.MediaController(
            InstrumentationRegistry.getInstrumentation().targetContext, fixture.session.platformToken)
        for (modal in Modal.entries) {
            scenario.onActivity { open(it, modal) }
            awaitModal(modal)
            platform.transportControls.apply {
                play(); pause(); seekTo(55_000); skipToNext(); skipToPrevious(); fastForward(); rewind(); stop(); prepare()
                setPlaybackSpeed(1.5f)
            }
            for (key in mediaKeys) {
                val down = SystemClock.uptimeMillis()
                platform.dispatchMediaButtonEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, key, 0))
                platform.dispatchMediaButtonEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
            }
            // Framework controllers use the platform binder, independently of Activity input.
            SystemClock.sleep(200)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertRetained(scenario, fixture)
            close(scenario, modal)
            BrowserTestUi.waitUntil { fixture.playerView.rootView.hasWindowFocus() }
            platform.transportControls.seekTo(20_000)
            BrowserTestUi.waitUntil { fixture.player.currentPosition == 20_000L }
            scenario.onActivity { fixture.player.seekTo(1, 30_000) }
            BrowserTestUi.waitUntil { fixture.player.currentPosition == 30_000L }
        }
    }

    @Test fun foregroundDialogBlocksSessionWithoutASelectionOrInformationSnapshot() = withPlayback { scenario, fixture ->
        val remote = connect(scenario, fixture)
        lateinit var dialog: AlertDialog
        scenario.onActivity { dialog = AlertDialog.Builder(it).setMessage("D10 modal fixture").setPositiveButton("OK", null).show() }
        try {
            BrowserTestUi.waitUntil { dialog.window?.decorView?.hasWindowFocus() == true }
            awaitBlocked(remote)
            scenario.onActivity { sendModernMutations(remote, fixture) }
            assertRetained(scenario, fixture)
        } finally {
            scenario.onActivity { dialog.dismiss() }
        }
        BrowserTestUi.waitUntil { remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        scenario.onActivity { remote.seekTo(20_000) }
        BrowserTestUi.waitUntil { fixture.player.currentPosition == 20_000L }
    }

    @Test fun playbackErrorDialogBlocksCommandsUntilItCloses() = withPlayback { scenario, fixture ->
        val remote = connect(scenario, fixture)
        scenario.onActivity {
            MainActivity::class.java.getDeclaredMethod("showPlaybackError", PlaybackException::class.java)
                .apply { isAccessible = true }.invoke(it, PlaybackException("D10 fixture error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            // No window-focus callback has run yet; registration itself must block commands.
            sendModernMutations(remote, fixture)
            for (key in listOf(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_SPACE)) {
                val down = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    it.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, key, 0))
                }
            }
        }
        BrowserTestUi.waitUntil { errorWindow() != null && errorWindow()!!.hasWindowFocus() }
        awaitBlocked(remote)
        scenario.onActivity { sendModernMutations(remote, fixture) }
        for (key in mediaKeys) stroke(key)
        assertRetained(scenario, fixture)
        scenario.onActivity { errorWindow()!!.findViewById<View>(R.id.error_close).performClick() }
        BrowserTestUi.waitUntil { remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
        scenario.onActivity { remote.seekTo(20_000) }
        BrowserTestUi.waitUntil { fixture.player.currentPosition == 20_000L }
    }

    @Test fun focusedTextEditorBlocksSessionButKeepsTypingAndDeletion() = withPlayback { scenario, fixture ->
        val remote = connect(scenario, fixture)
        lateinit var editor: EditText
        scenario.onActivity {
            editor = EditText(it).apply { setSingleLine(); showSoftInputOnFocus = false; isFocusableInTouchMode = true }
            it.findViewById<ViewGroup>(R.id.player_container).addView(editor, ViewGroup.LayoutParams(400, 100))
            assertTrue(editor.requestFocus())
        }
        awaitBlocked(remote)
        scenario.onActivity { sendModernMutations(remote, fixture) }
        for (key in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL)) stroke(key)
        scenario.onActivity { assertEquals("ioh", editor.text.toString()) }
        assertRetained(scenario, fixture)
        scenario.onActivity { it.findViewById<ViewGroup>(R.id.player_container).removeView(editor); it.findViewById<View>(R.id.player_view).requestFocus() }
        BrowserTestUi.waitUntil { remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
    }

    @Test fun informationConsumesMediaKeysAndArrowsOnlyScrollItsOwnContent() = withPlayback { scenario, fixture ->
        scenario.onActivity { open(it, Modal.INFO) }
        awaitModal(Modal.INFO)
        for (key in mediaKeys + listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_O)) stroke(key)
        assertRetained(scenario, fixture)
        scenario.onActivity { assertNotNull(infoWindow()); assertNull(fileWindow()) }
        stroke(KeyEvent.KEYCODE_I)
        BrowserTestUi.waitUntil { infoWindow() == null && fixture.playerView.rootView.hasWindowFocus() }
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertEquals(30_000L, fixture.player.currentPosition) }
    }

    @Test fun fileListConsumesMediaKeysAndDecisionSelectsANewSession() = withPlayback { scenario, fixture ->
        scenario.onActivity { open(it, Modal.FILES) }
        awaitModal(Modal.FILES)
        for (key in mediaKeys) stroke(key)
        assertRetained(scenario, fixture)
        scenario.onActivity {
            fileWindow()!!.findViewById<ListView>(R.id.dlna_list).apply { requestFocusFromTouch(); setSelection(1) }
        }
        stroke(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity {
            val list = fileWindow()!!.findViewById<ListView>(R.id.dlna_list)
            assertEquals(2, list.selectedItemPosition)
            assertEquals(1, fixture.player.currentMediaItemIndex)
            assertFalse(fixture.player.playWhenReady)
        }
        stroke(KeyEvent.KEYCODE_ENTER)
        BrowserTestUi.waitUntil { fixture.playerView.player?.let { it !== fixture.player && it.isPlaying } == true && fileWindow() == null }
        scenario.onActivity { assertEquals(2, fixture.playerView.player!!.currentMediaItemIndex); assertTrue(fixture.playerView.player!!.currentPosition < 5_000) }
    }

    @Test fun delayedGestureOrStaleControlHelpersCannotMoveBehindAModal() = withPlayback { scenario, fixture ->
        for (modal in Modal.entries) {
            scenario.onActivity {
                open(it, modal)
                fixture.owner.seekForward(); fixture.owner.seekBackward(); fixture.owner.nextVideo(); fixture.owner.previousVideo()
                fixture.owner.togglePlayPause()
            }
            awaitModal(modal)
            assertRetained(scenario, fixture)
            close(scenario, modal)
            BrowserTestUi.waitUntil { fixture.playerView.rootView.hasWindowFocus() }
        }
        scenario.onActivity { fixture.owner.seekBackward() }
        BrowserTestUi.waitUntil { fixture.player.currentPosition == 20_000L }
    }

    private enum class Modal { INFO, FILES }
    private val mediaKeys = listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_HEADSETHOOK)

    private fun sendModernMutations(remote: MediaController, fixture: Fixture) {
        remote.play(); remote.pause(); remote.seekTo(55_000); remote.seekTo(2, 0)
        remote.seekBack(); remote.seekForward(); remote.seekToNextMediaItem(); remote.seekToPreviousMediaItem()
        remote.seekToDefaultPosition(); remote.stop(); remote.prepare()
        remote.setPlaybackSpeed(1.5f); remote.repeatMode = Player.REPEAT_MODE_ALL; remote.shuffleModeEnabled = true
        remote.removeMediaItem(0); remote.moveMediaItem(0, 2); remote.clearMediaItems()
        remote.setMediaItem(MediaItem.fromUri(fixture.videos[0].uri))
    }

    private fun prepareIntent(scenario: ActivityScenario<MainActivity>, fixture: Fixture, playing: Boolean) {
        scenario.onActivity { if (playing) fixture.player.play() else fixture.player.pause() }
        BrowserTestUi.waitUntil { fixture.player.playWhenReady == playing && (!playing || fixture.player.isPlaying) }
        scenario.onActivity { fixture.player.seekTo(1, 30_000) }
    }

    private fun open(activity: MainActivity, modal: Modal) = activity.findViewById<View>(
        if (modal == Modal.INFO) R.id.btn_overlay_info else R.id.btn_overlay_select_folder).performClick()
    private fun awaitModal(modal: Modal) = BrowserTestUi.waitUntil {
        (if (modal == Modal.INFO) infoWindow() else fileWindow())?.hasWindowFocus() == true
    }
    private fun close(scenario: ActivityScenario<MainActivity>, modal: Modal, afterClose: () -> Unit = {}) = scenario.onActivity {
        (if (modal == Modal.INFO) infoWindow()!! else fileWindow()!!).findViewById<View>(
            if (modal == Modal.INFO) R.id.info_close else R.id.dlna_close).performClick()
        // The window is gone, but asynchronous retained-state restoration is not complete yet.
        afterClose()
    }
    private fun awaitBlocked(remote: MediaController) = BrowserTestUi.waitUntil { !remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
    private fun assertRetained(scenario: ActivityScenario<MainActivity>, fixture: Fixture) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            assertSame(fixture.player, fixture.playerView.player)
            assertFalse(fixture.player.playWhenReady); assertEquals(1, fixture.player.currentMediaItemIndex)
            assertEquals(30_000.0, fixture.player.currentPosition.toDouble(), 500.0)
            assertEquals(Player.STATE_READY, fixture.player.playbackState); assertEquals(3, fixture.player.mediaItemCount)
            assertEquals(Player.REPEAT_MODE_OFF, fixture.player.repeatMode); assertFalse(fixture.player.shuffleModeEnabled)
            assertEquals(1f, fixture.player.playbackParameters.speed, 0f)
            assertSame(fixture.videos, ViewModelProvider(it)[PlayerViewModel::class.java].videos)
        }
    }
    private fun infoWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.info_close) != null }
    private fun fileWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.dlna_list) != null }
    private fun errorWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.error_retry) != null }

    private fun connect(scenario: ActivityScenario<MainActivity>, fixture: Fixture): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        scenario.onActivity {
            future = MediaController.Builder(it, fixture.session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
        }
        return future.get(5, TimeUnit.SECONDS).also { fixture.remotes.add(it) }
    }
    private fun stroke(key: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
                action, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
            instrumentation.waitForIdleSync()
        }
    }
    private data class Fixture(val playerView: PlayerView, val player: Player, val owner: PlayerController,
        val session: MediaSession, val videos: List<VideoItem>, val remotes: MutableList<MediaController> = mutableListOf())

    private fun withPlayback(action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "d10-modal-fixture").apply { deleteRecursively(); mkdirs() }
        val directory = File(root, "D10 Series").apply { mkdirs() }
        val stores = listOf("last_playback_folder", "saved_usb_folders", "usb_browser").associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val previous = stores.mapValues { it.value.all.toMap() }
        val dataSize = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        val videos = (1..3).map { index ->
            val file = File(directory, "D10 Episode $index.wav").apply { writeBytes(wave) }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), "audio/wav")
        }
        var wasInTouchMode = true
        try {
            SavedUsbFolders(context).save(root, root)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: PlayerView
                scenario.onActivity { view = it.findViewById(R.id.player_view); wasInTouchMode = view.isInTouchMode }
                BrowserTestUi.waitUntil { view.rootView.hasWindowFocus() && view.rootView.isLaidOut }
                scenario.onActivity {
                    ViewModelProvider(it)[PlayerViewModel::class.java].apply { this.videos = videos; playbackFolder = PlaybackFolder.Usb(root, directory) }
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(it, videos, 1)
                }
                BrowserTestUi.waitUntil { view.player?.isPlaying == true && view.rootView.hasWindowFocus() }
                val player = view.player!!
                scenario.onActivity { player.seekTo(1, 30_000); player.pause() }
                BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY && view.rootView.findViewById<View>(R.id.pause_overlay).isShown }
                instrumentation.setInTouchMode(false)
                lateinit var owner: PlayerController
                scenario.onActivity {
                    view.requestFocus()
                    owner = MainActivity::class.java.getDeclaredField("playerController").apply { isAccessible = true }.get(it) as PlayerController
                }
                val session = PlayerController::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }.get(owner) as MediaSession
                val fixture = Fixture(view, player, owner, session, videos)
                try { action(scenario, fixture) } finally {
                    instrumentation.runOnMainSync { fixture.remotes.forEach { it.release() } }
                }
            }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
            stores.forEach { (name, store) ->
                val editor = store.edit().clear()
                previous.getValue(name).forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                editor.commit()
            }
            root.deleteRecursively()
        }
    }
}
