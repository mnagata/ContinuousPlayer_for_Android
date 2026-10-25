package jp.nagu.continuousplayer

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import android.widget.ListView
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
class PointerPlaybackTest {
    @Test fun leftBackgroundClickTogglesWithoutSeeking() = checkBackgroundClick(1f / 6)
    @Test fun centerBackgroundClickTogglesWithoutSeeking() = checkBackgroundClick(0.5f)
    @Test fun rightBackgroundClickTogglesWithoutSeeking() = checkBackgroundClick(5f / 6)

    private fun checkBackgroundClick(fraction: Float) = withPlayback { scenario, fixture ->
        click(point(fixture.touch, fraction))
        scenario.onActivity {
            assertTrue(fixture.player.playWhenReady)
            assertEquals(1, fixture.player.currentMediaItemIndex)
            assertTrue(fixture.player.currentPosition in 30_000..31_500)
        }
    }

    @Test fun doubleClickTogglesOnceWithoutChangingWindowSize() = withPlayback { scenario, fixture ->
        val width = fixture.touch.width
        val height = fixture.touch.height
        val target = point(fixture.touch, 1f / 6)
        click(target)
        SystemClock.sleep(80)
        click(target)
        scenario.onActivity {
            assertTrue(fixture.player.playWhenReady)
            assertTrue(fixture.player.currentPosition in 30_000..31_500)
            assertEquals(width, fixture.touch.width)
            assertEquals(height, fixture.touch.height)
        }
        settleClicks()
        click(target)
        scenario.onActivity { assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun rightDragDoesNotSkipOrToggle() = checkDrag(100f)
    @Test fun leftDragDoesNotSkipOrToggle() = checkDrag(-100f)
    private fun checkDrag(distanceDp: Float) = withPlayback { scenario, fixture ->
        val start = point(fixture.touch)
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, start, MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_MOVE, down, start.copy(x = start.x + distanceDp * fixture.density), MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_UP, down, start.copy(x = start.x + distanceDp * fixture.density))
        assertPausedAtThirty(scenario, fixture)
    }

    @Test fun dragOutAndBackDoesNotBecomeAClick() = withPlayback { scenario, fixture ->
        val start = point(fixture.touch)
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, start, MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_MOVE, down, start.copy(x = start.x + 30 * fixture.density), MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_MOVE, down, start, MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_UP, down, start)
        assertPausedAtThirty(scenario, fixture)
    }

    @Test fun heldPrimaryClickActsOnReleaseWithoutUsingTouchLongPress() = withPlayback { scenario, fixture ->
        val target = point(fixture.touch)
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        scenario.onActivity { assertFalse(fixture.player.playWhenReady) }
        mouse(MotionEvent.ACTION_UP, down, target)
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
    }

