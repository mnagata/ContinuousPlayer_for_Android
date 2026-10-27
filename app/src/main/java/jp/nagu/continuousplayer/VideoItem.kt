package jp.nagu.continuousplayer

import java.util.Locale

/**
 * 動画ファイルのメタデータを保持するデータクラス。
 *
 * @property uri メディアURI（content / file / DLNAのhttp(s) URI）
 * @property displayName 表示用ファイル名
 * @property size ファイルサイズ（バイト）。DLNAで不明の場合は-1。
 * @property lastModified 最終更新日時（エポックミリ秒）
 */
data class VideoItem(
    val uri: String,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String? = null,
    /** Stable DLNA identity, retained when the server's media URL changes. */
    val dlnaObjectId: String? = null
) {
    /** File browsers hide media suffixes; DLNA titles without a suffix stay intact. */
    internal val selectionTitle: String
        get() = displayName.replace(mediaExtension, "").ifEmpty { displayName }

    /** Report the named container/format, never infer a codec from it. */
    internal val formatLabel: String?
        get() = mediaExtension.find(displayName)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
            ?: mimeFormats[mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)]

    private companion object {
        val mimeFormats = mapOf(
            "video/mp4" to "MP4", "audio/mp4" to "M4A", "audio/x-m4a" to "M4A",
            "video/x-matroska" to "MKV", "audio/x-matroska" to "MKA", "video/webm" to "WEBM",
            "video/quicktime" to "MOV", "video/x-msvideo" to "AVI", "video/x-ms-wmv" to "WMV",
            "video/mpeg" to "MPEG", "video/mp2t" to "TS", "video/3gpp" to "3GP",
            "audio/mpeg" to "MP3", "audio/aac" to "AAC", "audio/flac" to "FLAC", "audio/x-flac" to "FLAC",
            "audio/wav" to "WAV", "audio/x-wav" to "WAV", "audio/ogg" to "OGG", "audio/opus" to "OPUS",
            "video/ogg" to "OGV", "audio/aiff" to "AIFF", "audio/x-aiff" to "AIFF", "audio/x-ms-wma" to "WMA"
        )
        // Include common DLNA formats in addition to the local scanner's supported formats.
        // Recognizing suffixes preserves dots in titles such as "Dr.STONE" or "第1.5話".
        val mediaExtension = Regex(
            "\\.(mp4|m4v|mp3|flac|m4a|aac|wav|ogg|opus|mkv|avi|mov|webm|wmv|mpg|mpeg|" +
                "m2v|ts|mts|m2ts|vob|asf|flv|3gp|3g2|ogv|oga|wma|aif|aiff|aifc|alac|ape|mka|ac3|eac3|dts|mp2)$",
            RegexOption.IGNORE_CASE
        )
    }
}

/** Count visible names across the directory, without changing media order or identities. */
internal fun ambiguousMediaTitles(media: List<VideoItem>, folderTitles: List<String> = emptyList()): Set<String> =
    (media.map { it.selectionTitle } + folderTitles).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
