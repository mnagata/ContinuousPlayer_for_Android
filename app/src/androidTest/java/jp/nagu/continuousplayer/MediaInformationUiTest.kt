package jp.nagu.continuousplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MediaInformationUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext

    @Test fun audioInformationHasOrderedSectionsAndActualFormats()=fixture { f ->
        f.start(listOf(f.wave("D24 original.part.1.wav")))
        lateinit var output:AudioTrackInformation
        f.scenario.onActivity { output=controller(it).audioOutputInformation() }
        open(f);awaitOutput(f)
        f.scenario.onActivity {
            val view=window()!!
            assertTrue(view.findViewById<View>(R.id.info_file_heading).top<view.findViewById<View>(R.id.info_formats_heading).top)
            assertTrue(view.findViewById<View>(R.id.info_formats_heading).top<view.findViewById<View>(R.id.info_output_heading).top)
            assertEquals("D24 original.part.1.wav",view.findViewById<TextView>(R.id.info_filename).text.toString())
            assertTrue(text(R.id.info_file).contains("1:30"));assertTrue(text(R.id.info_file).contains("MiB"))
            assertTrue(text(R.id.info_formats).contains("audio/raw"));assertTrue(text(R.id.info_formats).contains("8000 Hz"));assertTrue(text(R.id.info_formats).contains("1 ch"))
            assertTrue(text(R.id.info_formats).contains("映像トラック：なし"))
            assertTrue(text(R.id.info_audio).contains("AudioTrack"))
            output.sampleRate?.takeIf { it>0 }?.let { rate -> assertTrue(text(R.id.info_audio).contains("$rate Hz")) }
            output.device?.productName?.toString()?.takeIf { it.isNotBlank() }?.let { name -> assertTrue(text(R.id.info_audio).contains(name)) }
            assertFalse(f.player.playWhenReady)
        }
        shot("audio")
    }
    @Test fun playingOutputShowsConfirmedRouteAndOutputLimits()=fixture { f ->
        f.start(listOf(f.wave("confirmed route.wav")))
        BrowserTestUi.waitUntil {
            var confirmed=false
            f.scenario.onActivity { confirmed=controller(it).audioOutputInformation().routeObservation==AudioRouteObservation.LIVE }
            confirmed
        }
        open(f);awaitOutput(f)
        f.scenario.onActivity {
            assertTrue(text(R.id.info_audio).contains("出力経路：AudioTrack → "))
            assertFalse(text(R.id.info_audio).contains("経路の確認："))
            assertTrue(window()!!.findViewById<TextView>(R.id.info_audio_note).text.contains("HALポート名"))
            assertEquals(View.VISIBLE,window()!!.findViewById<View>(R.id.info_audio_note).visibility)
        }
        shot("route")
    }
    @Test fun pausedOutputIdentifiesTheLastConfirmedRoute()=fixture { f ->
        f.start(listOf(f.wave("remembered route.wav")))
        BrowserTestUi.waitUntil {
            var confirmed=false
            f.scenario.onActivity { confirmed=controller(it).audioOutputInformation().routeObservation==AudioRouteObservation.LIVE }
            confirmed
        }
        f.scenario.onActivity { f.player.pause() }
        BrowserTestUi.waitUntil {
            var remembered=false
            f.scenario.onActivity { remembered=controller(it).audioOutputInformation().routeObservation==AudioRouteObservation.LAST_KNOWN }
            remembered
        }
        open(f);awaitOutput(f)
        f.scenario.onActivity {
            assertTrue(text(R.id.info_audio).contains("出力経路：AudioTrack → "))
            assertFalse(text(R.id.info_audio).contains("経路の確認："))
        }
        close(f)
        f.scenario.onActivity { assertFalse(f.player.playWhenReady) }
    }
    @Test fun debugDiagnosticsShowTheObservedMixerAndHalAfterPausing()=fixture { f ->
        assumeTrue("Requires the ADB diagnostic permission",
            AudioMixerDiagnostics(context).accessState()==MixerDiagnosticsState.WAITING)
        f.start(listOf(f.wave("diagnostic route.wav",96000,highPrecision=true)))
        lateinit var snapshot: MixerRouteSnapshot
        BrowserTestUi.waitUntil {
            var captured: MixerRouteSnapshot?=null
            f.scenario.onActivity { captured=controller(it).audioOutputInformation().mixerSnapshot }
            captured?.let { snapshot=it; true } ?: false
        }
        f.scenario.onActivity { f.player.play() }
        BrowserTestUi.waitUntil { f.player.isPlaying }
        open(f);awaitOutput(f)
        var retained:Any?=null
        f.scenario.onActivity {
            retained=MainActivity::class.java.getDeclaredField("playbackInformation").apply { isAccessible=true }.get(it)
            val output=text(R.id.info_audio)
            assertTrue(output.contains("出力経路：AudioTrack → ${snapshot.profile} → HAL → "))
            assertTrue(output.contains("実ミキサー：${snapshot.profile} (${snapshot.type}, I/O ${snapshot.ioHandle})"))
            assertTrue(output.contains("実ミキサー形式：${snapshot.mixerFormat.display()}"))
            assertTrue(output.contains("HAL形式：${snapshot.halFormat.display()}"))
            assertFalse(output.contains("ミキサー情報の確認："))
            assertFalse(output.contains("ミキサー確認時刻："))
            assertFalse(f.player.playWhenReady)
            window()!!.findViewById<ScrollView>(R.id.info_scroll).fullScroll(View.FOCUS_DOWN)
        }
        shot("actual-mixer")
        close(f)
        BrowserTestUi.waitUntil {
            var restored=false
            f.scenario.onActivity { activity ->
                restored=MainActivity::class.java.getDeclaredField("playbackInformation").apply { isAccessible=true }.get(activity)==null
            }
            restored
        }
        f.scenario.onActivity { activity ->
            val background=MainActivity::class.java.getDeclaredField("isInBackground").apply { isAccessible=true }.getBoolean(activity)
            val interrupted=retained!!.javaClass.getDeclaredField("interrupted").apply { isAccessible=true }.getBoolean(retained)
            assertEquals("Backgrounding must still prevent automatic resume",!background && !interrupted,f.player.playWhenReady)
        }
    }
    @Test fun observationWithoutRoutedDeviceDoesNotInventAnOutputPath() {
        val audio=BitPerfectAudioManager(context)
        try {
            val info=audio.getOutputInformation(AudioTrackInformation(routeObservation=AudioRouteObservation.LIVE,
                routeConfirmedAtMs=System.currentTimeMillis()))
            assertEquals(InformationValue.Unavailable,info.route)
            assertEquals(InformationValue.Unavailable,info.routeObservation)
            assertEquals(InformationValue.Unavailable,info.routeConfirmedAt)
        } finally { audio.release() }
    }
    @Test fun videoOnlyShowsVideoCodecResolutionAndNoAudio()=fixture { f ->
        val file=File(f.root,"D24 video.mp4").apply { writeBytes(instrumentation.context.assets.open("d13-video.mp4").use { it.readBytes() }) }
        f.start(listOf(VideoItem(file.toURI().toString(),file.name,file.length(),0,"video/mp4")))
        BrowserTestUi.waitUntil { var ready=false;f.scenario.onActivity { ready=controller(it).videoFormat!=null };ready }
        open(f);awaitOutput(f)
        f.scenario.onActivity {
            assertTrue(text(R.id.info_formats).contains("video/avc"));assertTrue(text(R.id.info_formats).contains("160 × 90"))
            val fps=controller(it).videoFormat?.frameRate ?: -1f
            if(fps.isFinite() && fps>0)assertTrue(text(R.id.info_formats).contains(String.format(java.util.Locale.ROOT,"%.2f fps",fps)))
            else assertTrue(text(R.id.info_formats).contains("フレームレート：取得できません"))
            assertTrue(text(R.id.info_formats).contains("音声トラック：なし"))
            assertEquals("音声出力：なし",text(R.id.info_audio))
            assertEquals(View.GONE,window()!!.findViewById<View>(R.id.info_audio_note).visibility)
        }
        shot("video")
    }
    @Test fun unknownSizeIsVisibleRatherThanHiddenOrNegative()=fixture { f ->
        f.start(listOf(f.wave("D24 unknown size.wav").copy(size=-1)))
        open(f);awaitOutput(f)
        f.scenario.onActivity { assertTrue(text(R.id.info_file).contains("ファイルサイズ：取得できません"));assertFalse(text(R.id.info_file).contains("-1"));assertTrue(window()!!.findViewById<View>(R.id.info_audio).isShown) }
    }
    @Test fun unknownOutputDoesNotPretendFeatureIsUnsupported()=fixture { f ->
        f.start(listOf(f.wave("D24 no output data.wav")))
        f.scenario.onActivity { audioField().set(it,null) }
        open(f);awaitOutput(f)
        f.scenario.onActivity {
            assertTrue(text(R.id.info_audio).contains("出力経路：取得できません"))
            assertFalse(text(R.id.info_audio).contains("経路の確認："))
            assertFalse(text(R.id.info_audio).contains("最終確認時刻："))
            assertFalse(text(R.id.info_audio).contains("未対応"));assertTrue(window()!!.findViewById<View>(R.id.info_audio).isShown)
        }
    }
    @Test fun deviceWithoutBitPerfectReportsUnsupported()=fixture { f ->
        f.start(listOf(f.wave("D24 unsupported.wav")))
        BrowserTestUi.waitUntil { var known=false;f.scenario.onActivity { known=controller(it).audioOutputInformation().device!=null };known }
        open(f);awaitOutput(f)
        f.scenario.onActivity { assertTrue(text(R.id.info_audio).contains("Bit-Perfect設定：未対応"));assertFalse(text(R.id.info_audio).contains("設定済み")) }
    }
    @Test fun outputQueryFailureKeepsUnavailableFields()=fixture { f ->
        f.start(listOf(f.wave("D24 query failure.wav")))
        f.scenario.onActivity { audioField().set(it,object:BitPerfectAudioManager(it) { override fun getOutputInformation(track:AudioTrackInformation):AudioOutputInformation { throw IOException("D24 query failed") } }) }
        open(f);awaitOutput(f)
        f.scenario.onActivity { assertTrue(text(R.id.info_audio).contains("出力デバイス：取得できません"));assertTrue(text(R.id.info_formats).contains("8000 Hz")) }
    }
    @Test fun nextMediaHasItsOwnNameAndSourceFormat()=fixture { f ->
        f.start(listOf(f.wave("D24 first.wav"),f.wave("D24 second.wav",16000)))
        open(f);awaitOutput(f);close(f)
        BrowserTestUi.waitUntil { f.player.playWhenReady }
        f.scenario.onActivity { f.player.seekToNextMediaItem() }
        BrowserTestUi.waitUntil { var ready=false;f.scenario.onActivity { ready=f.player.currentMediaItemIndex==1 && controller(it).audioFormat?.sampleRate==16000 };ready }
        open(f);awaitOutput(f)
        f.scenario.onActivity { assertEquals("D24 second.wav",text(R.id.info_filename));assertTrue(text(R.id.info_formats).contains("16000 Hz"));assertFalse(text(R.id.info_formats).contains("8000 Hz")) }
    }
    @Test fun closingInfoDiscardsDelayedOutputResult()=delayed(false)
    @Test fun delayedOldResultCannotReplaceAnotherFileInformation()=delayed(true)
    private fun delayed(reopen:Boolean)=fixture { f ->
        f.start(listOf(f.wave("D24 first.wav"),f.wave("D24 second.wav",16000)))
        val gate=CountDownLatch(1);val began=CountDownLatch(1);val calls=AtomicInteger()
        f.release={ gate.countDown() }
        f.scenario.onActivity { audioField().set(it,object:BitPerfectAudioManager(it) {
            override fun getOutputInformation(track:AudioTrackInformation):AudioOutputInformation {
                val first=calls.incrementAndGet()==1
                if(first) { began.countDown();check(gate.await(15,TimeUnit.SECONDS)) }
                return AudioOutputInformation(device=InformationValue.Text(if(first)"D24 OLD OUTPUT" else "D24 NEW OUTPUT"))
            }
        }) }
        open(f);BrowserTestUi.waitUntil { began.count==0L }
        lateinit var request:kotlinx.coroutines.Job
        f.scenario.onActivity { request=MainActivity::class.java.getDeclaredField("informationJob").apply { isAccessible=true }.get(it) as kotlinx.coroutines.Job }
        close(f)
        if(reopen) {
            BrowserTestUi.waitUntil { f.player.playWhenReady }
            f.scenario.onActivity { f.player.seekToNextMediaItem() }
            BrowserTestUi.waitUntil { f.player.currentMediaItemIndex==1 }
            open(f);awaitOutput(f)
        }
        gate.countDown();BrowserTestUi.waitUntil { request.isCompleted }
        f.scenario.onActivity {
            if(reopen) { assertEquals("D24 second.wav",text(R.id.info_filename));assertTrue(text(R.id.info_audio).contains("D24 NEW OUTPUT"));assertFalse(text(R.id.info_audio).contains("D24 OLD OUTPUT")) }
            else assertNull(window())
        }
    }
    @Test fun safInformationUsesPlayerFormatsWithoutReopeningSource()=fixture { f ->
        val tree=DocumentsContract.buildTreeDocumentUri(ScrollFixtureProvider.tree.authority,"d24-audio")
        val resolver=context.contentResolver
        val prior=resolver.persistedUriPermissions.firstOrNull { it.uri==tree }
        try {
            if(prior==null) {
                context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test",ScrollFixtureGrantActivity::class.java.name).putExtra("target",context.packageName).putExtra("trees",arrayOf(tree.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                BrowserTestUi.waitUntil { runCatching { resolver.takePersistableUriPermission(tree,1) }.isSuccess }
            }
            val uri=DocumentsContract.buildDocumentUriUsingTree(tree,"d24-audio/media")
            f.start(listOf(VideoItem(uri.toString(),"D24 provider.wav",1440044,0,"audio/wav")),PlaybackFolder.Saf(listOf(SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree,"d24-audio"),"D24 SAF"))))
            f.scenario.onActivity { f.player.pause() }
            var previous=-1;var stableSince=SystemClock.uptimeMillis()
            BrowserTestUi.waitUntil {
                val count=resolver.call(tree,"d24-count",null,null)!!.getInt("opens")
                if(count!=previous) { previous=count;stableSince=SystemClock.uptimeMillis() }
                count>0 && SystemClock.uptimeMillis()-stableSince>=300
            }
            resolver.call(tree,"d24-reset",null,null)
            open(f);awaitOutput(f)
            assertEquals(0,resolver.call(tree,"d24-count",null,null)!!.getInt("opens"))
            f.scenario.onActivity { assertTrue(text(R.id.info_formats).contains("8000 Hz"));assertEquals("D24 provider.wav",text(R.id.info_filename)) }
        } finally {
            f.scenario.onActivity { MainActivity::class.java.getDeclaredMethod("stopPlayback").apply { isAccessible=true }.invoke(it) }
            resolver.call(tree,"d24-clean",null,null)
            if(prior==null)resolver.releasePersistableUriPermission(tree,1)
        }
    }
    @Test fun longNameIsCompleteAndOkRemainsVisible()=fit(false)
    @Test fun enlargedTextKeepsScrollableContentAndOk()=fit(true)
    private fun fit(large:Boolean)=fixture(large=large) { f ->
        val name="長い作品名と情報を最後まで確認する".repeat(4)+".part.1.wav"
        f.start(listOf(f.wave(name)));open(f);awaitOutput(f)
        f.scenario.onActivity {
            assertEquals(name,text(R.id.info_filename))
            val scroll=window()!!.findViewById<ScrollView>(R.id.info_scroll)
            assertTrue(scroll.height>0)
            if(large || context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)assertTrue(scroll.canScrollVertically(1) || scroll.canScrollVertically(-1))
            val button=window()!!.findViewById<Button>(R.id.info_close);val bounds=Rect()
            assertTrue(button.getGlobalVisibleRect(bounds));assertEquals(button.height,bounds.height())
            assertTrue(button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
        }
        shot(if(large)"large-text" else "long-name")
    }
    @Test fun televisionOkFocusAndReturnKeepPausedSession()=fixture(tv=true) { f ->
        f.start(listOf(f.wave("D24 television.wav")))
        f.scenario.onActivity { f.player.pause();it.findViewById<View>(R.id.btn_overlay_info).requestFocusFromTouch() }
        open(f);awaitOutput(f)
        BrowserTestUi.waitUntil { window()!!.findViewById<View>(R.id.info_close).hasFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        BrowserTestUi.waitUntil { window()==null }
        f.scenario.onActivity { assertFalse(f.player.playWhenReady);assertSame(f.player,it.findViewById<PlayerView>(R.id.player_view).player) }
    }
    @Test fun rotationKeepsInformationAndRetainedPosition()=fixture { f ->
        f.start(listOf(f.wave("D24 rotation.wav")));open(f);awaitOutput(f)
        val before=textOnMain(f,R.id.info_formats)
        f.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        BrowserTestUi.waitUntil { context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
        f.scenario.onActivity { assertEquals(before,text(R.id.info_formats));assertFalse(f.player.playWhenReady);assertTrue(f.player.currentPosition in 29_000..32_000) }
        close(f);BrowserTestUi.waitUntil { f.player.playWhenReady }
    }

    private inner class Fixture(val scenario:ActivityScenario<MainActivity>,val root:File) {
        lateinit var player:Player;var release:()->Unit={};var originalAudio:Any?=null
        fun wave(name:String,rate:Int=8000,highPrecision:Boolean=false):VideoItem {
            val channels=if(highPrecision)2 else 1
            val sampleBytes=if(highPrecision)4 else 2
            val data=ByteArray(rate*channels*sampleBytes*(if(highPrecision)40 else 90))
            val bytes=ByteBuffer.allocate(data.size+44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(data.size+36)
                .put("WAVEfmt ".toByteArray()).putInt(16).putShort(if(highPrecision)3 else 1).putShort(channels.toShort()).putInt(rate)
                .putInt(rate*channels*sampleBytes).putShort((channels*sampleBytes).toShort()).putShort((sampleBytes*8).toShort())
                .put("data".toByteArray()).putInt(data.size).put(data).array()
            val file=File(root,name).apply { writeBytes(bytes) };return VideoItem(file.toURI().toString(),name,file.length(),0,"audio/wav")
        }
        fun start(media:List<VideoItem>,folder:PlaybackFolder=PlaybackFolder.Usb(root,root)) {
            scenario.onActivity {
                ViewModelProvider(it)[PlayerViewModel::class.java].apply { videos=media;playbackFolder=folder }
                MainActivity::class.java.getDeclaredMethod("startPlayback",List::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(it,media,0)
                originalAudio=audioField().get(it)
            }
            BrowserTestUi.waitUntil { var ready=false;scenario.onActivity { player=it.findViewById<PlayerView>(R.id.player_view).player!!;ready=player.isPlaying };ready }
            scenario.onActivity { player.seekTo(30_000) }
            BrowserTestUi.waitUntil { player.playbackState==Player.STATE_READY }
        }
    }
    private fun fixture(tv:Boolean=false,large:Boolean=false,action:(Fixture)->Unit) {
        val preferences=listOf("saved_dlna_folders","saved_usb_folders","last_playback_folder","usb_browser").map { context.getSharedPreferences(it,Context.MODE_PRIVATE) };val before=preferences.map { it.all.toMap() }
        val root=File(context.filesDir,"d24-information-fixture").apply { deleteRecursively();mkdirs() }
        var touch=true
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val f=Fixture(scenario,root);lateinit var original:Configuration
            scenario.onActivity {
                touch=it.window.decorView.isInTouchMode;original=Configuration(it.resources.configuration)
                val config=Configuration(original).apply { if(tv)uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION;if(large)fontScale=2f }
                @Suppress("DEPRECATION") it.resources.updateConfiguration(config,it.resources.displayMetrics)
                if(tv)instrumentation.setInTouchMode(false)
            }
            try { action(f) } finally { f.release();scenario.onActivity { if(it.findViewById<PlayerView>(R.id.player_view).player!=null)audioField().set(it,f.originalAudio);@Suppress("DEPRECATION") it.resources.updateConfiguration(original,it.resources.displayMetrics) } }
        } } finally {
            instrumentation.setInTouchMode(touch);root.deleteRecursively()
            preferences.zip(before).forEach { (p,values)->val edit=p.edit().clear();values.forEach { (k,v)->when(v) { is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v) } };edit.commit() }
        }
    }
    private fun audioField()=MainActivity::class.java.getDeclaredField("bitPerfectAudio").apply { isAccessible=true }
    private fun controller(activity:MainActivity)=MainActivity::class.java.getDeclaredField("playerController").apply { isAccessible=true }.get(activity) as PlayerController
    private fun open(f:Fixture) { f.scenario.onActivity { it.findViewById<View>(R.id.btn_overlay_info).performClick() };BrowserTestUi.waitUntil { window()!=null } }
    private fun close(f:Fixture) { f.scenario.onActivity { window()!!.findViewById<View>(R.id.info_close).performClick() };BrowserTestUi.waitUntil { window()==null } }
    private fun awaitOutput(f:Fixture)=BrowserTestUi.waitUntil { var done=false;f.scenario.onActivity { val job=MainActivity::class.java.getDeclaredField("informationJob").apply { isAccessible=true }.get(it) as? kotlinx.coroutines.Job;done=job?.isCompleted!=false };done }
    private fun window()=WindowInspector.getGlobalWindowViews().singleOrNull { it.isAttachedToWindow && it.findViewById<View>(R.id.info_close)!=null }
    private fun text(id:Int)=window()!!.findViewById<TextView>(id).text.toString()
    private fun textOnMain(f:Fixture,id:Int):String { var value="";f.scenario.onActivity { value=text(id) };return value }
    private fun key(code:Int) { instrumentation.waitForIdleSync();SystemClock.sleep(500);val down=SystemClock.uptimeMillis();for(a in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),a,code,0),true)) }
    private fun shot(name:String) { instrumentation.waitForIdleSync();SystemClock.sleep(500);File(context.filesDir,"d24-$name-${context.resources.configuration.screenWidthDp}dp.png").outputStream().use { assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)) } }
}
