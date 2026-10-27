package jp.nagu.continuousplayer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
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
class PlaybackDisplayTest {
    @Test fun audioShowsTitleAndAllControlsImmediatelyWithoutPointerInput() = withPlayback(listOf(true)) { scenario, fixture ->
        scenario.onActivity { activity ->
            assertTrue(fixture.player.isPlaying)
            assertTrue(fixture.overlay.isShown)
            assertEquals(fixture.videos[0].selectionTitle, activity.findViewById<TextView>(R.id.overlay_filename).text.toString())
            controls.forEach { assertTrue(activity.findViewById<View>(it).isShown) }
            assertEquals(activity.getString(R.string.pause_playback), activity.findViewById<View>(R.id.btn_overlay_play_pause).contentDescription)
            assertFalse(activity.findViewById<View>(R.id.playback_status).isShown)
        }
        screenshot("audio-playing")
    }

    @Test fun audioControlsRemainAfterInactivityAndPauseResumeKeepsGeometry() = withPlayback(listOf(true)) { scenario, fixture ->
        SystemClock.sleep(3_250)
        var original = Rect()
        scenario.onActivity { activity ->
            fixture.playerView.getGlobalVisibleRect(original)
            assertTrue(fixture.overlay.isShown)
            activity.findViewById<View>(R.id.btn_overlay_play_pause).performClick()
            assertFalse(fixture.player.playWhenReady)
            assertEquals(activity.getString(R.string.resume_playback), activity.findViewById<View>(R.id.btn_overlay_play_pause).contentDescription)
            activity.findViewById<View>(R.id.btn_overlay_play_pause).performClick()
            assertTrue(fixture.player.playWhenReady)
            assertTrue(fixture.overlay.isShown)
            val now = Rect();fixture.playerView.getGlobalVisibleRect(now);assertEquals(original, now)
        }
    }

    @Test fun videoShowsForPauseAndHidesForTouchResume() = withPlayback { scenario, fixture ->
        scenario.onActivity { activity ->
            assertFalse(fixture.overlay.isShown)
            fixture.player.pause()
            assertTrue(fixture.overlay.isShown)
            assertEquals(activity.getString(R.string.resume_playback), activity.findViewById<View>(R.id.btn_overlay_play_pause).contentDescription)
            activity.findViewById<View>(R.id.btn_overlay_play_pause).performClick()
            assertTrue(fixture.player.playWhenReady)
            assertFalse(fixture.overlay.isShown)
        }
    }

    @Test fun mediaTransitionsReclassifyAudioAndVideoAndUpdateTitle() = withPlayback(listOf(true, false, true)) { scenario, fixture ->
        scenario.onActivity { assertTrue(fixture.overlay.isShown);fixture.player.seekTo(1, 0) }
        BrowserTestUi.waitUntil { fixture.player.isPlaying && fixture.player.currentTracks.containsType(C.TRACK_TYPE_VIDEO) }
        scenario.onActivity { assertFalse(fixture.overlay.isShown);fixture.player.seekTo(2, 0) }
        BrowserTestUi.waitUntil { fixture.player.isPlaying && fixture.player.currentTracks.containsType(C.TRACK_TYPE_AUDIO) }
        scenario.onActivity {
            assertTrue(fixture.overlay.isShown)
            assertEquals(fixture.videos[2].selectionTitle, it.findViewById<TextView>(R.id.overlay_filename).text.toString())
        }
    }

