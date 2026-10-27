package jp.nagu.continuousplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
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
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserLoadingTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private enum class Source { SAF, USB_REGISTER, USB_PLAYBACK, DLNA }

    @Test fun initialSafBackCancelsAndIgnoresLateSuccess()=cancel(Source.SAF)
    @Test fun initialUsbRegistrationBackCancelsAndIgnoresLateSuccess()=cancel(Source.USB_REGISTER)
    @Test fun initialUsbPlaybackBackCancelsAndIgnoresLateSuccess()=cancel(Source.USB_PLAYBACK)
    @Test fun initialDlnaBackCancelsAndIgnoresLateSuccess()=cancel(Source.DLNA)
    @Test fun initialSafBackspaceIgnoresLateFailure()=cancel(Source.SAF,true)
    @Test fun initialUsbRegistrationBackspaceIgnoresLateFailure()=cancel(Source.USB_REGISTER,true)
    @Test fun initialUsbPlaybackBackspaceIgnoresLateFailure()=cancel(Source.USB_PLAYBACK,true)
    @Test fun initialDlnaBackspaceIgnoresLateFailure()=cancel(Source.DLNA,true)
    @Test fun slowSafKeepsListAndShowsIndicator()=preserve(Source.SAF)
    @Test fun slowUsbRegistrationKeepsListAndShowsIndicator()=preserve(Source.USB_REGISTER)
    @Test fun slowUsbPlaybackKeepsListAndShowsIndicator()=preserve(Source.USB_PLAYBACK)
    @Test fun slowDlnaKeepsListAndShowsIndicator()=preserve(Source.DLNA)
    @Test fun fastSafDoesNotLeaveDelayedIndicator()=preserve(Source.SAF,true)
    @Test fun fastUsbRegistrationDoesNotLeaveDelayedIndicator()=preserve(Source.USB_REGISTER,true)
    @Test fun fastUsbPlaybackDoesNotLeaveDelayedIndicator()=preserve(Source.USB_PLAYBACK,true)
    @Test fun fastDlnaDoesNotLeaveDelayedIndicator()=preserve(Source.DLNA,true)

    @Test fun safCancelButtonRemainsUsableDuringLoading()=cancel(Source.SAF,true,true)
    @Test fun usbRegistrationCancelButtonRemainsUsableDuringLoading()=cancel(Source.USB_REGISTER,true,true)
    @Test fun usbPlaybackCancelButtonRemainsUsableDuringLoading()=cancel(Source.USB_PLAYBACK,true,true)
    @Test fun dlnaCancelButtonRemainsUsableDuringLoading()=cancel(Source.DLNA,true,true)

    private fun cancel(source:Source,fail:Boolean=false,button:Boolean=false)=fixture(source) { f ->
        if(button) {
            f.scenario.onActivity { f.open(it) }
            BrowserTestUi.waitUntil { window()?.findViewById<ListView>(R.id.dlna_list)?.let { it.isEnabled && it.count>25 }==true }
            f.prepare(initial=false,fail=fail)
            f.scenario.onActivity {
                val view=window()!!;val list=view.findViewById<ListView>(R.id.dlna_list)
                if(source==Source.SAF || source==Source.DLNA)list.performItemClick(null,25,list.adapter.getItemId(25))
                else view.findViewById<View>(R.id.dlna_refresh).performClick()
            }
        } else {
            f.prepare(initial=true,fail=fail)
            f.scenario.onActivity { f.open(it) }
        }
        BrowserTestUi.waitUntil { f.started() && window()?.hasWindowFocus()==true }
        if(button)BrowserTestUi.waitUntil { window()!!.findViewById<View>(R.id.dlna_pending_progress).isShown }
        val request=f.request()
        f.scenario.onActivity {
            val view=window()!!
            assertTrue(view.findViewById<View>(if(button)R.id.dlna_pending_progress else R.id.dlna_progress).isShown)
            assertTrue(view.findViewById<View>(R.id.dlna_close).isEnabled)
            assertFalse(view.findViewById<View>(R.id.dlna_up).isEnabled)
            assertFalse(view.findViewById<View>(R.id.dlna_refresh).isEnabled)
            assertFalse(view.findViewById<View>(R.id.dlna_register).isEnabled)
            if(!button)assertTrue(view.findViewById<View>(R.id.dlna_close).hasFocus())
        }
        if(button) {
            var label=""
            f.scenario.onActivity { label=window()!!.findViewById<TextView>(R.id.dlna_close).text.toString() }
            BrowserTestUi.tap(label)
        } else key(if(fail)KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { window()==null && f.cancelled==1 }
        assertTrue(request.isCancelled)
        f.release()
        BrowserTestUi.waitUntil { request.isCompleted }
        instrumentation.waitForIdleSync()
        assertNull(window());assertEquals(1,f.cancelled);assertEquals(0,f.selected)
    }

    private fun preserve(source:Source,fast:Boolean=false)=fixture(source) { f ->
        f.scenario.onActivity { f.open(it) }
        BrowserTestUi.waitUntil { window()?.findViewById<ListView>(R.id.dlna_list)?.let { it.isEnabled && it.count>25 }==true }
        f.scenario.onActivity { window()!!.findViewById<ListView>(R.id.dlna_list).apply { requestFocusFromTouch();setSelectionFromTop(25,0) } }
        BrowserTestUi.waitUntil { window()!!.findViewById<ListView>(R.id.dlna_list).selectedItemPosition==25 }
        lateinit var view:View
        lateinit var adapter:android.widget.ListAdapter
        var first=0;var top=0;var size=0 to 0;var title=""
        f.prepare(initial=false)
        f.scenario.onActivity {
            view=window()!!;val list=view.findViewById<ListView>(R.id.dlna_list)
            adapter=list.adapter;first=list.firstVisiblePosition;top=list.getChildAt(0).top
            size=view.width to view.height;title=view.findViewById<TextView>(R.id.dlna_title).text.toString()
            if(source==Source.SAF || source==Source.DLNA) list.performItemClick(list.selectedView,25,list.adapter.getItemId(25))
            else view.findViewById<View>(R.id.dlna_refresh).performClick()
            assertFalse(list.isEnabled)
            assertEquals(View.GONE,view.findViewById<View>(R.id.dlna_pending_progress).visibility)
            if(fast)f.release()
        }
        val request=f.request()
        if(!fast) {
            BrowserTestUi.waitUntil { f.started() }
            BrowserTestUi.waitUntil { view.findViewById<View>(R.id.dlna_pending_progress).isShown }
            f.scenario.onActivity {
                assertSame(view,window());val list=view.findViewById<ListView>(R.id.dlna_list)
                assertSame(adapter,list.adapter);assertEquals(first,list.firstVisiblePosition);assertEquals(top,list.getChildAt(0).top)
                assertEquals(size,view.width to view.height);assertEquals(title,view.findViewById<TextView>(R.id.dlna_title).text.toString())
                assertFalse(list.isEnabled);assertTrue(view.findViewById<View>(R.id.dlna_close).isEnabled)
                view.findViewById<View>(R.id.dlna_register).performClick()
                view.findViewById<View>(R.id.dlna_refresh).performClick()
                view.findViewById<View>(R.id.dlna_up).performClick()
                view.findViewById<View>(R.id.dlna_details).performClick()
                list.performItemClick(list.getChildAt(0),25,list.adapter.getItemId(25))
                assertSame(request,f.request());assertEquals(0,f.selected)
            }
            screenshot("loading-${source.name.lowercase()}")
            f.release()
        }
        BrowserTestUi.waitUntil { request.isCompleted && window()?.findViewById<ListView>(R.id.dlna_list)?.isEnabled==true }
        SystemClock.sleep(600)
        f.scenario.onActivity {
            assertSame(view,window());assertEquals(View.GONE,view.findViewById<View>(R.id.dlna_pending_progress).visibility)
            assertEquals(0,f.selected);assertEquals(0,f.cancelled)
        }
    }

    @Test fun initialLoadingAndUpdatedLoadingRouteBackToCancel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(update in listOf(false,true)) {
                lateinit var dialog:DlnaSelectionDialog
                var up=0;var cancelled=0;var actions=0
                scenario.onActivity { activity ->
                    val content=DlnaDialogContent("D22 initial",loading=true,onUp={up++},onRefresh={actions++},onRegister={actions++},onClose={cancelled++})
                    dialog=DlnaSelectionDialog(activity,if(update)content.copy(loading=false) else content);dialog.show()
                    if(update)dialog.updateContent(content)
                    assertFalse(dialog.findViewById<View>(R.id.dlna_up).isEnabled)
                    dialog.findViewById<View>(R.id.dlna_up).performClick()
                    dialog.findViewById<View>(R.id.dlna_refresh).performClick()
                    dialog.findViewById<View>(R.id.dlna_register).performClick()
                }
                key(KeyEvent.KEYCODE_BACK)
                assertEquals(0,up);assertEquals(0,actions);assertEquals(1,cancelled)
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test fun televisionInitialLoadingFocusesCancelAndEscapeClosesOnlyBrowser() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original:Configuration
            lateinit var dialog:DlnaSelectionDialog
            var up=0;var cancelled=0
            scenario.onActivity { activity ->
                original=Configuration(activity.resources.configuration)
                val tv=Configuration(original).apply { uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }
                @Suppress("DEPRECATION") activity.resources.updateConfiguration(tv,activity.resources.displayMetrics)
                dialog=DlnaSelectionDialog(activity,DlnaDialogContent("D22 TV",loading=true,onUp={up++},onClose={cancelled++}));dialog.show()
            }
            try {
                BrowserTestUi.waitUntil { dialog.findViewById<View>(R.id.dlna_close).hasFocus() }
                key(KeyEvent.KEYCODE_ESCAPE)
                assertFalse(dialog.isShowing);assertEquals(0,up);assertEquals(1,cancelled)
            } finally { scenario.onActivity { dialog.dismiss();@Suppress("DEPRECATION") it.resources.updateConfiguration(original,it.resources.displayMetrics) } }
        }
    }

    @Test fun defaultIndicatorWaitsHalfSecondAndKeepsDeadlineAcrossConfiguration() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog:DlnaSelectionDialog
            lateinit var content:DlnaDialogContent
            var began=0L
            scenario.onActivity {
                content=DlnaDialogContent("D22 timing",rows=(0..39).map { DlnaRow("File $it",R.drawable.ic_play) },onClose={})
                dialog=DlnaSelectionDialog(it,content);dialog.show();began=SystemClock.uptimeMillis()
                assertTrue(dialog.keepListWhileLoading())
            }
            SystemClock.sleep(150)
            scenario.onActivity {
                if(SystemClock.uptimeMillis()-began<500)assertEquals(View.GONE,dialog.findViewById<View>(R.id.dlna_pending_progress).visibility)
                dialog.refreshForConfiguration()
            }
            BrowserTestUi.waitUntil { dialog.findViewById<View>(R.id.dlna_pending_progress).isShown }
            assertTrue(SystemClock.uptimeMillis()-began>=500)
            scenario.onActivity { dialog.updateContent(content);assertTrue(dialog.keepListWhileLoading());dialog.updateContent(content) }
            SystemClock.sleep(600)
            scenario.onActivity { assertEquals(View.GONE,dialog.findViewById<View>(R.id.dlna_pending_progress).visibility);dialog.dismiss() }
        }
    }

    @Test fun dismissedAndReplacedRowsRejectStaleSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var selected=0
                val content=DlnaDialogContent("D22 stale",rows=listOf(DlnaRow("Old",R.drawable.ic_play)),onClose={},onSelected={selected++})
                val dialog=DlnaSelectionDialog(activity,content);dialog.show()
                val oldList=dialog.findViewById<ListView>(R.id.dlna_list)
                val oldListener=oldList.onItemClickListener
                dialog.updateContent(content.copy(rows=listOf(DlnaRow("New",R.drawable.ic_play)),onSelected={selected+=100}))
                oldListener!!.onItemClick(oldList,null,0,0)
                assertEquals(0,selected)
                val activeList=dialog.findViewById<ListView>(R.id.dlna_list)
                val listener=activeList.onItemClickListener
                dialog.dismiss();listener!!.onItemClick(oldList,null,0,0)
                assertEquals(0,selected)
            }
        }
    }

    @Test fun playingLoadingCancelRestoresSession()=retained()
    @Test fun pausedLoadingCancelKeepsSessionPaused()=retained(paused=true)
    @Test fun backgroundWhileLoadingPreventsAutomaticResume()=retained(background=true)
    private fun retained(paused:Boolean=false,background:Boolean=false)=fixture(Source.DLNA) { f ->
        val bytes=ByteArray(8_000*2*90)
        val wave=ByteBuffer.allocate(bytes.size+44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(bytes.size+36)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.size).put(bytes).array()
        val file=File(f.root,"retained.wav").apply { writeBytes(wave) }
        val media=listOf(VideoItem(file.toURI().toString(),file.name,file.length(),0,"audio/wav",dlnaObjectId="media"))
        lateinit var player:Player
        f.prepare(initial=true,fail=true)
        f.scenario.onActivity { activity ->
            val browser=DlnaBrowser(activity,f.client,onCancelled={ MainActivity::class.java.getDeclaredMethod("cancelPlaybackSelection").apply { isAccessible=true }.invoke(activity) }) { _,_->fail("Unexpected playback") }
            f.owner=browser;f.close=browser::close
            MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible=true }.set(activity,browser)
            ViewModelProvider(activity)[PlayerViewModel::class.java].apply { videos=media;playbackFolder=PlaybackFolder.Dlna(f.savedDlna()) }
            MainActivity::class.java.getDeclaredMethod("startPlayback",List::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,media,0)
        }
        BrowserTestUi.waitUntil { var ready=false;f.scenario.onActivity { player=it.findViewById<PlayerView>(R.id.player_view).player!!;ready=player.isPlaying };ready }
        f.scenario.onActivity { player.seekTo(30_000);if(paused)player.pause();it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
        BrowserTestUi.waitUntil { f.started() && window()?.hasWindowFocus()==true }
        val request=f.request()
        if(background) { f.scenario.moveToState(Lifecycle.State.CREATED);f.scenario.moveToState(Lifecycle.State.RESUMED) }
        key(KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { window()==null && player.playWhenReady==(!paused && !background) }
        f.release();BrowserTestUi.waitUntil { request.isCompleted }
        f.scenario.onActivity {
            assertSame(player,it.findViewById<PlayerView>(R.id.player_view).player)
            assertEquals(1,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex)
            assertTrue(player.currentPosition in 29_000..34_000)
            assertEquals(PlaybackFolder.Dlna(f.savedDlna()),ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder)
        }
        assertNull(window())
    }

    private class Gate(val fail:Boolean=false) {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        fun await() { started.countDown();check(release.await(15,TimeUnit.SECONDS)) { "D22 gate timed out" };if(fail)throw IOException("D22 late I/O error") }
    }
    private class BlockingFile(file:File):File(file.path) {
        @Volatile var gate:Gate?=null
        override fun listFiles():Array<File>? { gate?.await();return super.listFiles() }
    }
    private class Client:DlnaClient() {
        val server=DlnaServer("uuid:d22","D22 NAS","http://192.0.2.1/control","ContentDirectory:1")
        @Volatile var gate:Gate?=null
        override suspend fun discover()=listOf(server)
        override suspend fun browse(server:DlnaServer,objectId:String):List<DlnaEntry> {
            gate?.await()
            return if(objectId=="0") (0..39).map { DlnaEntry("child-%02d".format(it),"Child %02d".format(it)) }
            else listOf(DlnaEntry("media","Sample OP.mp4",VideoItem("http://192.0.2.1/media.mp4","Sample OP.mp4",1,0,"video/mp4")))
        }
    }
    private inner class Fixture(val source:Source,val scenario:ActivityScenario<MainActivity>,val root:File,val usb:BlockingFile) {
        val client=Client();var gate:Gate?=null;lateinit var owner:Any;var close:()->Unit={};var cancelled=0;var selected=0
        val tree=DocumentsContract.buildTreeDocumentUri(ScrollFixtureProvider.tree.authority,"d22-source")
        fun savedDlna()=SavedDlnaFolder(client.server.id,client.server.name,listOf(SavedDlnaPath("0","D22 NAS")))
        fun prepare(initial:Boolean,fail:Boolean=false) {
            if(source==Source.SAF) context.contentResolver.call(tree,"d22-prepare",if(initial)"d22-source" else "d22-source/child-25",Bundle().apply { putBoolean("fail",fail) })
            else { gate=Gate(fail);usb.gate=gate;client.gate=gate }
        }
        fun release() {
            if(source==Source.SAF) context.contentResolver.call(tree,"d22-release",null,null)
            else gate?.release?.countDown()
        }
        fun started()=if(source==Source.SAF)context.contentResolver.call(tree,"d22-state",null,null)?.getBoolean("started")==true else gate?.started?.count==0L
        fun request()=owner.javaClass.getDeclaredField("job").apply { isAccessible=true }.get(owner) as Job
        fun open(activity:MainActivity) {
            when(source) {
                Source.SAF -> SafFileBrowser(activity,{},{},{},onCancelled={cancelled++}) { _,_->selected++ }.also {
                    owner=it;close=it::close;it.openFolder(PlaybackFolder.Saf(listOf(SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree,"d22-source"),"D22 SAF"))))
                }
                Source.USB_REGISTER -> UsbFileBrowser(activity,onCancelled={cancelled++}) { _,_->selected++ }.also {
                    owner=it;close=it::close;it.openFolder(PlaybackFolder.Usb(root,usb),forRegistration=true)
                }
                Source.USB_PLAYBACK -> UsbPlaybackBrowser(activity,onCancelled={cancelled++}) { _,_,_->selected++ }.also {
                    owner=it;close=it::close;it.open(PlaybackFolder.Usb(root,usb),null,null) {}
                }
                Source.DLNA -> DlnaBrowser(activity,client,onCancelled={cancelled++}) { _,_->selected++ }.also {
                    owner=it;close=it::close;it.openSaved(savedDlna()) {}
                }
            }
        }
    }
    private fun fixture(source:Source,action:(Fixture)->Unit) {
        val prefs=listOf("saved_usb_folders","saved_dlna_folders","last_playback_folder","usb_browser").map { context.getSharedPreferences(it,Context.MODE_PRIVATE) };val before=prefs.map { it.all.toMap() }
        val root=File(context.filesDir,"d22-loading-fixture").apply { deleteRecursively();mkdirs() }
        val usb=BlockingFile(File(root,"D22 USB").apply { mkdirs() });for(i in 0..39)File(usb,"Child %02d".format(i)).mkdirs()
        File(usb,"Sample OP.mp4").writeBytes(byteArrayOf(1))
        val tree=DocumentsContract.buildTreeDocumentUri(ScrollFixtureProvider.tree.authority,"d22-source")
        val oldGrant=context.contentResolver.persistedUriPermissions.firstOrNull { it.uri==tree }
        var scenario:ActivityScenario<MainActivity>?=null;var fixture:Fixture?=null;var touch=true
        try {
            if(source==Source.SAF && oldGrant==null) {
                context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test",ScrollFixtureGrantActivity::class.java.name)
                    .putExtra("target",context.packageName).putExtra("trees",arrayOf(tree.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                BrowserTestUi.waitUntil { runCatching { context.contentResolver.takePersistableUriPermission(tree,1) }.isSuccess }
            }
            scenario=ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { touch=it.window.decorView.isInTouchMode }
            fixture=Fixture(source,scenario,root,usb);action(fixture)
        } finally {
            scenario?.onActivity { fixture?.close?.invoke() }
            fixture?.release();scenario?.close();instrumentation.setInTouchMode(touch)
            if(source==Source.SAF && oldGrant==null)context.contentResolver.releasePersistableUriPermission(tree,1)
            prefs.zip(before).forEach { (p,values)->val edit=p.edit().clear();values.forEach { (k,v)->when(v) { is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v) } };edit.commit() }
            root.deleteRecursively()
        }
    }
    private fun window()=WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<TextView>(R.id.dlna_title)!=null && it.isAttachedToWindow }
    private fun key(code:Int) { instrumentation.waitForIdleSync();SystemClock.sleep(500);val down=SystemClock.uptimeMillis();for(a in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),a,code,0),true)) }
    private fun screenshot(name:String) { instrumentation.waitForIdleSync();SystemClock.sleep(100);File(context.filesDir,"d22-$name.png").outputStream().use { assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)) } }
}
