package jp.nagu.continuousplayer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.MediaExtractor
import android.net.Uri
import android.util.Log

/**
 * USBオーディオデバイスのビットパーフェクト再生を管理するクラス。
 *
 * USB DACが接続されている場合、[AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT] モードを
 * 設定し、動画の音声をビットパーフェクトで出力する。
 * 使用後は [release] でミキサー設定をクリアすること。
 */
class BitPerfectAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "BitPerfectAudio"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeDevice: AudioDeviceInfo? = null

    /**
     * 動画のオーディオフォーマットに基づいてビットパーフェクトモードを設定する。
     *
     * USBオーディオデバイスが接続されていない場合、またはBIT_PERFECTモード非対応の場合は何もしない。
     *
     * @param videoUri 再生する動画のURI（オーディオフォーマット抽出に使用）
     */
    fun configure(videoUri: Uri) {
        try {
            val usbDevice = findUsbOutputDevice() ?: run {
                Log.d(TAG, "No USB audio output device found")
                return
            }

            val allMixerAttrs = audioManager.getSupportedMixerAttributes(usbDevice)
            Log.d(TAG, "Supported mixer attributes: ${allMixerAttrs.size}")
            allMixerAttrs.forEach { attr ->
                Log.d(TAG, "  behavior=${mixerBehaviorName(attr.mixerBehavior)}, " +
                    "sampleRate=${attr.format.sampleRate}, " +
                    "channels=${attr.format.channelCount}, " +
                    "encoding=${encodingName(attr.format.encoding)}")
            }

            val bitPerfectAttrs = allMixerAttrs
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }

            if (bitPerfectAttrs.isEmpty()) {
                Log.d(TAG, "No BIT_PERFECT mixer attributes supported")
                return
            }

            val audioFormat = extractAudioFormat(videoUri)
            if (audioFormat == null) {
                Log.d(TAG, "Could not extract audio format from video")
                return
            }
            Log.d(TAG, "Source audio: sampleRate=${audioFormat.sampleRate}, channels=${audioFormat.channelCount}")

            val matched = bitPerfectAttrs.firstOrNull { attr ->
                val fmt = attr.format
                fmt.sampleRate == audioFormat.sampleRate &&
                    fmt.channelCount == audioFormat.channelCount
            } ?: bitPerfectAttrs.first()

            Log.d(TAG, "Selected mixer: " +
                "sampleRate=${matched.format.sampleRate}, " +
                "channels=${matched.format.channelCount}, " +
                "encoding=${encodingName(matched.format.encoding)}")

            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val success = audioManager.setPreferredMixerAttributes(
                audioAttributes, usbDevice, matched
            )
            if (success) {
                activeDevice = usbDevice
                Log.i(TAG, "BIT_PERFECT mode enabled: ${matched.format}")
            } else {
                Log.w(TAG, "Failed to set preferred mixer attributes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mixer configuration failed", e)
        }
    }

    /** ビットパーフェクトモードを解除し、ミキサー設定をデフォルトに戻す。 */
    fun release() {
        val device = activeDevice ?: return
        try {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioManager.clearPreferredMixerAttributes(audioAttributes, device)
            activeDevice = null
            Log.i(TAG, "Preferred mixer released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear mixer attributes", e)
        }
    }

    /**
     * 現在のオーディオ出力状態を人間可読な文字列で返す。
     *
     * 出力デバイス名、ビットパーフェクト状態、サンプルレート、チャンネル数、
     * ミキサーアトリビュート情報を含む。
     *
     * @param videoUri 現在再生中の動画URI（フォーマット情報抽出用、nullの場合はスキップ）
     * @return フォーマット済みのオーディオ情報文字列
     */
    fun getAudioOutputInfo(videoUri: Uri?): String {
        val sb = StringBuilder()

        // Output device
        val outputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
        val deviceName = outputDevice?.productName?.takeIf { it.isNotBlank() }
            ?: getDeviceTypeName(outputDevice?.type)
        sb.appendLine("Output: $deviceName")

        // Bit-perfect status
        if (activeDevice != null) {
            sb.appendLine("Mode: Bit-Perfect")
        } else {
            sb.appendLine("Mode: Standard")
        }

        // Audio format from current video
        if (videoUri != null) {
            val format = extractAudioFormat(videoUri)
            if (format != null) {
                sb.appendLine("Sample Rate: ${format.sampleRate} Hz")
                sb.appendLine("Channels: ${format.channelCount}")
            }
        }

        // AudioMixerAttributes info for all output devices
        val allOutputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val mixerDevices = allOutputDevices.filter { device ->
            try {
                audioManager.getSupportedMixerAttributes(device).isNotEmpty()
            } catch (e: Exception) {
                false
            }
        }

        if (mixerDevices.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- Mixer Attributes ---")

            val audioAttrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            for (device in mixerDevices) {
                val name = device.productName?.takeIf { it.isNotBlank() }
                    ?: getDeviceTypeName(device.type)
                sb.appendLine("Device: $name")

                val preferred = audioManager.getPreferredMixerAttributes(audioAttrs, device)
                if (preferred != null) {
                    sb.appendLine("  Preferred:")
                    sb.appendLine("    Behavior: ${mixerBehaviorName(preferred.mixerBehavior)}")
                    appendFormatInfo(sb, preferred.format, "    ")
                } else {
                    sb.appendLine("  Preferred: None")
                }
            }
        } else {
            sb.appendLine()
            sb.appendLine("--- Mixer Attributes ---")
            sb.appendLine("No mixer attributes available")
        }

        return sb.toString().trimEnd()
    }

    /**
     * 出力デバイスとミキサー状態の概要を返す（UIスレッドから呼べる軽量版）。
     */
    fun getOutputSummary(): String {
        val sb = StringBuilder()

        // Output device
        val outputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
        val deviceName = outputDevice?.productName?.takeIf { it.isNotBlank() }
            ?: getDeviceTypeName(outputDevice?.type)
        sb.append("Output: $deviceName")

        // Bit-perfect status
        if (activeDevice != null) {
            sb.append("  [Bit-Perfect]")
        }

        // Preferred mixer info
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val mixerDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
            try {
                audioManager.getSupportedMixerAttributes(device).isNotEmpty()
            } catch (_: Exception) { false }
        }
        for (device in mixerDevices) {
            val preferred = audioManager.getPreferredMixerAttributes(audioAttrs, device)
            if (preferred != null) {
                val fmt = preferred.format
                sb.append("\nMixer: ${encodingName(fmt.encoding)}  ${fmt.sampleRate}Hz  ${fmt.channelCount}ch")
            }
        }

        return sb.toString()
    }

    private fun getDeviceTypeName(type: Int?): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Built-in Speaker"
    }

    private fun mixerBehaviorName(behavior: Int): String = when (behavior) {
        AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "Default"
        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "Bit-Perfect"
        else -> "Unknown ($behavior)"
    }

    private fun appendFormatInfo(sb: StringBuilder, format: AudioFormat, indent: String) {
        sb.appendLine("${indent}Sample Rate: ${format.sampleRate} Hz")
        sb.appendLine("${indent}Channels: ${format.channelCount}")
        sb.appendLine("${indent}Encoding: ${encodingName(format.encoding)}")
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM Float"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "E-AC3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_AAC_LC -> "AAC-LC"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        AudioFormat.ENCODING_OPUS -> "Opus"
        else -> "Unknown ($encoding)"
    }

    private fun findUsbOutputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devices.forEach { d ->
            Log.d(TAG, "Output device: type=${d.type}, name=${d.productName}, id=${d.id}")
        }
        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun extractAudioFormat(uri: Uri): AudioFormatInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    return AudioFormatInfo(sampleRate, channelCount)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract audio format", e)
            null
        } finally {
            extractor.release()
        }
    }

    private data class AudioFormatInfo(val sampleRate: Int, val channelCount: Int)
}
