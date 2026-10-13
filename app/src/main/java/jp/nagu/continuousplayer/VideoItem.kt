package jp.nagu.continuousplayer

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

    private companion object {
        // Include common DLNA formats in addition to the local scanner's supported formats.
        // Recognizing suffixes preserves dots in titles such as "Dr.STONE" or "第1.5話".
        val mediaExtension = Regex(
            "\\.(mp4|m4v|mp3|flac|m4a|aac|wav|ogg|opus|mkv|avi|mov|webm|wmv|mpg|mpeg|" +
                "m2v|ts|mts|m2ts|vob|asf|flv|3gp|3g2|ogv|oga|wma|aif|aiff|aifc|alac|ape|mka|ac3|eac3|dts|mp2)$",
            RegexOption.IGNORE_CASE
        )
    }
}
