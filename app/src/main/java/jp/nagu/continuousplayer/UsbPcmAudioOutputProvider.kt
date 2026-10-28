package jp.nagu.continuousplayer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import java.nio.ByteBuffer

/** Media3 supplies Float PCM; a USB DAC may require integer PCM at the same rate. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class UsbPcmAudioOutputProvider(
    private val preferences: BitPerfectAudioManager,
    private val monitor: PlaybackAudioMonitor,
    private val delegate: AudioOutputProvider
) : AudioOutputProvider by delegate {
    constructor(context: Context, preferences: BitPerfectAudioManager, monitor: PlaybackAudioMonitor) :
        this(preferences, monitor, AudioTrackAudioOutputProvider.Builder(context).build())

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        try {
            return delegate.getOutputConfig(formatConfig)
        } catch (error: Exception) {
            if (error !is AudioOutputProvider.ConfigurationException && error !is IllegalArgumentException &&
                error !is IllegalStateException) throw error
            if (formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW ||
                formatConfig.format.pcmEncoding != C.ENCODING_PCM_FLOAT || formatConfig.enableTunneling) throw error
            // Some devices reject Float even in getMinBufferSize, before track initialization.
            // Keep the sink-facing contract in Float, including its buffer/frame accounting.
            val integerFormat = formatConfig.format.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build()
            val integerConfig = AudioOutputProvider.FormatConfig.Builder(integerFormat)
                .setAudioAttributes(formatConfig.audioAttributes)
                .setPreferredDevice(formatConfig.preferredDevice)
                .setEnableHighResolutionPcmOutput(formatConfig.enableHighResolutionPcmOutput)
                .setEnablePlaybackParameters(formatConfig.enablePlaybackParameters)
                .setEnableOffload(formatConfig.enableOffload)
                .setAudioSessionId(formatConfig.audioSessionId)
                .setVirtualDeviceId(formatConfig.virtualDeviceId)
                .setEnableTunneling(false)
                .setPreferredBufferSize(if (formatConfig.preferredBufferSize > 0)
                    (formatConfig.preferredBufferSize / 2).coerceAtLeast(1) else formatConfig.preferredBufferSize)
                .build()
            val compatible = try { delegate.getOutputConfig(integerConfig) }
                catch (failure: Exception) { failure.addSuppressed(error); throw failure }
            Log.w("UsbPcmOutput", "Float configuration rejected; using PCM16-compatible buffer", error)
            return compatible.buildUpon().setEncoding(C.ENCODING_PCM_FLOAT)
                .setBufferSize(Math.multiplyExact(compatible.bufferSize, 2)).build()
        }
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        // Keep the provider-facing config in Float: Media3 still processes/ramp-scales Float
        // buffers. Only the underlying track gets integer data. The writer maps consumed
        // bytes back to Float samples; frame counts and presentation timestamps are unchanged.
        val input = PcmOutputFormat(config.encoding, config.sampleRate, config.channelMask)
        val native = if (config.isOffload || config.isTunneling) input else preferences.nativeOutputFormat(input)
        var actualConfig = config.buildUpon().setEncoding(native.encoding).build()
        val created = try {
            createOutput(actualConfig)
        } catch (error: AudioOutputProvider.InitializationException) {
            if (config.encoding != C.ENCODING_PCM_FLOAT || config.isOffload || config.isTunneling) throw error
            val minimum = AudioTrack.getMinBufferSize(config.sampleRate, config.channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val frameSize = (Integer.bitCount(config.channelMask and 0x3fffffff) * 2).coerceAtLeast(2)
            val size = maxOf(config.bufferSize / 2, minimum, frameSize)
            actualConfig = config.buildUpon().setEncoding(C.ENCODING_PCM_16BIT)
                .setBufferSize(((size.toLong() + frameSize - 1) / frameSize * frameSize).toInt()).build()
            Log.w("UsbPcmOutput", "High-resolution output unavailable; retrying PCM16 at ${config.sampleRate}Hz", error)
            try { createOutput(actualConfig) }
                catch (failure: AudioOutputProvider.InitializationException) { failure.addSuppressed(error); throw failure }
        }
        val (output, generation) = created
        (output as? AudioTrackAudioOutput)?.let { monitor.observeTrack(it.audioTrack, generation) }
        if (actualConfig.encoding == input.encoding) return output
        Log.i("UsbPcmOutput", "Converting Float to encoding=${actualConfig.encoding}: ${config.sampleRate}Hz, mask=${config.channelMask}")
        return object : AudioOutput by output {
            private val integer16 = actualConfig.encoding == C.ENCODING_PCM_16BIT
            private val writer16 = FloatPcm16Writer()
            private val writer32 = FloatPcm32Writer()
            override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean =
                if (integer16) writer16.write(buffer) { output.write(it, encodedAccessUnitCount, presentationTimeUs) }
                else writer32.write(buffer) { output.write(it, encodedAccessUnitCount, presentationTimeUs) }
            override fun flush() { writer16.reset(); writer32.reset(); output.flush() }
            override fun release() { writer16.reset(); writer32.reset(); output.release() }
        }
    }

    private fun createOutput(config: AudioOutputProvider.OutputConfig): Pair<AudioOutput, Long> {
        val generation = preferences.prepareOutput(config.encoding, config.sampleRate, config.channelMask)
        val output = try { delegate.getAudioOutput(config) }
            catch (error: AudioOutputProvider.InitializationException) {
                if (!preferences.fallBack(generation)) throw error
                Log.w("UsbPcmOutput", "Retrying AudioTrack without preferred mixer", error)
                try { delegate.getAudioOutput(config) }
                    catch (failure: AudioOutputProvider.InitializationException) { failure.addSuppressed(error); throw failure }
            }
        return output to generation
    }
}
