package jp.nagu.continuousplayer

/** The PCM actually written to AudioTrack, rather than the encoded source file. */
internal data class PcmOutputFormat(val encoding: Int, val sampleRate: Int, val channelMask: Int)
internal enum class OutputMixerBehavior { DEFAULT, BIT_PERFECT }
internal data class OutputMixerConfiguration(val format: PcmOutputFormat, val behavior: OutputMixerBehavior)
internal data class PreferredOutputMixer(val deviceId: Int, val configuration: OutputMixerConfiguration)

internal interface OutputMixerBackend {
    fun supported(deviceId: Int): List<OutputMixerConfiguration>
    fun set(preference: PreferredOutputMixer): Boolean
    fun clear(deviceId: Int)
}

/** Only changes preferences for the selected USB output. Never guesses a connected device. */
internal class AudioOutputPolicy(
    private val backend: OutputMixerBackend,
    private val onFailure: (Throwable) -> Unit = {}
) {
    var applied: PreferredOutputMixer? = null
        private set
    private val rejected = mutableSetOf<PreferredOutputMixer>()
    private var closed = false

    @Synchronized
    fun configure(usbDeviceId: Int?, format: PcmOutputFormat?) {
        if (closed) return
        val candidates = if (usbDeviceId == null || format == null) emptyList() else {
            runCatching { backend.supported(usbDeviceId) }.onFailure(onFailure).getOrDefault(emptyList())
                .filter { it.format == format }
                .sortedBy { if (it.behavior == OutputMixerBehavior.BIT_PERFECT) 0 else 1 }
                .map { PreferredOutputMixer(usbDeviceId, it) }
                .filterNot { it in rejected }
        }
        if (applied != null && applied == candidates.firstOrNull()) return
        if (!clear()) return
        for (candidate in candidates) {
            if (runCatching { backend.set(candidate) }.onFailure(onFailure).getOrDefault(false)) {
                applied = candidate
                return
            }
        }
    }

    /** Retry track creation without a preference that caused initialization to fail. */
    @Synchronized
    fun fallBack(): Boolean {
        val failed = applied ?: return false
        rejected.add(failed)
        return clear()
    }

    @Synchronized
    fun environmentChanged() { rejected.clear() }

    @Synchronized
    fun close() {
        closed = true
        clear()
    }

    private fun clear(): Boolean {
        val previous = applied ?: return true
        return runCatching { backend.clear(previous.deviceId) }.onFailure(onFailure)
            .onSuccess { applied = null }.isSuccess
    }
}