    @Test fun pausedTransitionToVideoKeepsControlsWithoutResuming() = withPlayback(listOf(true, false)) { scenario, fixture ->
        scenario.onActivity { fixture.player.pause();fixture.player.seekTo(1, 0) }
        BrowserTestUi.waitUntil { fixture.player.playbackState == Player.STATE_READY && fixture.player.currentMediaItemIndex == 1 }
        scenario.onActivity { assertTrue(fixture.overlay.isShown);assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun videoBufferingDoesNotBecomeAPauseOverlay() = checkBuffering(audio = false)
    @Test fun audioBufferingKeepsTitleAndControls() = checkBuffering(audio = true)
    private fun checkBuffering(audio: Boolean) = withPlayback(listOf(audio)) { scenario, fixture ->
        var observed = false
        scenario.onActivity {
            fixture.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING) {
                        observed = true
                        assertTrue(fixture.player.playWhenReady)
                        assertEquals(audio, fixture.overlay.isShown)
                    }
                }
            })
            fixture.player.stop()
            fixture.player.prepare()
        }
        BrowserTestUi.waitUntil { fixture.player.isPlaying }
        scenario.onActivity { assertTrue("A real BUFFERING state must have occurred", observed);assertEquals(audio, fixture.overlay.isShown) }
    }

    @Test fun videoEndShowsCompletedStatusAndControls() = checkEnd(audio = false)
    @Test fun audioEndShowsCompletedStatusAndControls() = checkEnd(audio = true)
    private fun checkEnd(audio: Boolean) = withPlayback(listOf(audio)) { scenario, fixture ->
        scenario.onActivity { fixture.player.seekTo(fixture.player.duration - 250) }
        BrowserTestUi.waitUntil { fixture.player.playbackState == Player.STATE_ENDED }
        scenario.onActivity { activity ->
            assertTrue(fixture.overlay.isShown)
            val status = activity.findViewById<TextView>(R.id.playback_status)
            assertEquals(activity.getString(R.string.playback_completed), status.text.toString())
            assertTrue(status.isShown)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
            assertEquals(activity.getString(R.string.resume_playback), activity.findViewById<View>(R.id.btn_overlay_play_pause).contentDescription)
            assertTrue(activity.findViewById<View>(R.id.btn_overlay_select_folder).isShown)
            assertTrue(activity.findViewById<View>(R.id.btn_overlay_home).isShown)
        }
        screenshot(if (audio) "audio-ended" else "video-ended")
    }

    @Test fun automaticNextItemDoesNotShowFinalCompletion() = withPlayback(listOf(false, true)) { scenario, fixture ->
        scenario.onActivity { fixture.player.seekTo(fixture.player.duration - 250) }
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 1 && fixture.player.isPlaying && fixture.overlay.isShown }
        scenario.onActivity {
            assertFalse(it.findViewById<View>(R.id.playback_status).isShown)
            assertEquals(fixture.videos[1].selectionTitle, it.findViewById<TextView>(R.id.overlay_filename).text.toString())
        }
    }

    @Test fun preparingAfterEndClearsTheCompletedStatus() = withPlayback { scenario, fixture ->
        scenario.onActivity { fixture.player.seekTo(fixture.player.duration - 250) }
        BrowserTestUi.waitUntil { fixture.player.playbackState == Player.STATE_ENDED }
        // Exercise display reset through the engine; the UI replay action is handled in D15.
        scenario.onActivity { fixture.player.seekTo(0);fixture.player.prepare();fixture.player.play() }
        BrowserTestUi.waitUntil { fixture.player.isPlaying }
        scenario.onActivity { assertFalse(fixture.overlay.isShown);assertFalse(it.findViewById<View>(R.id.playback_status).isShown) }
    }

    @Test fun localFinalErrorShowsTitleStatusAndControls() = withPlayback(broken = true) { scenario, fixture ->
        dismissError(scenario, fixture)
        scenario.onActivity { activity ->
            assertNotNull(fixture.player.playerError)
            assertTrue(fixture.overlay.isShown)
            assertEquals(activity.getString(R.string.playback_failed), activity.findViewById<TextView>(R.id.playback_status).text.toString())
            assertEquals(fixture.videos[0].selectionTitle, activity.findViewById<TextView>(R.id.overlay_filename).text.toString())
            controls.forEach { assertTrue(activity.findViewById<View>(it).isShown) }
        }
        screenshot("local-error")
    }

    @Test fun errorRemainsVisibleAfterBackgroundReturn() = withPlayback(broken = true) { scenario, fixture ->
        dismissError(scenario, fixture)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        BrowserTestUi.waitUntil { fixture.playerView.rootView.hasWindowFocus() }
        scenario.onActivity { assertTrue(fixture.overlay.isShown);assertNotNull(fixture.player.playerError);assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun closingTvErrorDialogLeavesVisibleControls() = withPlayback(broken = true, tv = true) { scenario, fixture ->
        lateinit var dialog: AlertDialog
        BrowserTestUi.waitUntil { errorDialog(scenario)?.window?.decorView?.hasWindowFocus() == true }
        scenario.onActivity { dialog = errorDialogOnMain(it)!!;dialog.findViewById<View>(R.id.error_close)!!.performClick() }
        BrowserTestUi.waitUntil { fixture.playerView.rootView.hasWindowFocus() }
        scenario.onActivity { assertTrue(fixture.overlay.isShown);assertNotNull(fixture.player.playerError);assertTrue(it.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus()) }
    }

    @Test fun tvAudioResumeReturnsFocusToMediaWhileKeepingControls() = withPlayback(listOf(true), tv = true) { scenario, fixture ->
        scenario.onActivity { activity ->
            fixture.player.pause()
            assertTrue(activity.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus())
            activity.findViewById<View>(R.id.btn_overlay_play_pause).performClick()
            assertTrue(fixture.player.playWhenReady)
            assertTrue(fixture.overlay.isShown)
            assertTrue(fixture.playerView.hasFocus())
        }
    }

    @Test fun enablingTouchExplorationRevealsVideoControlsAndDisablingHidesThem() = withPlayback { scenario, fixture ->
        scenario.onActivity { assertFalse(fixture.overlay.isShown) }
        withTouchExploration {
            BrowserTestUi.waitUntil { fixture.overlay.isShown }
            scenario.onActivity { activity ->
                controls.forEach { id -> assertTrue(activity.findViewById<View>(id).createAccessibilityNodeInfo().isVisibleToUser) }
                val button = activity.findViewById<View>(R.id.btn_overlay_play_pause)
                assertEquals(activity.getString(R.string.pause_playback), button.contentDescription)
                assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertFalse(fixture.player.playWhenReady)
                assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertTrue(fixture.player.playWhenReady)
            }
            SystemClock.sleep(3_250)
            scenario.onActivity { assertTrue(fixture.overlay.isShown) }
            screenshot("accessibility-playing")
        }
        BrowserTestUi.waitUntil { !fixture.overlay.isShown }
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
    }

    @Test fun touchExplorationCanOpenAndCloseInformationDuringVideoPlayback() = withPlayback { scenario, fixture ->
        withTouchExploration {
            BrowserTestUi.waitUntil { fixture.overlay.isShown }
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_info).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)) }
            BrowserTestUi.waitUntil { infoWindow()?.hasWindowFocus() == true }
            scenario.onActivity {
                assertFalse(fixture.player.playWhenReady)
                assertFalse(fixture.overlay.isShown)
                assertTrue(infoWindow()!!.findViewById<View>(R.id.info_close).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            }
            BrowserTestUi.waitUntil { fixture.player.isPlaying && fixture.overlay.isShown && fixture.playerView.rootView.hasWindowFocus() }
        }
    }

    @Test fun endedControlsFitAndStatusIsVisibleAtRequestedDisplayScale() = withPlayback { scenario, fixture ->
        scenario.onActivity { fixture.player.seekTo(fixture.player.duration - 250) }
        BrowserTestUi.waitUntil { fixture.player.playbackState == Player.STATE_ENDED }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            val overlay = Rect();assertTrue(fixture.overlay.getGlobalVisibleRect(overlay))
            val status = activity.findViewById<View>(R.id.playback_status)
            val statusBounds = Rect();assertTrue(status.getGlobalVisibleRect(statusBounds))
            assertEquals(status.height, statusBounds.height())
            controls.forEach { id ->
                val view = activity.findViewById<View>(id);val bounds = Rect()
                assertTrue(view.getGlobalVisibleRect(bounds));assertTrue(overlay.contains(bounds))
                assertEquals(view.height, bounds.height());assertEquals(view.width, bounds.width())
                assertTrue(view.height >= 48 * activity.resources.displayMetrics.density)
            }
            val transport = Rect();activity.findViewById<View>(R.id.player_transport_controls).getGlobalVisibleRect(transport)
            assertTrue(statusBounds.bottom <= transport.top)
        }
        screenshot("ended-bounds")
    }

    private val controls = listOf(R.id.btn_overlay_home, R.id.btn_overlay_select_folder, R.id.btn_overlay_info,
        R.id.btn_overlay_previous, R.id.btn_overlay_rewind, R.id.btn_overlay_play_pause, R.id.btn_overlay_forward, R.id.btn_overlay_next)
    private data class Fixture(val playerView: PlayerView, val player: Player, val overlay: View, val videos: List<VideoItem>)
    private fun infoWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.info_close) != null }
    private fun dismissError(scenario: ActivityScenario<MainActivity>, fixture: Fixture) {
        BrowserTestUi.waitUntil { errorDialog(scenario)?.window?.decorView?.hasWindowFocus() == true }
        scenario.onActivity { errorDialogOnMain(it)!!.findViewById<View>(R.id.error_close)!!.performClick() }
        BrowserTestUi.waitUntil { fixture.playerView.rootView.hasWindowFocus() }
    }
    private fun errorDialogOnMain(activity: MainActivity) = MainActivity::class.java.getDeclaredField("playbackErrorDialog").apply { isAccessible = true }.get(activity) as? AlertDialog
    private fun errorDialog(scenario: ActivityScenario<MainActivity>): AlertDialog? { var result: AlertDialog? = null;scenario.onActivity { result = errorDialogOnMain(it) };return result }
    private fun withTouchExploration(action: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val flags = info.flags
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        try {
            info.flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            automation.serviceInfo = info
            BrowserTestUi.waitUntil { manager.isTouchExplorationEnabled }
            action()
        } finally {
            info.flags = flags;automation.serviceInfo = info
            BrowserTestUi.waitUntil { manager.isTouchExplorationEnabled == (flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0) }
        }
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Let SurfaceFlinger finish the launch/visibility frame before capturing it.
        SystemClock.sleep(600)
        val args = InstrumentationRegistry.getArguments()
        val suffix = args.getString("expectedDensity") ?: "normal"
        File(instrumentation.targetContext.cacheDir, "d14-$name-$suffix.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun withPlayback(audio: List<Boolean> = listOf(false), broken: Boolean = false, tv: Boolean = false,
        action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d14-display-fixture").apply { deleteRecursively();mkdirs() }
        val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val video = instrumentation.context.assets.open("d13-video.mp4").use { it.readBytes() }
        val size = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + size)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        val videos = audio.mapIndexed { index, audioOnly ->
            val file = File(directory, "D14 Episode ${index + 1}.${if (audioOnly) "wav" else "mp4"}")
                .apply { writeBytes(if (broken) "invalid media".toByteArray() else if (audioOnly) wave else video) }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), if (audioOnly) "audio/wav" else "video/mp4")
        }
        var originalConfiguration: Configuration? = null
        var wasInTouchMode = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: PlayerView
                scenario.onActivity { activity ->
                    view = activity.findViewById(R.id.player_view)
                    wasInTouchMode = view.isInTouchMode
                    originalConfiguration = Configuration(activity.resources.configuration)
                    if (tv) {
                        val configuration = Configuration(originalConfiguration).apply { uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                        view.isFocusable = true
                    }
                    InstrumentationRegistry.getArguments().getString("expectedDensity")?.toFloat()?.let {
                        assertEquals(it, activity.resources.displayMetrics.density, 0f)
                    }
                    InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let {
                        assertEquals(it, activity.resources.configuration.fontScale, 0f)
                    }
                }
                if (tv) instrumentation.setInTouchMode(false)
                BrowserTestUi.waitUntil { view.rootView.hasWindowFocus() && view.rootView.isLaidOut }
                scenario.onActivity {
                    ViewModelProvider(it)[PlayerViewModel::class.java].apply { this.videos = videos;playbackFolder = PlaybackFolder.Usb(directory, directory) }
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, videos, 0)
                }
                if (broken) BrowserTestUi.waitUntil { view.player?.playerError != null }
                else {
                    BrowserTestUi.waitUntil { view.player?.isPlaying == true && view.rootView.hasWindowFocus() }
                    scenario.onActivity { view.player!!.seekTo(30_000) }
                    BrowserTestUi.waitUntil { view.player!!.isPlaying && view.player!!.playbackState == Player.STATE_READY }
                }
                instrumentation.waitForIdleSync()
                try {
                    action(scenario, Fixture(view, view.player!!, view.rootView.findViewById(R.id.pause_overlay), videos))
                } finally {
                    if (tv) scenario.onActivity {
                        @Suppress("DEPRECATION")
                        it.resources.updateConfiguration(originalConfiguration, it.resources.displayMetrics)
                    }
                }
            }
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } };editor.commit();directory.deleteRecursively()
        }
    }
}
