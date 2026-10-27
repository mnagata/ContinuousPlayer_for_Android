package jp.nagu.continuousplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SavedFolderRemovalTest {
    @Test fun phoneLongPressCanRemoveSafAndFocusNextRow() = remove("Season 00", "Season 01")
    @Test fun tvLongPressCanRemoveSafAndFocusNextRow() = remove("Season 00", "Season 01", tv = true)
    @Test fun phoneOfflineDlnaRemovalFocusesNextRowWithoutConnecting() = remove("D19 NAS A", "D19 NAS B")
    @Test fun tvOfflineDlnaRemovalFocusesNextRowWithoutConnecting() = remove("D19 NAS A", "D19 NAS B", tv = true)
    @Test fun phoneLastUsbRemovalFocusesPreviousRow() = remove("D19 USB Z offline", "D19 USB A")
    @Test fun tvLastUsbRemovalFocusesPreviousRow() = remove("D19 USB Z offline", "D19 USB A", tv = true)
    @Test fun lastSafRowRemovalSelectsFirstDlnaRow() = remove("Season 01", "D19 NAS A")
    @Test fun lastDlnaRowRemovalSelectsFirstUsbRow() = remove("D19 NAS B", "D19 USB A")
    @Test fun singleSafRemovalFocusesAdd() = remove("Season 00", null, only = "saf")
    @Test fun singleDlnaRemovalFocusesAdd() = remove("D19 NAS A", null, only = "dlna")
    @Test fun singleUsbRemovalFocusesAddAndKeepsFile() = remove("D19 USB A", null, only = "usb")
    @Test fun offlineSafRegistrationCanBeRemovedWithoutReadingTheFolder() = remove(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.saf_unavailable_folder), null, only = "offline-saf")
    @Test fun tvRowsAllowVerticalNavigationWithoutOtherActions() = withFixture(tv = true) { f ->
        open(f);val row = visibleRow(f, "D19 NAS A")
        f.scenario.onActivity { row.findViewById<View>(R.id.saved_folder_open).requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        BrowserTestUi.waitUntil { (saved()!!.findViewById<ListView>(R.id.dlna_list).selectedItem as? DlnaRow)?.title == "D19 NAS B" }
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocus(f, "D19 NAS A", 6)
        assertEquals(0, f.client.requests)
    }
    @Test fun safConfirmationCanBeCancelledWithoutReleasingPermission() = cancel("Season 00")
    @Test fun dlnaConfirmationCanBeCancelledWithoutConnecting() = cancel("D19 NAS A")
    @Test fun usbConfirmationCanBeCancelledWithoutRemovingBookmark() = cancel("D19 USB A")
    @Test fun tvDecisionLongPressOpensConfirmationWithoutOpeningFolder() = withFixture(tv = true) { f ->
        open(f);val row = visibleRow(f, "D19 NAS A")
        f.scenario.onActivity { row.findViewById<View>(R.id.saved_folder_open).requestFocus() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, down,
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD), true))
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 200)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD), true))
        awaitConfirmation(f)
        f.scenario.onActivity { assertEquals(R.id.dlna_close, confirmation()!!.findFocus()!!.id) }
        key(KeyEvent.KEYCODE_DPAD_CENTER);awaitFocus(f, "D19 NAS A", 6)
        assertEquals(0, f.client.requests)
    }
    @Test fun savedRowExposesOpenAndAccessibleLongClickWithoutMoreButton() = withFixture { f ->
        open(f);val row = visibleRow(f, "D19 NAS A")
        f.scenario.onActivity {
            val main = row.findViewById<View>(R.id.saved_folder_open)
            val node = main.createAccessibilityNodeInfo()
            assertEquals("android.widget.Button", node.className.toString())
            assertTrue(main.contentDescription.toString().startsWith("D19 NAS A。"))
            assertTrue(main.isClickable && main.isLongClickable && main.isFocusable && main.isImportantForAccessibility)
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label == "登録解除" })
            assertEquals(1, (row as android.view.ViewGroup).childCount)
            val bounds = Rect();assertTrue(main.getGlobalVisibleRect(bounds))
            assertEquals(row.width, main.width)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val node = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("D19 NAS A").first { it.isLongClickable }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        awaitConfirmation(f)
        assertEquals(0, f.client.requests)
    }
    @Test fun longPressStillOpensConfirmationWithoutOpeningFolder() = withFixture { f ->
        open(f);visibleRow(f, "D19 NAS A");BrowserTestUi.tap("D19 NAS A", longPress = true)
        awaitConfirmation(f);assertEquals(0, f.client.requests)
    }
    @Test fun secondaryClickStillOpensOnlyOneConfirmation() = withFixture { f ->
        open(f);val row = visibleRow(f, "D19 NAS A")
        pointer(row.findViewById(R.id.saved_folder_open))
        awaitConfirmation(f)
        f.scenario.onActivity { assertEquals(2, browserWindows().size) };assertEquals(0, f.client.requests)
    }
    @Test fun closedConfirmationCannotRemoveItsOldTarget() = withFixture { f ->
        open(f);confirmFromLongPress(f, "D19 NAS A")
        lateinit var old: View
        f.scenario.onActivity { old = confirmation()!!.findViewById(R.id.dlna_register);confirmation()!!.findViewById<View>(R.id.dlna_close).performClick() }
        awaitFocus(f, "D19 NAS A", 6)
        f.scenario.onActivity { old.performClick() }
        assertEquals(2, SavedDlnaFolders(f.context).all().size)
    }
    @Test fun loadingSavedFolderDisablesLongPressAndRejectsDelayedAction() = withFixture { f ->
        f.client.delayed = true
        open(f);val row = visibleRow(f, "D19 NAS A")
        val main = row.findViewById<View>(R.id.saved_folder_open)
        f.scenario.onActivity { main.performClick() }
        BrowserTestUi.waitUntil { f.client.requests == 1 && !main.isEnabled }
        f.scenario.onActivity { main.performLongClick();assertNull(confirmation()) }
        key(KeyEvent.KEYCODE_BACK);BrowserTestUi.waitUntil { browserWindows().isEmpty() }
        assertEquals(2, SavedDlnaFolders(f.context).all().size)
    }
    @Test fun repeatedRemovalsEndAtAddWithoutDeletingFiles() = withFixture { f ->
        open(f)
        for (left in 6 downTo 1) {
            var title = ""
            f.scenario.onActivity { title = (saved()!!.findViewById<ListView>(R.id.dlna_list).getItemAtPosition(0) as DlnaRow).title }
            confirmFromLongPress(f, title);approve(f);awaitCount(f, left - 1)
        }
        f.scenario.onActivity { assertEquals(R.id.dlna_refresh, saved()!!.findFocus()!!.id) }
        assertTrue(f.sentinel.exists());assertEquals(0, f.client.requests)
    }
    @Test fun playingFolderCanBeUnregisteredWhileSessionIsRetained() = retained(playing = true)
    @Test fun pausedFolderCanBeUnregisteredWithoutResuming() = retained(playing = false)
    @Test fun backgroundDuringConfirmationKeepsRetainedPlaybackPaused() = retained(playing = true, background = true)
    @Test fun largeFontRowAndConfirmationRemainReachable() = withFixture(font = 2f) { f ->
        open(f);val row = visibleRow(f, "D19 USB A")
        f.scenario.onActivity {
            val main = row.findViewById<View>(R.id.saved_folder_open);val bounds = Rect()
            assertTrue(main.getGlobalVisibleRect(bounds));assertEquals(main.height, bounds.height());assertEquals(main.width, bounds.width())
        }
        screenshot(f, "saved-font200")
        confirmFromLongPress(f, "D19 USB A");screenshot(f, "confirmation-font200")
        f.scenario.onActivity {
            for (id in listOf(R.id.dlna_register, R.id.dlna_close)) {
                val v = confirmation()!!.findViewById<TextView>(id);val bounds=Rect()
                assertTrue(v.getGlobalVisibleRect(bounds));assertEquals(v.height,bounds.height())
                assertTrue(v.layout.height <= v.height - v.compoundPaddingTop - v.compoundPaddingBottom)
            }
        }
        approve(f);awaitCount(f, 5);assertTrue(f.sentinel.exists())
    }

    private fun remove(title: String, next: String?, tv: Boolean = false, only: String? = null) = withFixture(tv, only) { f ->
        open(f)
        val before = f.context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE).all.toMap()
        confirmFromLongPress(f, title)
        f.scenario.onActivity {
            assertEquals(f.context.getString(R.string.folder_unregister_message), confirmation()!!.findViewById<TextView>(R.id.dlna_message).text.toString())
            assertEquals(R.id.dlna_close, confirmation()!!.findFocus()!!.id)
        }
        if (!tv && title == "D19 NAS A") screenshot(f, "confirmation")
        approve(f)
        if (next != null) awaitFocus(f, next, 5)
        else { awaitCount(f, 0);f.scenario.onActivity { assertEquals(R.id.dlna_refresh, saved()!!.findFocus()!!.id) } }
        assertTrue(f.sentinel.exists())
        assertEquals(before, f.context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE).all)
        assertEquals(0, f.client.requests)
        if (only == "offline-saf") assertFalse(f.context.contentResolver.persistedUriPermissions.any { it.uri == f.trees[0] })
        if (title == "Season 00") {
            assertFalse(f.context.contentResolver.persistedUriPermissions.any { it.uri == f.trees[0] })
            if (only == null) assertTrue(f.context.contentResolver.persistedUriPermissions.single { it.uri == f.trees[1] }.let { it.isReadPermission && it.isWritePermission })
        }
        if (!tv && title == "D19 NAS A") screenshot(f, "after-removal")
    }
    private fun cancel(title: String) = withFixture { f ->
        open(f)
        for (action in listOf("close", "back", "escape")) {
            confirmFromLongPress(f, title)
            if (action == "close") f.scenario.onActivity { confirmation()!!.findViewById<View>(R.id.dlna_close).performClick() }
            else key(if (action == "back") KeyEvent.KEYCODE_BACK else KeyEvent.KEYCODE_ESCAPE)
            awaitFocus(f, title, 6)
        }
        assertEquals(2, f.context.contentResolver.persistedUriPermissions.size)
        assertEquals(2, SavedDlnaFolders(f.context).all().size);assertEquals(2, SavedUsbFolders(f.context).all().size)
        assertTrue(f.sentinel.exists());assertEquals(0, f.client.requests)
    }
    private fun retained(playing: Boolean, background: Boolean = false) = withFixture { f ->
        val size = 8_000 * 2 * 90
        val wave = ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36+size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        val media = (0..2).map { val file=File(f.usb,"Episode $it.wav").apply { writeBytes(wave) };VideoItem(file.toURI().toString(),file.name,file.length(),0,"audio/wav") }
        f.scenario.onActivity {
            ViewModelProvider(it)[PlayerViewModel::class.java].apply { videos=media;playbackFolder=PlaybackFolder.Usb(f.root,f.usb) }
            MainActivity::class.java.getDeclaredMethod("startPlayback",List::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(it,media,1)
        }
        BrowserTestUi.waitUntil { player(f)?.isPlaying == true }
        val original = player(f)!!
        f.scenario.onActivity { original.seekTo(30_000);if(!playing) original.pause() }
        BrowserTestUi.waitUntil { original.playbackState == Player.STATE_READY }
        f.scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
        BrowserTestUi.waitUntil { browserWindows().any { it.findViewById<TextView>(R.id.dlna_title).text == f.usb.name } }
        f.scenario.onActivity { browserWindows().single().findViewById<View>(R.id.dlna_up).performClick() }
        awaitCount(f,6);confirmFromLongPress(f,"D19 USB A")
        if(background) { f.scenario.moveToState(Lifecycle.State.CREATED);f.scenario.moveToState(Lifecycle.State.RESUMED);awaitConfirmation(f) }
        approve(f);awaitCount(f,5)
        f.scenario.onActivity {
            assertSame(original,player(f));assertFalse(original.playWhenReady);assertEquals(1,original.currentMediaItemIndex);assertEquals(3,original.mediaItemCount)
            assertTrue(original.currentPosition in 29_000..32_000)
            saved()!!.findViewById<View>(R.id.dlna_close).performClick()
        }
        BrowserTestUi.waitUntil { saved()==null && original.playWhenReady == (playing && !background) }
        assertTrue(f.sentinel.exists());assertTrue(File(f.usb,"Episode 1.wav").exists())
    }
    private fun open(f: Fixture) { f.scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).performClick() };awaitCount(f,f.count) }
    private fun visibleRow(f: Fixture,title: String): View {
        var index=0
        f.scenario.onActivity { val list=saved()!!.findViewById<ListView>(R.id.dlna_list);index=(0 until list.count).single { (list.getItemAtPosition(it) as DlnaRow).title==title };list.setSelectionFromTop(index,0) }
        BrowserTestUi.waitUntil { saved()!!.findViewById<ListView>(R.id.dlna_list).let { index in it.firstVisiblePosition..it.lastVisiblePosition && it.getChildAt(index-it.firstVisiblePosition)?.isLaidOut==true } }
        var row: View?=null;f.scenario.onActivity { val list=saved()!!.findViewById<ListView>(R.id.dlna_list);row=list.getChildAt(index-list.firstVisiblePosition) };return row!!
    }
    private fun confirmFromLongPress(f: Fixture,title:String) {
        val row = visibleRow(f,title)
        f.scenario.onActivity { assertTrue(row.findViewById<View>(R.id.saved_folder_open).performLongClick()) }
        awaitConfirmation(f)
    }
    private fun approve(f:Fixture) { f.scenario.onActivity { confirmation()!!.findViewById<View>(R.id.dlna_register).performClick() } }
    private fun awaitFocus(f:Fixture,title:String,count:Int) { awaitCount(f,count);BrowserTestUi.waitUntil { saved()!!.findViewById<ListView>(R.id.dlna_list).let { (it.selectedItem as? DlnaRow)?.title==title && it.hasFocus() && it.selectedView?.findViewById<View>(R.id.saved_folder_open)?.hasFocus()==true } } }
    private fun awaitCount(f:Fixture,count:Int) = BrowserTestUi.waitUntil { saved()?.let { it.hasWindowFocus() && it.findViewById<ListView>(R.id.dlna_list).count==count && it.findViewById<View>(R.id.dlna_refresh).isEnabled }==true }
    private fun awaitConfirmation(f:Fixture) = BrowserTestUi.waitUntil { confirmation()?.hasWindowFocus()==true }
    private fun browserWindows() = WindowInspector.getGlobalWindowViews().filter { it.findViewById<View>(R.id.dlna_title)!=null }
    private fun saved() = browserWindows().singleOrNull { it.findViewById<TextView>(R.id.dlna_title).text.toString()==InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.saf_choose_folder) }
    private fun confirmation() = browserWindows().singleOrNull { it.findViewById<TextView>(R.id.dlna_message).text.toString()==InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.folder_unregister_message) }
    private fun player(f:Fixture):Player? { var result:Player?=null;f.scenario.onActivity { result=it.findViewById<PlayerView>(R.id.player_view).player };return result }
    private fun key(code:Int,meta:Int=0) { val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(300);val down=SystemClock.uptimeMillis();for(a in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) assertTrue(i.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),a,code,0,meta),true)) }
    private fun pointer(view:View) {
        val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(500)
        val location=IntArray(2);i.runOnMainSync { view.getLocationOnScreen(location) }
        val props=MotionEvent.PointerProperties().apply { id=0;toolType=MotionEvent.TOOL_TYPE_MOUSE }
        val coords=MotionEvent.PointerCoords().apply { x=location[0]+view.width/2f;y=location[1]+view.height/2f;pressure=1f;size=1f }
        val down=SystemClock.uptimeMillis()
        for(a in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) { val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),a,1,arrayOf(props),arrayOf(coords),0,if(a==MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_SECONDARY else 0,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0);assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle() }
    }
    private fun screenshot(f:Fixture,name:String) { val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();SystemClock.sleep(600);File(f.context.filesDir,"d19-$name.png").outputStream().use { assertTrue(i.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)) } }
    private class Client: DlnaClient() { @Volatile var requests=0;var delayed=false;private val gate=CompletableDeferred<Unit>();override suspend fun discover():List<DlnaServer> { requests++;if(delayed) gate.await();return emptyList() } }
    private data class Fixture(val scenario:ActivityScenario<MainActivity>,val context:Context,val root:File,val usb:File,val sentinel:File,val trees:List<Uri>,val client:Client,val count:Int)
    private fun withFixture(tv:Boolean=false,only:String?=null,font:Float?=null,action:(Fixture)->Unit) {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val resolver=context.contentResolver;val authority=ScrollFixtureProvider.tree.authority!!
        val grants=resolver.persistedUriPermissions.toList();assumeTrue("Use fixture-owned URI grants only",grants.all { it.uri.authority==authority })
        val prefs=listOf("saved_dlna_folders","saved_usb_folders","last_playback_folder","usb_browser").map { context.getSharedPreferences(it,Context.MODE_PRIVATE) };val before=prefs.map { it.all.toMap() }
        val trees=if(only=="offline-saf") listOf(DocumentsContract.buildTreeDocumentUri(authority,"d19-offline"))
            else (0..1).map { DocumentsContract.buildTreeDocumentUri(authority,"season-%02d".format(it)) }
        val root=File(context.filesDir,"d19-removal-fixture").apply { deleteRecursively();mkdirs() };val usb=File(root,"D19 USB A").apply { mkdirs() };val sentinel=File(usb,"keep.txt").apply { writeText("The media must not be deleted") }
        var touch=true
        fun clearGrants() { resolver.persistedUriPermissions.filter { it.uri.authority==authority }.forEach { resolver.releasePersistableUriPermission(it.uri,(if(it.isReadPermission)1 else 0) or (if(it.isWritePermission)2 else 0)) } }
        fun grant(uris:List<Uri>,flags:Int=3) {
            if(uris.isEmpty())return
            context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test",ScrollFixtureGrantActivity::class.java.name).putExtra("target",context.packageName).putExtra("trees",uris.map { it.toString() }.toTypedArray()).putExtra("accessFlags",flags).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            BrowserTestUi.waitUntil { runCatching { uris.forEach { resolver.takePersistableUriPermission(it,flags) } }.isSuccess }
        }
        try {
            clearGrants();prefs.forEach { it.edit().clear().commit() }
            if(only==null || only=="saf" || only=="offline-saf")grant(if(only=="saf") trees.take(1) else trees)
            if(only==null || only=="dlna") for(n in (if(only=="dlna") listOf("A") else listOf("A","B"))) SavedDlnaFolders(context).save(DlnaServer("uuid:d19-$n","D19 NAS $n","http://192.0.2.1/control","ContentDirectory:1"),listOf(DlnaEntry("0","D19 NAS $n")))
            if(only==null || only=="usb") { SavedUsbFolders(context).save(root,usb);if(only==null)SavedUsbFolders(context).save(root,File(root,"D19 USB Z offline")) }
            LastPlaybackFolder(context).save(PlaybackFolder.Saf(listOf(SafFolder(DocumentsContract.buildDocumentUriUsingTree(trees[0],"season-00"),"Season 00"))))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var config:Configuration;val client=Client()
                scenario.onActivity {
                    config=Configuration(it.resources.configuration);touch=it.findViewById<View>(R.id.btn_select_folder).isInTouchMode
                    if(tv || font!=null) { @Suppress("DEPRECATION") it.resources.updateConfiguration(Configuration(config).apply { if(tv)uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION;if(font!=null)fontScale=font },it.resources.displayMetrics) }
                    MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible=true }.set(it,DlnaBrowser(it,client) { _,_->fail("Removal must not play") })
                }
                i.setInTouchMode(false)
                try { action(Fixture(scenario,context,root,usb,sentinel,trees,client,if(only==null)6 else 1)) }
                finally { scenario.onActivity { @Suppress("DEPRECATION") it.resources.updateConfiguration(config,it.resources.displayMetrics) } }
            }
        } finally {
            i.setInTouchMode(touch);clearGrants();grants.forEach { grant(listOf(it.uri),(if(it.isReadPermission)1 else 0) or (if(it.isWritePermission)2 else 0)) }
            prefs.zip(before).forEach { (p,values)->val e=p.edit().clear();values.forEach { (k,v)->when(v) { is String->e.putString(k,v);is Set<*>->e.putStringSet(k,v.filterIsInstance<String>().toSet());is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v) } };e.commit() };root.deleteRecursively()
        }
    }
}
