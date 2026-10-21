package lib.editors.gbase.ui.views

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * A mouse, or a touchpad moving the pointer: presses and drags work as on a desktop. Touchpad
 * gestures (scrolling and pinching with fingers) are handled like touch instead.
 */
internal fun isMouse(event: MotionEvent): Boolean {
    if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val gesture = event.classification
        if (gesture == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE || gesture == MotionEvent.CLASSIFICATION_PINCH) return false
    }
    return true
}

/** Ctrl is held during a mouse event. */
internal val MotionEvent.isCtrlDown get() = metaState and KeyEvent.META_CTRL_ON != 0

/** Shift is held during a mouse event. */
internal val MotionEvent.isShiftDown get() = metaState and KeyEvent.META_SHIFT_ON != 0
