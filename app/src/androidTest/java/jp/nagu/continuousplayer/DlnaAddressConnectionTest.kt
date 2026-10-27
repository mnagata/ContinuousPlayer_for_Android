package jp.nagu.continuousplayer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
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
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DlnaAddressConnectionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val address="http://192.0.2.1/device.xml"

    @Test fun manualEntryCanInterruptDiscoveryAndRejectItsLateFailure()=fixture { f ->
        val gate=Gate(true);f.client.discoveryGate=gate
        f.scenario.onActivity { f.browser.open() }
        BrowserTestUi.waitUntil { gate.started.count==0L && window()?.hasWindowFocus()==true }
        val request=f.request()
        f.scenario.onActivity { assertTrue(window()!!.findViewById<View>(R.id.dlna_manual_address).isEnabled) }
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput()
        assertTrue(request.isCancelled);gate.release.countDown();BrowserTestUi.waitUntil { request.isCompleted }
        f.scenario.onActivity { assertTrue(editor().isShown);assertNull(editor().error) }
        assertEquals(0,f.cancelled)
    }
    @Test fun unavailableDiscoveryOffersInputOnServerList()=fixture { f ->
        f.client.discoveryFailure=true;f.scenario.onActivity { f.browser.open() }
        BrowserTestUi.waitUntil { window()?.findViewById<TextView>(R.id.dlna_message)?.text.toString()==context.getString(R.string.dlna_discovery_unavailable) }
        f.scenario.onActivity { assertEquals(context.getString(R.string.dlna_choose_server),title());assertTrue(window()!!.findViewById<View>(R.id.dlna_manual_address).isEnabled) }
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput()
    }
    @Test fun emptyDiscoveryAlsoOffersInput()=fixture { f ->
        f.client.emptyDiscovery=true;f.scenario.onActivity { f.browser.open() }
        BrowserTestUi.waitForText(R.string.dlna_no_server)
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput()
    }
    @Test fun invalidInputKeepsFormAndDoesNotConnect()=fixture { f ->
        openManual(f)
        for(value in listOf("","file:///private/file","http://user:secret@nas/desc","http://nas:65536/")) {
            fill(f,value);connect(f)
            f.scenario.onActivity { assertNotNull(editor().error);assertEquals(value,editor().text.toString());assertTrue(editor().isEnabled) }
        }
        assertTrue(f.client.connections.isEmpty());assertTrue(SavedDlnaFolders(context).all().isEmpty())
    }
    @Test fun enterConnectsAndStoresDescriptionAddressWithoutRegisteringFolder()=fixture { f ->
        openManual(f);fill(f,address);key(KeyEvent.KEYCODE_ENTER);awaitRoot()
        assertEquals(listOf(address),f.client.connections.toList())
        assertEquals(address,SavedDlnaFolders(context).addressFor(f.client.server.id))
        assertTrue(SavedDlnaFolders(context).all().isEmpty())
    }
    @Test fun imeDoneConnectsOnce()=fixture { f ->
        openManual(f);fill(f,"192.0.2.1")
        f.scenario.onActivity { editor().onEditorAction(EditorInfo.IME_ACTION_DONE) }
        awaitRoot();assertEquals(listOf("192.0.2.1"),f.client.connections.toList())
    }
    @Test fun textBackspaceEditsAddressInsteadOfNavigating()=fixture { f ->
        openManual(f);fill(f,"nas.localx");key(KeyEvent.KEYCODE_DEL)
        f.scenario.onActivity { assertEquals("nas.local",editor().text.toString());assertTrue(editor().isShown) }
        assertEquals(0,f.cancelled)
    }
    @Test fun configurationRetainsTextAndCursorInSameWindow()=fixture { f ->
        openManual(f);fill(f,"https://nas.local:8200/description.xml?key=abc")
        lateinit var original:View
        f.scenario.onActivity { original=window()!!;editor().setSelection(8);f.browser.onConfigurationChanged() }
        BrowserTestUi.waitUntil { editor().hasFocus() }
        f.scenario.onActivity { assertSame(original,window());assertEquals("https://nas.local:8200/description.xml?key=abc",editor().text.toString());assertEquals(8,editor().selectionStart) }
    }
    @Test fun idleBackReturnsToServerList()=fixture { f ->
        openManual(f);key(KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { title()==context.getString(R.string.dlna_choose_server) }
        assertEquals(0,f.cancelled)
    }
    @Test fun cancelButtonClosesFormThroughBrowserCancellation()=fixture { f ->
        openManual(f);BrowserTestUi.tap(R.string.file_browser_cancel)
        BrowserTestUi.waitUntil { window()==null && f.cancelled==1 }
    }
    @Test fun connectingDisablesInputAndDoubleSubmitAndCancelsLateResponse()=fixture { f ->
        openManual(f);fill(f,address);val gate=Gate(true);f.client.connectGate=gate;connect(f)
        BrowserTestUi.waitUntil { gate.started.count==0L }
        val request=f.request()
        f.scenario.onActivity {
            assertEquals(address,editor().text.toString());assertFalse(editor().isEnabled)
            assertFalse(window()!!.findViewById<View>(R.id.dlna_register).isEnabled)
            assertTrue(window()!!.findViewById<View>(R.id.dlna_close).isEnabled)
            window()!!.findViewById<View>(R.id.dlna_register).performClick()
        }
        key(KeyEvent.KEYCODE_BACK);BrowserTestUi.waitUntil { window()==null }
        assertTrue(request.isCancelled);gate.release.countDown();BrowserTestUi.waitUntil { request.isCompleted }
        assertNull(window());assertEquals(1,f.client.connections.size);assertNull(SavedDlnaFolders(context).addressFor(f.client.server.id))
    }
    @Test fun failureKeepsInputAndRetryConnects()=fixture { f ->
        f.client.connectFailure=true;openManual(f);fill(f,address);connect(f);awaitRetry()
        f.scenario.onActivity { assertEquals(address,editor().text.toString());assertTrue(editor().isEnabled) }
        assertNull(SavedDlnaFolders(context).addressFor(f.client.server.id))
        f.client.connectFailure=false;connect(f);awaitRoot();assertEquals(2,f.client.connections.size)
    }
    @Test fun browseFailureAfterConnectionCanBeRetried()=fixture { f ->
        f.client.browseFailure=true;openManual(f);fill(f,address);connect(f);awaitRetry()
        f.client.browseFailure=false;connect(f);awaitRoot()
        assertTrue(SavedDlnaFolders(context).all().isEmpty())
    }
    @Test fun manualRegistrationReopensWithoutDiscovery()=fixture { f ->
        openManual(f);fill(f,address);connect(f);awaitRoot()
        f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_register).performClick() }
        val saved=SavedDlnaFolders(context).all().single()
        f.client.discoveries=0;f.client.discoveryFailure=true
        f.scenario.onActivity { f.browser.openSaved(saved) {} }
        awaitRoot();assertEquals(0,f.client.discoveries);assertEquals(2,f.client.connections.size)
        assertEquals(address,SavedDlnaFolders(context).addressFor(saved.serverId))
    }
    @Test fun missingSavedServerCanReconnectToItsOriginalFolderAndFile()=fixture { f ->
        val saved=f.saved();SavedDlnaFolders(context).save(f.client.server,listOf(DlnaEntry("0","D23 NAS"),DlnaEntry("nested","D23 folder")))
        f.client.emptyDiscovery=true
        f.scenario.onActivity { f.browser.openSaved(saved,focusMediaId="media") {} }
        BrowserTestUi.waitUntil { title()==context.getString(R.string.dlna_error) }
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput();fill(f,address);connect(f)
        BrowserTestUi.waitUntil { title()=="D23 folder" && window()!!.findViewById<ListView>(R.id.dlna_list).isEnabled }
        f.scenario.onActivity { val list=window()!!.findViewById<ListView>(R.id.dlna_list);assertEquals("media",(list.getItemAtPosition(0) as DlnaRow).media!!.dlnaObjectId) }
        assertEquals(saved,SavedDlnaFolders(context).all().single())
    }
    @Test fun wrongServerCannotReplaceSavedAddressOrOpenFolder()=fixture { f ->
        val store=SavedDlnaFolders(context);store.save(f.client.server,listOf(DlnaEntry("0","D23 NAS"),DlnaEntry("nested","D23 folder")))
        f.client.emptyDiscovery=true;f.scenario.onActivity { f.browser.openSaved(f.saved()) {} }
        BrowserTestUi.waitUntil { title()==context.getString(R.string.dlna_error) }
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput();fill(f,address)
        f.client.wrongServer=true;connect(f);awaitRetry()
        f.scenario.onActivity { assertTrue(window()!!.findViewById<TextView>(R.id.dlna_message).text.toString().contains(context.getString(R.string.dlna_wrong_server))) }
        assertNull(store.addressFor(f.client.server.id));assertEquals(1,store.all().size);assertTrue(f.client.browsed.isEmpty())
    }
    @Test fun failedCachedAddressDoesNotDeleteBookmarkAndCanBeCorrected()=fixture { f ->
        val store=SavedDlnaFolders(context);store.save(f.client.server,listOf(DlnaEntry("0","D23 NAS"),DlnaEntry("nested","D23 folder")))
        store.rememberAddress(f.client.server.copy(descriptionUrl=address));f.client.connectFailure=true
        f.scenario.onActivity { f.browser.openSaved(f.saved()) {} }
        BrowserTestUi.waitUntil { title()==context.getString(R.string.dlna_error) }
        assertEquals(0,f.client.discoveries);assertEquals(1,store.all().size)
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput()
        f.scenario.onActivity { assertEquals(address,editor().text.toString()) }
        f.client.connectFailure=false;fill(f,"http://192.0.2.2/device.xml");connect(f)
        BrowserTestUi.waitUntil { title()=="D23 folder" }
        assertEquals("http://192.0.2.2/device.xml",store.addressFor(f.client.server.id));assertEquals(1,store.all().size)
    }
    @Test fun addressCacheIsPerServerAndInvalidLegacyValuesAreIgnored()=fixture { f ->
        val store=SavedDlnaFolders(context);store.rememberAddress(f.client.server.copy(descriptionUrl=address))
        store.rememberAddress(f.client.server.copy(id="uuid:other",descriptionUrl="https://other/desc"))
        assertEquals(address,store.addressFor(f.client.server.id));assertEquals("https://other/desc",store.addressFor("uuid:other"))
        context.getSharedPreferences("saved_dlna_folders",Context.MODE_PRIVATE).edit().putString("address:bad","file:///private/file").commit()
        assertNull(SavedDlnaFolders(context).addressFor("bad"));assertTrue(store.all().isEmpty())
    }
    @Test fun televisionCanMoveFromCancelToAddressDuringDiscovery()=fixture(tv=true) { f ->
        val gate=Gate();f.client.discoveryGate=gate;f.scenario.onActivity { f.browser.open() }
        BrowserTestUi.waitUntil { gate.started.count==0L && window()?.findViewById<View>(R.id.dlna_close)?.hasFocus()==true }
        key(KeyEvent.KEYCODE_DPAD_UP)
        f.scenario.onActivity { assertTrue(window()!!.findViewById<View>(R.id.dlna_manual_address).hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER);awaitInput()
        f.scenario.onActivity { assertTrue(editor().hasFocus()) }
        fill(f,address);key(KeyEvent.KEYCODE_DPAD_DOWN)
        f.scenario.onActivity { assertTrue(window()!!.findViewById<View>(R.id.dlna_register).hasFocus()) }
        key(KeyEvent.KEYCODE_DPAD_CENTER);awaitRoot()
    }
    @Test fun formAndManualButtonFitNormalText()=fit(false)
    @Test fun formAndManualButtonFitLargeText()=fit(true)
    private fun fit(large:Boolean)=fixture(large=large) { f ->
        f.client.emptyDiscovery=true;f.scenario.onActivity { f.browser.open() }
        BrowserTestUi.waitUntil { title()==context.getString(R.string.dlna_choose_server) }
        f.scenario.onActivity {
            val button=window()!!.findViewById<Button>(R.id.dlna_manual_address)
            assertTrue(button.isShown);assertTrue(button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
        }
        shot("server-list-${if(large)"large" else "normal"}")
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput();fill(f,address)
        BrowserTestUi.waitUntil { val bounds=Rect();editor().getGlobalVisibleRect(bounds) && bounds.height()>=editor().height-1 }
        f.scenario.onActivity {
            assertTrue(editor().width>0);assertEquals(address,editor().text.toString())
            for(id in listOf(R.id.dlna_register,R.id.dlna_close)) { val button=window()!!.findViewById<Button>(id);assertTrue(button.isShown);assertTrue(button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom) }
            assertTrue(window()!!.findViewById<View>(R.id.dlna_message_container).height>0)
        }
        shot("input-${if(large)"large" else "normal"}")
    }

    @Test fun playingSessionIsRestoredAfterManualConnectionCancel()=retained()
    @Test fun pausedSessionStaysPausedAfterManualConnectionCancel()=retained(paused=true)
    @Test fun interruptionWhileConnectingRequiresManualResume()=retained(background=true)
    private fun retained(paused:Boolean=false,background:Boolean=false)=fixture { f ->
        val bytes=ByteArray(8_000*2*90)
        val wave=ByteBuffer.allocate(bytes.size+44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(bytes.size+36)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.size).put(bytes).array()
        val file=File(f.root,"D23 current.wav").apply { writeBytes(wave) }
        val media=listOf(VideoItem(file.toURI().toString(),file.name,file.length(),0,"audio/wav",dlnaObjectId="media"))
        lateinit var player:Player
        f.client.emptyDiscovery=true
        f.scenario.onActivity { activity ->
            f.browser.close()
            f.browser=DlnaBrowser(activity,f.client,onCancelled={ f.cancelled++;MainActivity::class.java.getDeclaredMethod("cancelPlaybackSelection").apply { isAccessible=true }.invoke(activity) }) { _,_->fail("Unexpected playback") }
            MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible=true }.set(activity,f.browser)
            ViewModelProvider(activity)[PlayerViewModel::class.java].apply { videos=media;playbackFolder=PlaybackFolder.Dlna(f.saved()) }
            MainActivity::class.java.getDeclaredMethod("startPlayback",List::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,media,0)
        }
        BrowserTestUi.waitUntil { var ready=false;f.scenario.onActivity { player=it.findViewById<PlayerView>(R.id.player_view).player!!;ready=player.isPlaying };ready }
        f.scenario.onActivity { player.seekTo(30_000);if(paused)player.pause();it.findViewById<View>(R.id.btn_overlay_select_folder).performClick() }
        BrowserTestUi.waitUntil { title()==context.getString(R.string.dlna_error) }
        BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput();fill(f,address)
        val gate=Gate(true);f.client.connectGate=gate;connect(f)
        BrowserTestUi.waitUntil { gate.started.count==0L };val request=f.request()
        if(background) { f.scenario.moveToState(Lifecycle.State.CREATED);f.scenario.moveToState(Lifecycle.State.RESUMED) }
        key(KeyEvent.KEYCODE_BACK)
        BrowserTestUi.waitUntil { window()==null && player.playWhenReady==(!paused && !background) }
        gate.release.countDown();BrowserTestUi.waitUntil { request.isCompleted }
        f.scenario.onActivity {
            assertSame(player,it.findViewById<PlayerView>(R.id.player_view).player)
            assertEquals(0,player.currentMediaItemIndex);assertEquals(1,player.mediaItemCount)
            assertTrue(player.currentPosition in 29_000..35_000)
            assertEquals(PlaybackFolder.Dlna(f.saved()),ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder)
        }
        assertNull(window());assertNull(SavedDlnaFolders(context).addressFor(f.client.server.id))
    }

    @Test fun realHttpDescriptionAndFolderBrowseReopenWithoutDiscovery()=fixture { f ->
        ServerSocket(0).use { listener ->
            listener.soTimeout=5000
            val executor=Executors.newSingleThreadExecutor()
            val url="http://127.0.0.1:${listener.localPort}/device.xml"
            val worker=executor.submit<List<String>> {
                (0..3).map {
                    listener.accept().use { socket ->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader()
                        val headers=generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val length=headers.firstOrNull { it.startsWith("Content-Length:",true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        var remaining=length;val buffer=CharArray(8192)
                        while(remaining>0) { val read=reader.read(buffer,0,minOf(buffer.size,remaining));check(read>0);remaining-=read }
                        val body=if(headers.first().startsWith("GET")) """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>HTTP NAS</friendlyName><UDN>uuid:http-d23</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/control</controlURL></service></serviceList></device></root>"""
                        else """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"&gt;&lt;container id="nested"&gt;&lt;dc:title&gt;HTTP folder&lt;/dc:title&gt;&lt;/container&gt;&lt;/DIDL-Lite&gt;</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
                        val bytes=body.toByteArray()
                        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
                        headers.first()
                    }
                }
            }
            var discoveries=0
            try {
                f.scenario.onActivity { activity ->
                    f.browser.close()
                    val client=object:DlnaClient() { override suspend fun discover():List<DlnaServer> { discoveries++;return emptyList() } }
                    f.browser=DlnaBrowser(activity,client,onCancelled={f.cancelled++}) { _,_->fail("Unexpected playback") }
                    MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible=true }.set(activity,f.browser)
                }
                openManual(f);fill(f,url);connect(f)
                BrowserTestUi.waitForText("HTTP folder")
                f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_register).performClick() }
                val saved=SavedDlnaFolders(context).all().single();assertEquals(url,SavedDlnaFolders(context).addressFor(saved.serverId))
                f.scenario.onActivity { f.browser.openSaved(saved) {} }
                BrowserTestUi.waitForText("HTTP folder")
                assertEquals(1,discoveries)
                assertEquals(listOf("GET /device.xml HTTP/1.1","POST /control HTTP/1.1","GET /device.xml HTTP/1.1","POST /control HTTP/1.1"),worker.get(5,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    private class Gate(val fail:Boolean=false) {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        fun await() { started.countDown();check(release.await(15,TimeUnit.SECONDS));if(fail)throw IOException("D23 delayed failure") }
    }
    private class Client:DlnaClient() {
        val server=DlnaServer("uuid:d23","D23 NAS","http://192.0.2.1/control","ContentDirectory:1")
        @Volatile var discoveries=0;@Volatile var discoveryFailure=false;@Volatile var emptyDiscovery=false
        @Volatile var connectFailure=false;@Volatile var browseFailure=false;@Volatile var wrongServer=false
        @Volatile var discoveryGate:Gate?=null;@Volatile var connectGate:Gate?=null
        val connections=Collections.synchronizedList(mutableListOf<String>());val browsed=Collections.synchronizedList(mutableListOf<String>())
        override suspend fun discover():List<DlnaServer> { discoveries++;discoveryGate?.await();if(discoveryFailure)throw IOException("Discovery unavailable");return if(emptyDiscovery)emptyList() else listOf(server) }
        override suspend fun connect(address:String):DlnaServer {
            connections+=address;connectGate?.await();if(connectFailure)throw IOException("Connection failed")
            return server.copy(id=if(wrongServer)"uuid:wrong" else server.id,descriptionUrl=if(address.startsWith("http"))address else "http://192.0.2.1/device.xml")
        }
        override suspend fun browse(server:DlnaServer,objectId:String):List<DlnaEntry> {
            browsed+=objectId;if(browseFailure)throw IOException("Browse failed")
            return if(objectId=="0")listOf(DlnaEntry("nested","D23 folder")) else listOf(DlnaEntry("media","Sample OP.mp4",VideoItem("http://192.0.2.1/media.mp4","Sample OP.mp4",1,0,"video/mp4")))
        }
    }
    private inner class Fixture(val scenario:ActivityScenario<MainActivity>,val client:Client) {
        lateinit var browser:DlnaBrowser;var cancelled=0
        val root=File(context.filesDir,"d23-retention-fixture").apply { deleteRecursively();mkdirs() }
        fun saved()=SavedDlnaFolder(client.server.id,client.server.name,listOf(SavedDlnaPath("0","D23 NAS"),SavedDlnaPath("nested","D23 folder")))
        fun request()=DlnaBrowser::class.java.getDeclaredField("job").apply { isAccessible=true }.get(browser) as Job
    }
    private fun fixture(tv:Boolean=false,large:Boolean=false,action:(Fixture)->Unit) {
        val preferences=listOf("saved_dlna_folders","last_playback_folder","usb_browser","saved_usb_folders").map { context.getSharedPreferences(it,Context.MODE_PRIVATE) }
        val before=preferences.map { it.all.toMap() };val prefs=preferences.first()
        var touch=true
        try {
            prefs.edit().clear().commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val f=Fixture(scenario,Client());lateinit var original:Configuration
                scenario.onActivity { activity ->
                    touch=activity.window.decorView.isInTouchMode;original=Configuration(activity.resources.configuration)
                    val config=Configuration(original).apply { if(tv)uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION;if(large)fontScale=2f }
                    @Suppress("DEPRECATION") activity.resources.updateConfiguration(config,activity.resources.displayMetrics)
                    f.browser=DlnaBrowser(activity,f.client,onCancelled={f.cancelled++}) { _,_->fail("Unexpected playback") }
                    MainActivity::class.java.getDeclaredField("dlnaBrowser").apply { isAccessible=true }.set(activity,f.browser)
                }
                try { action(f) } finally {
                    scenario.onActivity { f.browser.close();@Suppress("DEPRECATION") it.resources.updateConfiguration(original,it.resources.displayMetrics) }
                    f.client.discoveryGate?.release?.countDown();f.client.connectGate?.release?.countDown()
                }
            }
        } finally {
            instrumentation.setInTouchMode(touch)
            preferences.zip(before).forEach { (p,values)->val edit=p.edit().clear();values.forEach { (k,v)->when(v) { is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v) } };edit.commit() }
            File(context.filesDir,"d23-retention-fixture").deleteRecursively()
        }
    }
    private fun openManual(f:Fixture) { f.scenario.onActivity { f.browser.open() };BrowserTestUi.waitUntil { window()?.findViewById<View>(R.id.dlna_manual_address)?.isShown==true };BrowserTestUi.tap(R.string.dlna_manual_address);awaitInput() }
    private fun fill(f:Fixture,text:String)=f.scenario.onActivity { editor().apply { setText(text);requestFocusFromTouch();setSelection(text.length) } }
    private fun connect(f:Fixture)=f.scenario.onActivity { window()!!.findViewById<View>(R.id.dlna_register).performClick() }
    private fun awaitInput()=BrowserTestUi.waitUntil { window()?.findViewById<EditText>(R.id.dlna_address)?.let { it.isShown && it.isEnabled }==true }
    private fun awaitRoot()=BrowserTestUi.waitUntil { title()=="D23 NAS" && window()!!.findViewById<ListView>(R.id.dlna_list).isEnabled }
    private fun awaitRetry()=BrowserTestUi.waitUntil { window()?.findViewById<Button>(R.id.dlna_register)?.let { it.isEnabled && it.text.toString()==context.getString(R.string.playback_retry) }==true }
    private fun window()=WindowInspector.getGlobalWindowViews().singleOrNull { it.isAttachedToWindow && it.findViewById<TextView>(R.id.dlna_title)!=null }
    private fun title()=window()?.findViewById<TextView>(R.id.dlna_title)?.text?.toString().orEmpty()
    private fun editor()=window()!!.findViewById<EditText>(R.id.dlna_address)
    private fun key(code:Int) { instrumentation.waitForIdleSync();SystemClock.sleep(500);val down=SystemClock.uptimeMillis();for(a in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),a,code,0),true)) }
    private fun shot(name:String) { instrumentation.waitForIdleSync();SystemClock.sleep(500);File(context.filesDir,"d23-$name-${context.resources.configuration.screenWidthDp}dp.png").outputStream().use { assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)) } }
}
