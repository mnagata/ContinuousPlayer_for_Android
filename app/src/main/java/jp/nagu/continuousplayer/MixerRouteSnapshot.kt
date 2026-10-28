package jp.nagu.continuousplayer

internal enum class MixerDiagnosticsState { DISABLED, PERMISSION_REQUIRED, WAITING, UNAVAILABLE }

internal data class DiagnosticPcmFormat(val encoding: String, val sampleRate: Int, val channels: Int) {
    fun display(): String = "$encoding / $sampleRate Hz / $channels ch"
}

internal data class MixerRouteSnapshot(
    val sessionId: Int, val deviceId: Int, val ioHandle: Int,
    val profile: String, val type: String,
    val mixerFormat: DiagnosticPcmFormat, val halFormat: DiagnosticPcmFormat,
    val confirmedAtMs: Long
)

/** Fail closed on unknown dump layouts, ambiguous clients or an unmatched physical-device patch. */
internal object MixerRouteParser {
    private val outputHeader = Regex("(?m)^  \\d+\\. Port ID:.*?I/O handle: (\\d+); IOProfile name:([^;\\n]+);.*$")
    private val anyOutputHeader = Regex("(?m)^  \\d+\\. Port ID:")
    private val format = Regex("AUDIO_FORMAT_([A-Z0-9_]+); (\\d+); Channel mask: 0x([a-fA-F0-9]+)")
    private fun encoding(value: String): String? = when (value) {
        "PCM_FLOAT" -> "PCM Float"
        "PCM_16_BIT" -> "PCM 16-bit"
        "PCM_32_BIT" -> "PCM 32-bit"
        "PCM_24_BIT_PACKED" -> "PCM 24-bit"
        "PCM_8_24_BIT" -> "PCM 8.24-bit"
        "PCM_8_BIT" -> "PCM 8-bit"
        else -> null
    }

    fun parse(policy: String, flinger: String, uid: Int, sessionId: Int, deviceId: Int,
        confirmedAtMs: Long): MixerRouteSnapshot? {
        if (uid <= 0 || sessionId <= 0 || deviceId <= 0) return null
        val outputs = policy.substringAfter("\n Outputs (", "").substringBefore("\n Inputs (")
        val client = Regex("Port ID: (\\d+); Session ID: $sessionId; uid $uid; State: Active\\b")
        val candidates = outputHeader.findAll(outputs).mapNotNull { header ->
            val end = anyOutputHeader.find(outputs, header.range.last + 1)?.range?.first ?: outputs.length
            val block = outputs.substring(header.range.first, end)
            val port = client.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            Triple(header, block, port)
        }.toList()
        val (header, block, clientPort) = candidates.singleOrNull() ?: return null
        val io = header.groupValues[1].toIntOrNull() ?: return null
        val profile = header.groupValues[2].trim().takeIf { it.matches(Regex("[A-Za-z0-9_. -]{1,100}")) } ?: return null
        val mixer = format.find(block) ?: return null
        val mixerRate = mixer.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val mask = mixer.groupValues[3].toLongOrNull(16)?.toInt() ?: return null
        // Index channel masks have a representation bit which is not a channel.
        val mixerChannels = Integer.bitCount(mask and 0x3fffffff).takeIf { it > 0 } ?: return null
        if (encoding(mixer.groupValues[1]) == null) return null

        val patches = policy.substringAfter("\n Audio Patches (", "").substringBefore("\n Audio Policy Mix:")
        val patchHeader = Regex("(?m)^  \\d+\\. owner uid")
        val patchBlocks = patchHeader.findAll(patches).map { h ->
            val end = patchHeader.find(patches, h.range.last + 1)?.range?.first ?: patches.length
            patches.substring(h.range.first, end)
        }
        val patched = patchBlocks.any { patch ->
            Regex("I/O handle: $io;").containsMatchIn(patch) &&
                Regex("Device Port ID: (\\d+);").findAll(patch).map { it.groupValues[1].toInt() }.toList() == listOf(deviceId)
        }
        if (!patched) return null

        val live = flinger.substringBefore("Historical Thread Log")
        val threadHeader = Regex("(?m)^Output thread [^\\n]*type \\d+ \\(([A-Z_]+)\\):$")
        val threads = threadHeader.findAll(live).map { h ->
            val end = threadHeader.find(live, h.range.last + 1)?.range?.first ?: live.length
            h to live.substring(h.range.first, end)
        }.filter { (_, text) -> Regex("(?m)^  I/O handle: $io$").containsMatchIn(text) }.toList()
        val (thread, text) = threads.singleOrNull() ?: return null
        if (!Regex("(?m)^  Standby: no$").containsMatchIn(text)) return null
        // Bind BOTH dumps to this track. No cached thread, other app, or inactive system track qualifies.
        if (!Regex("(?m)^\\s*(?:[A-Z]\\s+)?\\d+\\s+yes\\s+\\d+/\\s*$uid\\s+$sessionId\\s+$clientPort\\s+A\\s+")
                .containsMatchIn(text)) return null
        val rate = Regex("(?m)^  Sample rate: (\\d+) Hz$").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (rate != mixerRate) return null
        val channels = Regex("(?m)^  Channel count: (\\d+)$").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        val hal = Regex("(?m)^  HAL format: 0x[0-9a-fA-F]+ \\(AUDIO_FORMAT_([A-Z0-9_]+)\\)$")
            .find(text)?.groupValues?.get(1)?.let(::encoding) ?: return null
        val processing = Regex("(?m)^  Processing format: 0x[0-9a-fA-F]+ \\(AUDIO_FORMAT_([A-Z0-9_]+)\\)$")
            .find(text)?.groupValues?.get(1)?.let(::encoding) ?: return null
        if (channels != mixerChannels) return null
        return MixerRouteSnapshot(sessionId, deviceId, io, profile, thread.groupValues[1],
            DiagnosticPcmFormat(processing, mixerRate, mixerChannels),
            DiagnosticPcmFormat(hal, rate, channels), confirmedAtMs)
    }
}
