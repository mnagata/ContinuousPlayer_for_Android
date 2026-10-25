package jp.nagu.continuousplayer

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.WrappingMediaSource
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

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackBoundaryTest {
    @Test fun screenPlayRestartsFinalAudioAtZero() = checkScreenReplay(video = false)
    @Test fun screenPlayRestartsFinalVideoAtZero() = checkScreenReplay(video = true)
    private fun checkScreenReplay(video: Boolean) = withPlayback(video = video) { scenario, f ->
        end(scenario, f)
        scenario.onActivity {
            it.findViewById<View>(R.id.btn_overlay_play_pause).performClick()
            assertReplayStarted(f)
        }
        awaitReplay(scenario, f)
    }

    @Test fun touchCenterRestartsOnlyTheFinalItem() = withPlayback { scenario, f ->
        end(scenario, f)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        touch(f, 0.5f)
        awaitReplay(scenario, f)
    }

    @Test fun spaceWithRepeatAndKeyUpRestartsOnlyOnce() = withPlayback { scenario, f ->
        end(scenario, f)
        scenario.onActivity { f.view.requestFocus() }
        val down = SystemClock.uptimeMillis()
        key(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_DOWN, down)
        key(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_DOWN, down, repeat = 1)
        key(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP, down)
        awaitReplay(scenario, f)
    }

    @Test fun explicitMediaPlayRestartsAtZero() = checkKeyReplay(KeyEvent.KEYCODE_MEDIA_PLAY)
    @Test fun mediaPlayPauseRestartsAtZero() = checkKeyReplay(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    private fun checkKeyReplay(code: Int) = withPlayback { scenario, f ->
        end(scenario, f);stroke(code);awaitReplay(scenario, f)
    }

    @Test fun tvDecisionOnTheFocusedPlayButtonRestartsAtZero() = withPlayback(tv = true) { scenario, f ->
        end(scenario, f)
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus()) }
        stroke(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitReplay(scenario, f)
        scenario.onActivity { assertTrue(f.view.hasFocus()) }
    }

    @Test fun modernControllerPlayRestartsFinalItem() = withPlayback { scenario, f ->
        val remote = connect(scenario, f);end(scenario, f)
        scenario.onActivity { remote.play() }
        awaitReplay(scenario, f)
    }

    @Test fun platformControllerPlayRestartsFinalItem() = withPlayback { scenario, f ->
        end(scenario, f);platform(f).transportControls.play();awaitReplay(scenario, f)
    }

    @Test fun platformPlayPauseButtonRestartsFinalItem() = withPlayback { scenario, f ->
        end(scenario, f)
        val remote = platform(f);val down = SystemClock.uptimeMillis()
        remote.dispatchMediaButtonEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        remote.dispatchMediaButtonEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        awaitReplay(scenario, f)
    }

    @Test fun setPlayWhenReadyRestartsFinalItem() = withPlayback { scenario, f ->
        end(scenario, f)
        scenario.onActivity { f.player.playWhenReady = true;assertReplayStarted(f) }
        awaitReplay(scenario, f)
    }

    @Test fun deniedPlayDoesNotRewindOrStartTheEndedPlayer() = withPlayback { scenario, f ->
        end(scenario, f)
        scenario.onActivity {
            val position = f.player.currentPosition
            val denied = PlaybackTransportPlayer(f.engine) { false }
            denied.play()
            assertEquals(Player.STATE_ENDED, f.player.playbackState)
            assertEquals(position, f.player.currentPosition)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test fun modalBlocksRemoteReplayAndCancelDoesNotAutoResumeTheEnd() = withPlayback { scenario, f ->
        val remote = connect(scenario, f);end(scenario, f)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { infoWindow()?.hasWindowFocus() == true }
        scenario.onActivity { remote.play();remote.seekTo(0) }
        platform(f).transportControls.play()
        SystemClock.sleep(200)
        scenario.onActivity { assertFalse(f.player.playWhenReady);assertEquals(2, f.player.currentMediaItemIndex) }
        scenario.onActivity { infoWindow()!!.findViewById<View>(R.id.info_close).performClick() }
        BrowserTestUi.waitUntil { infoWindow() == null && f.view.rootView.hasWindowFocus() && f.view.rootView.findViewById<View>(R.id.pause_overlay).isShown }
        scenario.onActivity {
            assertFalse(f.player.playWhenReady)
            assertTrue(f.owner.isPlaybackCompleted)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("d15_restored_completion", "engine=${f.player.playbackState}, position=${f.player.currentPosition}, completed=${f.owner.isPlaybackCompleted}")
            })
            assertEquals(it.getString(R.string.playback_completed), it.findViewById<android.widget.TextView>(R.id.playback_status).text.toString())
            it.findViewById<View>(R.id.btn_overlay_play_pause).performClick()
            assertReplayStarted(f)
        }
        awaitReplay(scenario, f)
    }

    @Test fun seekingBackFromCompletionClearsItWithoutResuming() = withPlayback { scenario, f ->
        end(scenario, f)
        scenario.onActivity { f.player.seekBack() }
        BrowserTestUi.waitUntil { f.player.playbackState == Player.STATE_READY }
        scenario.onActivity {
            assertFalse(f.owner.isPlaybackCompleted)
            assertFalse(f.player.playWhenReady)
            assertEquals(2, f.player.currentMediaItemIndex)
            assertTrue(f.player.currentPosition in 79_000..81_000)
            assertFalse(it.findViewById<View>(R.id.playback_status).isShown)
        }
    }

    @Test fun exactForwardBoundarySelectsNextAtZeroAndKeepsPause() = withPlayback { scenario, f ->
        scenario.onActivity { f.player.seekTo(f.player.duration - 10_000);f.owner.seekForward() }
        moved(scenario, f, 2, playing = false)
    }

    @Test fun forwardBeyondBoundarySelectsNextAtZeroAndKeepsPlaying() = withPlayback { scenario, f ->
        scenario.onActivity { f.player.seekTo(f.player.duration - 5_000);f.player.play();f.owner.seekForward() }
        moved(scenario, f, 2, playing = true)
    }

    @Test fun backwardBelowZeroStopsAtZeroWithoutChangingItemOrPause() = withPlayback { scenario, f ->
        scenario.onActivity {
            f.player.seekTo(5_000);f.owner.seekBackward()
            assertEquals(0L, f.player.currentPosition);assertEquals(1, f.player.currentMediaItemIndex);assertFalse(f.player.playWhenReady)
        }
    }

    @Test fun lastForwardBoundaryKeepsPositionAndPause() = withPlayback { scenario, f ->
        scenario.onActivity { f.player.seekTo(2, 0) }
        BrowserTestUi.waitUntil { f.player.playbackState == Player.STATE_READY && f.player.currentMediaItemIndex == 2 }
        scenario.onActivity {
            val position = f.player.duration - 10_000;f.player.seekTo(position);f.owner.seekForward()
            assertEquals(position, f.player.currentPosition);assertEquals(2, f.player.currentMediaItemIndex);assertFalse(f.player.playWhenReady)
        }
    }

    @Test fun lastForwardBeyondBoundaryKeepsPlayingWithoutSeekingToEnd() = withPlayback { scenario, f ->
        scenario.onActivity { f.player.seekTo(2, 0) }
        BrowserTestUi.waitUntil { f.player.playbackState == Player.STATE_READY && f.player.currentMediaItemIndex == 2 }
        scenario.onActivity {
            val position = f.player.duration - 5_000;f.player.seekTo(position);f.player.play();f.player.seekForward()
            assertEquals(position, f.player.currentPosition);assertTrue(f.player.playWhenReady)
        }
    }

    @Test fun firstPreviousDoesNotRestartTheCurrentItem() = withPlayback { scenario, f ->
        scenario.onActivity { f.player.seekTo(0, 30_000) }
        BrowserTestUi.waitUntil { f.player.currentMediaItemIndex == 0 && f.player.playbackState == Player.STATE_READY }
        scenario.onActivity {
            f.owner.previousVideo();f.player.seekToPrevious();f.player.seekToPreviousMediaItem()
            assertEquals(30_000L, f.player.currentPosition);assertEquals(0, f.player.currentMediaItemIndex)
        }
        platform(f).transportControls.skipToPrevious()
        SystemClock.sleep(200)
        scenario.onActivity { assertEquals(30_000L, f.player.currentPosition);assertFalse(f.player.playWhenReady) }
    }

    @Test fun sessionCommandsFollowFirstAndLastItemAvailability() = withPlayback { scenario, f ->
        val remote = connect(scenario, f)
        scenario.onActivity { f.player.seekTo(0, 0) }
        BrowserTestUi.waitUntil { !remote.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS) && !remote.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) }
        scenario.onActivity {
            assertTrue(remote.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            f.player.seekTo(2, 0)
        }
        BrowserTestUi.waitUntil { !remote.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) && !remote.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
        scenario.onActivity { assertTrue(remote.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) }
    }

    @Test fun modernPreviousGoesToPreviousFileRatherThanRestartingAtThirtySeconds() = withPlayback { scenario, f ->
        val remote = connect(scenario, f)
        scenario.onActivity { remote.seekToPrevious() }
        moved(scenario, f, 0, playing = false)
    }

    @Test fun platformPreviousGoesToPreviousFileAtZero() = withPlayback { scenario, f ->
        platform(f).transportControls.skipToPrevious();moved(scenario, f, 0, playing = false)
    }

    @Test fun modernSeekIncrementsAreTenSecondsAndKeepPause() = withPlayback { scenario, f ->
        val remote = connect(scenario, f)
        scenario.onActivity { assertEquals(10_000L, remote.seekBackIncrement);assertEquals(10_000L, remote.seekForwardIncrement);remote.seekForward() }
        BrowserTestUi.waitUntil { f.player.currentPosition == 40_000L }
        scenario.onActivity { remote.seekBack() }
        BrowserTestUi.waitUntil { f.player.currentPosition == 30_000L }
        scenario.onActivity { assertFalse(f.player.playWhenReady);assertEquals(1, f.player.currentMediaItemIndex) }
    }

    @Test fun platformFastForwardAndRewindAreTenSeconds() = withPlayback { scenario, f ->
        val remote = platform(f);remote.transportControls.fastForward()
        BrowserTestUi.waitUntil { f.player.currentPosition == 40_000L }
        remote.transportControls.rewind()
        BrowserTestUi.waitUntil { f.player.currentPosition == 30_000L }
        scenario.onActivity { assertFalse(f.player.playWhenReady) }
    }

    @Test fun unseekableItemDisablesButtonsAndRejectsAppAndDirectSeeks() = withPlayback(profile = Profile.UNSEEKABLE) { scenario, f ->
        scenario.onActivity {
            assertFalse(f.player.isCurrentMediaItemSeekable)
            assertFalse(it.findViewById<View>(R.id.btn_overlay_rewind).isEnabled)
            assertFalse(it.findViewById<View>(R.id.btn_overlay_forward).isEnabled)
            f.owner.seekForward();f.owner.seekBackward();f.player.seekForward();f.player.seekBack()
            f.player.seekTo(0);f.player.seekTo(1, 0);f.player.seekToDefaultPosition();f.player.seekToDefaultPosition(1)
            assertAtThirty(f)
        }
    }

    @Test fun unseekableItemRejectsKeyAndTouchSeeks() = withPlayback(profile = Profile.UNSEEKABLE) { scenario, f ->
        scenario.onActivity { f.view.requestFocus() }
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD).forEach { stroke(it) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        touch(f, 1f / 6);touch(f, 5f / 6)
        scenario.onActivity { assertAtThirty(f) }
    }

    @Test fun unseekableItemRejectsModernAndPlatformSeeks() = withPlayback(profile = Profile.UNSEEKABLE) { scenario, f ->
        val remote = connect(scenario, f)
        scenario.onActivity {
            assertFalse(remote.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertFalse(remote.isCommandAvailable(Player.COMMAND_SEEK_BACK))
            assertFalse(remote.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
            remote.seekTo(0);remote.seekBack();remote.seekForward()
        }
        platform(f).transportControls.apply { seekTo(0);rewind();fastForward() }
        SystemClock.sleep(200)
        scenario.onActivity { assertAtThirty(f) }
    }

    @Test fun unseekableItemCanStillSelectAnAdjacentFile() = withPlayback(profile = Profile.UNSEEKABLE) { scenario, f ->
        scenario.onActivity { f.owner.nextVideo() };moved(scenario, f, 2, playing = false)
    }

    @Test fun unknownDurationSeeksWithinItemAndDoesNotGuessTheNextFile() = withPlayback(profile = Profile.UNKNOWN_DURATION) { scenario, f ->
        scenario.onActivity {
            assertEquals(C.TIME_UNSET, f.player.duration);assertTrue(f.player.isCurrentMediaItemSeekable)
            assertTrue(it.findViewById<View>(R.id.btn_overlay_forward).isEnabled)
            f.owner.seekForward();assertEquals(40_000L, f.player.currentPosition)
            f.owner.seekBackward();assertEquals(30_000L, f.player.currentPosition)
            f.player.seekTo(85_000);f.owner.seekForward()
        }
        BrowserTestUi.waitUntil { f.player.playbackState == Player.STATE_READY }
        scenario.onActivity { assertEquals(1, f.player.currentMediaItemIndex);assertFalse(f.player.playWhenReady);assertTrue(f.player.currentPosition >= 85_000) }
    }

    @Test fun missingDirectionCommandIsRespectedWithoutBlockingTheOtherDirection() = withPlayback { scenario, f ->
        scenario.onActivity {
            val commands = object : ForwardingPlayer(f.engine) {
                override fun isCommandAvailable(command: Int) = command != Player.COMMAND_SEEK_BACK && super.isCommandAvailable(command)
            }
            val guarded = PlaybackTransportPlayer(commands) { true }
            guarded.seekBack();assertEquals(30_000L, f.player.currentPosition)
            guarded.seekForward();assertEquals(40_000L, f.player.currentPosition)
        }
    }

    @Test fun missingCurrentSeekCommandBlocksBothRelativeDirections() = withPlayback { scenario, f ->
        scenario.onActivity {
            val commands = object : ForwardingPlayer(f.engine) {
                override fun isCommandAvailable(command: Int) = command != Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM && super.isCommandAvailable(command)
            }
            val guarded = PlaybackTransportPlayer(commands) { true }
            guarded.seekBack();guarded.seekForward();guarded.seekTo(0)
            assertAtThirty(f)
        }
    }

    @Test fun invalidAndVeryLargeIncrementsDoNotOverflow() = withPlayback { scenario, f ->
        scenario.onActivity {
            f.owner.seekForward(0);f.owner.seekBackward(-1);assertAtThirty(f)
            f.owner.seekBackward(Long.MAX_VALUE);assertEquals(0L, f.player.currentPosition)
            f.owner.seekForward(Long.MAX_VALUE)
        }
        moved(scenario, f, 2, playing = false)
    }

    private enum class Profile { NORMAL, UNSEEKABLE, UNKNOWN_DURATION }
    private data class Fixture(val view: PlayerView, val player: Player, val engine: ExoPlayer,
        val owner: PlayerController, val session: MediaSession, val remotes: MutableList<MediaController> = mutableListOf())
    private fun assertAtThirty(f: Fixture) { assertEquals(30_000L, f.player.currentPosition);assertEquals(1, f.player.currentMediaItemIndex);assertFalse(f.player.playWhenReady) }
    private fun assertReplayStarted(f: Fixture) { assertEquals(2, f.player.currentMediaItemIndex);assertEquals(0L, f.player.currentPosition);assertTrue(f.player.playWhenReady) }
    private fun awaitReplay(s: ActivityScenario<MainActivity>, f: Fixture) {
        try {
            BrowserTestUi.waitUntil { f.player.isPlaying }
        } catch (failure: AssertionError) {
            s.onActivity { throw AssertionError("state=${f.player.playbackState}, intent=${f.player.playWhenReady}, position=${f.player.currentPosition}, index=${f.player.currentMediaItemIndex}", failure) }
        }
        s.onActivity {
            assertEquals(2, f.player.currentMediaItemIndex);assertTrue("Replay position=${f.player.currentPosition}", f.player.currentPosition < 2_000)
            assertFalse(it.findViewById<View>(R.id.playback_status).isShown)
        }
    }
    private fun end(s: ActivityScenario<MainActivity>, f: Fixture) {
        s.onActivity { f.player.seekTo(2, 0) }
        BrowserTestUi.waitUntil { f.player.currentMediaItemIndex == 2 && f.player.playbackState == Player.STATE_READY }
        s.onActivity { f.player.seekTo(f.player.duration - 200);f.player.play() }
        BrowserTestUi.waitUntil { f.player.playbackState == Player.STATE_ENDED && !f.player.playWhenReady }
    }
    private fun moved(s: ActivityScenario<MainActivity>, f: Fixture, index: Int, playing: Boolean) {
        BrowserTestUi.waitUntil { f.player.currentMediaItemIndex == index && f.player.playbackState == Player.STATE_READY }
        s.onActivity { assertEquals(playing, f.player.playWhenReady);if (playing) assertTrue("Replay position=${f.player.currentPosition}", f.player.currentPosition < 2_000) else assertEquals(0L, f.player.currentPosition) }
    }
    private fun platform(f: Fixture) = android.media.session.MediaController(InstrumentationRegistry.getInstrumentation().targetContext, f.session.platformToken)
    private fun connect(s: ActivityScenario<MainActivity>, f: Fixture): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        s.onActivity { future = MediaController.Builder(it, f.session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync() }
        return future.get(5, TimeUnit.SECONDS).also { f.remotes.add(it) }
    }
    private fun infoWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.info_close) != null }
    private fun stroke(code: Int) { val down = SystemClock.uptimeMillis();key(code, KeyEvent.ACTION_DOWN, down);key(code, KeyEvent.ACTION_UP, down) }
    private fun key(code: Int, action: Int, down: Long, repeat: Int = 0) {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, code, repeat, 0, -1, 0, 0,
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) InputDevice.SOURCE_DPAD else InputDevice.SOURCE_KEYBOARD), true))
    }
    private fun touch(f: Fixture, fraction: Float) {
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { f.view.getLocationOnScreen(location) }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0] + f.view.width * fraction, location[1] + f.view.height * 0.55f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun withPlayback(video: Boolean = false, tv: Boolean = false, profile: Profile = Profile.NORMAL,
        action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation();val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d15-boundary-fixture").apply { deleteRecursively();mkdirs() }
        val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE);val previous = preferences.all.toMap()
        val size = 8_000 * 2 * 90
        val bytes = if (video) instrumentation.context.assets.open("d13-video.mp4").use { it.readBytes() } else ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        val videos = (1..3).map { index ->
            val file = File(directory, "D15 Episode $index.${if (video) "mp4" else "wav"}").apply { writeBytes(bytes) }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), if (video) "video/mp4" else "audio/wav")
        }
        var touchMode = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: PlayerView;lateinit var configuration: Configuration
                scenario.onActivity { view = it.findViewById(R.id.player_view);touchMode = view.isInTouchMode;configuration = Configuration(it.resources.configuration) }
                instrumentation.setInTouchMode(false)
                BrowserTestUi.waitUntil { view.rootView.hasWindowFocus() && view.rootView.isLaidOut }
                scenario.onActivity {
                    if (tv) {
                        val tvConfig = Configuration(configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }
                        @Suppress("DEPRECATION")
                        it.resources.updateConfiguration(tvConfig, it.resources.displayMetrics)
                    }
                    ViewModelProvider(it)[PlayerViewModel::class.java].apply { this.videos = videos;playbackFolder = PlaybackFolder.Usb(directory, directory) }
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, videos, 1)
                }
                BrowserTestUi.waitUntil { view.player?.isPlaying == true }
                lateinit var f: Fixture
                scenario.onActivity { activity ->
                    val owner = MainActivity::class.java.getDeclaredField("playerController").apply { isAccessible = true }.get(activity) as PlayerController
                    val engine = PlayerController::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(owner) as ExoPlayer
                    val session = PlayerController::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }.get(owner) as MediaSession
                    f = Fixture(view, view.player!!, engine, owner, session)
                    f.player.pause()
                    if (profile == Profile.NORMAL) f.player.seekTo(1, 30_000) else {
                        val sources = videos.map { item ->
                            val child = DefaultMediaSourceFactory(activity).createMediaSource(MediaItem.Builder().setUri(item.uri).setMimeType(item.mimeType).build())
                            object : WrappingMediaSource(child) {
                                override fun onChildSourceInfoRefreshed(timeline: Timeline) {
                                    refreshSourceInfo(object : ForwardingTimeline(timeline) {
                                        override fun getWindow(index: Int, window: Timeline.Window, projection: Long): Timeline.Window {
                                            super.getWindow(index, window, projection)
                                            window.isSeekable = profile != Profile.UNSEEKABLE
                                            if (profile == Profile.UNKNOWN_DURATION) window.durationUs = C.TIME_UNSET
                                            return window
                                        }
                                    })
                                }
                            }
                        }
                        // Test source contracts change flags, while decoding the same real media.
                        engine.setMediaSources(sources, 1, 30_000);engine.prepare()
                    }
                    view.requestFocus()
                }
                BrowserTestUi.waitUntil {
                    f.player.playbackState == Player.STATE_READY && f.player.currentPosition == 30_000L &&
                        (profile != Profile.UNSEEKABLE || !f.player.isCurrentMediaItemSeekable) &&
                        (profile != Profile.UNKNOWN_DURATION || f.player.duration == C.TIME_UNSET)
                }
                try { action(scenario, f) } finally {
                    instrumentation.runOnMainSync { f.remotes.forEach { it.release() } }
                    if (tv) scenario.onActivity {
                        @Suppress("DEPRECATION")
                        it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
                    }
                }
            }
        } finally {
            instrumentation.setInTouchMode(touchMode)
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value);is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet()) } }
            editor.commit();directory.deleteRecursively()
        }
    }
}
