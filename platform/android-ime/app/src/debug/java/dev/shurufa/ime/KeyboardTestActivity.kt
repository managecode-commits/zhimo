package dev.shurufa.ime

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText

/** Debug-only editor for end-to-end IME tests; excluded from release builds. */
class KeyboardTestActivity : Activity() {
    lateinit var editor: EditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        editor = EditText(this).apply {
            contentDescription = "键盘回归输入框"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        setContentView(editor)
        editor.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}
