package jp.nagu.continuousplayer

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
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
class GesturePlaybackTest {
    @Test fun rightSwipeMovesToPreviousOnceAndKeepsPlaying() = withPlayback { scenario, fixture ->
        scenario.onActivity { fixture.player.play() }
        BrowserTestUi.waitUntil { fixture.player.isPlaying }
        swipe(fixture, 90f, 0f)
        assertMoved(scenario, fixture, 1, playing = true)
    }

    @Test fun leftSwipeMovesToNextOnceAndKeepsPause() = withPlayback { scenario, fixture ->
        swipe(fixture, -90f, 0f)
        assertMoved(scenario, fixture, 3, playing = false)
    }

    @Test fun slowSwipeWorksBelowTheOldVelocityRequirement() = withPlayback { scenario, fixture ->
        swipe(fixture, 80f, 0f, duration = 3_200)
        assertMoved(scenario, fixture, 1, playing = false)
    }

    @Test fun sixtyNineDpDragDoesNotSkipOrBecomeATap() = withPlayback { scenario, fixture ->
        swipe(fixture, 69f, 0f)
        assertUnchanged(scenario, fixture)
    }

    @Test fun diagonalAtOneAndAHalfRatioIsASwipe() = withPlayback { scenario, fixture ->
        swipe(fixture, 105f, 70f)
        assertMoved(scenario, fixture, 1, playing = false)
    }

    @Test fun diagonalBelowOneAndAHalfRatioDoesNotAct() = withPlayback { scenario, fixture ->
        swipe(fixture, 100f, 80f)
        assertUnchanged(scenario, fixture)
    }

    @Test fun verticalDragDoesNotAct() = withPlayback { scenario, fixture ->
        swipe(fixture, 0f, 100f)
        assertUnchanged(scenario, fixture)
    }

