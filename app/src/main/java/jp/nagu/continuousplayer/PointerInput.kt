package jp.nagu.continuousplayer

import android.view.InputDevice
import android.view.MotionEvent

/** Touchpads can report finger tools while still controlling a mouse pointer. */
internal fun MotionEvent.isPointerInput(): Boolean =
    isFromSource(InputDevice.SOURCE_MOUSE) || isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
        getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
