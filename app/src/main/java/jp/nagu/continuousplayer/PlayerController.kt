package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession

/**
 * ExoPlayerをラップし、プレイリスト再生・シーク・前後スキップを提供するコントローラ。
 *
 * [MediaSession] を保持しており、Bluetoothリモコン等のメディアボタンイベントを
 * ExoPlayerに自動的にルーティングする。
 * 再生エラー発生時は自動的に次のトラックへスキップする。
 * 使用後は [release] を呼んでリソースを解放すること。
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val onPlaybackFailure: ((PlaybackException) -> Unit)? = null,
    private val onPlaybackInterrupted: (() -> Unit)? = null,
    private val isPlaybackAllowed: () -> Boolean = { true }
) {

    companion object {
        private const val TAG = "PlayerController"
        private const val MAX_ERROR_COUNT = 3
        }

    private val audioMonitor = PlaybackAudioMonitor(context.applicationContext, ::interruptPlayback)

    // Keep the default decoder/output configuration and observe the actual AudioTrack route.
    // The provider hook also covers USB/HDMI devices that do not send BECOMING_NOISY.
    @Suppress("DEPRECATION") // Use Media3's default AudioTrack construction and observe its route.
    private val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioTrackProvider { config, attributes, sessionId, trackContext ->
                DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId, trackContext)
                    .also(audioMonitor::observeTrack)
            }.build()
    }

    private val engine = ExoPlayer.Builder(context, renderers)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        }

    /** Every caller uses this gate, so focus refusal cannot start even a brief playback. */
    val player: Player = object : ForwardingPlayer(engine) {
        override fun play() = setPlayWhenReady(true)

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady && !isPlaybackAllowed()) return
            if (!playWhenReady || audioMonitor.requestFocus()) super.setPlayWhenReady(playWhenReady)
        }
    }

    val videoFormat get() = engine.videoFormat
    val audioFormat get() = engine.audioFormat

    private val mediaSession: MediaSession = MediaSession.Builder(context, player).build()

    private var errorCount = 0

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed: ${error.errorCodeName}", error)
            // A TV without ADB needs a visible error instead of silently skipping files.
            if (onPlaybackFailure != null) {
                onPlaybackFailure.invoke(error)
                return
            }
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
        val items = videos.map {
            MediaItem.Builder().setUri(Uri.parse(it.uri)).setMimeType(it.mimeType).build()
        }
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
        }

        /** 再生中ならポーズ、ポーズ中なら再生を再開する。 */
    fun togglePlayPause() {
        // Playback intent stays true while buffering or switching media items.
        if (player.playWhenReady) player.pause() else player.play()
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

    private fun interruptPlayback() {
        player.pause()
        onPlaybackInterrupted?.invoke()
    }

    /** Background sessions stay paused and must request focus again on explicit Play. */
    fun pauseForBackground() {
        player.pause()
        audioMonitor.abandonFocus()
    }

        /** プレーヤーおよびMediaSessionのリソースを解放する。 */
    fun release() {
        errorCount = 0
        audioMonitor.release()
        player.removeListener(errorListener)
        mediaSession.release()
        player.release()
        }
}