    @Test fun multipleFingersRejectTheRemainingStreamAndAFreshSwipeStillWorks() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start.copy(id = 7)))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down,
            listOf(start.copy(id = 7), start.copy(id = 11, y = start.y + 30)))
        val moved = start.copy(id = 7, x = start.x + 90 * fixture.density)
        touch(MotionEvent.ACTION_MOVE, down, listOf(moved, moved.copy(id = 11, y = moved.y + 50)))
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down,
            listOf(moved, moved.copy(id = 11, y = moved.y + 50)))
        touch(MotionEvent.ACTION_MOVE, down, listOf(moved.copy(x = moved.x + 20 * fixture.density)))
        touch(MotionEvent.ACTION_UP, down, listOf(moved.copy(x = moved.x + 20 * fixture.density)))
        assertUnchanged(scenario, fixture)
        swipe(fixture, -90f, 0f)
        assertMoved(scenario, fixture, 3, playing = false)
    }

    @Test fun secondFingerOnAControlStillRejectsTheMediaSwipe() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val controlCoordinates = IntArray(2)
        lateinit var second: Point
        scenario.onActivity {
            val button = it.findViewById<View>(R.id.btn_overlay_play_pause)
            button.getLocationOnScreen(controlCoordinates)
            second = Point(id = 1, x = controlCoordinates[0] + button.width / 2f, y = controlCoordinates[1] + button.height / 2f)
        }
        val down = SystemClock.uptimeMillis()
        val moved = start.copy(x = start.x + 90 * fixture.density)
        val movedSecond = second.copy(y = second.y + 100 * fixture.density)
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, listOf(start, second))
        touch(MotionEvent.ACTION_MOVE, down, listOf(moved, movedSecond))
        // Moving outside the button cancels its click; the other finger must not skip a file.
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, listOf(moved, movedSecond))
        touch(MotionEvent.ACTION_UP, down, listOf(moved))
        assertUnchanged(scenario, fixture)
    }

    @Test fun twoFingerTapDoesNotToggleOrSeek() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val points = listOf(start, start.copy(id = 1, x = start.x + 20 * fixture.density))
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points)
        touch(MotionEvent.ACTION_UP, down, listOf(start))
        assertUnchanged(scenario, fixture)
    }

    @Test fun cancelDoesNotCommitAndAFreshSwipeStillWorks() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val moved = start.copy(x = start.x + 90 * fixture.density)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_MOVE, down, listOf(moved))
        scenario.onActivity { assertEquals(2, fixture.player.currentMediaItemIndex) }
        touch(MotionEvent.ACTION_CANCEL, down, listOf(moved))
        assertUnchanged(scenario, fixture)
        swipe(fixture, -90f, 0f)
        assertMoved(scenario, fixture, 3, playing = false)
    }

    @Test fun ordinarySingleTapsKeepTheirThreeRegionActions() = withPlayback { scenario, fixture ->
        tap(fixture, 1f / 6)
        BrowserTestUi.waitUntil { fixture.player.currentPosition == 20_000L }
        tap(fixture, 0.5f)
        BrowserTestUi.waitUntil { fixture.player.isPlaying }
        // Wait out the platform's double-tap window before the next independent tap.
        settleTaps()
        tap(fixture, 0.5f)
        BrowserTestUi.waitUntil { !fixture.player.playWhenReady }
        var pausedPosition = 0L
        scenario.onActivity { pausedPosition = fixture.player.currentPosition }
        tap(fixture, 5f / 6)
        BrowserTestUi.waitUntil { fixture.player.currentPosition == pausedPosition + 10_000L }
        scenario.onActivity { assertFalse(fixture.player.playWhenReady); assertEquals(2, fixture.player.currentMediaItemIndex) }
    }

    @Test fun ordinaryDoubleTapStillExecutesOneCombinedAction() = withPlayback { scenario, fixture ->
        tap(fixture, 1f / 6)
        SystemClock.sleep(80)
        tap(fixture, 1f / 6)
        settleTaps()
        scenario.onActivity { assertEquals(20_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady); assertEquals(2, fixture.player.currentMediaItemIndex) }
    }

    @Test fun secondTapThatBecomesASwipeDoesNotTogglePlayback() = withPlayback { scenario, fixture ->
        tap(fixture, 0.5f)
        SystemClock.sleep(80)
        var playingAfterFirstTap = false
        scenario.onActivity { playingAfterFirstTap = fixture.player.playWhenReady }
        swipe(fixture, 90f, 0f, duration = 120)
        assertMoved(scenario, fixture, 1, playing = playingAfterFirstTap)
    }

    @Test fun secondTapThatDragsOutAndBackDoesNotTogglePlayback() = withPlayback { scenario, fixture ->
        tap(fixture, 0.5f)
        SystemClock.sleep(80)
        var playingAfterFirstTap = false
        var firstPosition = 0L
        scenario.onActivity { playingAfterFirstTap = fixture.player.playWhenReady; firstPosition = fixture.player.currentPosition }
        val start = origin(fixture)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_MOVE, down, listOf(start.copy(x = start.x + 30 * fixture.density)))
        touch(MotionEvent.ACTION_MOVE, down, listOf(start))
        touch(MotionEvent.ACTION_UP, down, listOf(start))
        settleTaps()
        scenario.onActivity {
            assertEquals(2, fixture.player.currentMediaItemIndex)
            assertEquals(playingAfterFirstTap, fixture.player.playWhenReady)
            assertTrue(fixture.player.currentPosition in firstPosition..(firstPosition + 2_000))
        }
    }

    @Test fun modalFocusChangeCancelsTheOldSwipeBeforeRelease() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val moved = start.copy(x = start.x + 90 * fixture.density)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_MOVE, down, listOf(moved))
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { infoWindow()?.hasWindowFocus() == true }
        scenario.onActivity { infoWindow()!!.findViewById<View>(R.id.info_close).performClick() }
        BrowserTestUi.waitUntil { infoWindow() == null && fixture.playerView.rootView.hasWindowFocus() && fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
        // Android may discard a release after cancelling touch ownership at the window change.
        touch(MotionEvent.ACTION_UP, down, listOf(moved), requireDelivery = false)
        assertUnchanged(scenario, fixture)
        swipe(fixture, -90f, 0f)
        assertMoved(scenario, fixture, 3, playing = false)
    }

    @Test fun swipesAtPlaylistEdgesDoNotWrapOrBecomeTaps() = withPlayback { scenario, fixture ->
        scenario.onActivity { fixture.player.seekTo(0, 30_000) }
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 0 && fixture.player.playbackState == Player.STATE_READY }
        swipe(fixture, 90f, 0f)
        settleTaps()
        scenario.onActivity { assertEquals(0, fixture.player.currentMediaItemIndex); assertEquals(30_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
        scenario.onActivity { fixture.player.seekTo(4, 30_000) }
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == 4 && fixture.player.playbackState == Player.STATE_READY }
        swipe(fixture, -90f, 0f)
        settleTaps()
        scenario.onActivity { assertEquals(4, fixture.player.currentMediaItemIndex); assertEquals(30_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun backgroundStopsImmediatelyAcceptedTapAndFreshInputWorksAfterReturn() = withPlayback { scenario, fixture ->
        tap(fixture, 0.5f)
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
        scenario.moveToState(Lifecycle.State.CREATED)
        var stoppedPosition = 0L
        scenario.onActivity { stoppedPosition = fixture.player.currentPosition; assertFalse(fixture.player.playWhenReady) }
        settleTaps()
        scenario.onActivity { assertEquals(stoppedPosition, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        BrowserTestUi.waitUntil { fixture.playerView.rootView.hasWindowFocus() && fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
        scenario.onActivity { assertEquals(stoppedPosition, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady); assertEquals(2, fixture.player.currentMediaItemIndex) }
        // UiAutomation synchronizes window animations before injecting DOWN. Wait first so
        // the event's timestamp does not become stale and get classified as a long press.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        tap(fixture, 0.5f)
        try {
            BrowserTestUi.waitUntil { fixture.player.isPlaying }
        } catch (failure: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            File(instrumentation.targetContext.cacheDir, "d12-background-input-failure.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            scenario.onActivity {
                val handler = MainActivity::class.java.getDeclaredField("gestureHandler").apply { isAccessible = true }.get(it)
                val tapEligible = GestureHandler::class.java.getDeclaredField("tapEligible").apply { isAccessible = true }.get(handler)
                val pointer = GestureHandler::class.java.getDeclaredField("pointerId").apply { isAccessible = true }.get(handler)
                val blocked = MainActivity::class.java.getDeclaredMethod("isPlaybackInputBlocked").apply { isAccessible = true }.invoke(it)
                throw AssertionError("intent=${fixture.player.playWhenReady}, state=${fixture.player.playbackState}, " +
                    "windowFocus=${it.window.decorView.hasWindowFocus()}, blocked=$blocked, tapEligible=$tapEligible, pointer=$pointer", failure)
            }
        }
    }

    @Test fun rotationCancelsTheOldStreamWithoutChangingTheSession() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val moved = start.copy(x = start.x + 90 * fixture.density)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_MOVE, down, listOf(moved))
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
            BrowserTestUi.waitUntil {
                activity.resources.configuration.orientation == target && fixture.playerView.rootView.hasWindowFocus() &&
                    (if (target == Configuration.ORIENTATION_LANDSCAPE) fixture.touch.width > fixture.touch.height else fixture.touch.height > fixture.touch.width)
            }
            touch(MotionEvent.ACTION_UP, down, listOf(moved), requireDelivery = false)
            assertUnchanged(scenario, fixture)
            scenario.onActivity { assertSame(fixture.player, fixture.playerView.player) }
            swipe(fixture, -90f, 0f)
            assertMoved(scenario, fixture, 3, playing = false)
        } finally {
            scenario.onActivity { it.requestedOrientation = requested }
        }
    }

    @Test fun firstTapInEveryRegionActsBeforeReturningToTheMainLooper() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            localTap(fixture.touch, 1f / 6)
            assertEquals(20_000L, fixture.player.currentPosition)
            localTap(fixture.touch, 0.5f)
            assertTrue(fixture.player.playWhenReady)
            localTap(fixture.touch, 5f / 6)
            assertEquals(30_000L, fixture.player.currentPosition)
        }
    }

    @Test fun nearbyDoubleTapOnTheRightSeeksOnlyTenSeconds() = withPlayback { scenario, fixture ->
        tap(fixture, 5f / 6)
        scenario.onActivity { assertEquals(40_000L, fixture.player.currentPosition) }
        SystemClock.sleep(80)
        tap(fixture, 5f / 6)
        settleTaps()
        scenario.onActivity { assertEquals(40_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun nearbyDoubleTapInTheCenterTogglesOnlyOnceAndLaterTapWorks() = withPlayback { scenario, fixture ->
        tap(fixture, 0.5f)
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
        SystemClock.sleep(80)
        tap(fixture, 0.5f)
        settleTaps()
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
        tap(fixture, 0.5f)
        scenario.onActivity { assertFalse(fixture.player.playWhenReady) }
    }

    @Test fun additionalNearbyTapsDoNotAddMoreActions() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            repeat(3) { localTap(fixture.touch, 5f / 6) }
            assertEquals(40_000L, fixture.player.currentPosition)
        }
    }

    @Test fun nearbyTapsAcrossTheRegionBoundaryBothAct() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            val offset = 2f / fixture.touch.width
            localTap(fixture.touch, 1f / 3 - offset)
            assertEquals(20_000L, fixture.player.currentPosition)
            localTap(fixture.touch, 1f / 3 + offset)
            assertTrue(fixture.player.playWhenReady)
        }
    }

    @Test fun distantTapsInTheSameRegionBothActUsingSystemProximity() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            val distance = ViewConfiguration.get(it).scaledDoubleTapSlop + 10f
            val firstY = fixture.touch.height * 0.4f
            assertTrue(firstY + distance < fixture.touch.height * 0.8f)
            localTap(fixture.touch, 0.5f, firstY)
            assertTrue(fixture.player.playWhenReady)
            localTap(fixture.touch, 0.5f, firstY + distance)
            assertFalse(fixture.player.playWhenReady)
        }
    }

    @Test fun secondTapStartedInsideTheWindowIsIgnoredEvenWhenReleasedLater() = withPlayback { scenario, fixture ->
        var down = 0L
        scenario.onActivity {
            localTap(fixture.touch, 5f / 6)
            down = SystemClock.uptimeMillis()
            localTouch(fixture.touch, MotionEvent.ACTION_DOWN, down, fixture.touch.width * 5f / 6, fixture.touch.height * 0.55f)
        }
        // Keep the second press shorter than the OS long-press threshold while
        // releasing after the common double-tap window has elapsed.
        SystemClock.sleep(maxOf(300L, ViewConfiguration.getDoubleTapTimeout().toLong()) + 20)
        scenario.onActivity {
            localTouch(fixture.touch, MotionEvent.ACTION_UP, down, fixture.touch.width * 5f / 6, fixture.touch.height * 0.55f)
            assertEquals(40_000L, fixture.player.currentPosition)
        }
    }

    @Test fun anInterveningControlClickStartsANewTapSequence() = withPlayback { scenario, fixture ->
        scenario.onActivity {
            localTap(fixture.touch, 5f / 6)
            assertEquals(40_000L, fixture.player.currentPosition)
            it.findViewById<View>(R.id.btn_overlay_rewind).performClick()
            assertEquals(30_000L, fixture.player.currentPosition)
            localTap(fixture.touch, 5f / 6)
            assertEquals(40_000L, fixture.player.currentPosition)
        }
    }

    @Test fun platformLongPressDoesNotBecomeATap() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        SystemClock.sleep(platformLongPressTimeout() + 100)
        touch(MotionEvent.ACTION_UP, down, listOf(start))
        assertUnchanged(scenario, fixture)
        assertNull(infoWindow())
    }

    @Test fun playingLongPressDoesNotOpenInformationOrChangePlayback() = withPlayback { scenario, f ->
        scenario.onActivity { f.player.play() }
        BrowserTestUi.waitUntil { f.player.isPlaying }
        var pauses = 0
        var seeks = 0
        scenario.onActivity {
            f.player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { if (!ready) pauses++ }
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { seeks++ }
            })
        }
        val start = origin(f)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        SystemClock.sleep(platformLongPressTimeout() + 150)
        touch(MotionEvent.ACTION_UP, down, listOf(start))
        scenario.onActivity {
            assertNull(infoWindow())
            assertTrue(f.player.isPlaying)
            assertEquals(2, f.player.currentMediaItemIndex)
            assertEquals(0, pauses)
            assertEquals(0, seeks)
        }
    }

    @Test fun pressShorterThanPlatformLongPressIsATap() = withPlayback { scenario, fixture ->
        val start = origin(fixture)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        SystemClock.sleep((platformLongPressTimeout() - 100).coerceAtLeast(0))
        touch(MotionEvent.ACTION_UP, down, listOf(start))
        scenario.onActivity { assertTrue(fixture.player.playWhenReady) }
    }

    private fun platformLongPressTimeout(): Long {
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        InstrumentationRegistry.getArguments().getString("expectedLongPressTimeout")?.toLong()?.let {
            assertEquals("The requested OS long-press setting must reach the app", it, timeout)
        }
        return timeout
    }

    // Dispatch on the main thread and assert before yielding it: a delayed
    // confirmation callback cannot satisfy these immediate-response assertions.
    private fun localTap(view: View, fraction: Float, y: Float = view.height * 0.55f) {
        val down = SystemClock.uptimeMillis()
        localTouch(view, MotionEvent.ACTION_DOWN, down, view.width * fraction, y)
        localTouch(view, MotionEvent.ACTION_UP, down, view.width * fraction, y)
    }

    private fun localTouch(view: View, action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private data class Point(val id: Int = 0, val x: Float, val y: Float)
    private data class Fixture(val playerView: PlayerView, val player: Player, val touch: View, val density: Float)

    private fun origin(fixture: Fixture, fraction: Float = 0.5f): Point {
        val coordinates = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { fixture.touch.getLocationOnScreen(coordinates) }
        return Point(x = coordinates[0] + fixture.touch.width * fraction, y = coordinates[1] + fixture.touch.height * 0.55f)
    }

    private fun swipe(fixture: Fixture, dxDp: Float, dyDp: Float, duration: Long = 180) {
        val start = origin(fixture)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        val steps = 8
        for (step in 1..steps) {
            SystemClock.sleep(duration / steps)
            touch(MotionEvent.ACTION_MOVE, down, listOf(start.copy(x = start.x + dxDp * fixture.density * step / steps,
                y = start.y + dyDp * fixture.density * step / steps)))
        }
        touch(MotionEvent.ACTION_UP, down, listOf(start.copy(x = start.x + dxDp * fixture.density, y = start.y + dyDp * fixture.density)))
    }

    private fun tap(fixture: Fixture, fraction: Float) {
        val start = origin(fixture, fraction)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, listOf(start))
        touch(MotionEvent.ACTION_UP, down, listOf(start))
    }

    private fun touch(action: Int, down: Long, points: List<Point>, requireDelivery: Boolean = true) {
        val properties = points.map { point -> MotionEvent.PointerProperties().apply { id = point.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            val delivered = InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
            if (requireDelivery) assertTrue("Touch event $action was not delivered", delivered)
        } finally { event.recycle() }
    }

    private fun settleTaps() {
        SystemClock.sleep(maxOf(300L, ViewConfiguration.getDoubleTapTimeout().toLong()) + 100)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun assertUnchanged(scenario: ActivityScenario<MainActivity>, fixture: Fixture) {
        settleTaps()
        scenario.onActivity { assertEquals(2, fixture.player.currentMediaItemIndex); assertEquals(30_000L, fixture.player.currentPosition); assertFalse(fixture.player.playWhenReady) }
    }
    private fun assertMoved(scenario: ActivityScenario<MainActivity>, fixture: Fixture, index: Int, playing: Boolean) {
        BrowserTestUi.waitUntil { fixture.player.currentMediaItemIndex == index && fixture.player.playbackState == Player.STATE_READY }
        settleTaps()
        scenario.onActivity {
            assertEquals(index, fixture.player.currentMediaItemIndex)
            assertEquals(playing, fixture.player.playWhenReady)
            if (playing) assertTrue("Swipe must not seek after selecting the file", fixture.player.currentPosition < 3_000)
            else assertEquals(0L, fixture.player.currentPosition)
        }
    }
    private fun infoWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.info_close) != null }

    private fun withPlayback(action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d11-gesture-fixture").apply { deleteRecursively(); mkdirs() }
        val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        val dataSize = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        val videos = (1..5).map { index ->
            val file = File(directory, "D11 Episode $index.wav").apply { writeBytes(wave) }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), "audio/wav")
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
                        .apply { isAccessible = true }.invoke(it, videos, 2)
                }
                BrowserTestUi.waitUntil { view.player?.isPlaying == true && view.rootView.hasWindowFocus() }
                val player = view.player!!
                scenario.onActivity { player.seekTo(2, 30_000); player.pause() }
                BrowserTestUi.waitUntil { player.playbackState == Player.STATE_READY && view.rootView.findViewById<View>(R.id.pause_overlay).isShown }
                instrumentation.setInTouchMode(true)
                lateinit var fixture: Fixture
                scenario.onActivity {
                    fixture = Fixture(view, player, it.findViewById(R.id.touch_overlay), it.resources.displayMetrics.density)
                    InstrumentationRegistry.getArguments().getString("expectedDensity")?.toFloat()?.let { expected ->
                        assertEquals("AVD density must match the requested test condition", expected, fixture.density, 0f)
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
