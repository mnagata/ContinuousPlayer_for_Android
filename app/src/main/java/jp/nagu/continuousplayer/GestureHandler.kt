package jp.nagu.continuousplayer

import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Handle immediate taps and classify single-pointer swipes at release. */
class GestureHandler(
    private val view: View,
    private val controller: PlayerController,
    private val onPlayPauseToggled: () -> Unit = {}
) : GestureDetector.SimpleOnGestureListener() {
    private val configuration = ViewConfiguration.get(view.context)
    // Receive each completed tap immediately; suppress extra taps without a confirmation timer.
    private val detector = GestureDetector(view.context, this).apply { setOnDoubleTapListener(null) }
    private val touchSlop = configuration.scaledTouchSlop.toFloat()
    private val tapSuppression = TapSuppression(
        systemDoubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
        proximityPx = configuration.scaledDoubleTapSlop.toFloat()
    )
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var tapEligible = false
    private var pointerInput = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        val isPointer = event.isPointerInput()
        if (isPointer && (event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE ||
                event.classification == MotionEvent.CLASSIFICATION_PINCH ||
                (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL &&
                    event.buttonState != MotionEvent.BUTTON_PRIMARY))) {
            cancel()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount > 1 ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP
        ) {
            // Once a second finger appears, reject the whole stream until a fresh DOWN.
            cancel()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (pointerId != MotionEvent.INVALID_POINTER_ID || isPointer != pointerInput) cancel()
            pointerInput = isPointer
            // Holding a primary mouse button is still a click when released without dragging.
            detector.setIsLongpressEnabled(!isPointer)
            pointerId = event.getPointerId(0)
            startX = event.x
            startY = event.y
            tapEligible = true
            detector.onTouchEvent(event)
            return true
        }
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return true
        if (isPointer != pointerInput) { cancel(); return true }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) {
            cancel()
            return true
        }
        for (history in 0 until event.historySize) {
            updateTapEligibility(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
        }
        updateTapEligibility(event.getX(index), event.getY(index))
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> detector.onTouchEvent(event)
            MotionEvent.ACTION_UP -> {
                val direction = if (isPointer) null else SwipeDirection.fromDistance(event.getX(index) - startX,
                    event.getY(index) - startY, view.resources.displayMetrics.density)
                if (direction != null || !tapEligible) {
                    // A drag never becomes a tap, including after an accepted nearby tap.
                    cancel()
                    when (direction) {
                        SwipeDirection.PREVIOUS -> controller.previousVideo()
                        SwipeDirection.NEXT -> controller.nextVideo()
                        null -> Unit
                    }
                } else {
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    detector.onTouchEvent(event)
                }
            }
            else -> cancel()
        }
        return true
    }

    private fun updateTapEligibility(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy > touchSlop * touchSlop) tapEligible = false
    }

    fun cancel() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        tapEligible = false
        tapSuppression.reset()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(event) } finally { event.recycle() }
    }

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val third = view.width / 3f
        val region = when {
            e.isPointerInput() -> TapRegion.TOGGLE
            e.x < third -> TapRegion.REWIND
            e.x < third * 2 -> TapRegion.TOGGLE
            else -> TapRegion.FORWARD
        }
        if (tapSuppression.accept(region, e.x, e.y, e.downTime, e.eventTime)) {
            when (region) {
                TapRegion.REWIND -> controller.seekBackward(10_000L)
                TapRegion.TOGGLE -> {
                    controller.togglePlayPause()
                    onPlayPauseToggled()
                }
                TapRegion.FORWARD -> controller.seekForward(10_000L)
            }
        }
        return true
    }
}
