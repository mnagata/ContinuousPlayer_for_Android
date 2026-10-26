package jp.nagu.continuousplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class RegisteredRootNavigationTest {
    @Test fun phoneDlnaRootReturnsToOpenedBookmark() = root(Kind.DLNA)
    @Test fun tvDlnaRootReturnsToOpenedBookmark() = root(Kind.DLNA, tv=true)
    @Test fun phoneUsbRootReturnsToOpenedBookmark() = root(Kind.USB)
    @Test fun tvUsbRootReturnsToOpenedBookmark() = root(Kind.USB, tv=true)
    @Test fun phoneSafRootReturnsToOpenedBookmark() = root(Kind.SAF)
    @Test fun tvSafRootReturnsToOpenedBookmark() = root(Kind.SAF, tv=true)
    @Test fun dlnaBackspaceAtRootReturnsToSavedList() = root(Kind.DLNA, key=KeyEvent.KEYCODE_DEL)
    @Test fun usbBackspaceAtRootReturnsToSavedList() = root(Kind.USB, key=KeyEvent.KEYCODE_DEL)
    @Test fun safBackspaceAtRootReturnsToSavedList() = root(Kind.SAF, key=KeyEvent.KEYCODE_DEL)
    @Test fun tvBackAtDlnaRootReturnsToSavedList() = root(Kind.DLNA, tv=true, key=KeyEvent.KEYCODE_BACK)
    @Test fun tvBackAtUsbRootReturnsToSavedList() = root(Kind.USB, tv=true, key=KeyEvent.KEYCODE_BACK)
    @Test fun tvBackAtSafRootReturnsToSavedList() = root(Kind.SAF, tv=true, key=KeyEvent.KEYCODE_BACK)
    @Test fun dlnaParentReselectsChildBeyondFirstPage() = childParent(Kind.DLNA)
    @Test fun usbParentReselectsChildBeyondFirstPage() = childParent(Kind.USB)
    @Test fun safParentReselectsChildBeyondFirstPage() = childParent(Kind.SAF)
    @Test fun failedDlnaOpenReturnsToItsBookmark() = root(Kind.DLNA, offline=true)
    @Test fun failedUsbOpenReturnsToItsBookmark() = root(Kind.USB, offline=true)
    @Test fun failedSafOpenReturnsToItsBookmark() = root(Kind.SAF, offline=true)
    @Test fun distantDlnaBookmarkIsScrolledIntoViewOnReturn() = root(Kind.DLNA, far=true)
    @Test fun distantUsbBookmarkIsScrolledIntoViewOnReturn() = root(Kind.USB, far=true)
    @Test fun distantSafBookmarkIsScrolledIntoViewOnReturn() = root(Kind.SAF, far=true)
    @Test fun playingDlnaTraversalPreservesPlaybackUntilSavedListCloses() = retained(Kind.DLNA, true)
    @Test fun pausedDlnaTraversalKeepsPause() = retained(Kind.DLNA, false)
    @Test fun playingUsbTraversalPreservesPlaybackUntilSavedListCloses() = retained(Kind.USB, true)
    @Test fun pausedUsbTraversalKeepsPause() = retained(Kind.USB, false)
    @Test fun playingSafTraversalPreservesPlaybackUntilSavedListCloses() = retained(Kind.SAF, true)
    @Test fun pausedSafTraversalKeepsPause() = retained(Kind.SAF, false)
    @Test fun playbackDlnaUsesClosestRegisteredAncestor() = retained(Kind.DLNA, false, nested=true)
    @Test fun playbackUsbUsesClosestRegisteredAncestor() = retained(Kind.USB, false, nested=true)
    @Test fun unregisteredDlnaPlaybackDoesNotExposeServerParents() = retained(Kind.DLNA, false, unregistered=true)
    @Test fun selectedDlnaParentRemainsRootEvenWithChildBookmark() = root(Kind.DLNA, nested=true)
    @Test fun selectedUsbParentRemainsRootEvenWithChildBookmark() = root(Kind.USB, nested=true)
    @Test fun backgroundWhileTraversingKeepsPlaybackPausedOnCancel() = retained(Kind.DLNA, true, background=true)

    @Test fun sameNameDlnaAndUsbBookmarksReturnByIdentity() = withFixture { f ->
        val duplicate = f.usb.name
        SavedDlnaFolders(f.context).save(f.client.server, dlnaPath("target").map {
            if (it.id == "target") it.copy(title = duplicate) else it
        })
        openHome(f)
        for (kind in listOf(Kind.DLNA, Kind.USB)) {
            f.scenario.onActivity {
                val list = saved()!!.findViewById<ListView>(R.id.dlna_list)
                val index = (0 until list.count).single {
                    val row = list.getItemAtPosition(it) as DlnaRow
                    row.title == duplicate && row.detail.startsWith(f.context.getString(
                        if (kind == Kind.DLNA) R.string.dlna_dialog_label else R.string.saved_folder_usb))
                }
                list.performItemClick(null, index, list.adapter.getItemId(index))
            }
            if (kind == Kind.USB) finishUsbPermission(f)
            awaitTitle(duplicate);up(f);awaitSavedFocus(f, duplicate)
            f.scenario.onActivity {
                val row = saved()!!.findViewById<ListView>(R.id.dlna_list).selectedItem as DlnaRow
                assertTrue(row.detail.startsWith(f.context.getString(
                    if (kind == Kind.DLNA) R.string.dlna_dialog_label else R.string.saved_folder_usb)))
            }
        }
        assertLastUnchanged(f)
    }

    private enum class Kind { SAF, USB, DLNA }
    private fun root(kind:Kind,tv:Boolean=false,key:Int?=null,offline:Boolean=false,far:Boolean=false,nested:Boolean=false) = withFixture(tv,far,nested) { f ->
        val expected=if(offline && kind==Kind.SAF) f.context.getString(R.string.saf_unavailable_folder) else title(kind)
        if(offline && kind==Kind.DLNA)f.client.available=false
        if(offline && kind==Kind.USB)f.usb.deleteRecursively()
        openHome(f)
        val original=saved()!!
        select(f,expected)
        if(kind==Kind.USB)finishUsbPermission(f)
        if(offline) awaitError(f,kind) else awaitTitle(title(kind))
        f.scenario.onActivity { assertEquals(f.context.getString(R.string.saved_folder_list),window()!!.findViewById<View>(R.id.dlna_up).contentDescription.toString()) }
        if(key!=null) stroke(key) else up(f)
        awaitSavedFocus(f,expected)
        if(kind==Kind.DLNA)assertFalse(f.client.requests.any { it=="0" || it=="catalog" })
        assertLastUnchanged(f)
        if(!offline && kind!=Kind.USB)f.scenario.onActivity { assertSame(original,saved()) }
        if(far)screenshot(f,"far-${kind.name.lowercase()}")
    }
    private fun childParent(kind:Kind) = withFixture { f ->
        openHome(f);select(f,title(kind));if(kind==Kind.USB)finishUsbPermission(f)
        awaitTitle(title(kind));select(f,childTitle(kind));awaitTitle(childTitle(kind))
        f.scenario.onActivity { assertEquals(f.context.getString(R.string.usb_parent),window()!!.findViewById<View>(R.id.dlna_up).contentDescription.toString()) }
        up(f);awaitTitle(title(kind))
        BrowserTestUi.waitUntil { window()!!.findViewById<ListView>(R.id.dlna_list).let { (it.selectedItem as? DlnaRow)?.title==childTitle(kind) && it.selectedItemPosition in it.firstVisiblePosition..it.lastVisiblePosition } }
        up(f);awaitSavedFocus(f,title(kind));assertLastUnchanged(f)
    }
    private fun retained(kind:Kind,playing:Boolean,nested:Boolean=false,unregistered:Boolean=false,background:Boolean=false) = withFixture(nested=nested) { f ->
        if(unregistered)SavedDlnaFolders(f.context).remove(f.client.server.id,"target")
        val dir=File(f.usb,"Child 27/Grandchild")
        val size=8_000*2*90
        val wave=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36+size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        val videos=(0..2).map { val file=File(dir,"D20 Episode $it.wav").apply { writeBytes(wave) };VideoItem(file.toURI().toString(),file.name,file.length(),0,"audio/wav",dlnaObjectId="media-$it") }
        f.client.media=videos
        val folder:PlaybackFolder=when(kind) {
            Kind.DLNA->PlaybackFolder.Dlna(SavedDlnaFolder(f.client.server.id,f.client.server.name,dlnaPath("grandchild").map { SavedDlnaPath(it.id,it.title) }))
            Kind.USB->PlaybackFolder.Usb(f.root,dir)
            Kind.SAF->PlaybackFolder.Saf(listOf("season-24","season-24/collection-27","season-24/collection-27/nested").map { SafFolder(DocumentsContract.buildDocumentUriUsingTree(f.targetTree,it),if(it.endsWith("nested"))"Nested folder" else if(it.contains("collection"))"Collection 27" else "Season 24") })
        }
        f.scenario.onActivity {
            ViewModelProvider(it)[PlayerViewModel::class.java].apply { this.videos=videos;playbackFolder=folder }
            MainActivity::class.java.getDeclaredMethod("startPlayback",List::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(it,videos,1)
        }
        BrowserTestUi.waitUntil { player(f)?.isPlaying==true }
        val original=player(f)!!
        f.scenario.onActivity { original.seekTo(30_000);if(!playing)original.pause() }
        BrowserTestUi.waitUntil { original.playbackState==Player.STATE_READY }
        f.scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
        awaitTitle(if(kind==Kind.SAF)"Nested folder" else "Grandchild")
        if(background) { f.scenario.moveToState(Lifecycle.State.CREATED);f.scenario.moveToState(Lifecycle.State.RESUMED) }
        if(unregistered) {
            f.scenario.onActivity { assertEquals(f.context.getString(R.string.saved_folder_list),window()!!.findViewById<View>(R.id.dlna_up).contentDescription.toString()) }
            up(f);BrowserTestUi.waitUntil { saved()?.hasWindowFocus()==true }
        } else {
            up(f);awaitTitle(childTitle(kind))
            if(!nested || kind==Kind.SAF) { up(f);awaitTitle(title(kind)) }
            val expected=if(nested && kind!=Kind.SAF)"Child 27" else title(kind)
            f.scenario.onActivity { assertEquals(f.context.getString(R.string.saved_folder_list),window()!!.findViewById<View>(R.id.dlna_up).contentDescription.toString()) }
            up(f);awaitSavedFocus(f,expected)
        }
        f.scenario.onActivity {
            assertSame(original,player(f));assertFalse(original.playWhenReady);assertEquals(1,original.currentMediaItemIndex);assertEquals(3,original.mediaItemCount)
            assertEquals(folder,ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder)
            assertTrue(original.currentPosition in 29_000..33_000)
            saved()!!.findViewById<View>(R.id.dlna_close).performClick()
        }
        BrowserTestUi.waitUntil { saved()==null && original.playWhenReady==(playing && !background) }
        assertFalse(f.client.requests.any { it=="0" || it=="catalog" })
        assertTrue(File(dir,"D20 Episode 1.wav").exists())
    }
    private fun title(kind:Kind)=when(kind) { Kind.SAF->"Season 24";Kind.USB->"D20 registered USB";Kind.DLNA->"D20 registered DLNA" }
    private fun childTitle(kind:Kind)=if(kind==Kind.SAF)"Collection 27" else "Child 27"
    private fun dlnaPath(last:String):List<DlnaEntry> {
        val all=listOf(DlnaEntry("0","D20 server"),DlnaEntry("catalog","Outside catalog"),DlnaEntry("target","D20 registered DLNA"),DlnaEntry("child-27","Child 27"),DlnaEntry("grandchild","Grandchild"))
        return all.take(all.indexOfFirst { it.id==last }+1)
    }
    private fun openHome(f:Fixture) { f.scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).performClick() };BrowserTestUi.waitUntil { saved()?.let { it.hasWindowFocus() && it.findViewById<View>(R.id.dlna_refresh).isEnabled }==true } }
    private fun select(f:Fixture,title:String) = f.scenario.onActivity {
        val list=window()!!.findViewById<ListView>(R.id.dlna_list)
        val index=(0 until list.count).single { (list.getItemAtPosition(it) as DlnaRow).title==title }
        list.setSelectionFromTop(index,0);list.performItemClick(null,index,list.adapter.getItemId(index))
    }
    private fun finishUsbPermission(f:Fixture) {
        // An AVD has no USB volume. Preserve the real MainActivity return callbacks and simulate
        // the granted-settings result while browsing only controlled app-private directories.
        f.scenario.onActivity { activity ->
            val permission=WindowInspector.getGlobalWindowViews().firstOrNull { it.findViewById<TextView>(android.R.id.message)?.text.toString()==f.context.getString(R.string.usb_permission_message) }
            if(permission!=null) {
                val dialog=MainActivity::class.java.getDeclaredField("playbackModalDialogs").apply { isAccessible=true }.get(activity) as Set<*>
                dialog.filterIsInstance<AlertDialog>().first { it.window?.decorView===permission }.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                MainActivity::class.java.getDeclaredMethod("openUsbBrowser").apply { isAccessible=true }.invoke(activity)
            }
        }
    }
    private fun up(f:Fixture)=f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_up).performClick() }
    private fun awaitTitle(title:String)=BrowserTestUi.waitUntil { window()?.let { it.hasWindowFocus() && it.findViewById<TextView>(R.id.dlna_title).text.toString()==title && it.findViewById<ListView>(R.id.dlna_list).isEnabled }==true }
    private fun awaitError(f:Fixture,kind:Kind)=BrowserTestUi.waitUntil {
        window()?.let {
            val text=it.findViewById<TextView>(R.id.dlna_message).text.toString()
            it.hasWindowFocus() && (if(kind==Kind.DLNA)it.findViewById<TextView>(R.id.dlna_title).text==f.context.getString(R.string.dlna_error)
                else text.contains(f.context.getString(if(kind==Kind.USB)R.string.usb_read_error else R.string.saf_error_help)))
        }==true
    }
    private fun awaitSavedFocus(f:Fixture,title:String)=BrowserTestUi.waitUntil {
        saved()?.let { root->val list=root.findViewById<ListView>(R.id.dlna_list)
            root.hasWindowFocus() && (list.selectedItem as? DlnaRow)?.title==title && list.selectedItemPosition in list.firstVisiblePosition..list.lastVisiblePosition && list.selectedView?.findViewById<View>(R.id.saved_folder_open)?.hasFocus()==true
        }==true
    }
    private fun assertLastUnchanged(f:Fixture) { assertEquals(f.lastBefore,f.context.getSharedPreferences("last_playback_folder",Context.MODE_PRIVATE).all) }
    private fun windows()=WindowInspector.getGlobalWindowViews().filter { it.findViewById<View>(R.id.dlna_title)!=null }
    private fun window()=windows().singleOrNull()
    private fun saved()=windows().singleOrNull { it.findViewById<TextView>(R.id.dlna_title).text.toString()==InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.saf_choose_folder) }
    private fun player(f:Fixture):Player? { var p:Player?=null;f.scenario.onActivity { p=it.findViewById<PlayerView>(R.id.player_view).player };return p }
    private fun stroke(code:Int) { val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(300);val down=SystemClock.uptimeMillis();for(a in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))assertTrue(i.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),a,code,0),true)) }
    private fun screenshot(f:Fixture,name:String) { val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(600);File(f.context.filesDir,"d20-$name.png").outputStream().use { assertTrue(i.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)) } }
    private class Client:DlnaClient() {
        val server=DlnaServer("uuid:d20-target","D20 server","http://192.0.2.1/control","ContentDirectory:1")
        var available=true;var media=emptyList<VideoItem>();val requests=mutableListOf<String>()
        override suspend fun discover()=if(available)listOf(server) else emptyList()
        override suspend fun browse(server:DlnaServer,objectId:String):List<DlnaEntry> { requests+=objectId;return when(objectId) {
            "0"->listOf(DlnaEntry("catalog","Outside catalog"))
            "catalog"->listOf(DlnaEntry("target","D20 registered DLNA"))
            "target"->(0..39).map { DlnaEntry("child-%02d".format(it),"Child %02d".format(it)) }
            "child-27"->listOf(DlnaEntry("grandchild","Grandchild"))
            "grandchild"->media.mapIndexed { index,item->DlnaEntry("media-$index",item.displayName,item) }
            else->emptyList()
        } }
    }
    private data class Fixture(val scenario:ActivityScenario<MainActivity>,val context:Context,val root:File,val usb:File,val targetTree:Uri,val client:Client,val lastBefore:Map<String,*>)
    private fun withFixture(tv:Boolean=false,far:Boolean=false,nested:Boolean=false,action:(Fixture)->Unit) {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val resolver=context.contentResolver;val authority=ScrollFixtureProvider.tree.authority!!
        val grants=resolver.persistedUriPermissions.toList();assumeTrue("Only fixture-owned tree permissions may be changed",grants.all { it.uri.authority==authority })
        val prefs=listOf("saved_dlna_folders","saved_usb_folders","last_playback_folder","usb_browser").map { context.getSharedPreferences(it,Context.MODE_PRIVATE) };val before=prefs.map { it.all.toMap() }
        val tree=DocumentsContract.buildTreeDocumentUri(authority,"season-24")
        val trees=(if(far)(0..34).toList() else listOf(0,24)).map { DocumentsContract.buildTreeDocumentUri(authority,"season-%02d".format(it)) } + DocumentsContract.buildTreeDocumentUri(authority,"d19-offline")
        val root=File(context.filesDir,"d20-boundary-fixture").apply { deleteRecursively();mkdirs() };val usb=File(root,"D20 registered USB").apply { mkdirs() }
        for(n in 0..39)File(usb,"Child %02d".format(n)).mkdirs();File(usb,"Child 27/Grandchild").mkdirs()
        var touch=true
        fun clear() { resolver.persistedUriPermissions.filter { it.uri.authority==authority }.forEach { resolver.releasePersistableUriPermission(it.uri,(if(it.isReadPermission)1 else 0) or (if(it.isWritePermission)2 else 0)) } }
        fun grant(uris:List<Uri>,flags:Int=1) { context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test",ScrollFixtureGrantActivity::class.java.name).putExtra("target",context.packageName).putExtra("trees",uris.map { it.toString() }.toTypedArray()).putExtra("accessFlags",flags).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));BrowserTestUi.waitUntil { runCatching { uris.forEach { resolver.takePersistableUriPermission(it,flags) } }.isSuccess } }
        try {
            clear();prefs.forEach { it.edit().clear().commit() };grant(trees)
            val client=Client()
            SavedDlnaFolders(context).save(DlnaServer("uuid:d20-other","AAA other server","http://192.0.2.1/control","ContentDirectory:1"),listOf(DlnaEntry("0","AAA other NAS")))
            SavedDlnaFolders(context).save(client.server,dlnaPath("target"))
            SavedUsbFolders(context).save(root,File(root,"AAA other USB"));SavedUsbFolders(context).save(root,usb)
            if(nested) { SavedDlnaFolders(context).save(client.server,dlnaPath("child-27"));SavedUsbFolders(context).save(root,File(usb,"Child 27")) }
            val other=trees.first();LastPlaybackFolder(context).save(PlaybackFolder.Saf(listOf(SafFolder(DocumentsContract.buildDocumentUriUsingTree(other,DocumentsContract.getTreeDocumentId(other)),"Season 00"))))
            val last=prefs[2].all.toMap()
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var config:Configuration
                scenario.onActivity {
                    config=Configuration(it.resources.configuration);touch=it.findViewById<View>(R.id.btn_select_folder).isInTouchMode
                    if(tv) { @Suppress("DEPRECATION") it.resources.updateConfiguration(Configuration(config).apply { uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION },it.resources.displayMetrics) }
                    MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible=true }.set(it,DlnaBrowser(it,client) { _,_->fail("Browsing must not start playback") })
                }
                i.setInTouchMode(false)
                try { action(Fixture(scenario,context,root,usb,tree,client,last)) }
                finally { scenario.onActivity { @Suppress("DEPRECATION") it.resources.updateConfiguration(config,it.resources.displayMetrics) } }
            }
        } finally {
            i.setInTouchMode(touch);clear();grants.forEach { grant(listOf(it.uri),(if(it.isReadPermission)1 else 0) or (if(it.isWritePermission)2 else 0)) }
            prefs.zip(before).forEach { (p,values)->val e=p.edit().clear();values.forEach { (k,v)->when(v) { is String->e.putString(k,v);is Set<*>->e.putStringSet(k,v.filterIsInstance<String>().toSet());is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v) } };e.commit() };root.deleteRecursively()
        }
    }
}
