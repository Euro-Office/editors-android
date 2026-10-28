package lib.editors.gbase.ui.views

import android.view.KeyEvent

// Keys as sdkjs takes them, for typing into documents and into shapes on sheets

internal val CLIPBOARD_KEYS = setOf(KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_A)

internal const val KEY_BACKSPACE = 8
internal const val KEY_ENTER = 13
internal const val KEY_DELETE = 46

// Android key codes to the web key codes sdkjs expects
internal fun webKeyCode(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
    KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KEY_ENTER
    KeyEvent.KEYCODE_TAB -> 9
    KeyEvent.KEYCODE_ESCAPE -> 27
    KeyEvent.KEYCODE_PAGE_UP -> 33
    KeyEvent.KEYCODE_PAGE_DOWN -> 34
    KeyEvent.KEYCODE_MOVE_END -> 35
    KeyEvent.KEYCODE_MOVE_HOME -> 36
    KeyEvent.KEYCODE_DPAD_LEFT -> 37
    KeyEvent.KEYCODE_DPAD_UP -> 38
    KeyEvent.KEYCODE_DPAD_RIGHT -> 39
    KeyEvent.KEYCODE_DPAD_DOWN -> 40
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + keyCode - KeyEvent.KEYCODE_A
    else -> null
}
