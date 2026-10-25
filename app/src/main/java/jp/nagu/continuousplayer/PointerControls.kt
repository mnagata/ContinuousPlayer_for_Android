package jp.nagu.continuousplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager

/** Pointer-only reveal and inactivity handling; playback state remains owned by the activity. */
internal class PointerControls(
    private val surface: View,
    private val overlay: View,
    private val buttons: List<View>,
    private val canReveal: () -> Boolean,
    private val canAutoHide: () -> Boolean,
    private val onChanged: () -> Unit
) {
    var visible = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val accessibility = surface.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var lastActivity = 0L
    private var pointerX = Float.NaN
    private var pointerY = Float.NaN
    private var pressed = false
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> refresh() }
    private val accessibilityListener = AccessibilityManager.TouchExplorationStateChangeListener { refresh() }
    private val hide = Runnable {
        if (mayHide()) {
            visible = false
            surface.pointerIcon = PointerIcon.getSystemIcon(surface.context, PointerIcon.TYPE_NULL)
            onChanged()
        }
    }

    init {
        surface.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        accessibility.addTouchExplorationStateChangeListener(accessibilityListener)
    }

    fun onPointerEvent(event: MotionEvent) {
        if (!canReveal()) return
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            pointerX = Float.NaN
            pointerY = Float.NaN
            refresh()
            return
        }
        pointerX = event.rawX
        pointerY = event.rawY
        lastActivity = SystemClock.uptimeMillis()
        visible = true
        surface.pointerIcon = null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = false
        }
        onChanged()
        refresh()
    }

    fun refresh() {
        handler.removeCallbacks(hide)
        if (!canAutoHide()) surface.pointerIcon = null
        if (!canReveal()) {
            reset(notify = false)
        } else if (visible && mayHide()) {
            handler.postDelayed(hide, (lastActivity + 3_000 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    private fun mayHide(): Boolean = visible && canReveal() && canAutoHide() && !pressed &&
        !isOverButton() && !accessibility.isTouchExplorationEnabled && !focusIsInOverlay()

    // Re-evaluate bounds after layout too: the first reveal starts from a GONE overlay.
    private fun isOverButton(): Boolean = buttons.any { button ->
        if (!button.isShown) false else {
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            pointerX >= location[0] && pointerX < location[0] + button.width &&
                pointerY >= location[1] && pointerY < location[1] + button.height
        }
    }

    private fun focusIsInOverlay(): Boolean {
        var focus = overlay.rootView.findFocus()
        while (focus != null) {
            if (focus === overlay) return true
            focus = focus.parent as? View
        }
        return false
    }

    fun reset(notify: Boolean = true) {
        handler.removeCallbacks(hide)
        val changed = visible
        visible = false
        pressed = false
        pointerX = Float.NaN
        pointerY = Float.NaN
        surface.pointerIcon = null
        if (notify && changed) onChanged()
    }

    fun release() {
        reset(notify = false)
        if (surface.viewTreeObserver.isAlive) surface.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        accessibility.removeTouchExplorationStateChangeListener(accessibilityListener)
    }
}
