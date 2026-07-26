package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

/**
 * ExoPlayerをラップし、プレイリスト再生・シーク・前後スキップを提供するコントローラ。
 *
 * [MediaSession] を保持しており、Bluetoothリモコン等のメディアボタンイベントを
 * ExoPlayerに自動的にルーティングする。
 * 再生エラー発生時は自動的に次のトラックへスキップする。
 * 使用後は [release] を呼んでリソースを解放すること。
 */
class PlayerController(context: Context) {

    companion object {
        private const val TAG = "PlayerController"
        private const val MAX_ERROR_COUNT = 3
        }

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        }

    private val mediaSession: MediaSession = MediaSession.Builder(context, player).build()

    private var errorCount = 0

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            errorCount++
            Log.e(TAG, "Playback error ($errorCount/$MAX_ERROR_COUNT): ${error.message}")
            if (errorCount >= MAX_ERROR_COUNT) {
                Log.w(TAG, "Max errors reached, stopping playback")
                errorCount = 0
                return
                }
            val current = player.currentMediaItemIndex
            if (current < player.mediaItemCount - 1) {
                player.seekToDefaultPosition(current + 1)
                player.prepare()
              } else {
                Log.w(TAG, "Playback error on last track, stopping")
                errorCount = 0
                }
             }
           }

    init {
        player.addListener(errorListener)
        }

        /**
         * プレイリストをセットし、指定インデックスから再生を開始する。
         *
         * @param videos 再生する動画のリスト
         * @param startIndex 再生開始位置（デフォルト: 0）
         */
    fun setPlaylist(videos: List<VideoItem>, startIndex: Int = 0) {
        val items = videos.map { MediaItem.fromUri(Uri.parse(it.uri)) }
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
        }

        /** 再生中ならポーズ、ポーズ中なら再生を再開する。 */
    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        }

        /** 指定ミリ秒だけ前方にシークする。末尾を超える場合は次の動画へスキップする。 */
    fun seekForward(ms: Long = 10_000L) {
        val duration = player.duration
        if (duration != C.TIME_UNSET && player.currentPosition + ms >= duration) {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                }
               } else {
            player.seekTo(player.currentPosition + ms)
             }
          }

        /** 指定ミリ秒だけ後方にシークする（0未満にはならない）。 */
    fun seekBackward(ms: Long = 10_000L) {
        player.seekTo((player.currentPosition - ms).coerceAtLeast(0))
        }

        /** プレイリストの次の動画へスキップする。次が無い場合は何もしない。 */
    fun nextVideo() {
        Log.d(TAG, "nextVideo: current=${player.currentMediaItemIndex}, count=${player.mediaItemCount}, hasNext=${player.hasNextMediaItem()}")
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
             }
          }

        /** プレイリストの前の動画へスキップする。前が無い場合は何もしない。 */
    fun previousVideo() {
        Log.d(TAG, "previousVideo: current=${player.currentMediaItemIndex}, count=${player.mediaItemCount}, hasPrev=${player.hasPreviousMediaItem()}")
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
             }
          }

        /** プレーヤーおよびMediaSessionのリソースを解放する。 */
    fun release() {
        errorCount = 0
        player.removeListener(errorListener)
        mediaSession.release()
        player.release()
        }
}
