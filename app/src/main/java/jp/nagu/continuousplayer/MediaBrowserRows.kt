package jp.nagu.continuousplayer

import android.content.Context

/** One presentation for SAF, USB (including registration), and DLNA media. */
internal fun mediaBrowserRows(context: Context, media: List<VideoItem>, folderTitles: List<String>): List<DlnaRow> {
    val ambiguous = ambiguousMediaTitles(media, folderTitles)
    return media.map { item ->
        val audio = item.mimeType?.startsWith("audio/") == true || item.formatLabel in setOf(
            "MP3", "FLAC", "M4A", "AAC", "WAV", "OGG", "OGA", "OPUS", "WMA", "AIF", "AIFF", "AIFC",
            "ALAC", "APE", "MKA", "AC3", "EAC3", "DTS", "MP2")
        val format = item.formatLabel ?: context.getString(R.string.media_format_unknown)
        val action = context.getString(if (audio) R.string.dlna_audio_detail else R.string.dlna_video_detail)
        DlnaRow(item.selectionTitle, if (audio) R.drawable.ic_dlna_audio else R.drawable.ic_play,
            description = "${item.selectionTitle}。${context.getString(R.string.media_original_name)}：${item.displayName}。${context.getString(R.string.media_format_value, format)}。$action",
            media = item, formatBadge = format.takeIf { item.selectionTitle in ambiguous })
    }
}
