package jp.nagu.continuousplayer

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
import org.junit.Assume.assumeFalse
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
            it.findViewById<View>(R.id.info_close) != null && it.hasWindowFocus()
        } }
        keyStroke(KeyEvent.KEYCODE_ESCAPE)
        BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().none {
            it.findViewById<View>(R.id.info_close) != null
        } }
        BrowserTestUi.waitUntil { original.playWhenReady }
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

    @Test fun backspaceNavigatesToSavedFoldersThenRestoresPlaybackAtThirtySeconds() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        val down = SystemClock.uptimeMillis()
        keyEvent(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, down)
        waitForTitle(fixture.root.name)
        keyEvent(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, down, repeat = 1)
        keyEvent(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP, down)
        scenario.onActivity {
            assertNotNull(window(fixture.root.name))
            assertFalse(original.playWhenReady)
            val list = window(fixture.root.name)!!.findViewById<ListView>(R.id.dlna_list)
            assertEquals(fixture.directory.name, (list.selectedItem as DlnaRow).title)
        }
        keyStroke(KeyEvent.KEYCODE_DEL)
        waitForTitle(R.string.saf_choose_folder)
        keyStroke(KeyEvent.KEYCODE_DEL)
        BrowserTestUi.waitUntil { original.playWhenReady && !browserShowing() }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_500.0)
            assertSame(fixture.playlist, ViewModelProvider(it)[PlayerViewModel::class.java].videos)
        }
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
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER)
                InputDevice.SOURCE_DPAD else InputDevice.SOURCE_KEYBOARD), true))
        instrumentation.waitForIdleSync()
    }

    @Test fun backgroundFromPlaybackReturnsPausedUntilExplicitPlay() = checkBackgroundReturn(paused = false)

    @Test fun backgroundFromPauseKeepsPositionAndControls() = checkBackgroundReturn(paused = true)

    private fun checkBackgroundReturn(paused: Boolean) = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused)
        repeat(2) {
            scenario.moveToState(Lifecycle.State.CREATED)
            var stoppedPosition = 0L
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertFalse(original.playWhenReady)
                stoppedPosition = original.currentPosition
                assertEquals(View.GONE, it.findViewById<View>(R.id.pause_overlay).visibility)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertFalse(original.playWhenReady)
                assertEquals(1, original.currentMediaItemIndex)
                assertEquals(stoppedPosition.toDouble(), original.currentPosition.toDouble(), 250.0)
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.pause_overlay).visibility)
                assertEquals("D01 ED.wav", it.findViewById<TextView>(R.id.overlay_filename).text.toString())
                val model = ViewModelProvider(it)[PlayerViewModel::class.java]
                assertSame(fixture.playlist, model.videos)
                assertEquals(fixture.folder, model.playbackFolder)
            }
        }
        scenario.onActivity { it.findViewById<ImageButton>(R.id.btn_overlay_play_pause).performClick() }
        BrowserTestUi.waitUntil { original.isPlaying }
        scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.pause_overlay).visibility) }
    }

    @Test fun interruptionWithInformationOpenStaysPausedAfterClosing() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity { it.findViewById<ImageButton>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().any {
            it.findViewById<View>(R.id.info_close) != null
        } }
        scenario.moveToState(Lifecycle.State.CREATED)
        var stoppedPosition = 0L
        scenario.onActivity { stoppedPosition = original.currentPosition; assertFalse(original.playWhenReady) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity {
            assertFalse(original.playWhenReady)
            WindowInspector.getGlobalWindowViews().single { it.findViewById<View>(R.id.info_close) != null }
                .findViewById<View>(R.id.info_close).performClick()
        }
        BrowserTestUi.waitUntil { fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            assertEquals(stoppedPosition.toDouble(), original.currentPosition.toDouble(), 250.0)
            assertEquals(View.VISIBLE, it.findViewById<View>(R.id.pause_overlay).visibility)
            assertFalse(browserShowing())
        }
    }

    @Test fun visibleFocusChangeKeepsPlaybackRunning() = checkVisibleFocusChange(paused = false)

    @Test fun visibleFocusChangeKeepsPause() = checkVisibleFocusChange(paused = true)

    private fun checkVisibleFocusChange(paused: Boolean) = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused)
        // STARTED is visible but paused in the Activity lifecycle; it must not count as background.
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity {
            it.onWindowFocusChanged(false)
            assertSame(original, fixture.playerView.player)
            assertEquals(!paused, original.playWhenReady)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity {
            it.onWindowFocusChanged(true)
            assertSame(original, fixture.playerView.player)
            assertEquals(!paused, original.playWhenReady)
            assertEquals(1, original.currentMediaItemIndex)
            assertEquals(if (paused) View.VISIBLE else View.GONE, it.findViewById<View>(R.id.pause_overlay).visibility)
        }
    }

    @Test fun rotationKeepsPlaybackAndSession() = checkRotation(paused = false)

    @Test fun rotationKeepsPauseAndSession() = checkRotation(paused = true)

    private fun checkRotation(paused: Boolean) = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused)
        lateinit var activity: MainActivity
        var requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var target = Configuration.ORIENTATION_LANDSCAPE
        scenario.onActivity {
            activity = it
            requestedOrientation = it.requestedOrientation
            if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) target = Configuration.ORIENTATION_PORTRAIT
            it.requestedOrientation = if (target == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try {
            BrowserTestUi.waitUntil { activity.resources.configuration.orientation == target }
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertEquals(!paused, original.playWhenReady)
                assertEquals(1, original.currentMediaItemIndex)
                if (paused) assertEquals(30_000.0, original.currentPosition.toDouble(), 250.0)
                else assertTrue(original.currentPosition >= 30_000)
            }
        } finally {
            scenario.onActivity { it.requestedOrientation = requestedOrientation }
        }
    }

    @Test fun televisionReturnFocusesResumeAndWaitsForDecision() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        lateinit var configuration: Configuration
        var wasFocusable = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wasInTouchMode = fixture.playerView.isInTouchMode
        instrumentation.setInTouchMode(false)
        scenario.onActivity {
            configuration = Configuration(it.resources.configuration)
            // The player was created in phone mode; model the focusability set by TV startup too.
            wasFocusable = fixture.playerView.isFocusable
            fixture.playerView.isFocusable = true
            val tv = Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(tv, it.resources.displayMetrics)
        }
        try {
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertFalse(original.playWhenReady)
                assertTrue("TV focus=${it.currentFocus}, touch=${fixture.playerView.isInTouchMode}, uiMode=${it.resources.configuration.uiMode}",
                    it.findViewById<View>(R.id.btn_overlay_play_pause).hasFocus())
            }
            keyStroke(KeyEvent.KEYCODE_DPAD_CENTER)
            BrowserTestUi.waitUntil { original.isPlaying }
            scenario.onActivity {
                assertTrue(fixture.playerView.hasFocus())
                assertEquals(View.GONE, it.findViewById<View>(R.id.pause_overlay).visibility)
            }
        } finally {
            scenario.onActivity {
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
                fixture.playerView.isFocusable = wasFocusable
            }
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    @Test fun screenOffAndWakeRequireManualResume() = withPlayback { scenario, fixture ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assumeFalse("Screen test requires a device without a PIN/password", keyguard.isKeyguardSecure)
        val original = positionAtThirty(scenario, fixture, paused = false)
        val power = context.getSystemService(PowerManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.WAKE_LOCK)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ContinuousPlayer:D04-test")
        try {
            wakeLock.acquire(60_000)
            // Delegate only acquisition; playback focus must use the app's normal identity.
            automation.dropShellPermissionIdentity()
            keyStroke(KeyEvent.KEYCODE_SLEEP)
            BrowserTestUi.waitUntil { !power.isInteractive && !original.playWhenReady }
            var stoppedPosition = 0L
            scenario.onActivity { stoppedPosition = original.currentPosition }
            keyStroke(KeyEvent.KEYCODE_WAKEUP)
            dismissTestKeyguard()
            BrowserTestUi.waitUntil { power.isInteractive && !keyguard.isKeyguardLocked &&
                fixture.playerView.rootView.hasWindowFocus() &&
                fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
            scenario.onActivity {
                assertSame(original, fixture.playerView.player)
                assertFalse(original.playWhenReady)
                assertEquals(1, original.currentMediaItemIndex)
                assertEquals(stoppedPosition.toDouble(), original.currentPosition.toDouble(), 250.0)
            }
            BrowserTestUi.tap(R.string.resume_playback)
            try {
                BrowserTestUi.waitUntil { original.isPlaying }
            } catch (failure: AssertionError) {
                shell("screencap -p /data/local/tmp/continuousplayer-d05-screen-failure.png")
                scenario.onActivity {
                    val monitor = audioMonitor(it)
                    val focus = PlaybackAudioMonitor::class.java.getDeclaredField("hasFocus").apply { isAccessible = true }.getBoolean(monitor)
                    throw AssertionError("Resume focus=$focus, intent=${original.playWhenReady}, state=${original.playbackState}, overlay=${it.findViewById<View>(R.id.pause_overlay).visibility}", failure)
                }
            }
        } finally {
            try {
                if (!power.isInteractive) keyStroke(KeyEvent.KEYCODE_WAKEUP)
                dismissTestKeyguard()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                automation.dropShellPermissionIdentity()
            }
        }
    }

    private fun dismissTestKeyguard() {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm dismiss-keyguard")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    @Test fun permanentAudioFocusLossRequiresManualPlay() = checkFocusLoss(AudioManager.AUDIOFOCUS_GAIN)

    @Test fun transientAudioFocusLossRequiresManualPlay() = checkFocusLoss(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

    @Test fun duckingRequestPausesInsteadOfLoweringVolume() = checkFocusLoss(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    @Test fun audioFocusLossKeepsExistingPause() = checkFocusLoss(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, paused = true)

    private fun checkFocusLoss(gain: Int, paused: Boolean = false) = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused)
        withCompetingFocus(scenario, gain) {
            BrowserTestUi.waitUntil { !original.playWhenReady }
            assertRetainedPause(scenario, fixture, original)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250) // Allow an erroneous GAIN-triggered resume to become observable.
        assertRetainedPause(scenario, fixture, original)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_play_pause).performClick() }
        BrowserTestUi.waitUntil { original.isPlaying }
        scenario.onActivity { assertEquals(1f, original.volume) }
    }

    @Test fun audioFocusLossInListPreventsResumeOnCancel() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        withCompetingFocus(scenario, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) {
            // The original session must retain focus during its temporary selection pause.
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            BrowserTestUi.waitUntil { selectionInterrupted(activity) }
        }
        cancel(scenario, fixture.directory.name)
        BrowserTestUi.waitUntil { !browserShowing() }
        assertRetainedPause(scenario, fixture, original)
    }

    @Test fun audioFocusLossWithInformationStaysPausedAfterClosing() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(R.id.info_close) != null } }
        withCompetingFocus(scenario, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) {
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            BrowserTestUi.waitUntil { informationInterrupted(activity) }
        }
        scenario.onActivity {
            WindowInspector.getGlobalWindowViews().single { it.findViewById<View>(R.id.info_close) != null }
                .findViewById<View>(R.id.info_close).performClick()
        }
        assertRetainedPause(scenario, fixture, original)
    }

    @Test fun activeOutputRemovalKeepsPositionAndWaitsForPlay() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity { audioMonitor(it).onOutputsRemoved(currentOutputIds(it)) }
        assertRetainedPause(scenario, fixture, original)
        scenario.onActivity { original.play() }
        BrowserTestUi.waitUntil { original.isPlaying }
    }

    @Test fun unrelatedOutputRemovalDoesNotPause() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity { audioMonitor(it).onOutputsRemoved(setOf(Int.MIN_VALUE)) }
        scenario.onActivity { assertTrue(original.playWhenReady) }
    }

    @Test fun activeOutputRemovalInListPreventsResumeOnCancel() = withPlayback { scenario, fixture ->
        val original = openSelection(scenario, fixture, paused = false)
        scenario.onActivity { audioMonitor(it).onOutputsRemoved(currentOutputIds(it)) }
        scenario.onActivity { assertTrue(selectionInterrupted(it)) }
        cancel(scenario, fixture.directory.name)
        BrowserTestUi.waitUntil { !browserShowing() }
        assertRetainedPause(scenario, fixture, original)
    }

    @Test fun becomingNoisyRequiresManualPlay() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity {
            val monitor = audioMonitor(it)
            val field = PlaybackAudioMonitor::class.java.getDeclaredField("noisyReceiver").apply { isAccessible = true }
            (field.get(monitor) as BroadcastReceiver).onReceive(it, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        assertRetainedPause(scenario, fixture, original)
        scenario.onActivity { original.play() }
        BrowserTestUi.waitUntil { original.isPlaying }
    }

    @Test fun refusedFocusDoesNotQueueResume() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        val previous = shell("appops get jp.nagu.continuousplayer TAKE_AUDIO_FOCUS")
        val previousMode = Regex("TAKE_AUDIO_FOCUS: (\\w+)").find(previous)?.groupValues?.get(1) ?: "default"
        scenario.onActivity { original.pause(); audioMonitor(it).abandonFocus() }
        try {
            shell("appops set jp.nagu.continuousplayer TAKE_AUDIO_FOCUS ignore")
            scenario.onActivity {
                original.play()
                assertFalse(original.playWhenReady)
            }
            assertRetainedPause(scenario, fixture, original)
        } finally {
            shell("appops set jp.nagu.continuousplayer TAKE_AUDIO_FOCUS $previousMode")
        }
        assertRetainedPause(scenario, fixture, original)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_play_pause).performClick() }
        BrowserTestUi.waitUntil { original.isPlaying }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun assertRetainedPause(scenario: ActivityScenario<MainActivity>, fixture: Fixture, original: Player) {
        BrowserTestUi.waitUntil { !original.playWhenReady &&
            fixture.playerView.rootView.findViewById<View>(R.id.pause_overlay).isShown }
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            assertEquals(1, original.currentMediaItemIndex)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 1_500.0)
            assertEquals(View.VISIBLE, it.findViewById<View>(R.id.pause_overlay).visibility)
            assertEquals("D01 ED.wav", it.findViewById<TextView>(R.id.overlay_filename).text.toString())
            assertSame(fixture.playlist, ViewModelProvider(it)[PlayerViewModel::class.java].videos)
        }
    }

    private fun withCompetingFocus(scenario: ActivityScenario<MainActivity>, gain: Int, action: () -> Unit) {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
        try {
            scenario.onActivity { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request)) }
            action()
        } finally {
            scenario.onActivity { manager.abandonAudioFocusRequest(request) }
        }
    }

    private fun audioMonitor(activity: MainActivity): PlaybackAudioMonitor {
        val controller = MainActivity::class.java.getDeclaredField("playerController").apply { isAccessible = true }.get(activity)
        return PlayerController::class.java.getDeclaredField("audioMonitor").apply { isAccessible = true }
            .get(controller) as PlaybackAudioMonitor
    }

    private fun selectionInterrupted(activity: MainActivity): Boolean {
        val selection = MainActivity::class.java.getDeclaredField("playbackSelection").apply { isAccessible = true }.get(activity)
        return selection != null && selection.javaClass.getDeclaredField("interrupted").apply { isAccessible = true }.getBoolean(selection)
    }

    private fun currentOutputIds(activity: MainActivity): Set<Int> {
        val ids = activity.getSystemService(AudioManager::class.java).getAudioDevicesForAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .map { it.id }.toSet()
        assertTrue("An output route is required for this test", ids.isNotEmpty())
        return ids
    }

    @Test fun informationPausesPlaybackAndOkRestoresOriginalPosition() = checkInformationRestore(paused = false)

    @Test fun informationFromPauseKeepsPauseOnOk() = checkInformationRestore(paused = true)

    private fun checkInformationRestore(paused: Boolean) = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused)
        openInformation(scenario)
        SystemClock.sleep(250)
        scenario.onActivity {
            assertSame(original, fixture.playerView.player)
            assertFalse(original.playWhenReady)
            assertEquals(1, original.currentMediaItemIndex)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 250.0)
            assertSame(fixture.playlist, ViewModelProvider(it)[PlayerViewModel::class.java].videos)
            assertEquals("D01 ED.wav", informationWindow()!!.findViewById<TextView>(R.id.info_filename).text.toString())
            assertEquals(View.GONE, it.findViewById<View>(R.id.pause_overlay).visibility)
        }
        closeInformationWithOk(scenario)
        if (paused) assertRetainedPause(scenario, fixture, original)
        else {
            BrowserTestUi.waitUntil { original.isPlaying }
            scenario.onActivity { assertEquals(30_000.0, original.currentPosition.toDouble(), 1_000.0) }
        }
    }

    @Test fun iTogglesInformationAndRestoresPlaybackWithoutOpeningList() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        keyStroke(KeyEvent.KEYCODE_I)
        waitForInformation()
        scenario.onActivity { assertFalse(original.playWhenReady) }
        keyStroke(KeyEvent.KEYCODE_I)
        BrowserTestUi.waitUntil { original.isPlaying && informationWindow() == null }
        scenario.onActivity { assertFalse(browserShowing()); assertSame(original, fixture.playerView.player) }
    }

    @Test fun backClosesOnlyInformationAndRestoresPlayback() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        openInformation(scenario)
        keyStroke(KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { original.isPlaying && informationWindow() == null }
        scenario.onActivity { assertFalse(browserShowing()) }
    }

    @Test fun infoKeyPausesBeforeDisplayingInformation() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        keyStroke(KeyEvent.KEYCODE_INFO)
        waitForInformation()
        scenario.onActivity { assertFalse(original.playWhenReady) }
        closeInformationWithOk(scenario)
        BrowserTestUi.waitUntil { original.isPlaying }
    }

    @Test fun modifiedIDoesNotOpenInformation() = withPlayback { scenario, fixture ->
        val original = fixture.playerView.player!!
        keyStroke(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON)
        scenario.onActivity { assertNull(informationWindow()); assertTrue(original.playWhenReady) }
    }

    @Test fun iRepeatDoesNotCloseOrReopenInformation() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        val first = SystemClock.uptimeMillis()
        keyEvent(KeyEvent.KEYCODE_I, KeyEvent.ACTION_DOWN, first)
        waitForInformation()
        keyEvent(KeyEvent.KEYCODE_I, KeyEvent.ACTION_DOWN, first, repeat = 1)
        keyEvent(KeyEvent.KEYCODE_I, KeyEvent.ACTION_UP, first)
        scenario.onActivity { assertNotNull(informationWindow()); assertFalse(original.playWhenReady) }
        val second = SystemClock.uptimeMillis()
        keyEvent(KeyEvent.KEYCODE_I, KeyEvent.ACTION_DOWN, second)
        BrowserTestUi.waitUntil { original.isPlaying && informationWindow() == null }
        keyEvent(KeyEvent.KEYCODE_I, KeyEvent.ACTION_DOWN, second, repeat = 1)
        keyEvent(KeyEvent.KEYCODE_I, KeyEvent.ACTION_UP, second)
        scenario.onActivity { assertNull(informationWindow()); assertFalse(browserShowing()); assertTrue(original.playWhenReady) }
    }

    @Test fun mediaPlayCannotStartBehindInformation() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        openInformation(scenario)
        scenario.onActivity {
            original.play()
            original.playWhenReady = true
            assertFalse(original.playWhenReady)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 250.0)
        }
        closeInformationWithOk(scenario)
        BrowserTestUi.waitUntil { original.isPlaying }
    }

    @Test fun outputRemovalDuringInformationPreventsResumeOnClose() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        openInformation(scenario)
        scenario.onActivity {
            audioMonitor(it).onOutputsRemoved(currentOutputIds(it))
            assertTrue(informationInterrupted(it))
        }
        closeInformationWithOk(scenario)
        assertRetainedPause(scenario, fixture, original)
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_play_pause).performClick() }
        BrowserTestUi.waitUntil { original.isPlaying }
    }

    @Test fun lostLocalFileDuringInformationIsNotResumedFromCache() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        openInformation(scenario)
        assertTrue(fixture.files[1].delete())
        closeInformationWithOk(scenario)
        waitForTitle(R.string.playback_source_unavailable)
        scenario.onActivity {
            assertFalse(original.playWhenReady)
            assertSame(original, fixture.playerView.player)
            assertEquals(30_000.0, original.currentPosition.toDouble(), 250.0)
        }
    }

    @Test fun closingInformationImmediatelyCannotReopenFromLateResult() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity {
            it.findViewById<View>(R.id.btn_overlay_info).performClick()
            assertFalse(original.playWhenReady)
            assertEquals(1, WindowInspector.getGlobalWindowViews().count { window -> window.findViewById<View>(R.id.info_close) != null })
            informationWindow()!!.findViewById<View>(R.id.info_close).performClick()
        }
        BrowserTestUi.waitUntil { original.isPlaying }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { assertNull(informationWindow()); assertFalse(browserShowing()) }
    }

    @Test fun endingSessionDuringInformationLoadDiscardsRestoration() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        scenario.onActivity {
            it.findViewById<View>(R.id.btn_overlay_info).performClick()
            it.findViewById<View>(R.id.btn_overlay_home).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            assertNull(informationWindow())
            assertNull(fixture.playerView.player)
            assertFalse(ViewModelProvider(it)[PlayerViewModel::class.java].isPlayerScreen)
            assertEquals(View.VISIBLE, it.findViewById<View>(R.id.folder_select_container).visibility)
            original.play() // A stale handle to the released controller must remain inert.
            assertNull(fixture.playerView.player)
        }
    }

    @Test fun televisionInformationReturnsFocusToPausedInfoButton() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = true)
        lateinit var configuration: Configuration
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wasInTouchMode = fixture.playerView.isInTouchMode
        instrumentation.setInTouchMode(false)
        scenario.onActivity {
            configuration = Configuration(it.resources.configuration)
            val tv = Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(tv, it.resources.displayMetrics)
            it.findViewById<View>(R.id.btn_overlay_info).requestFocus()
            it.findViewById<View>(R.id.btn_overlay_info).performClick()
        }
        try {
            waitForInformation()
            scenario.onActivity {
                val window = informationWindow()!!
                assertTrue("Info focus=${window.findFocus()}, touch=${window.isInTouchMode}, uiMode=${it.resources.configuration.uiMode}",
                    window.findViewById<View>(R.id.info_close).hasFocus())
            }
            keyStroke(KeyEvent.KEYCODE_DPAD_CENTER)
            assertRetainedPause(scenario, fixture, original)
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.btn_overlay_info).hasFocus()) }
        } finally {
            scenario.onActivity {
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
            }
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    @Test fun rotationDuringInformationKeepsSnapshotUntilClose() = withPlayback { scenario, fixture ->
        val original = positionAtThirty(scenario, fixture, paused = false)
        openInformation(scenario)
        lateinit var activity: MainActivity
        var requested = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var target = Configuration.ORIENTATION_LANDSCAPE
        scenario.onActivity {
            activity = it
            requested = it.requestedOrientation
            if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) target = Configuration.ORIENTATION_PORTRAIT
            it.requestedOrientation = if (target == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try {
            BrowserTestUi.waitUntil { activity.resources.configuration.orientation == target }
            scenario.onActivity { assertSame(original, fixture.playerView.player); assertFalse(original.playWhenReady) }
            closeInformationWithOk(scenario)
            BrowserTestUi.waitUntil { original.isPlaying }
            scenario.onActivity { assertEquals(30_000.0, original.currentPosition.toDouble(), 1_000.0) }
        } finally {
            scenario.onActivity { it.requestedOrientation = requested }
        }
    }

    private fun informationWindow() = WindowInspector.getGlobalWindowViews().singleOrNull {
        it.findViewById<View>(R.id.info_close) != null
    }

    private fun waitForInformation() = BrowserTestUi.waitUntil { informationWindow()?.hasWindowFocus() == true }

    private fun openInformation(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        waitForInformation()
    }

    private fun closeInformationWithOk(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { informationWindow()!!.findViewById<View>(R.id.info_close).performClick() }
        BrowserTestUi.waitUntil { informationWindow() == null }
    }

    private fun informationInterrupted(activity: MainActivity): Boolean {
        val state = MainActivity::class.java.getDeclaredField("playbackInformation").apply { isAccessible = true }.get(activity)
        return state != null && state.javaClass.getDeclaredField("interrupted").apply { isAccessible = true }.getBoolean(state)
    }

    private fun positionAtThirty(scenario: ActivityScenario<MainActivity>, fixture: Fixture, paused: Boolean): Player {
        val original = fixture.playerView.player!!
        scenario.onActivity {
            original.seekTo(1, 30_000)
            if (paused) original.pause()
        }
        BrowserTestUi.waitUntil { original.currentMediaItemIndex == 1 && original.playbackState == Player.STATE_READY }
        return original
    }

    private fun openSelection(scenario: ActivityScenario<MainActivity>, fixture: Fixture, paused: Boolean): Player {
        val original = positionAtThirty(scenario, fixture, paused)
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
