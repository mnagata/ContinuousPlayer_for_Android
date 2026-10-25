package jp.nagu.continuousplayer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/** The same transport rules apply to app controls and MediaSession callers. */
@OptIn(UnstableApi::class)
internal class PlaybackTransportPlayer(
    private val delegate: Player,
    private val requestPlay: () -> Boolean
) : ForwardingPlayer(delegate) {
    private var completedIndex = C.INDEX_UNSET
    private var completedItem: MediaItem? = null
    private var completedPosition = C.TIME_UNSET
    val isCompleted: Boolean
        get() = delegate.playbackState == Player.STATE_ENDED ||
            (completedIndex != C.INDEX_UNSET && delegate.currentMediaItemIndex == completedIndex &&
                delegate.currentMediaItem == completedItem)

    private fun clearCompletion() {
        completedIndex = C.INDEX_UNSET
        completedItem = null
        completedPosition = C.TIME_UNSET
    }

    private val endedListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // A later seek (including Media3's Play helper) must not restart under stale intent.
            if (playbackState == Player.STATE_ENDED) {
                completedIndex = delegate.currentMediaItemIndex
                completedItem = delegate.currentMediaItem
                completedPosition = delegate.currentPosition
                delegate.pause()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            clearCompletion()
        }
    }

    init { delegate.addListener(endedListener) }

    override fun play() = setPlayWhenReady(true)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            if (!requestPlay()) return
            if (isCompleted && delegate.mediaItemCount > 0) {
                clearCompletion()
                delegate.seekTo(delegate.currentMediaItemIndex, 0L)
            }
        }
        delegate.playWhenReady = playWhenReady
    }

    override fun getSeekBackIncrement(): Long = 10_000L
    override fun getSeekForwardIncrement(): Long = 10_000L
    override fun seekBack() = seekBackwardBy(seekBackIncrement)
    override fun seekForward() = seekForwardBy(seekForwardIncrement)

    private fun canSeek(command: Int): Boolean = delegate.isCurrentMediaItemSeekable &&
        delegate.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && delegate.isCommandAvailable(command)

    fun seekBackwardBy(increment: Long) {
        if (increment <= 0 || !canSeek(Player.COMMAND_SEEK_BACK)) return
        val position = delegate.currentPosition.coerceAtLeast(0)
        clearCompletion()
        delegate.seekTo(if (increment >= position) 0 else position - increment)
    }

    fun seekForwardBy(increment: Long) {
        if (increment <= 0 || !canSeek(Player.COMMAND_SEEK_FORWARD)) return
        val position = delegate.currentPosition.coerceAtLeast(0)
        val duration = delegate.duration
        if (duration >= 0 && (position >= duration || increment >= duration - position)) {
            seekToNextMediaItem()
        } else {
            clearCompletion()
            delegate.seekTo(if (increment > Long.MAX_VALUE - position) Long.MAX_VALUE else position + increment)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!canSeek(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val position = boundedPosition(positionMs)
        // Modal restoration may clamp the final sample by 1ms and leave the engine READY.
        // Restoring the recorded end is still completion; an explicit seek away clears it.
        if (position == C.TIME_UNSET || position < completedPosition) clearCompletion()
        delegate.seekTo(position)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == delegate.currentMediaItemIndex) {
            seekTo(positionMs)
        } else if (mediaItemIndex in 0 until delegate.mediaItemCount &&
            delegate.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) {
            clearCompletion()
            delegate.seekTo(mediaItemIndex, if (positionMs == C.TIME_UNSET) C.TIME_UNSET else positionMs.coerceAtLeast(0))
        }
    }

    override fun seekToDefaultPosition() {
        if (canSeek(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
            clearCompletion()
            delegate.seekToDefaultPosition()
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == delegate.currentMediaItemIndex) seekToDefaultPosition()
        else seekTo(mediaItemIndex, 0L)
    }

    override fun seekToNextMediaItem() = seekAdjacent(next = true, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    override fun seekToNext() = seekAdjacent(next = true, Player.COMMAND_SEEK_TO_NEXT)
    override fun seekToPreviousMediaItem() = seekAdjacent(next = false, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    override fun seekToPrevious() = seekAdjacent(next = false, Player.COMMAND_SEEK_TO_PREVIOUS)

    private fun seekAdjacent(next: Boolean, command: Int) {
        if (!delegate.isCommandAvailable(command)) return
        val index = if (next) delegate.nextMediaItemIndex else delegate.previousMediaItemIndex
        if (index != C.INDEX_UNSET) {
            clearCompletion()
            delegate.seekTo(index, 0L)
        }
    }

    private fun boundedPosition(position: Long): Long {
        if (position == C.TIME_UNSET) return position
        val nonNegative = position.coerceAtLeast(0)
        return if (delegate.duration >= 0) nonNegative.coerceAtMost(delegate.duration) else nonNegative
    }

    override fun release() {
        delegate.removeListener(endedListener)
        super.release()
    }
}