    @Test fun secondaryMiddleAndChordButtonsDoNotOperatePlayback() = withPlayback { scenario, fixture ->
        val target = point(fixture.touch)
        click(target, button = MotionEvent.BUTTON_SECONDARY)
        click(target, button = MotionEvent.BUTTON_TERTIARY)
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_MOVE, down, target, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY)
        mouse(MotionEvent.ACTION_UP, down, target)
        assertPausedAtThirty(scenario, fixture)
    }

    @Test fun mouseSourceWithFingerToolUsesPointerRules() = withPlayback { scenario, fixture ->
        val target = point(fixture.touch, 5f / 6)
        click(target, tool = MotionEvent.TOOL_TYPE_FINGER)
        scenario.onActivity { assertTrue(fixture.player.playWhenReady); assertTrue(fixture.player.currentPosition < 32_000) }
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY, tool = MotionEvent.TOOL_TYPE_FINGER)
        mouse(MotionEvent.ACTION_MOVE, down, target.copy(x = target.x - 100 * fixture.density), MotionEvent.BUTTON_PRIMARY, tool = MotionEvent.TOOL_TYPE_FINGER)
        mouse(MotionEvent.ACTION_UP, down, target.copy(x = target.x - 100 * fixture.density), tool = MotionEvent.TOOL_TYPE_FINGER)
        scenario.onActivity { assertEquals(1, fixture.player.currentMediaItemIndex); assertTrue(fixture.player.playWhenReady) }
    }

    @Test fun rawTouchpadSourceIsAlsoSeparatedFromDirectTouch() = withPlayback { scenario, fixture ->
        scenario.onActivity { activity ->
            val target = pointOnMain(fixture.touch, 1f / 6)
            val down = SystemClock.uptimeMillis()
            localMouse(activity, MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY, InputDevice.SOURCE_TOUCHPAD, MotionEvent.TOOL_TYPE_FINGER)
            localMouse(activity, MotionEvent.ACTION_UP, down, target, 0, InputDevice.SOURCE_TOUCHPAD, MotionEvent.TOOL_TYPE_FINGER)
            assertTrue(fixture.player.playWhenReady)
            assertEquals(30_000L, fixture.player.currentPosition)
        }
    }

    @Test fun switchingBetweenTouchAndMouseStartsIndependentClickSequences() = withPlayback { scenario, fixture ->
        scenario.onActivity { activity ->
            val target = pointOnMain(fixture.touch)
            val down = SystemClock.uptimeMillis()
            localMouse(activity, MotionEvent.ACTION_DOWN, down, target, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
            localMouse(activity, MotionEvent.ACTION_UP, down, target, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
            assertTrue(fixture.player.playWhenReady)
            localMouse(activity, MotionEvent.ACTION_DOWN, down + 1, target, MotionEvent.BUTTON_PRIMARY)
            localMouse(activity, MotionEvent.ACTION_UP, down + 1, target)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test fun pointerClicksOnTransportButtonsKeepTheirOwnActions() = withPlayback { scenario, fixture ->
        click(point(fixture.root.findViewById(R.id.btn_overlay_rewind)))
        scenario.onActivity { assertEquals(20_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
        click(point(fixture.root.findViewById(R.id.btn_overlay_forward)))
        scenario.onActivity { assertEquals(30_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
        click(point(fixture.root.findViewById(R.id.btn_overlay_next)))
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 2 && fixture.player.playbackState == Player.STATE_READY }
        scenario.onActivity { assertEquals(0L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun backgroundClickDoesNotActivateControlsRevealedUnderThePress() = withPlayback { scenario, fixture ->
        play(scenario, fixture)
        val controlPosition = point(fixture.root.findViewById(R.id.btn_overlay_rewind))
        click(controlPosition)
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertTrue(fixture.player.currentPosition >= 30_000) }
    }

    @Test fun wheelAndTrackpadScrollDoNotChangePlaybackOrVolume() = withPlayback { scenario, fixture ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = point(fixture.touch)
        mouse(MotionEvent.ACTION_SCROLL, SystemClock.uptimeMillis(), target, vertical = -4f, horizontal = 3f)
        mouse(MotionEvent.ACTION_SCROLL, SystemClock.uptimeMillis(), target, vertical = 4f, tool = MotionEvent.TOOL_TYPE_FINGER)
        assertPausedAtThirty(scenario, fixture)
        assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun pointerMotionRevealsVideoControlsThenHidesAfterThreeSecondsAndRestores() = withPlayback { scenario, fixture ->
        play(scenario, fixture)
        hover(point(fixture.touch))
        scenario.onActivity { assertTrue(fixture.overlay.isShown) }
        SystemClock.sleep(2_000)
        scenario.onActivity { assertTrue(fixture.overlay.isShown) }
        BrowserTestUi.waitUntil { !fixture.overlay.isShown }
        scenario.onActivity { assertEquals(PointerIcon.getSystemIcon(fixture.touch.context, PointerIcon.TYPE_NULL), fixture.touch.pointerIcon) }
        hover(point(fixture.touch).copy(x = point(fixture.touch).x + 10))
        scenario.onActivity { assertTrue(fixture.overlay.isShown); assertNull(fixture.touch.pointerIcon) }
    }

    @Test fun hoveringOverAButtonKeepsControlsUntilPointerLeavesIt() = withPlayback { scenario, fixture ->
        play(scenario, fixture)
        hover(point(fixture.touch))
        hover(point(fixture.root.findViewById(R.id.btn_overlay_info)))
        SystemClock.sleep(3_250)
        scenario.onActivity { assertTrue(fixture.overlay.isShown) }
        hover(point(fixture.touch))
        BrowserTestUi.waitUntil { !fixture.overlay.isShown }
    }

    @Test fun keyboardFocusInControlsPreventsAutomaticHiding() = withPlayback { scenario, fixture ->
        play(scenario, fixture)
        hover(point(fixture.touch))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).requestFocus() }
        SystemClock.sleep(3_250)
        scenario.onActivity { assertTrue(fixture.overlay.isShown); assertTrue(it.findViewById<View>(R.id.btn_overlay_info).hasFocus()) }
        scenario.onActivity { fixture.playerView.requestFocus() }
        BrowserTestUi.waitUntil { !fixture.overlay.isShown }
    }

    @Test fun pausedControlsDoNotHideAfterInactivity() = withPlayback { scenario, fixture ->
        hover(point(fixture.touch))
        SystemClock.sleep(3_250)
        assertPausedAtThirty(scenario, fixture)
        scenario.onActivity { assertTrue(fixture.overlay.isShown); assertNull(fixture.touch.pointerIcon) }
    }

    @Test fun audioControlsRevealedByPointerDoNotUseVideoAutoHide() = withPlayback(audioOnly = true) { scenario, fixture ->
        play(scenario, fixture)
        hover(point(fixture.touch))
        SystemClock.sleep(3_250)
        scenario.onActivity { assertTrue(fixture.overlay.isShown); assertTrue(fixture.player.playWhenReady) }
    }

    @Test fun focusAcquisitionConsumesTheWholeFirstClickThenFreshClickWorks() = withPlayback { scenario, fixture ->
        scenario.onActivity { activity ->
            val target = pointOnMain(fixture.touch, 5f / 6)
            val down = SystemClock.uptimeMillis()
            activity.onWindowFocusChanged(false)
            localMouse(activity, MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY)
            activity.onWindowFocusChanged(true)
            localMouse(activity, MotionEvent.ACTION_UP, down, target)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(30_000L, fixture.player.currentPosition)
        }
        SystemClock.sleep(20)
        click(point(fixture.touch, 5f / 6))
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
    }

    @Test fun clickTimestampBeforeFocusGainIsConsumedEvenIfDownArrivesAfterCallback() = withPlayback { scenario, fixture ->
        scenario.onActivity { activity ->
            val target = pointOnMain(fixture.touch)
            val down = SystemClock.uptimeMillis()
            activity.onWindowFocusChanged(false)
            activity.onWindowFocusChanged(true)
            localMouse(activity, MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY)
            localMouse(activity, MotionEvent.ACTION_UP, down, target)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test fun cancelledPressAndOrphanReleaseDoNotClickAndFreshClickWorks() = withPlayback { scenario, fixture ->
        val target = point(fixture.touch)
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY)
        mouse(MotionEvent.ACTION_CANCEL, down, target)
        // InputDispatcher can discard an orphan UP after cancelling touch ownership.
        mouse(MotionEvent.ACTION_UP, down, target, requireDelivery = false)
        assertPausedAtThirty(scenario, fixture)
        click(target)
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
    }

    @Test fun modalWindowCancelsOldPointerPressAndProtectsPlayback() = withPlayback { scenario, fixture ->
        val target = point(fixture.touch)
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, target, MotionEvent.BUTTON_PRIMARY)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { infoWindow()?.hasWindowFocus() == true }
        scenario.onActivity {
            assertFalse(fixture.player.playWhenReady)
            assertEquals(30_000L, fixture.player.currentPosition)
            infoWindow()!!.findViewById<View>(R.id.info_close).performClick()
        }
        BrowserTestUi.waitUntil { infoWindow() == null && fixture.root.hasWindowFocus() }
        mouse(MotionEvent.ACTION_UP, down, target, requireDelivery = false)
        assertPausedAtThirty(scenario, fixture)
    }

    @Test fun browserWheelScrollsRowsWithoutOperatingThePlayer() = withPlayback { scenario, fixture ->
        lateinit var dialog: DlnaSelectionDialog
        scenario.onActivity { activity ->
            dialog = DlnaSelectionDialog(activity, DlnaDialogContent(
                title = "D13 pointer list", fileSelection = true,
                rows = (1..60).map { DlnaRow("D13 row $it", android.R.drawable.ic_media_play) }, onClose = {}))
            dialog.show()
        }
        try {
            BrowserTestUi.waitUntil { dialog.window?.decorView?.hasWindowFocus() == true }
            val list = dialog.findViewById<ListView>(R.id.dlna_list)!!
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
            mouse(MotionEvent.ACTION_SCROLL, SystemClock.uptimeMillis(), point(list), vertical = -12f)
            BrowserTestUi.waitUntil { list.firstVisiblePosition > 0 }
            assertPausedAtThirty(scenario, fixture)
        } finally { scenario.onActivity { dialog.dismiss() } }
    }

    @Test fun savedRowSecondaryClickInvokesItsContextActionOnceWithoutSelection() = withPlayback { scenario, _ ->
        var selected = 0
        var context = 0
        lateinit var dialog: DlnaSelectionDialog
        scenario.onActivity { activity ->
            dialog = DlnaSelectionDialog(activity, DlnaDialogContent(
                title = "D13 saved pointer", rows = listOf(DlnaRow("D13 saved folder", android.R.drawable.ic_menu_save)),
                onClose = {}, onSelected = { selected++ }, onLongSelected = { index -> assertEquals(0, index); context++ }))
            dialog.show()
        }
        try {
            BrowserTestUi.waitUntil { dialog.window?.decorView?.hasWindowFocus() == true }
            val list = dialog.findViewById<ListView>(R.id.dlna_list)!!
            BrowserTestUi.waitUntil { list.childCount == 1 }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
            click(point(list.getChildAt(0)), button = MotionEvent.BUTTON_SECONDARY)
            scenario.onActivity { assertEquals(1, context); assertEquals(0, selected) }
        } finally { scenario.onActivity { dialog.dismiss() } }
    }

    @Test fun fileRowSingleClickStartsANewSessionAtZero() = withPlayback { scenario, fixture ->
        click(point(fixture.root.findViewById(R.id.btn_overlay_select_folder)))
        BrowserTestUi.waitUntil { browserWindow(fixture.directory.name)?.hasWindowFocus() == true }
        lateinit var list: ListView
        var position = -1
        BrowserTestUi.waitUntil {
            list = browserWindow(fixture.directory.name)!!.findViewById(R.id.dlna_list)
            position = (0 until list.count).firstOrNull { (list.getItemAtPosition(it) as DlnaRow).title.contains("Episode 3") } ?: -1
            position >= 0 && list.childCount > position
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        click(point(list.getChildAt(position)))
        BrowserTestUi.waitUntil { fixture.playerView.player !== fixture.player && fixture.playerView.player?.isPlaying == true }
        scenario.onActivity { assertTrue(fixture.playerView.player!!.currentPosition < 2_000); assertEquals(2, fixture.playerView.player!!.currentMediaItemIndex) }
    }

    private data class Point(val x: Float, val y: Float)
    private data class Fixture(val playerView: PlayerView, val player: Player, val touch: View, val overlay: View,
        val density: Float, val directory: File) { val root: View get() = playerView.rootView }

    private fun point(view: View, fraction: Float = 0.5f): Point {
        lateinit var result: Point
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = pointOnMain(view, fraction) }
        return result
    }
    private fun pointOnMain(view: View, fraction: Float = 0.5f): Point {
        val location = IntArray(2); view.getLocationOnScreen(location)
        return Point(location[0] + view.width * fraction, location[1] + view.height * 0.55f)
    }
    private fun click(target: Point, button: Int = MotionEvent.BUTTON_PRIMARY, tool: Int = MotionEvent.TOOL_TYPE_MOUSE) {
        val down = SystemClock.uptimeMillis()
        mouse(MotionEvent.ACTION_DOWN, down, target, button, tool = tool)
        mouse(MotionEvent.ACTION_UP, down, target, tool = tool)
    }
    private fun hover(target: Point) = mouse(MotionEvent.ACTION_HOVER_MOVE, SystemClock.uptimeMillis(), target)
    private fun mouse(action: Int, down: Long, target: Point, buttons: Int = 0, vertical: Float = 0f,
        horizontal: Float = 0f, tool: Int = MotionEvent.TOOL_TYPE_MOUSE, requireDelivery: Boolean = true) {
        val event = event(action, down, target, buttons, InputDevice.SOURCE_MOUSE, tool, vertical, horizontal)
        try {
            val result = InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
            if (requireDelivery) assertTrue("Pointer event $action was not delivered", result)
        } finally { event.recycle() }
    }
    private fun event(action: Int, down: Long, target: Point, buttons: Int, source: Int, tool: Int,
        vertical: Float = 0f, horizontal: Float = 0f): MotionEvent = MotionEvent.obtain(
        down, SystemClock.uptimeMillis(), action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool }),
        arrayOf(MotionEvent.PointerCoords().apply {
            x = target.x; y = target.y; pressure = if (buttons != 0) 1f else 0f; size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical); setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
        }), 0, buttons, 1f, 1f, 0, 0, source, 0)

    private fun localMouse(activity: MainActivity, action: Int, down: Long, target: Point, buttons: Int = 0,
        source: Int = InputDevice.SOURCE_MOUSE, tool: Int = MotionEvent.TOOL_TYPE_MOUSE) {
        val event = event(action, down, target, buttons, source, tool)
        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun settleClicks() = SystemClock.sleep(400)
    private fun assertPausedAtThirty(scenario: ActivityScenario<MainActivity>, fixture: Fixture) {
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertEquals(30_000L, fixture.player.currentPosition); assertEquals(1, fixture.player.currentMediaItemIndex) }
    }
    private fun play(scenario: ActivityScenario<MainActivity>, fixture: Fixture) {
        scenario.onActivity { fixture.player.play() }
        BrowserTestUi.waitUntil { fixture.player.isPlaying &&
            (if (fixture.player.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO))
                !fixture.overlay.isShown else fixture.overlay.isShown) }
    }
    private fun infoWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.info_close) != null }
    private fun browserWindow(title: String) = WindowInspector.getGlobalWindowViews().singleOrNull {
        it.findViewById<android.widget.TextView>(R.id.dlna_title)?.text?.toString() == title }

    private fun withPlayback(audioOnly: Boolean = false, action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d13-pointer-fixture").apply { deleteRecursively(); mkdirs() }
        val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val bytes = if (audioOnly) {
            val size = 8_000 * 2 * 90
            ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size).array()
        } else instrumentation.context.assets.open("d13-video.mp4").use { it.readBytes() }
        val videos = (1..3).map { index ->
            val file = File(directory, "D13 Episode $index.${if (audioOnly) "wav" else "mp4"}").apply { writeBytes(bytes) }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), if (audioOnly) "audio/wav" else "video/mp4")
        }
        var wasInTouchMode = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: PlayerView
                scenario.onActivity { view = it.findViewById(R.id.player_view); wasInTouchMode = view.isInTouchMode }
                BrowserTestUi.waitUntil { view.rootView.hasWindowFocus() && view.rootView.isLaidOut }
                scenario.onActivity {
                    ViewModelProvider(it)[PlayerViewModel::class.java].apply { this.videos = videos; playbackFolder = PlaybackFolder.Usb(directory, directory) }
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(it, videos, 1)
                }
                BrowserTestUi.waitUntil { view.player?.isPlaying == true && view.rootView.hasWindowFocus() }
                val player = view.player!!
                scenario.onActivity { player.seekTo(1, 30_000); player.pause() }
                BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY && view.rootView.findViewById<View>(R.id.pause_overlay).isShown }
                instrumentation.setInTouchMode(true)
                lateinit var fixture: Fixture
                scenario.onActivity {
                    fixture = Fixture(view, player, it.findViewById(R.id.touch_overlay), it.findViewById(R.id.pause_overlay), it.resources.displayMetrics.density, directory)
                    InstrumentationRegistry.getArguments().getString("expectedDensity")?.toFloat()?.let { expected ->
                        assertEquals("AVD density must match the requested condition", expected, fixture.density, 0f)
                    }
                }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                action(scenario, fixture)
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
