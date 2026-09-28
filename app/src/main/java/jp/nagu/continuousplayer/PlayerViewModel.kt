package jp.nagu.continuousplayer

import androidx.lifecycle.ViewModel

/**
 * 再生状態を画面回転等のコンフィグ変更を跨いで保持するViewModel。
 *
 * @property videos 現在のプレイリスト
 * @property isPlayerScreen プレーヤー画面を表示中かどうか
 */
class PlayerViewModel : ViewModel() {
    var videos: List<VideoItem> = emptyList()
    internal var playbackFolder: PlaybackFolder? = null
    var isPlayerScreen: Boolean = false
}
