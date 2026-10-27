package jp.nagu.continuousplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
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
class MediaPresentationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val longName = "長い作品名のコレクション 内部のピリオド.part.1 最後まで名前を確認するための追加タイトル.mp4"
    private fun item(name: String, mime: String? = "video/mp4") = VideoItem("http://192.0.2.1/${name.hashCode()}", name, 1, 0, mime)

    @Test fun usbRegistrationKeepsFormatsAndOriginalSelection() = source("registration")
    @Test fun usbPlaybackKeepsFormatsAndOriginalSelection() = source("usb")
    @Test fun dlnaKeepsFormatsAndObjectIds() = source("dlna")
    @Test fun safKeepsFormatsAndDocumentUris() = source("saf")

    private fun source(kind: String) = fixture { scenario, directory ->
        var selected: VideoItem? = null
        var close: () -> Unit = {}
        val tree = DocumentsContract.buildTreeDocumentUri(ScrollFixtureProvider.tree.authority, "d21-format")
        val oldGrant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        if (kind == "saf" && oldGrant == null) {
            context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test", ScrollFixtureGrantActivity::class.java.name)
                .putExtra("target", context.packageName).putExtra("trees", arrayOf(tree.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            BrowserTestUi.waitUntil { runCatching { context.contentResolver.takePersistableUriPermission(tree, 1) }.isSuccess }
        }
        try {
            scenario.onActivity { activity ->
                when (kind) {
                    "registration" -> UsbFileBrowser(activity) { media, index -> selected = media[index] }.also {
                        close = it::close; it.openFolder(PlaybackFolder.Usb(directory, directory), forRegistration = true)
                    }
                    "usb" -> UsbPlaybackBrowser(activity) { _, media, index -> selected = media[index] }.also {
                        close = it::close; it.open(PlaybackFolder.Usb(directory, directory), null, null) {}
                    }
                    "saf" -> SafFileBrowser(activity, {}, {}, {}, onSelected = { media, index -> selected = media[index] }).also {
                        close = it::close; it.openFolder(PlaybackFolder.Saf(listOf(SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree, "d21-format"), "D21 source"))))
                    }
                    else -> {
                        val server = DlnaServer("uuid:d21", "D21 server", "http://192.0.2.1/control", "ContentDirectory:1")
                        val client = object : DlnaClient() {
                            override suspend fun discover() = listOf(server)
                            override suspend fun browse(server: DlnaServer, objectId: String) = listOf("mp4", "m4v").map {
                                DlnaEntry(it, "Episode.part.1.$it", item("Episode.part.1.$it"))
                            }
                        }
                        DlnaBrowser(activity, client) { media, index -> selected = media[index] }.also {
                            close = it::close; it.openSaved(SavedDlnaFolder(server.id, server.name, listOf(SavedDlnaPath("0", "D21 source")))) {}
                        }
                    }
                }
            }
            BrowserTestUi.waitUntil { windows().any { it.findViewById<ListView>(R.id.dlna_list)?.count == 2 && it.hasWindowFocus() } }
            lateinit var chosen: DlnaRow
            scenario.onActivity {
                val list = browser().findViewById<ListView>(R.id.dlna_list)
                val rows = (0 until list.count).map { list.getItemAtPosition(it) as DlnaRow }
                assertEquals(setOf("MP4", "M4V"), rows.map { it.formatBadge }.toSet())
                assertTrue(rows.all { it.title == "Episode.part.1" && it.description.contains(it.media!!.displayName) })
                val index = rows.indexOfFirst { it.formatBadge == "M4V" };chosen = rows[index]
                val view = list.adapter.getView(index, null, list)
                assertEquals("M4V", view.findViewById<TextView>(R.id.dlna_row_format).text.toString())
                assertTrue(view.tooltipText.toString().contains(chosen.media!!.displayName))
                list.setSelectionFromTop(index, 0)
            }
            BrowserTestUi.waitUntil { (browser().findViewById<ListView>(R.id.dlna_list).selectedItem as? DlnaRow)?.formatBadge == "M4V" }
            scenario.onActivity { browser().findViewById<View>(R.id.dlna_details).performClick() }
            awaitInfo()
            scenario.onActivity {
                assertTrue(info().findViewById<TextView>(R.id.dlna_message).text.toString().contains("Episode.part.1.m4v"))
                assertNull(selected)
                info().findViewById<View>(R.id.dlna_close).performClick()
            }
            BrowserTestUi.waitUntil { browser().hasWindowFocus() && browser().findViewById<ListView>(R.id.dlna_list).selectedItem == chosen }
            scenario.onActivity {
                val list = browser().findViewById<ListView>(R.id.dlna_list)
                assertEquals(chosen, list.selectedItem)
                list.performItemClick(list.selectedView, list.selectedItemPosition, list.selectedItemId)
            }
            BrowserTestUi.waitUntil { selected != null }
            assertEquals(chosen.media!!.uri, selected!!.uri)
            if (kind == "dlna") assertEquals("m4v", selected!!.dlnaObjectId)
            if (kind == "saf") assertEquals("d21-format/m4v", DocumentsContract.getDocumentId(Uri.parse(selected!!.uri)))
        } finally {
            scenario.onActivity { close() }
            if (kind == "saf" && oldGrant == null) context.contentResolver.releasePersistableUriPermission(tree, 1)
        }
    }

    @Test fun longPressOpensFullNameWithoutPlayback() = direct { scenario, dialog, _ ->
        instrumentation.waitForIdleSync();SystemClock.sleep(500)
        BrowserTestUi.tap(longName.removeSuffix(".mp4"), longPress = true)
        awaitInfo()
        scenario.onActivity { assertTrue(info().findViewById<TextView>(R.id.dlna_message).text.toString().contains(longName)) }
        screenshot("long-name")
        key(KeyEvent.KEYCODE_ESCAPE)
        BrowserTestUi.waitUntil { dialog.window!!.decorView.hasWindowFocus() && dialog.findViewById<ListView>(R.id.dlna_list).selectedItemPosition == 2 }
        scenario.onActivity { assertEquals(2, dialog.findViewById<ListView>(R.id.dlna_list).selectedItemPosition) }
    }

    @Test fun secondaryPointerOpensInformationWithoutSelecting() = direct { scenario, dialog, _ ->
        instrumentation.waitForIdleSync();SystemClock.sleep(500)
        var x=0f;var y=0f
        scenario.onActivity {
            val list=dialog.findViewById<ListView>(R.id.dlna_list)
            val row=list.getChildAt(2-list.firstVisiblePosition)
            val location=IntArray(2);row.getLocationOnScreen(location)
            x=location[0]+row.width/2f;y=location[1]+row.height/2f
            list.getLocationOnScreen(location)
            assertEquals(2,list.pointToPosition((x-location[0]).toInt(),(y-location[1]).toInt()))
        }
        val props=MotionEvent.PointerProperties().apply { id=0;toolType=MotionEvent.TOOL_TYPE_MOUSE }
        val coords=MotionEvent.PointerCoords().apply { this.x=x;this.y=y;pressure=1f;size=1f }
        val down=SystemClock.uptimeMillis()
        for(a in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),a,1,arrayOf(props),arrayOf(coords),0,
                if(a==MotionEvent.ACTION_DOWN)MotionEvent.BUTTON_SECONDARY else 0,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        try { awaitInfo() } catch (error:AssertionError) { screenshot("pointer-failure");throw error }
        scenario.onActivity { assertTrue(info().findViewById<TextView>(R.id.dlna_message).text.toString().contains(longName)) }
    }

    @Test fun remoteDetailsReturnsToSelectedRow() = direct(tv = true) { scenario, dialog, _ ->
        scenario.onActivity { dialog.findViewById<ListView>(R.id.dlna_list).requestFocusFromTouch() }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue(dialog.findViewById<View>(R.id.dlna_details).hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER);awaitInfo()
        key(KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { dialog.findViewById<ListView>(R.id.dlna_list).let { it.hasFocus() && it.selectedItemPosition == 2 } }
        scenario.onActivity { assertEquals(2, dialog.findViewById<ListView>(R.id.dlna_list).selectedItemPosition) }
    }

    @Test fun configurationKeepsInformationAndFullName() = direct { scenario, dialog, _ ->
        scenario.onActivity { dialog.findViewById<View>(R.id.dlna_details).performClick() };awaitInfo()
        scenario.onActivity { dialog.refreshForConfiguration();assertEquals(longName, info().findViewById<TextView>(R.id.dlna_message).text.toString().substringAfter("元の名前\n").substringBefore("\n\n")) }
        BrowserTestUi.waitForText(android.R.string.ok)
    }

    @Test fun changedContentClosesStaleInformation() = direct { scenario, dialog, content ->
        scenario.onActivity { dialog.findViewById<View>(R.id.dlna_details).performClick() };awaitInfo()
        scenario.onActivity { dialog.updateContent(content.copy(rows = emptyList(), message = "Empty")) }
        BrowserTestUi.waitUntil { windows().none { title(it) == context.getString(R.string.info) } }
        scenario.onActivity { assertEquals(View.GONE, dialog.findViewById<View>(R.id.dlna_details).visibility) }
    }

    @Test fun loadingDisablesInformationAndClosesChild() = direct { scenario, dialog, _ ->
        scenario.onActivity { dialog.findViewById<View>(R.id.dlna_details).performClick() };awaitInfo()
        scenario.onActivity { assertTrue(dialog.keepListWhileLoading());assertFalse(dialog.findViewById<View>(R.id.dlna_details).isEnabled) }
        BrowserTestUi.waitUntil { windows().none { title(it) == context.getString(R.string.info) } }
    }

    @Test fun dismissingBrowserClosesInformation() = direct { scenario, dialog, _ ->
        scenario.onActivity { dialog.findViewById<View>(R.id.dlna_details).performClick() };awaitInfo()
        scenario.onActivity { dialog.dismiss() }
        BrowserTestUi.waitUntil { windows().isEmpty() }
    }

    @Test fun badgesAndActionsFitWithNormalText() = fit(false)
    @Test fun badgesAndActionsFitWithLargeText() = fit(true)
    private fun fit(large: Boolean) = direct(large = large) { scenario, dialog, content ->
        scenario.onActivity {
            dialog.updateContent(content.copy(initialSelection = 0, onRegister = {}))
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            val list = dialog.findViewById<ListView>(R.id.dlna_list)
            val badge = list.getChildAt(0).findViewById<TextView>(R.id.dlna_row_format)
            assertTrue(badge.isShown);assertEquals("MP4", badge.text.toString())
            assertTrue(badge.layout.getEllipsisCount(0) == 0)
            val details = dialog.findViewById<Button>(R.id.dlna_details)
            assertTrue(details.isShown && details.width >= 48 * it.resources.displayMetrics.density)
            assertTrue(details.layout.height <= details.height - details.compoundPaddingTop - details.compoundPaddingBottom)
            val register=dialog.findViewById<Button>(R.id.dlna_register)
            assertTrue(register.layout.height <= register.height - register.compoundPaddingTop - register.compoundPaddingBottom)
            assertTrue("At least one complete media row must fit", list.height >= list.getChildAt(0).height)
        }
        screenshot(if (large) "large-text" else "normal-text")
    }

    @Test fun recycledUniqueRowsHideOldFormatBadge() = direct { scenario, dialog, _ ->
        scenario.onActivity {
            val list=dialog.findViewById<ListView>(R.id.dlna_list)
            val recycled=list.adapter.getView(0,null,list)
            assertEquals(View.VISIBLE,recycled.findViewById<View>(R.id.dlna_row_format).visibility)
            val unique=list.adapter.getView(2,recycled,list)
            assertSame(recycled,unique)
            assertEquals(View.GONE,unique.findViewById<View>(R.id.dlna_row_format).visibility)
            assertFalse(unique.isClickable);assertFalse(unique.isLongClickable)
        }
    }

    @Test fun unknownAndExtensionlessFormatsAndFolderCollision() = fixture { scenario, _ ->
        scenario.onActivity { activity ->
            val media = listOf(item("Recording", "audio/flac"), item("Recording", "application/octet-stream"), item("Solo.episode.mp4"))
            val rows = mediaBrowserRows(activity, media, listOf("Solo.episode"))
            assertEquals(listOf("FLAC", "不明", "MP4"), rows.map { it.formatBadge })
            assertEquals("Recording", rows[0].title)
            assertSame(media[0], rows[0].media)
            val unique = mediaBrowserRows(activity, listOf(item("Unique.mp4")), emptyList()).single()
            assertNull(unique.formatBadge)
            assertTrue(rows[0].description.contains("Recording。元の名前：Recording。形式：FLAC"))
        }
    }

    @Test fun nameInformationPreservesRetainedPlayback() = fixture { scenario, directory ->
        val data = ByteArray(8_000 * 2 * 90)
        val wave = ByteBuffer.allocate(data.size + 44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(data.size+36)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.size).put(data).array()
        val file = File(directory, "Current.part.1.wav").apply { writeBytes(wave) }
        val media = listOf(VideoItem(file.toURI().toString(), file.name, file.length(), 0, "audio/wav"))
        lateinit var player: Player
        scenario.onActivity {
            ViewModelProvider(it)[PlayerViewModel::class.java].apply { videos = media;playbackFolder = PlaybackFolder.Usb(directory, directory) }
            MainActivity::class.java.getDeclaredMethod("startPlayback", List::class.java, Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(it, media, 0)
        }
        BrowserTestUi.waitUntil { var ready=false;scenario.onActivity { player=it.findViewById<PlayerView>(R.id.player_view).player!!;ready=player.isPlaying };ready }
        scenario.onActivity { player.seekTo(30_000);it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
        BrowserTestUi.waitUntil { windows().any { it.hasWindowFocus() && it.findViewById<ListView>(R.id.dlna_list)?.isEnabled==true } }
        scenario.onActivity { browser().findViewById<View>(R.id.dlna_details).performClick() };awaitInfo()
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        scenario.onActivity {
            assertSame(player, it.findViewById<PlayerView>(R.id.player_view).player)
            assertFalse(player.playWhenReady);assertTrue(player.currentPosition in 29_000..31_000)
            assertEquals(1, player.mediaItemCount)
        }
        key(KeyEvent.KEYCODE_ESCAPE)
        BrowserTestUi.waitUntil { browser().hasWindowFocus() }
        scenario.onActivity { assertFalse(player.playWhenReady);browser().findViewById<View>(R.id.dlna_close).performClick() }
        BrowserTestUi.waitUntil { player.playWhenReady }
    }

    private fun direct(tv: Boolean=false, large: Boolean=false, action: (ActivityScenario<MainActivity>, DlnaSelectionDialog, DlnaDialogContent)->Unit) = fixture { scenario, _ ->
        lateinit var dialog: DlnaSelectionDialog
        lateinit var content: DlnaDialogContent
        lateinit var original: Configuration
        scenario.onActivity { activity ->
            original=Configuration(activity.resources.configuration)
            val config=Configuration(original).apply { if(large)fontScale=2f;if(tv)uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            content=DlnaDialogContent("D21 presentation", label=null, fileSelection=true,
                rows=mediaBrowserRows(activity,listOf(item("Episode.part.1.mp4"),item("Episode.part.1.m4v"),item(longName)),emptyList()),
                initialSelection=2, onClose={}, onSelected={ fail("Information must not select a media file") })
            dialog=DlnaSelectionDialog(activity,content);dialog.show()
        }
        try {
            BrowserTestUi.waitUntil { dialog.window!!.decorView.hasWindowFocus() && dialog.findViewById<ListView>(R.id.dlna_list).height>0 }
            action(scenario,dialog,content)
        } finally { scenario.onActivity { dialog.dismiss();@Suppress("DEPRECATION") it.resources.updateConfiguration(original,it.resources.displayMetrics) } }
    }
    private fun windows()=WindowInspector.getGlobalWindowViews().filter { it.findViewById<TextView>(R.id.dlna_title)!=null && it.isAttachedToWindow }
    private fun title(view:View)=view.findViewById<TextView>(R.id.dlna_title).text.toString()
    private fun browser()=windows().single { title(it)!=context.getString(R.string.info) }
    private fun info()=windows().single { title(it)==context.getString(R.string.info) }
    private fun awaitInfo()=BrowserTestUi.waitUntil { windows().any { title(it)==context.getString(R.string.info) && it.hasWindowFocus() } }
    private fun key(code:Int) { instrumentation.waitForIdleSync();SystemClock.sleep(500);val down=SystemClock.uptimeMillis();for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),action,code,0),true)) }
    private fun screenshot(name:String) { instrumentation.waitForIdleSync();SystemClock.sleep(600);File(context.filesDir,"d21-$name-${context.resources.configuration.screenWidthDp}dp.png").outputStream().use { assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)) } }
    private fun fixture(action:(ActivityScenario<MainActivity>,File)->Unit) {
        val prefs=listOf("saved_usb_folders","saved_dlna_folders","last_playback_folder","usb_browser").map { context.getSharedPreferences(it,Context.MODE_PRIVATE) };val before=prefs.map { it.all.toMap() }
        val root=File(context.filesDir,"d21-media-fixture").apply { deleteRecursively();mkdirs() }
        listOf("Episode.part.1.mp4","Episode.part.1.m4v").forEach { File(root,it).writeBytes(byteArrayOf(1)) }
        var touch=true
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { touch=it.window.decorView.isInTouchMode }
            action(scenario,root)
        } }
        finally {
            instrumentation.setInTouchMode(touch)
            prefs.zip(before).forEach { (p,values)->val editor=p.edit().clear();values.forEach { (k,v)->when(v) { is String->editor.putString(k,v);is Boolean->editor.putBoolean(k,v);is Int->editor.putInt(k,v);is Long->editor.putLong(k,v);is Float->editor.putFloat(k,v) } };editor.commit() }
            root.deleteRecursively()
        }
    }
}
