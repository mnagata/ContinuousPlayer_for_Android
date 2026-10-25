package jp.nagu.continuousplayer

import kotlin.math.abs

internal enum class SwipeDirection {
    PREVIOUS, NEXT;

    companion object {
        fun fromDistance(dxPx: Float, dyPx: Float, density: Float): SwipeDirection? {
            val horizontal = abs(dxPx)
            if (horizontal < 70f * density || horizontal < 1.5f * abs(dyPx)) return null
            return if (dxPx > 0f) PREVIOUS else NEXT
        }
    }
}
