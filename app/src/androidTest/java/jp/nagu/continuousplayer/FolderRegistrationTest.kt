package jp.nagu.continuousplayer

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class FolderRegistrationTest {
    @Test fun phoneSafRegistrationSelectsNewRow() = registration(Kind.SAF)
    @Test fun tvSafRegistrationSelectsNewRow() = registration(Kind.SAF, tv = true)
    @Test fun phoneSafDuplicateSelectsExistingRow() = registration(Kind.SAF, duplicate = true)
    @Test fun tvSafDuplicateSelectsExistingRow() = registration(Kind.SAF, duplicate = true, tv = true)
    @Test fun phoneUsbRegistrationSelectsNewRow() = registration(Kind.USB)
    @Test fun tvUsbRegistrationSelectsNewRow() = registration(Kind.USB, tv = true)
    @Test fun phoneUsbDuplicateSelectsExistingRow() = registration(Kind.USB, duplicate = true)
    @Test fun tvUsbDuplicateSelectsExistingRow() = registration(Kind.USB, duplicate = true, tv = true)
    @Test fun phoneDlnaRegistrationSelectsNewRow() = registration(Kind.DLNA)
    @Test fun tvDlnaRegistrationSelectsNewRow() = registration(Kind.DLNA, tv = true)
    @Test fun phoneDlnaDuplicateUpdatesAndSelectsExistingRow() = registration(Kind.DLNA, duplicate = true)
    @Test fun tvDlnaDuplicateUpdatesAndSelectsExistingRow() = registration(Kind.DLNA, duplicate = true, tv = true)
    @Test fun phoneEmptyAdditionCancelReturnsToSavedList() = cancelAddition(seed = false)
    @Test fun tvEmptyAdditionCancelReturnsToSavedList() = cancelAddition(seed = false, tv = true)
    @Test fun phoneNonemptyAdditionCancelReturnsToSavedList() = cancelAddition()
    @Test fun tvNonemptyAdditionCancelReturnsToSavedList() = cancelAddition(tv = true)
    @Test fun phonePickerCancelKeepsSourceChooser() = pickerCancel()
    @Test fun tvPickerCancelKeepsSourceChooser() = pickerCancel(tv = true)
    @Test fun failedSafPersistenceKeepsSourceChooserWithoutRegistering() = withFixture { f ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val unavailable = DocumentsContract.buildTreeDocumentUri("d18.ungranted.fixture", "root")
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(unavailable)), true)
        try {
            openAddition(f);choose(f, R.string.saved_folder_local)
            BrowserTestUi.waitUntil { monitor.hits == 1 };awaitTitle(f, R.string.saf_add_folder)
            assertFalse(f.context.contentResolver.persistedUriPermissions.any { it.uri == unavailable })
            closeBrowser(f);awaitSaved(f, 3);assertNull(player(f))
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun usbPermissionCancelKeepsSourceChooser() = withFixture(tv = true) { f ->
        openAddition(f);choose(f, R.string.usb_choose_drive)
        BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().any { it.findViewById<TextView>(android.R.id.message)?.text.toString() == f.context.getString(R.string.usb_permission_message) } }
        f.scenario.onActivity { WindowInspector.getGlobalWindowViews().single { it.findViewById<TextView>(android.R.id.message)?.text.toString() == it.context.getString(R.string.usb_permission_message) }.findViewById<View>(android.R.id.button2).performClick() }
        awaitTitle(f, R.string.saf_add_folder)
        closeBrowser(f);awaitSaved(f, 3)
    }
    @Test fun cancellingLoadingDlnaReturnsToSavedListAndCancelsRequest() = withFixture { f ->
        f.client.delayed = true
        openAddition(f);choose(f, R.string.dlna_select)
        BrowserTestUi.waitUntil { f.client.started }
        closeBrowser(f);awaitSaved(f, 3)
        BrowserTestUi.waitUntil { f.client.cancelled }
        assertNull(player(f))
    }
    @Test fun dlnaServerListCanReturnToSavedList() = withFixture { f ->
        openAddition(f);choose(f, R.string.dlna_select)
        awaitTitle(f, R.string.dlna_choose_server)
        f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_up).performClick() }
        awaitSaved(f, 3);assertNull(player(f))
    }
    @Test fun dlnaRetryKeepsRegistrationReturnAndFocus() = withFixture { f ->
        f.client.failing = true
        openAddition(f);choose(f, R.string.dlna_select)
        awaitTitle(f, R.string.dlna_choose_server)
        f.scenario.onActivity { assertTrue(window()!!.findViewById<View>(R.id.dlna_manual_address).isShown) }
        f.client.failing = false
        f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_refresh).performClick() }
        finishDlnaRegistration(f);assertRegistered(f, Kind.DLNA, 4)
    }
    @Test fun playingSafAdditionKeepsSessionUntilSavedListCloses() = registration(Kind.SAF, playing = true)
    @Test fun pausedSafAdditionKeepsPause() = registration(Kind.SAF, playing = false)
    @Test fun playingUsbAdditionKeepsSessionUntilSavedListCloses() = registration(Kind.USB, playing = true)
    @Test fun pausedUsbAdditionKeepsPause() = registration(Kind.USB, playing = false)
    @Test fun playingDlnaAdditionKeepsSessionUntilSavedListCloses() = registration(Kind.DLNA, playing = true)
    @Test fun pausedDlnaAdditionKeepsPause() = registration(Kind.DLNA, playing = false)
    @Test fun backgroundDuringAdditionPreventsResumeAfterCancellingSavedList() = withFixture { f ->
        val original = startRetainedPlayback(f, true)
        openAddition(f, fromPlayback = true)
        f.scenario.moveToState(Lifecycle.State.CREATED);f.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitTitle(f, R.string.saf_add_folder)
        closeBrowser(f);awaitSaved(f, 3);assertRetained(f, original)
        closeBrowser(f)
        BrowserTestUi.waitUntil { window() == null }
        f.scenario.onActivity { assertSame(original, player(f));assertFalse(original.playWhenReady) }
    }

    private enum class Kind { SAF, USB, DLNA }
    private fun registration(kind: Kind, duplicate: Boolean = false, tv: Boolean = false, playing: Boolean? = null) = withFixture(tv) { f ->
        if (duplicate) when (kind) {
            Kind.SAF -> f.context.contentResolver.takePersistableUriPermission(f.newTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Kind.USB -> SavedUsbFolders(f.context).save(f.root, f.target)
            Kind.DLNA -> SavedDlnaFolders(f.context).save(f.client.server.copy(name = "Old server name"), listOf(DlnaEntry("0", "Old root"), DlnaEntry("new", "Old folder name")))
        }
        val lastBefore = f.context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE).all.toMap()
        val original = playing?.let { startRetainedPlayback(f, it) }
        openAddition(f, fromPlayback = original != null)
        when (kind) {
            Kind.SAF -> {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(f.newTree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
                try { choose(f, R.string.saved_folder_local);BrowserTestUi.waitUntil { monitor.hits == 1 };awaitSaved(f, 4) }
                finally { instrumentation.removeMonitor(monitor) }
            }
            Kind.USB -> {
                // Exercise MainActivity's actual registration callbacks with a controlled directory.
                // AVDs have no removable volume; the OS permission prompt is covered separately.
                f.scenario.onActivity { invoke(it, "openUsbBrowser") }
                BrowserTestUi.waitUntil { window()?.findViewById<ListView>(R.id.dlna_list)?.isEnabled == true }
                f.scenario.onActivity {
                    val usb = field<UsbFileBrowser>(it, "usbBrowser")
                    UsbFileBrowser::class.java.getDeclaredMethod("browse", File::class.java, File::class.java, File::class.java)
                        .apply { isAccessible = true }.invoke(usb, f.root, f.target, null)
                }
                awaitTitle(f, f.target.name)
                f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_register).performClick() }
            }
            Kind.DLNA -> { choose(f, R.string.dlna_select);finishDlnaRegistration(f) }
        }
        assertRegistered(f, kind, 4)
        if (original == null) {
            assertNull(player(f))
            assertEquals(lastBefore, f.context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE).all)
        } else {
            assertRetained(f, original)
            closeBrowser(f)
            BrowserTestUi.waitUntil { window() == null && original.playWhenReady == playing }
            f.scenario.onActivity { assertSame(original, player(f));assertEquals(1, original.currentMediaItemIndex);assertEquals(3, original.mediaItemCount);assertTrue(original.currentPosition in 29_000..33_000) }
        }
        if (!tv && playing == null && !duplicate) screenshot(f, kind.name.lowercase())
    }
    private fun assertRegistered(f: Fixture, kind: Kind, count: Int) {
        awaitSaved(f, count)
        f.scenario.onActivity {
            val list = window()!!.findViewById<ListView>(R.id.dlna_list)
            val title = when (kind) { Kind.SAF -> "Season 01";Kind.USB -> f.target.name;Kind.DLNA -> "ZZZ registered NAS" }
            assertEquals(title, (list.selectedItem as DlnaRow).title)
            assertTrue(list.selectedItemPosition > 0)
            assertTrue(list.selectedItemPosition in list.firstVisiblePosition..list.lastVisiblePosition)
            assertTrue(list.hasFocus())
            assertEquals(1, windows().size)
            if (kind == Kind.DLNA) {
                val saved = SavedDlnaFolders(f.context).all().single { it.serverId == f.client.server.id }
                assertEquals(f.client.server.name, saved.serverName);assertEquals(title, saved.title)
                assertEquals(2, SavedDlnaFolders(f.context).all().size)
            }
        }
    }
    private fun cancelAddition(seed: Boolean = true, tv: Boolean = false) = withFixture(tv, seed) { f ->
        for (action in listOf("close", "back", "escape")) {
            openAddition(f)
            if (action == "close") closeBrowser(f) else key(if (action == "back") KeyEvent.KEYCODE_BACK else KeyEvent.KEYCODE_ESCAPE)
            awaitSaved(f, if (seed) 3 else 0)
            assertNull(player(f))
            closeBrowser(f);BrowserTestUi.waitUntil { window() == null }
        }
    }
    private fun pickerCancel(tv: Boolean = false) = withFixture(tv) { f ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            openAddition(f);choose(f, R.string.saved_folder_local)
            BrowserTestUi.waitUntil { monitor.hits == 1 };awaitTitle(f, R.string.saf_add_folder)
            closeBrowser(f);awaitSaved(f, 3);assertNull(player(f))
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun finishDlnaRegistration(f: Fixture) {
        awaitTitle(f, R.string.dlna_choose_server);choose(f, f.client.server.name)
        awaitTitle(f, f.client.server.name)
        f.scenario.onActivity { assertEquals(f.context.getString(R.string.dlna_server_list), window()!!.findViewById<View>(R.id.dlna_up).contentDescription.toString()) }
        choose(f, "ZZZ registered NAS")
        awaitTitle(f, "ZZZ registered NAS")
        f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_register).performClick() }
    }
    private fun startRetainedPlayback(f: Fixture, playing: Boolean): Player {
        val dir = File(f.root, "Original playback").apply { mkdirs() }
        val size = 8_000 * 2 * 90
        val bytes = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + size)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size).array()
        val videos = (0..2).map { val file = File(dir, "D18 Episode $it.wav").apply { writeBytes(bytes) };VideoItem(file.toURI().toString(), file.name, file.length(), 0, "audio/wav") }
        f.scenario.onActivity {
            ViewModelProvider(it)[PlayerViewModel::class.java].apply { this.videos = videos;playbackFolder = PlaybackFolder.Usb(f.root, dir) }
            MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, videos, 1)
        }
        BrowserTestUi.waitUntil { player(f)?.isPlaying == true }
        f.scenario.onActivity { player(f)!!.seekTo(30_000);if (!playing) player(f)!!.pause() }
        BrowserTestUi.waitUntil { player(f)!!.playbackState == Player.STATE_READY }
        return player(f)!!
    }
    private fun assertRetained(f: Fixture, original: Player) = f.scenario.onActivity {
        assertSame(original, player(f));assertFalse(original.playWhenReady)
        assertEquals(1, original.currentMediaItemIndex);assertEquals(3, original.mediaItemCount)
        assertTrue(original.currentPosition in 29_000..33_000)
        assertEquals("Original playback", (ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder as PlaybackFolder.Usb).directory.name)
    }
    private fun openAddition(f: Fixture, fromPlayback: Boolean = false) {
        if (fromPlayback) {
            f.scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
            awaitTitle(f, "Original playback")
            // Its unregistered playback directory is the browsing boundary.
            f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_up).performClick() }
        } else f.scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).performClick() }
        awaitTitle(f, R.string.saf_choose_folder)
        f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_refresh).performClick() }
        awaitTitle(f, R.string.saf_add_folder)
    }
    private fun choose(f: Fixture, resource: Int) = choose(f, f.context.getString(resource))
    private fun choose(f: Fixture, title: String) = f.scenario.onActivity {
        val list = window()!!.findViewById<ListView>(R.id.dlna_list)
        val index = (0 until list.count).single { (list.getItemAtPosition(it) as DlnaRow).title == title }
        list.performItemClick(null, index, list.adapter.getItemId(index))
    }
    private fun closeBrowser(f: Fixture) = f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_close).performClick() }
    private fun awaitTitle(f: Fixture, resource: Int) = awaitTitle(f, f.context.getString(resource))
    private fun awaitTitle(f: Fixture, title: String) = BrowserTestUi.waitUntil { window()?.let { it.hasWindowFocus() && it.findViewById<TextView>(R.id.dlna_title).text.toString() == title } == true }
    private fun awaitSaved(f: Fixture, count: Int) { awaitTitle(f, R.string.saf_choose_folder);BrowserTestUi.waitUntil { window()!!.findViewById<ListView>(R.id.dlna_list).count == count && window()!!.findViewById<View>(R.id.dlna_refresh).isEnabled } }
    private fun windows() = WindowInspector.getGlobalWindowViews().filter { it.findViewById<View>(R.id.dlna_title) != null }
    private fun window() = windows().singleOrNull()
    private fun <T> field(activity: MainActivity, name: String): T { @Suppress("UNCHECKED_CAST") return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T }
    private fun invoke(activity: MainActivity, method: String) = MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    private fun player(f: Fixture): Player? { var result: Player? = null;f.scenario.onActivity { result = it.findViewById<PlayerView>(R.id.player_view).player };return result }
    private fun key(code: Int) { val i = InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(500);val down = SystemClock.uptimeMillis();for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) assertTrue(i.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), a, code, 0), true)) }
    private fun screenshot(f: Fixture, name: String) { val i = InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(600);File(f.context.filesDir, "d18-$name.png").outputStream().use { assertTrue(i.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it)) } }

    private class Client : DlnaClient() {
        val server = DlnaServer("uuid:d18-new", "D18 NAS", "http://192.0.2.1/control", "ContentDirectory:1")
        var failing = false;var delayed = false
        @Volatile var started = false
        @Volatile var cancelled = false
        private val gate = CompletableDeferred<Unit>()
        override suspend fun discover(): List<DlnaServer> {
            started = true
            if (delayed) try { gate.await() } catch (e: CancellationException) { cancelled = true;throw e }
            if (failing) throw IOException("D18 controlled discovery failure")
            return listOf(server)
        }
        override suspend fun browse(server: DlnaServer, objectId: String) = if (objectId == "0") listOf(DlnaEntry("new", "ZZZ registered NAS")) else emptyList()
    }
    private data class Fixture(val scenario: ActivityScenario<MainActivity>, val context: Context, val root: File, val target: File, val newTree: Uri, val client: Client)
    private fun withFixture(tv: Boolean = false, seed: Boolean = true, action: (Fixture) -> Unit) {
        val i = InstrumentationRegistry.getInstrumentation();val context = i.targetContext;val resolver = context.contentResolver
        val grants = resolver.persistedUriPermissions.toList();val authority = ScrollFixtureProvider.tree.authority!!
        assumeTrue("Use fixture-owned URI grants only", grants.all { it.uri.authority == authority })
        val prefs = listOf("saved_dlna_folders", "saved_usb_folders", "last_playback_folder", "usb_browser").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val before = prefs.map { it.all.toMap() }
        val oldTree = DocumentsContract.buildTreeDocumentUri(authority, "season-00");val newTree = DocumentsContract.buildTreeDocumentUri(authority, "season-01")
        val root = File(context.filesDir, "d18-registration-fixture").apply { deleteRecursively();mkdirs() }
        val target = File(root, "ZZZ registered USB").apply { mkdirs() }
        var touch = true
        fun clearGrants() { resolver.persistedUriPermissions.filter { it.uri.authority == authority }.forEach { resolver.releasePersistableUriPermission(it.uri, (if (it.isReadPermission) 1 else 0) or (if (it.isWritePermission) 2 else 0)) } }
        fun grantTransient(trees: List<Uri>, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
            context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test", ScrollFixtureGrantActivity::class.java.name).putExtra("target", context.packageName)
                .putExtra("trees", trees.map { it.toString() }.toTypedArray()).putExtra("accessFlags", flags).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i.waitForIdleSync();SystemClock.sleep(200)
        }
        try {
            clearGrants();prefs.forEach { it.edit().clear().commit() }
            grantTransient(listOf(oldTree, newTree))
            if (seed) {
                BrowserTestUi.waitUntil { runCatching { resolver.takePersistableUriPermission(oldTree, 1) }.isSuccess }
                SavedDlnaFolders(context).save(DlnaServer("uuid:d18-old", "AAA existing NAS", "http://192.0.2.1/control", "ContentDirectory:1"), listOf(DlnaEntry("0", "AAA existing NAS")))
                val oldUsb = File(root, "AAA existing USB").apply { mkdirs() }
                SavedUsbFolders(context).save(root, oldUsb);LastPlaybackFolder(context).save(PlaybackFolder.Usb(root, oldUsb))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var config: Configuration
                val client = Client()
                scenario.onActivity {
                    config = Configuration(it.resources.configuration);touch = it.findViewById<View>(R.id.btn_select_folder).isInTouchMode
                    if (tv) { @Suppress("DEPRECATION") it.resources.updateConfiguration(Configuration(config).apply { uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }, it.resources.displayMetrics) }
                    MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible = true }.set(it, DlnaBrowser(it, client) { _, _ -> fail("Registration must not play") })
                }
                i.setInTouchMode(false)
                try { action(Fixture(scenario, context, root, target, newTree, client)) }
                finally { scenario.onActivity { @Suppress("DEPRECATION") it.resources.updateConfiguration(config, it.resources.displayMetrics) } }
            }
        } finally {
            i.setInTouchMode(touch);clearGrants()
            grants.forEach { g -> val flags = (if (g.isReadPermission) 1 else 0) or (if (g.isWritePermission) 2 else 0);grantTransient(listOf(g.uri), flags);BrowserTestUi.waitUntil { runCatching { resolver.takePersistableUriPermission(g.uri, flags) }.isSuccess } }
            prefs.zip(before).forEach { (p, values) -> val e=p.edit().clear();values.forEach { (k,v) -> when(v) { is String -> e.putString(k,v);is Set<*> -> e.putStringSet(k,v.filterIsInstance<String>().toSet());is Boolean -> e.putBoolean(k,v);is Int -> e.putInt(k,v);is Long -> e.putLong(k,v);is Float -> e.putFloat(k,v) } };e.commit() }
            root.deleteRecursively()
        }
    }
}
