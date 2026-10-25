package jp.nagu.continuousplayer

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
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
class PlaybackControlsTest {
    private val navigation = listOf(R.id.btn_overlay_home, R.id.btn_overlay_select_folder,
        R.id.btn_overlay_info)
    private val transport = listOf(R.id.btn_overlay_previous, R.id.btn_overlay_rewind,
        R.id.btn_overlay_play_pause, R.id.btn_overlay_forward, R.id.btn_overlay_next)

    @Test fun screenSeekButtonsMoveTenSecondsAndKeepPause() = withPlayback { scenario, fixture ->
        BrowserTestUi.tap(R.string.rewind_ten_seconds)
        BrowserTestUi.waitUntil { fixture.player.currentPosition in 19_750..20_250 }
        BrowserTestUi.tap(R.string.forward_ten_seconds)
        BrowserTestUi.waitUntil { fixture.player.currentPosition in 29_750..30_250 }
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertEquals(1, fixture.player.currentMediaItemIndex) }
    }

    @Test fun screenSkipButtonsChangeFileAtZeroWithoutResuming() = withPlayback { scenario, fixture ->
        BrowserTestUi.tap(R.string.next_file)
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 2 && fixture.player.playbackState == Player.STATE_READY }
        scenario.onActivity {
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0L, fixture.player.currentPosition)
            assertEquals(fixture.videos[2].displayName, it.findViewById<TextView>(R.id.overlay_filename).text.toString())
        }
        BrowserTestUi.tap(R.string.previous_file)
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 1 && fixture.player.playbackState == Player.STATE_READY }
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertEquals(0L, fixture.player.currentPosition) }
    }

    @Test fun firstAndLastFilesDisableUnavailableSkipButtons() = withPlayback { scenario, fixture ->
        scenario.onActivity { fixture.player.seekTo(0, 0) }
        BrowserTestUi.waitUntil { !fixture.playerView.rootView.findViewById<View>(R.id.btn_overlay_previous).isEnabled }
        scenario.onActivity {
            assertTrue(it.findViewById<View>(R.id.btn_overlay_next).isEnabled)
            assertFalse(it.findViewById<View>(R.id.btn_overlay_previous).createAccessibilityNodeInfo().isEnabled)
            fixture.player.seekTo(2, 0)
        }
        BrowserTestUi.waitUntil { !fixture.playerView.rootView.findViewById<View>(R.id.btn_overlay_next).isEnabled }
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_previous).isEnabled); assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun singleFileDisablesBothSkipButtons() = withPlayback(count = 1) { scenario, fixture ->
        scenario.onActivity {
            assertFalse(it.findViewById<View>(R.id.btn_overlay_previous).isEnabled)
            assertFalse(it.findViewById<View>(R.id.btn_overlay_next).isEnabled)
            assertTrue(it.findViewById<View>(R.id.btn_overlay_rewind).isEnabled)
            assertTrue(it.findViewById<View>(R.id.btn_overlay_forward).isEnabled)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test fun accessibilityActionsCanSeekAndSelectAdjacentFiles() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            (navigation + transport).forEach { id ->
                val button = it.findViewById<View>(id)
                val node = button.createAccessibilityNodeInfo()
                assertTrue("Missing spoken label for $id", !node.contentDescription.isNullOrBlank())
                assertTrue("Missing tooltip for $id", !button.tooltipText.isNullOrBlank())
                assertTrue(node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK })
            }
            assertTrue(it.findViewById<View>(R.id.btn_overlay_rewind).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        BrowserTestUi.waitUntil { fixture.player.currentPosition in 19_750..20_250 }
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_next).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)) }
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 2 }
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertEquals(0L, fixture.player.currentPosition) }
    }

    @Test fun televisionArrowsNavigateControlsAndDecisionExecutesOnce() = withPlayback { scenario, fixture ->
        withNonTouchMode(fixture.playerView) {
            lateinit var configuration: Configuration
            var wasFocusable = false
            scenario.onActivity {
                configuration = Configuration(it.resources.configuration)
                wasFocusable = fixture.playerView.isFocusable
                fixture.playerView.isFocusable = true
                val tv = Configuration(configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                }
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(tv, it.resources.displayMetrics)
                it.findViewById<View>(R.id.btn_overlay_play_pause).requestFocus()
            }
            try {
                saveScreenshot(fixture, "tv")
                keyStroke(KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity {
                    assertTrue(it.findViewById<View>(R.id.btn_overlay_rewind).hasFocus())
                    assertEquals(30_000L, fixture.player.currentPosition)
                }
                keyStroke(KeyEvent.KEYCODE_DPAD_CENTER)
                BrowserTestUi.waitUntil { fixture.player.currentPosition in 19_750..20_250 }
                keyStroke(KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_previous).hasFocus()) }
                keyStroke(KeyEvent.KEYCODE_DPAD_CENTER)
                BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 0 }
                scenario.onActivity {
                    assertTrue(it.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus())
                    assertFalse(fixture.player.playWhenReady)
                }
                keyStroke(KeyEvent.KEYCODE_DPAD_UP)
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_info).hasFocus()) }
                keyStroke(KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_select_folder).hasFocus()) }
                keyStroke(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus()) }
            } finally {
                scenario.onActivity {
                    @Suppress("DEPRECATION")
                    it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
                    fixture.playerView.isFocusable = wasFocusable
                }
            }
        }
    }

    @Test fun keyboardTabReachesNavigationThenTransportInOrder() = withPlayback { scenario, fixture ->
        withNonTouchMode(fixture.playerView) {
            scenario.onActivity { it.findViewById<View>(navigation.first()).requestFocus() }
            for (id in (navigation + transport).drop(1) + navigation.first()) {
                keyStroke(KeyEvent.KEYCODE_TAB)
                scenario.onActivity { assertTrue("Tab did not reach $id", it.findViewById<View>(id).hasFocus()) }
            }
            scenario.onActivity { assertEquals(30_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
        }
    }

    @Test fun visibleControlsFitWithoutOverlapAndExposeFilename() = withPlayback { scenario, fixture ->
        scenario.onActivity { assertControlBounds(it) }
        saveScreenshot(fixture, "portrait")
    }

    @Test fun rotationAndOverlayVisibilityKeepPlayerGeometryStable() = withPlayback(video = true) { scenario, fixture ->
        var requested = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var target = Configuration.ORIENTATION_LANDSCAPE
        lateinit var activity: MainActivity
        scenario.onActivity {
            activity = it
            requested = it.requestedOrientation
            if (it.resources.configuration.orientation == target) target = Configuration.ORIENTATION_PORTRAIT
            it.requestedOrientation = if (target == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try {
            BrowserTestUi.waitUntil { activity.resources.configuration.orientation == target }
            var pausedBounds = Rect()
            scenario.onActivity {
                assertControlBounds(it)
                assertSame(fixture.player, fixture.playerView.player)
                pausedBounds = bounds(fixture.playerView)
            }
            saveScreenshot(fixture, "rotated")
            scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_play_pause).performClick() }
            BrowserTestUi.waitUntil { fixture.player.isPlaying }
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<View>(R.id.pause_overlay).visibility)
                assertEquals(pausedBounds, bounds(fixture.playerView))
                fixture.player.pause()
            }
            BrowserTestUi.waitUntil { fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
            scenario.onActivity { assertEquals(pausedBounds, bounds(fixture.playerView)); assertControlBounds(it) }
        } finally {
            scenario.onActivity { it.requestedOrientation = requested }
        }
    }

    @Test fun seekButtonsFollowTimelineSeekability() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            fixture.player.setMediaItem(MediaItem.fromUri(fixture.videos.first().uri))
            assertFalse(it.findViewById<View>(R.id.btn_overlay_rewind).isEnabled)
            assertFalse(it.findViewById<View>(R.id.btn_overlay_forward).isEnabled)
            fixture.player.prepare()
        }
        BrowserTestUi.waitUntil { fixture.player.playbackState == Player.STATE_READY && fixture.player.isCurrentMediaItemSeekable }
        scenario.onActivity {
            assertTrue(it.findViewById<View>(R.id.btn_overlay_rewind).isEnabled)
            assertTrue(it.findViewById<View>(R.id.btn_overlay_forward).isEnabled)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test fun playbackNavigationUsesCommonNamesWithoutExit() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            val expected = listOf("ホームに戻る", "ファイル一覧", "メディア情報")
            assertEquals(3, it.findViewById<android.view.ViewGroup>(R.id.player_navigation_controls).childCount)
            navigation.zip(expected).forEach { (id, label) ->
                val button = it.findViewById<View>(id)
                assertEquals(label, button.createAccessibilityNodeInfo().contentDescription.toString())
                assertEquals(label, button.tooltipText.toString())
            }
            assertEquals("再生", it.findViewById<View>(R.id.btn_overlay_play_pause).contentDescription.toString())
            it.findViewById<View>(R.id.btn_home_more).performClick() // A stale hidden-home action is inert.
            assertFalse(it.isFinishing)
            assertSame(fixture.player, fixture.playerView.player)
        }
    }

    @Test fun informationUsesCommonTitleWithoutPlayingStatus() = withPlayback { scenario, fixture ->
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(R.id.info_close) != null } }
        scenario.onActivity {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.findViewById<View>(R.id.info_close) != null }
            fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { index -> texts(view.getChildAt(index)) } else emptyList())
            assertEquals(1, texts(window).count { it == "メディア情報" })
            assertFalse(texts(window).contains("再生中"))
            assertFalse(fixture.player.playWhenReady)
        }
        saveScreenshot(fixture, "media-info")
        scenario.onActivity {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.findViewById<View>(R.id.info_close) != null }
            val close = window.findViewById<View>(R.id.info_close)
            assertEquals("Information close button is clipped", close.height, bounds(close).height())
        }
        BrowserTestUi.tap(android.R.string.ok)
        BrowserTestUi.waitUntil { fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
    }

    private fun assertControlBounds(activity: MainActivity) {
        val overlay = bounds(activity.findViewById(R.id.pause_overlay))
        val player = bounds(activity.findViewById(R.id.player_view))
        assertTrue(player.contains(overlay))
        val safe = ViewCompat.getRootWindowInsets(activity.findViewById(R.id.pause_overlay))!!
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val screen = bounds(activity.findViewById(R.id.player_container))
        assertTrue(bounds(activity.findViewById(navigation.first())).top >= screen.top + safe.top)
        assertTrue(bounds(activity.findViewById(navigation.first())).left >= screen.left + safe.left)
        assertTrue(bounds(activity.findViewById(navigation.last())).right <= screen.right - safe.right)
        val density = activity.resources.displayMetrics.density
        (navigation + transport).forEach { id ->
            val button = activity.findViewById<View>(id)
            val rect = bounds(button)
            assertTrue("Clipped control $id: $rect in $overlay", overlay.contains(rect))
            assertTrue(rect.width() >= (48 * density).toInt() - 1)
            assertTrue(rect.height() >= (48 * density).toInt() - 1)
        }
        for (row in listOf(navigation, transport)) row.zipWithNext().forEach { (first, second) ->
            assertTrue(bounds(activity.findViewById(first)).right <= bounds(activity.findViewById(second)).left)
        }
        val filename = activity.findViewById<TextView>(R.id.overlay_filename)
        assertTrue(bounds(activity.findViewById(R.id.player_navigation_controls)).bottom <= bounds(filename).top)
        assertTrue(bounds(filename).bottom <= bounds(activity.findViewById(R.id.player_transport_controls)).top)
        assertEquals(filename.text, filename.createAccessibilityNodeInfo().text)
    }

    private fun bounds(view: View): Rect = Rect().also { assertTrue("View is not visible: ${view.id}", view.getGlobalVisibleRect(it)) }

    private fun saveScreenshot(fixture: Fixture, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // SurfaceFlinger can still be animating rotation/system bars after the view layout is idle.
        SystemClock.sleep(600)
        val configuration = fixture.playerView.resources.configuration
        val filename = "d07-$name-${configuration.screenWidthDp}dp-font${configuration.fontScale}.png"
        File(instrumentation.targetContext.cacheDir, filename).outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun withNonTouchMode(view: View, action: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The runtime touch mode is independent of whether this AVD has a touchscreen.
        var wasInTouchMode = false
        instrumentation.runOnMainSync { wasInTouchMode = view.isInTouchMode }
        instrumentation.setInTouchMode(false)
        try { action() } finally { instrumentation.setInTouchMode(wasInTouchMode) }
    }

    private fun keyStroke(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        val source = if (code == KeyEvent.KEYCODE_TAB) InputDevice.SOURCE_KEYBOARD else InputDevice.SOURCE_DPAD
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
                action, code, 0, 0, -1, 0, 0, source), true))
        }
        instrumentation.waitForIdleSync()
    }

    private data class Fixture(val playerView: PlayerView, val player: Player, val videos: List<VideoItem>)

    private fun withPlayback(count: Int = 3, video: Boolean = false, action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "d07-controls-fixture").apply { deleteRecursively(); mkdirs() }
        val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val dataSize = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        val videos = (1..count).map { index ->
            val name = "D07 Episode $index — 長いファイル名でも操作ボタンに重ならず表示されることを確認します.${if (video) "mp4" else "wav"}"
            val bytes = if (video) InstrumentationRegistry.getInstrumentation().context.assets.open("d13-video.mp4").use { it.readBytes() } else wave
            val file = File(directory, name).apply { writeBytes(bytes) }
            VideoItem(file.toURI().toString(), name, file.length(), file.lastModified(), if (video) "video/mp4" else "audio/wav")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var playerView: PlayerView
                scenario.onActivity {
                    playerView = it.findViewById(R.id.player_view)
                    ViewModelProvider(it)[PlayerViewModel::class.java].apply {
                        this.videos = videos
                        playbackFolder = PlaybackFolder.Usb(directory, directory)
                    }
                }
                BrowserTestUi.waitUntil { playerView.rootView.hasWindowFocus() && playerView.rootView.isLaidOut }
                scenario.onActivity {
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(it, videos, if (count > 1) 1 else 0)
                }
                BrowserTestUi.waitUntil { playerView.player?.isPlaying == true && playerView.rootView.hasWindowFocus() }
                val player = playerView.player!!
                scenario.onActivity { player.seekTo(if (count > 1) 1 else 0, 30_000); player.pause() }
                BrowserTestUi.waitUntil {
                    val overlay = playerView.rootView.findViewById<View>(R.id.pause_overlay)
                    val safeTop = ViewCompat.getRootWindowInsets(overlay)?.getInsetsIgnoringVisibility(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.top
                    player.playbackState == Player.STATE_READY && overlay.isShown && safeTop == overlay.paddingTop &&
                        playerView.rootView.findViewById<View>(R.id.btn_overlay_rewind).isEnabled
                }
                action(scenario, Fixture(playerView, player, videos))
            }
        } finally {
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
