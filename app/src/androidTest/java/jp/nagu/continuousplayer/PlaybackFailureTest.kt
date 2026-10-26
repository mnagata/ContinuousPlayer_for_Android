package jp.nagu.continuousplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackFailureTest {
    @Test fun phoneCorruptLocalFileStopsWithoutSkipping() = checkFailure(Kind.CORRUPT)
    @Test fun tvCorruptLocalFileStopsWithoutSkipping() = checkFailure(Kind.CORRUPT, tv = true)
    @Test fun phoneMissingLocalFileStopsWithoutSkipping() = checkFailure(Kind.MISSING)
    @Test fun tvMissingLocalFileStopsWithoutSkipping() = checkFailure(Kind.MISSING, tv = true)
    @Test fun phoneContentReadFailureStopsWithoutSkipping() = checkFailure(Kind.CONTENT)
    @Test fun tvContentReadFailureStopsWithoutSkipping() = checkFailure(Kind.CONTENT, tv = true)
    @Test fun phoneProviderPermissionFailureStopsWithoutSkipping() = checkFailure(Kind.DENIED)
    @Test fun tvProviderPermissionFailureStopsWithoutSkipping() = checkFailure(Kind.DENIED, tv = true)
    @Test fun phoneHttpFailureStopsWithoutSkipping() = checkFailure(Kind.HTTP)
    @Test fun tvHttpFailureStopsWithoutSkipping() = checkFailure(Kind.HTTP, tv = true)

    private fun checkFailure(kind: Kind, tv: Boolean = false) = withPlayback(kind, tv = tv) { scenario, f ->
        awaitError(scenario)
        scenario.onActivity {
            assertStopped(f)
            val dialog = errorDialog(it)!!
            val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
            assertTrue(text.contains(f.videos[1].displayName))
            assertTrue(text.contains(f.player.playerError!!.errorCodeName))
            assertNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
            assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEUTRAL))
        }
        SystemClock.sleep(300)
        scenario.onActivity { assertStopped(f) }
        if (!tv && kind == Kind.CORRUPT) screenshot("phone-error")
    }

    @Test fun threeBrokenItemsDoNotCascadeToTheNextFile() = withPlayback(allBad = true) { scenario, f ->
        awaitError(scenario);SystemClock.sleep(500)
        scenario.onActivity { assertStopped(f, index = 0);assertEquals(3, f.player.mediaItemCount) }
    }

    @Test fun fourExplicitFailedRetriesStayOnTheSameFile() = withPlayback { scenario, f ->
        awaitError(scenario)
        repeat(4) {
            lateinit var previous: AlertDialog
            scenario.onActivity { activity ->
                previous = errorDialog(activity)!!
                previous.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            BrowserTestUi.waitUntil { errorDialog(scenario)?.let { it !== previous && it.window?.decorView?.hasWindowFocus() == true } == true }
            scenario.onActivity { assertStopped(f) }
        }
    }

    @Test fun closingErrorLeavesTheSamePlayerQueueAndFailurePaused() = withPlayback { scenario, f ->
        awaitError(scenario)
        scenario.onActivity { errorDialog(it)!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick() }
        BrowserTestUi.waitUntil { errorDialog(scenario) == null && f.view.rootView.hasWindowFocus() }
        SystemClock.sleep(350)
        scenario.onActivity {
            assertSame(f.player, f.view.player);assertStopped(f)
            assertTrue(it.findViewById<View>(R.id.pause_overlay).isShown)
            assertEquals(it.getString(R.string.playback_failed), it.findViewById<TextView>(R.id.playback_status).text.toString())
        }
        screenshot("closed-error-controls")
    }

    @Test fun networkRecoveryDoesNotAutomaticallyRetryOrResume() = withPlayback(Kind.HTTP) { scenario, f ->
        awaitError(scenario)
        val requests = f.http!!.requests.get()
        f.http.available.set(true)
        SystemClock.sleep(600)
        scenario.onActivity { assertStopped(f);assertEquals(requests, f.http.requests.get()) }
        scenario.onActivity { errorDialog(it)!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        BrowserTestUi.waitUntil { f.player.isPlaying }
        scenario.onActivity { assertEquals(1, f.player.currentMediaItemIndex);assertSame(f.player, f.view.player) }
    }

    @Test fun failureWhileBackgroundedIsPausedAndPresentedOnReturn() = withPlayback(validFirst = true) { scenario, f ->
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.onActivity { injectFailure(f) }
        BrowserTestUi.waitUntil { f.player.playerError != null }
        scenario.onActivity { assertStopped(f);assertNull(errorDialog(it)) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitError(scenario)
        scenario.onActivity { assertStopped(f) }
    }

    @Test fun failureWhileInformationIsOpenWaitsAndPreventsCancelResume() = withPlayback(validFirst = true) { scenario, f ->
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() }
        BrowserTestUi.waitUntil { infoWindow()?.hasWindowFocus() == true }
        scenario.onActivity { injectFailure(f) }
        BrowserTestUi.waitUntil { f.player.playerError != null }
        scenario.onActivity {
            assertStopped(f);assertNull(errorDialog(it));assertTrue(infoWindow()!!.hasWindowFocus())
            infoWindow()!!.findViewById<View>(R.id.info_close).performClick()
        }
        awaitError(scenario)
        scenario.onActivity { assertStopped(f);assertEquals(30_000L, f.player.currentPosition) }
    }

    @Test fun failureWhileFileListIsOpenWaitsAndPreventsCancelResume() = withPlayback(validFirst = true) { scenario, f ->
        openList(scenario)
        scenario.onActivity { injectFailure(f) }
        BrowserTestUi.waitUntil { f.player.playerError != null }
        scenario.onActivity {
            assertStopped(f);assertNull(errorDialog(it));assertTrue(fileWindow()!!.hasWindowFocus())
            fileWindow()!!.findViewById<View>(R.id.dlna_close).performClick()
        }
        awaitError(scenario)
        scenario.onActivity { assertStopped(f);assertEquals(30_000L, f.player.currentPosition) }
    }

    @Test fun selectingAnotherFileSupersedesAPendingFailure() = withPlayback(validFirst = true) { scenario, f ->
        openList(scenario)
        scenario.onActivity { injectFailure(f) }
        BrowserTestUi.waitUntil { f.player.playerError != null }
        scenario.onActivity {
            val list = fileWindow()!!.findViewById<ListView>(R.id.dlna_list)
            val index = (0 until list.count).first { (list.getItemAtPosition(it) as DlnaRow).title.contains("Episode 3") }
            list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
        }
        BrowserTestUi.waitUntil { f.view.player !== f.player && f.view.player?.isPlaying == true }
        SystemClock.sleep(300)
        scenario.onActivity { assertNull(errorDialog(it));assertEquals(2, f.view.player!!.currentMediaItemIndex);assertTrue(f.view.player!!.playWhenReady) }
    }

    @Test fun failureBehindAnotherDialogDoesNotReplaceItsWindow() = withPlayback(validFirst = true) { scenario, f ->
        lateinit var dialog: AlertDialog
        scenario.onActivity { dialog = AlertDialog.Builder(it).setMessage("D25 foreground dialog").setPositiveButton("OK", null).show() }
        try {
            BrowserTestUi.waitUntil { dialog.window?.decorView?.hasWindowFocus() == true }
            scenario.onActivity { injectFailure(f) }
            BrowserTestUi.waitUntil { f.player.playerError != null }
            scenario.onActivity { assertNull(errorDialog(it));assertTrue(dialog.window!!.decorView.hasWindowFocus());assertStopped(f) }
        } finally { scenario.onActivity { dialog.dismiss() } }
        awaitError(scenario)
        scenario.onActivity { assertStopped(f) }
    }

    @Test fun realErrorDialogBlocksModernAndPlatformTransportCommands() = withPlayback { scenario, f ->
        awaitError(scenario)
        lateinit var future: ListenableFuture<MediaController>
        scenario.onActivity { future = MediaController.Builder(it, f.session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync() }
        val remote = future.get(5, TimeUnit.SECONDS)
        try {
            scenario.onActivity {
                assertFalse(remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                remote.play();remote.prepare();remote.seekToNextMediaItem();remote.seekTo(0)
            }
            android.media.session.MediaController(InstrumentationRegistry.getInstrumentation().targetContext, f.session.platformToken)
                .transportControls.apply { play();prepare();skipToNext();seekTo(0) }
            SystemClock.sleep(250)
            scenario.onActivity { assertStopped(f) }
        } finally { scenario.onActivity { remote.release() } }
    }

    @Test fun controllerWithoutFailureCallbackAlsoPausesWithoutSkipping() = checkStandalone(callback = false)
    @Test fun callbackObservesAlreadyPausedPlayer() = checkStandalone(callback = true)
    private fun checkStandalone(callback: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation();val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d25-controller-fixture").apply { deleteRecursively();mkdirs() }
        val videos = (0..2).map { i ->
            val file = File(directory, "Broken $i.mp4").apply { writeText("not media") }
            VideoItem(file.toURI().toString(), file.name, file.length(), file.lastModified(), "video/mp4")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var controller: PlayerController
                val intents = mutableListOf<Boolean>();var called = false
                BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() } }
                scenario.onActivity {
                    controller = PlayerController(it, onPlaybackFailure = if (callback) ({ _ ->
                        called = true;assertFalse(controller.player.playWhenReady);assertEquals(0, controller.player.currentMediaItemIndex)
                    }) else null)
                    controller.player.addListener(object : Player.Listener {
                        override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { intents.add(ready) }
                    })
                    controller.setPlaylist(videos)
                }
                try {
                    BrowserTestUi.waitUntil { controller.player.playerError != null && !controller.player.playWhenReady }
                    scenario.onActivity {
                        assertTrue("The error must stop an accepted Play request", intents.contains(true))
                        assertEquals(0, controller.player.currentMediaItemIndex);assertEquals(3, controller.player.mediaItemCount)
                        assertEquals(callback, called)
                    }
                } finally { scenario.onActivity { controller.release() } }
            }
        } finally { directory.deleteRecursively() }
    }

    private enum class Kind { CORRUPT, MISSING, CONTENT, DENIED, HTTP }
    private data class Fixture(val view: PlayerView, val player: Player, val engine: ExoPlayer,
        val videos: List<VideoItem>, val session: MediaSession, val http: HttpFixture?)
    private fun assertStopped(f: Fixture, index: Int = 1) {
        assertSame(f.player, f.view.player);assertNotNull(f.player.playerError)
        assertFalse(f.player.playWhenReady);assertEquals(index, f.player.currentMediaItemIndex);assertEquals(3, f.player.mediaItemCount)
    }
    private fun errorDialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("playbackErrorDialog").apply { isAccessible = true }.get(activity) as? AlertDialog
    private fun errorDialog(scenario: ActivityScenario<MainActivity>): AlertDialog? { var result: AlertDialog? = null;scenario.onActivity { result = errorDialog(it) };return result }
    private fun awaitError(scenario: ActivityScenario<MainActivity>) = BrowserTestUi.waitUntil { errorDialog(scenario)?.window?.decorView?.hasWindowFocus() == true }
    private fun infoWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.info_close) != null }
    private fun fileWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.dlna_list) != null }
    private fun openList(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
        BrowserTestUi.waitUntil { fileWindow()?.hasWindowFocus() == true && fileWindow()!!.findViewById<ListView>(R.id.dlna_list).count == 3 }
    }
    private fun injectFailure(f: Fixture) {
        val factory = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(listener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw IOException("D25 injected loader failure")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("D25 injected loader failure")
                override fun getUri(): Uri? = null
                override fun close() = Unit
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(factory).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
        val sources = f.videos.map { sourceFactory.createMediaSource(MediaItem.Builder().setUri(it.uri).setMimeType(it.mimeType).build()) }
        f.engine.setMediaSources(sources, 1, 30_000)
        f.engine.prepare()
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync();SystemClock.sleep(600)
        File(instrumentation.targetContext.cacheDir, "d25-$name.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private class HttpFixture(private val bytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val available = AtomicBoolean(false)
        val requests = AtomicInteger(0)
        val uri = "http://127.0.0.1:${server.localPort}/d25-media.mp4"
        private val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (true) { val line = reader.readLine() ?: break;if (line.isEmpty()) break }
                        requests.incrementAndGet()
                        val body = if (available.get()) bytes else byteArrayOf()
                        val status = if (available.get()) "200 OK" else "404 Not Found"
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body);output.flush()
                    }
                } catch (_: IOException) { if (server.isClosed) break }
            }
        }.apply { isDaemon = true;start() }
        override fun close() { server.close();worker.join(2_500) }
    }
    private fun withPlayback(kind: Kind = Kind.CORRUPT, tv: Boolean = false, allBad: Boolean = false,
        validFirst: Boolean = false, action: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation();val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "d25-error-fixture").apply { deleteRecursively();mkdirs() }
        val prefs = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE);val previous = prefs.all.toMap()
        val tree = ScrollFixtureProvider.tree
        val hadGrant = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        val video = instrumentation.context.assets.open("d13-video.mp4").use { it.readBytes() }
        val http = if (kind == Kind.HTTP) HttpFixture(video) else null
        val size = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + size)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        var touchMode = true
        try {
            if (kind == Kind.CONTENT || kind == Kind.DENIED) {
                context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test", ScrollFixtureGrantActivity::class.java.name)
                    .putExtra("target", context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                BrowserTestUi.waitUntil { runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess }
            }
            val videos = (0..2).map { index ->
                val broken = allBad || (!validFirst && index == 1)
                val file = File(directory, "D25 Episode ${index + 1}.${if (broken) "mp4" else "wav"}")
                if (broken) { if (kind != Kind.MISSING) file.writeText("invalid media") } else file.writeBytes(wave)
                val uri = if (!broken) file.toURI().toString() else when (kind) {
                    Kind.HTTP -> http!!.uri
                    Kind.CONTENT, Kind.DENIED -> DocumentsContract.buildDocumentUriUsingTree(tree, if (kind == Kind.DENIED) "d25-denied" else "media").toString()
                    else -> file.toURI().toString()
                }
                VideoItem(uri, file.name, if (file.exists()) file.length() else -1, 0, if (broken) "video/mp4" else "audio/wav")
            }
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
                    MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, videos, if (allBad) 0 else 1)
                }
                lateinit var f: Fixture
                scenario.onActivity {
                    val owner = MainActivity::class.java.getDeclaredField("playerController").apply { isAccessible = true }.get(it) as PlayerController
                    val engine = PlayerController::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(owner) as ExoPlayer
                    val session = PlayerController::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }.get(owner) as MediaSession
                    f = Fixture(view, owner.player, engine, videos, session, http)
                }
                if (validFirst) {
                    BrowserTestUi.waitUntil { f.player.isPlaying }
                    scenario.onActivity { f.player.seekTo(30_000) }
                    BrowserTestUi.waitUntil { f.player.playbackState == Player.STATE_READY }
                } else BrowserTestUi.waitUntil { f.player.playerError != null && !f.player.playWhenReady }
                try { action(scenario, f) } finally {
                    if (tv) scenario.onActivity {
                        @Suppress("DEPRECATION")
                        it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
                    }
                }
            }
        } finally {
            http?.close()
            if (!hadGrant && (kind == Kind.CONTENT || kind == Kind.DENIED)) runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            instrumentation.setInTouchMode(touchMode)
            val editor = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value);is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet()) } }
            editor.commit();directory.deleteRecursively()
        }
    }
}
