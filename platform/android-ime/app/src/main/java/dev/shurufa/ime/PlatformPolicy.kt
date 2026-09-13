package dev.shurufa.ime

import android.text.InputType

object PlatformPolicy {
    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            else -> false
        }
    }

    fun engine(
        pinyin: Boolean,
        nativeRimeAvailable: Boolean,
        nineKeyPinyin: Boolean = false,
    ): String = when {
        !pinyin -> "latin"
        nineKeyPinyin -> "pinyin.reference"
        nativeRimeAvailable -> "rime"
        else -> "pinyin.reference"
    }

    fun mayStartSpeech(
        passwordScope: Boolean,
        microphoneGranted: Boolean,
        onDeviceAvailable: Boolean,
        networkExplicitlyAllowed: Boolean,
    ): Boolean = !passwordScope && microphoneGranted &&
        (onDeviceAvailable || networkExplicitlyAllowed)

    fun shouldInsertLiteralSpace(pinyin: Boolean, hadComposition: Boolean): Boolean =
        !pinyin || !hadComposition

    fun shouldFallbackToEditor(hadComposition: Boolean): Boolean = !hadComposition

    fun shouldRouteEditingToEngine(page: KeyboardPage): Boolean = page == KeyboardPage.TEXT

    fun shouldInitializeKeyboardState(
        restarting: Boolean,
        stateInitialized: Boolean,
        sameEditor: Boolean,
    ): Boolean = !stateInitialized || (!restarting && !sameEditor)

    fun initialKeyboardPage(inputType: Int): KeyboardPage = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
        -> KeyboardPage.NUMBER
        else -> KeyboardPage.TEXT
    }
}
