package jp.nagu.continuousplayer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Check the real AudioTrack, including a format change within the same player. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class HighPrecisionAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun integer16BitKeeps16BitOutput() = checkWave(16, 48_000, AudioFormat.ENCODING_PCM_16BIT)
    @Test fun integer24BitUsesFloatOutput() = checkWave(24, 96_000, AudioFormat.ENCODING_PCM_FLOAT)
    @Test fun integer32BitUsesFloatOutput() = checkWave(32, 96_000, AudioFormat.ENCODING_PCM_FLOAT)
    @Test fun floatPcmKeepsFloatOutput() = checkWave(32, 48_000, AudioFormat.ENCODING_PCM_FLOAT, floating = true)

    @Test fun flacDecoderUsesHighPrecisionOutput() = withPlayback { root, start ->
        val file = File(root, "24bit-96khz.flac").apply {
            instrumentation.context.assets.open("24bit-96khz.flac").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        val owner = start(listOf(item(file, "audio/flac")))
        awaitOutput(owner, 0, AudioFormat.ENCODING_PCM_FLOAT, 96_000)
        instrumentation.runOnMainSync { assertEquals("audio/flac", owner.audioFormat?.sampleMimeType) }
    }

    @Test fun continuousPlaybackSwitches16BitToFloatAndBack() = withPlayback { root, start ->
        val owner = start(listOf(
            wave(root, "first.wav", 16, 48_000, durationMs = 2000),
            wave(root, "second.wav", 24, 96_000, durationMs = 2000),
            wave(root, "third.wav", 16, 44_100)
        ))
        awaitOutput(owner, 0, AudioFormat.ENCODING_PCM_16BIT, 48_000)
        awaitOutput(owner, 1, AudioFormat.ENCODING_PCM_FLOAT, 96_000)
        awaitOutput(owner, 2, AudioFormat.ENCODING_PCM_16BIT, 44_100)
    }

    @Test fun rejectedHighPrecisionOutputFallsBackTo16BitAndKeepsPlaybackTiming() {
        val attempts=mutableListOf<Int>()
        val converted=mutableListOf<ByteArray>()
        val pts=mutableListOf<Long>()
        withPlayback(providerFactory={ ctx ->
            val delegate=AudioTrackAudioOutputProvider.Builder(ctx).build()
            object:AudioOutputProvider by delegate {
                override fun getAudioOutput(config:AudioOutputProvider.OutputConfig):AudioOutput {
                    synchronized(attempts) { attempts.add(config.encoding) }
                    if(config.encoding!=C.ENCODING_PCM_16BIT) throw AudioOutputProvider.InitializationException()
                    val output=delegate.getAudioOutput(config)
                    return object:AudioOutput by output {
                        override fun write(buffer:ByteBuffer,encodedAccessUnitCount:Int,presentationTimeUs:Long):Boolean {
                            val bytes=ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                            synchronized(converted) { if(converted.size<5) { converted.add(bytes);pts.add(presentationTimeUs) } }
                            return output.write(buffer,encodedAccessUnitCount,presentationTimeUs)
                        }
                    }
                }
            }
        }) { root,start ->
            val owner=start(listOf(wave(root,"fallback.wav",24,96000,durationMs=6000)))
            // The provider wrapper hides AudioTrack from observation; test the engine's clock and bytes directly.
            BrowserTestUi.waitUntil { assertNull(owner.player.playerError);owner.player.isPlaying && owner.player.currentPosition>=500 }
            instrumentation.runOnMainSync { owner.player.seekTo(2000) }
            BrowserTestUi.waitUntil { assertNull(owner.player.playerError);owner.player.isPlaying && owner.player.currentPosition>=2300 }
            instrumentation.runOnMainSync { owner.player.pause() }
            assertTrue(synchronized(attempts) { attempts.first()!=C.ENCODING_PCM_16BIT && attempts.contains(C.ENCODING_PCM_16BIT) })
            val first=synchronized(converted) { converted.first() }
            assertTrue(first.isNotEmpty() && first.size%4==0)
            val samples=ByteBuffer.wrap(first).order(ByteOrder.nativeOrder())
            var nonZero=false
            while(samples.hasRemaining()) {
                val sample=samples.short.toInt()
                assertTrue("Converted low-level test tone is corrupt: $sample",sample in -330..330)
                if(sample!=0)nonZero=true
            }
            assertTrue(nonZero)
            assertTrue(synchronized(converted) { pts.all { it>=0 } })
        }
    }

    @Test fun rejectedFloatConfigurationCanUseThe16BitCompatibilityPath() {
        val rejected=java.util.concurrent.atomic.AtomicInteger()
        withPlayback(providerFactory={ ctx ->
            val delegate=AudioTrackAudioOutputProvider.Builder(ctx).build()
            object:AudioOutputProvider by delegate {
                override fun getOutputConfig(config:AudioOutputProvider.FormatConfig):AudioOutputProvider.OutputConfig {
                    if(config.format.sampleMimeType==MimeTypes.AUDIO_RAW && config.format.pcmEncoding==C.ENCODING_PCM_FLOAT) {
                        rejected.incrementAndGet()
                        throw AudioOutputProvider.ConfigurationException("Simulated device without Float configuration")
                    }
                    return delegate.getOutputConfig(config)
                }
                override fun getAudioOutput(config:AudioOutputProvider.OutputConfig):AudioOutput {
                    if(config.encoding!=C.ENCODING_PCM_16BIT) throw AudioOutputProvider.InitializationException()
                    return delegate.getAudioOutput(config)
                }
            }
        }) { root,start ->
            val owner=start(listOf(wave(root,"configuration-fallback.wav",24,96000)))
            awaitOutput(owner,0,AudioFormat.ENCODING_PCM_16BIT,96000)
            assertTrue(rejected.get()>0)
            instrumentation.runOnMainSync { assertEquals(96000,owner.audioFormat?.sampleRate) }
        }
    }

    @Test fun rejected16BitFallbackStopsAtTheSameFile() {
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        withPlayback(providerFactory={ ctx ->
            val delegate=AudioTrackAudioOutputProvider.Builder(ctx).build()
            object:AudioOutputProvider by delegate {
                override fun getAudioOutput(config:AudioOutputProvider.OutputConfig):AudioOutput {
                    attempts.incrementAndGet()
                    throw AudioOutputProvider.InitializationException()
                }
            }
        }) { root,start ->
            val owner=start(listOf(wave(root,"failed.wav",24,96000),wave(root,"next.wav",16,48000)))
            BrowserTestUi.waitUntil { owner.player.playerError!=null && !owner.player.playWhenReady }
            instrumentation.runOnMainSync {
                assertEquals(0,owner.player.currentMediaItemIndex)
                assertEquals(2,owner.player.mediaItemCount)
            }
            assertTrue(attempts.get()>=2)
        }
    }

    /** Run on a connected integer-PCM USB DAC; ordinary emulator runs skip this test. */
    @Test fun usbDacPreservesSourceRateAndSelectsInteger32BitPerfect() {
        val manager = context.getSystemService(AudioManager::class.java)
        val attributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val device = manager.getAudioDevicesForAttributes(attributes).singleOrNull()
        val profiles = device?.let { manager.getSupportedMixerAttributes(it) }.orEmpty()
        assumeTrue("Requires a USB DAC with 96/192kHz integer32 bit-perfect profiles",
            device?.type in listOf(android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET) && listOf(96000, 192000).all { rate ->
                profiles.any { it.format.encoding == AudioFormat.ENCODING_PCM_32BIT &&
                    it.format.sampleRate == rate && it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO &&
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT } &&
                    profiles.none { it.format.encoding == AudioFormat.ENCODING_PCM_FLOAT && it.format.sampleRate == rate }
            })
        withPlayback { root, start ->
            val flac = File(root, "24bit-96khz.flac").apply {
                instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
            }
            val owner = start(listOf(item(flac, "audio/flac"),
                wave(root, "192khz.wav", 24, 192000, durationMs = 3000),
                wave(root, "48khz.wav", 16, 48000, durationMs = 6000)))
            fun checkOutput(index: Int, rate: Int, encoding: Int) {
                awaitOutput(owner, index, encoding, rate)
                var initialPosition = 0L
                instrumentation.runOnMainSync { initialPosition = owner.player.currentPosition }
                BrowserTestUi.waitUntil {
                    owner.player.isPlaying && owner.player.currentMediaItemIndex == index &&
                        owner.player.currentPosition >= initialPosition + 400
                }
                val preferred = manager.getPreferredMixerAttributes(attributes, device!!)
                assertEquals(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT, preferred?.mixerBehavior)
                assertEquals(rate, preferred?.format?.sampleRate)
                assertEquals(encoding, preferred?.format?.encoding)
                // Capture live HAL/policy evidence while this format is still playing.
                listOf("media.audio_policy", "media.audio_flinger").forEach { service ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand("dumpsys $service")).use { input ->
                        File(context.cacheDir, "usb-$rate-$service.txt").outputStream().use { input.copyTo(it) }
                    }
                }
            }
            checkOutput(0, 96000, AudioFormat.ENCODING_PCM_32BIT)
            checkOutput(1, 192000, AudioFormat.ENCODING_PCM_32BIT)
            checkOutput(2, 48000, AudioFormat.ENCODING_PCM_16BIT)
            instrumentation.runOnMainSync { owner.player.seekTo(0, 1000); owner.player.play() }
            checkOutput(0, 96000, AudioFormat.ENCODING_PCM_32BIT)
        }
        assertNull("Release must clear the mixer preference", manager.getPreferredMixerAttributes(attributes, device!!))
    }

    private fun checkWave(bits: Int, rate: Int, expected: Int, floating: Boolean = false) =
        withPlayback { root, start ->
            val owner = start(listOf(wave(root, "source.wav", bits, rate, floating)))
            awaitOutput(owner, 0, expected, rate)
        }

    private fun awaitOutput(owner: PlayerController, index: Int, encoding: Int, rate: Int) {
        BrowserTestUi.waitUntil {
            val player = owner.player
            assertNull("Playback failed: ${player.playerError}", player.playerError)
            val output = owner.audioOutputInformation()
            player.currentMediaItemIndex == index && player.isPlaying && player.currentPosition >= 100 &&
                owner.audioFormat?.sampleRate == rate && output.encoding == encoding &&
                output.sampleRate == rate && output.channels == 2
        }
    }

    private fun withPlayback(providerFactory:((android.content.Context)->AudioOutputProvider)?=null,
        action: (File, (List<VideoItem>) -> PlayerController) -> Unit) {
        val root = File(context.cacheDir, "high-precision-audio").apply { mkdirs() }
        var owner: PlayerController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    action(root) { media ->
                        scenario.onActivity { activity ->
                            owner = PlayerController(activity,audioOutputProviderFactory=providerFactory).also { it.setPlaylist(media) }
                        }
                        owner!!
                    }
                } finally {
                    instrumentation.runOnMainSync { owner?.release() }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun item(file: File, mime: String) =
        VideoItem(file.toURI().toString(), file.name, file.length(), 0, mime)

    private fun wave(root: File, name: String, bits: Int, rate: Int,
        floating: Boolean = false, durationMs: Int = 4000): VideoItem {
        val channels = 2
        val bytesPerSample = bits / 8
        val frames = rate * durationMs / 1000
        val dataSize = frames * channels * bytesPerSample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(if (floating) 3 else 1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * bytesPerSample)
            .putShort((channels * bytesPerSample).toShort()).putShort(bits.toShort())
            .put("data".toByteArray()).putInt(dataSize)
        repeat(frames) { frame ->
            val sample = sin(2 * PI * 440 * frame / rate) * 0.01
            repeat(channels) {
                if (floating) buffer.putFloat(sample.toFloat()) else {
                    val value = (sample * ((1L shl (bits - 1)) - 1)).toInt()
                    repeat(bytesPerSample) { byte -> buffer.put((value shr (8 * byte)).toByte()) }
                }
            }
        }
        return item(File(root, name).apply { writeBytes(buffer.array()) }, "audio/wav")
    }
}
