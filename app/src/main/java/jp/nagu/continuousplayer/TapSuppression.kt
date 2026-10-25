package jp.nagu.continuousplayer

internal enum class TapRegion { REWIND, TOGGLE, FORWARD }

/** Suppress nearby additional taps without postponing the first completed tap. */
internal class TapSuppression(systemDoubleTapTimeoutMs: Long, private val proximityPx: Float) {
    private val intervalMs = maxOf(300L, systemDoubleTapTimeoutMs)
    private data class AcceptedTap(val region: TapRegion, val x: Float, val y: Float, val upTime: Long)
    private var previous: AcceptedTap? = null

    fun accept(region: TapRegion, x: Float, y: Float, downTime: Long, upTime: Long): Boolean {
        val last = previous
        if (last != null && last.region == region) {
            val elapsed = downTime - last.upTime
            val dx = x - last.x
            val dy = y - last.y
            if (elapsed in 0..intervalMs && dx * dx + dy * dy <= proximityPx * proximityPx) return false
        }
        // Ignored taps do not extend the window anchored at the accepted tap.
        previous = AcceptedTap(region, x, y, upTime)
        return true
    }

    fun reset() { previous = null }
}
