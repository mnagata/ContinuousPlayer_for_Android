package jp.nagu.continuousplayer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.util.Log

/** Applies exact-format USB preferences while leaving device routing to Android. */
open class BitPerfectAudioManager(private val context: Context) {
    companion object {
        private const val TAG = "BitPerfectAudio"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val rememberedDevices = mutableMapOf<Int, AudioDeviceInfo>()
    private var released = false
    private var generation = 0L
    private var outputFormat: PcmOutputFormat? = null
    private val policy = AudioOutputPolicy(object : OutputMixerBackend {
        override fun supported(deviceId: Int): List<OutputMixerConfiguration> {
            val device = connectedDevice(deviceId) ?: return emptyList()
            rememberedDevices[deviceId] = device
            return audioManager.getSupportedMixerAttributes(device).map { it.configuration() }
        }

        override fun set(preference: PreferredOutputMixer): Boolean {
            val device = connectedDevice(preference.deviceId) ?: return false
            val matched = audioManager.getSupportedMixerAttributes(device)
                .firstOrNull { it.configuration() == preference.configuration } ?: return false
            return audioManager.setPreferredMixerAttributes(attributes, device, matched).also { success ->
                if (success) {
                    rememberedDevices[device.id] = device
                    Log.i(TAG, "Preferred output: device=${device.id}, ${preference.configuration}")
                } else Log.w(TAG, "Preferred mixer rejected: device=${device.id}")
            }
        }

        override fun clear(deviceId: Int) {
            val device = rememberedDevices[deviceId] ?: connectedDevice(deviceId) ?: return
            if (!audioManager.clearPreferredMixerAttributes(attributes, device) &&
                audioManager.getPreferredMixerAttributes(attributes, device) != null) {
                Log.w(TAG, "Could not clear preferred mixer: device=$deviceId")
                error("Could not clear preferred mixer")
            }
            rememberedDevices.remove(deviceId)
        }
    }) { error -> Log.w(TAG, "Audio output preference failed; retaining standard or previous output", error) }

    private fun AudioMixerAttributes.configuration() = OutputMixerConfiguration(
        PcmOutputFormat(format.encoding, format.sampleRate, format.channelMask),
        if (mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
            OutputMixerBehavior.BIT_PERFECT else OutputMixerBehavior.DEFAULT)

    private fun connectedDevice(id: Int) =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }

    /** Select only a lossless, same-rate alternative on the OS-selected USB route. */
    @Synchronized
    internal fun nativeOutputFormat(input: PcmOutputFormat): PcmOutputFormat {
        if (released || input.encoding != AudioFormat.ENCODING_PCM_FLOAT) return input
        return runCatching {
            val device = audioManager.getAudioDevicesForAttributes(attributes).singleOrNull()
                ?.takeIf { it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ?: return input
            val supported = audioManager.getSupportedMixerAttributes(device).map { it.configuration() }
            selectUsbPcmFormat(input, supported, AudioFormat.ENCODING_PCM_FLOAT,
                AudioFormat.ENCODING_PCM_32BIT)
        }.getOrElse { error ->
            Log.w(TAG, "Could not query USB PCM formats; retaining standard output", error)
            input
        }
    }

    /** Called on the playback thread before creating each AudioTrack. */
    @Synchronized
    internal fun prepareOutput(encoding: Int, sampleRate: Int, channelMask: Int): Long {
        if (released) return generation
        generation++
        outputFormat = if (encoding in setOf(AudioFormat.ENCODING_PCM_8BIT,
                AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED,
                AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT))
            PcmOutputFormat(encoding, sampleRate, channelMask) else null
        Log.d(TAG, "AudioTrack format: $outputFormat; generation=$generation")
        refreshOutput()
        return generation
    }

    /** Candidates are used for configuration only, never presented as an observed route. */
    @Synchronized
    internal fun refreshOutput(environmentChanged: Boolean = false) {
        if (released) return
        if (environmentChanged) policy.environmentChanged()
        val devices = runCatching { audioManager.getAudioDevicesForAttributes(attributes) }
            .getOrDefault(emptyList())
        configureRoute(devices.singleOrNull())
    }

    /** Ignore callbacks from a track that is being replaced by a new format. */
    @Synchronized
    internal fun onRouteChanged(trackGeneration: Long, device: AudioDeviceInfo?) {
        if (!released && trackGeneration == generation && device != null) configureRoute(device)
    }

    private fun configureRoute(device: AudioDeviceInfo?) {
        val usbId = device?.takeIf { it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.id
        policy.configure(usbId, outputFormat)
    }

    @Synchronized
    internal fun fallBack(trackGeneration: Long): Boolean =
        !released && generation == trackGeneration && policy.fallBack()

    /** Terminal: late AudioTrack callbacks cannot install a new preference after release. */
    @Synchronized
    fun release() {
        released = true
        policy.close()
    }

    /** Describe the observed route and configured AudioTrack, without reopening the media. */
    @Synchronized
    internal open fun getOutputInformation(track: AudioTrackInformation): AudioOutputInformation {
        val base = AudioOutputInformation(
            encoding = track.encoding?.let { knownEncoding(it) } ?: InformationValue.Unavailable,
            sampleRate = MediaInformationText.positive(track.sampleRate, "Hz"),
            channels = MediaInformationText.positive(track.channels, "ch"),
            mixerDiagnostics = when (track.mixerDiagnosticsState) {
                MixerDiagnosticsState.PERMISSION_REQUIRED -> InformationValue.Text(context.getString(R.string.info_diagnostics_permission))
                MixerDiagnosticsState.WAITING -> InformationValue.Text(context.getString(R.string.info_diagnostics_waiting))
                MixerDiagnosticsState.UNAVAILABLE -> InformationValue.Unavailable
                MixerDiagnosticsState.DISABLED, null -> null
            })
        val device = track.device?.takeIf { candidate ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.id == candidate.id }
        } ?: return base
        val type = getDeviceTypeName(device.type)
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() && it != type }
        val deviceLabel = if (name == null) type else "$name / $type"
        val observation = when (track.routeObservation) {
            AudioRouteObservation.LIVE -> InformationValue.Text(context.getString(R.string.info_route_live))
            AudioRouteObservation.LAST_KNOWN -> InformationValue.Text(context.getString(R.string.info_route_last_known))
            null -> InformationValue.Unavailable
        }
        var named = base.copy(device = InformationValue.Text(deviceLabel),
            route = InformationValue.Text(context.getString(R.string.info_route_value, deviceLabel)),
            routeObservation = observation,
            routeConfirmedAt = MediaInformationText.routeConfirmationTime(track.routeConfirmedAtMs))
        track.mixerSnapshot?.takeIf { it.deviceId == device.id &&
            track.mixerDiagnosticsState == MixerDiagnosticsState.WAITING }?.let { snapshot ->
            named = named.copy(
                route = InformationValue.Text(context.getString(R.string.info_diagnostic_route_value, snapshot.profile, deviceLabel)),
                mixerDiagnostics = InformationValue.Text(context.getString(R.string.info_diagnostics_recorded)),
                actualMixer = InformationValue.Text("${snapshot.profile} (${snapshot.type}, I/O ${snapshot.ioHandle})"),
                actualMixerFormat = InformationValue.Text(snapshot.mixerFormat.display()),
                halFormat = InformationValue.Text(snapshot.halFormat.display()),
                mixerConfirmedAt = MediaInformationText.routeConfirmationTime(snapshot.confirmedAtMs))
        }
        val usb = device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        val supported = try { audioManager.getSupportedMixerAttributes(device) }
            catch (_: Exception) { return named.copy(bitPerfect = if (usb) InformationValue.Unavailable else InformationValue.Unsupported) }
        if (supported.isEmpty()) return named.copy(bitPerfect = InformationValue.Unsupported, mixer = InformationValue.Unsupported)
        val attributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val preferred = try { audioManager.getPreferredMixerAttributes(attributes, device) }
            catch (_: Exception) { return named.copy(bitPerfect = if (usb) InformationValue.Unavailable else InformationValue.Unsupported) }
        val bitPerfect = when {
            !usb || supported.none { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT } -> InformationValue.Unsupported
            !released && policy.applied?.deviceId == device.id &&
                preferred?.configuration() == policy.applied?.configuration &&
                preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                preferred.format.encoding == track.encoding && preferred.format.sampleRate == track.sampleRate &&
                preferred.format.channelMask == track.channelMask ->
                InformationValue.Text(context.getString(R.string.info_configured))
            else -> InformationValue.NotSet
        }
        if (preferred == null) return named.copy(bitPerfect = bitPerfect, mixer = InformationValue.NotSet)
        return named.copy(bitPerfect = bitPerfect, mixer = InformationValue.Text(mixerBehaviorName(preferred.mixerBehavior)),
            mixerEncoding = knownEncoding(preferred.format.encoding),
            mixerSampleRate = MediaInformationText.positive(preferred.format.sampleRate, "Hz"),
            mixerChannels = MediaInformationText.positive(preferred.format.channelCount, "ch"))
    }

    private fun knownEncoding(encoding: Int): InformationValue = encodingName(encoding)
        .takeUnless { it.startsWith("Unknown") }?.let { InformationValue.Text(it) } ?: InformationValue.Unavailable

    /**
     * 出力デバイスとミキサー状態の概要を返す（UIスレッドから呼べる軽量版）。
     */
    @Synchronized
    fun getOutputSummary(outputDevice: AudioDeviceInfo?): String {
        val sb = StringBuilder()

        val deviceName = outputDevice?.productName?.takeIf { it.isNotBlank() }
            ?: getDeviceTypeName(outputDevice?.type)
        sb.append("Output: $deviceName")

        // Bit-perfect status
        if (!released && outputDevice != null && policy.applied?.deviceId == outputDevice.id &&
            policy.applied?.configuration?.behavior == OutputMixerBehavior.BIT_PERFECT) {
            sb.append("  [Bit-Perfect設定]")
        }

        // Preferred mixer info
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val mixerDevices = listOfNotNull(outputDevice).filter { device ->
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
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        else -> context.getString(R.string.info_unavailable)
    }

    private fun mixerBehaviorName(behavior: Int): String = when (behavior) {
        AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "Default"
        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "Bit-Perfect"
        else -> "Unknown ($behavior)"
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

}
