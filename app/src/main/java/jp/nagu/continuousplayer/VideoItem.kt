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
    val mimeType: String? = null
)
