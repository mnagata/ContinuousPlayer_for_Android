package jp.nagu.continuousplayer

import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal sealed interface InformationValue {
    data class Text(val value: String) : InformationValue
    data object Unavailable : InformationValue
    data object Unsupported : InformationValue
    data object None : InformationValue
    data object NotSet : InformationValue
}
internal data class InformationRow(val label: Int, val value: InformationValue)
internal enum class AudioRouteObservation { LIVE, LAST_KNOWN }
internal data class MediaTrackInformation(
    val codec: String? = null, val mime: String? = null,
    val width: Int = -1, val height: Int = -1, val frameRate: Float = -1f,
    val sampleRate: Int = -1, val channels: Int = -1
)
internal data class MediaInformation(
    val name: String, val size: Long, val durationMs: Long,
    val containerMime: String? = null,
    val video: MediaTrackInformation? = null, val audio: MediaTrackInformation? = null,
    val hasVideo: Boolean? = null, val hasAudio: Boolean? = null
)
internal data class AudioOutputInformation(
    val device: InformationValue = InformationValue.Unavailable,
    val encoding: InformationValue = InformationValue.Unavailable,
    val sampleRate: InformationValue = InformationValue.Unavailable,
    val channels: InformationValue = InformationValue.Unavailable,
    val bitPerfect: InformationValue = InformationValue.Unavailable,
    val mixer: InformationValue = InformationValue.Unavailable,
    val mixerEncoding: InformationValue = InformationValue.Unavailable,
    val mixerSampleRate: InformationValue = InformationValue.Unavailable,
    val mixerChannels: InformationValue = InformationValue.Unavailable,
    val route: InformationValue = InformationValue.Unavailable,
    val routeObservation: InformationValue = InformationValue.Unavailable,
    val routeConfirmedAt: InformationValue = InformationValue.Unavailable,
    val mixerDiagnostics: InformationValue? = null,
    val actualMixer: InformationValue = InformationValue.Unavailable,
    val actualMixerFormat: InformationValue = InformationValue.Unavailable,
    val halFormat: InformationValue = InformationValue.Unavailable,
    val mixerConfirmedAt: InformationValue = InformationValue.Unavailable
)

/** Values describe known facts; absence, unavailable data and unsupported features stay distinct. */
internal object MediaInformationText {
    private val routeTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    fun routeConfirmationTime(epochMs: Long?, zone: ZoneId = ZoneId.systemDefault()): InformationValue =
        epochMs?.takeIf { it > 0 }?.let {
            InformationValue.Text(routeTimeFormat.withZone(zone).format(Instant.ofEpochMilli(it)))
        } ?: InformationValue.Unavailable

    private fun text(value: String?) = value?.takeIf { it.isNotBlank() }?.let { InformationValue.Text(it) } ?: InformationValue.Unavailable
    fun positive(value: Int?, unit: String): InformationValue =
        value?.takeIf { it > 0 }?.let { InformationValue.Text("$it $unit") } ?: InformationValue.Unavailable

    fun file(info: MediaInformation): List<InformationRow> = listOf(
        InformationRow(R.string.info_file_size, if (info.size < 0) InformationValue.Unavailable else InformationValue.Text(size(info.size))),
        InformationRow(R.string.info_duration, if (info.durationMs < 0) InformationValue.Unavailable else InformationValue.Text(duration(info.durationMs))),
        InformationRow(R.string.info_container, text(info.containerMime)))

    fun formats(info: MediaInformation): List<InformationRow> = buildList {
        if (info.hasVideo == false) add(InformationRow(R.string.info_video_track, InformationValue.None))
        else {
            add(InformationRow(R.string.info_video_codec, codec(info.video)))
            val video = info.video
            add(InformationRow(R.string.info_resolution, if (video != null && video.width > 0 && video.height > 0)
                InformationValue.Text("${video.width} × ${video.height}") else InformationValue.Unavailable))
            val fps = video?.frameRate
            add(InformationRow(R.string.info_frame_rate, if (fps != null && fps.isFinite() && fps > 0)
                InformationValue.Text(String.format(Locale.ROOT, "%.2f fps", fps)) else InformationValue.Unavailable))
        }
        if (info.hasAudio == false) add(InformationRow(R.string.info_audio_track, InformationValue.None))
        else {
            add(InformationRow(R.string.info_audio_codec, codec(info.audio)))
            add(InformationRow(R.string.info_sample_rate, positive(info.audio?.sampleRate, "Hz")))
            add(InformationRow(R.string.info_channels, positive(info.audio?.channels, "ch")))
        }
    }

    fun output(info: AudioOutputInformation, hasAudio: Boolean?): List<InformationRow> {
        if (hasAudio == false) return listOf(InformationRow(R.string.info_output, InformationValue.None))
        return buildList {
            add(InformationRow(R.string.info_output_route, info.route))
            add(InformationRow(R.string.info_output_device, info.device))
            add(InformationRow(R.string.info_track_encoding, info.encoding))
            add(InformationRow(R.string.info_track_sample_rate, info.sampleRate))
            add(InformationRow(R.string.info_track_channels, info.channels))
            if (info.mixerDiagnostics != null) {
                add(InformationRow(R.string.info_actual_mixer, info.actualMixer))
                add(InformationRow(R.string.info_actual_mixer_format, info.actualMixerFormat))
                add(InformationRow(R.string.info_hal_format, info.halFormat))
            }
            add(InformationRow(R.string.info_bit_perfect, info.bitPerfect))
            add(InformationRow(R.string.info_mixer, info.mixer))
            if (info.mixer is InformationValue.Text) {
                add(InformationRow(R.string.info_mixer_encoding, info.mixerEncoding))
                add(InformationRow(R.string.info_mixer_sample_rate, info.mixerSampleRate))
                add(InformationRow(R.string.info_mixer_channels, info.mixerChannels))
            }
        }
    }

    fun render(rows: List<InformationRow>, string: (Int) -> String): String = rows.joinToString("\n") { row ->
        val value = when (val value = row.value) {
            is InformationValue.Text -> value.value
            InformationValue.Unavailable -> string(R.string.info_unavailable)
            InformationValue.Unsupported -> string(R.string.info_unsupported)
            InformationValue.None -> string(R.string.info_none)
            InformationValue.NotSet -> string(R.string.info_not_set)
        }
        "${string(row.label)}：$value"
    }

    private fun codec(track: MediaTrackInformation?): InformationValue = text(
        listOfNotNull(track?.codec?.takeIf { it.isNotBlank() }, track?.mime?.takeIf { it.isNotBlank() }).distinct().joinToString(" / "))

    private fun size(bytes: Long): String {
        val unit = when {
            bytes >= 1_099_511_627_776L -> 1_099_511_627_776L to "TiB"
            bytes >= 1_073_741_824L -> 1_073_741_824L to "GiB"
            bytes >= 1_048_576L -> 1_048_576L to "MiB"
            bytes >= 1024L -> 1024L to "KiB"
            else -> return "$bytes B"
        }
        return String.format(Locale.ROOT, "%.1f %s", bytes.toDouble() / unit.first, unit.second)
    }
    private fun duration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
