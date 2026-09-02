package dev.shurufa.ime

import android.text.InputType

enum class KeyboardPage {
    TEXT,
    NUMBER,
    SYMBOL,
}

object PlatformPolicy {
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

    fun initialKeyboardPage(inputType: Int): KeyboardPage = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
        -> KeyboardPage.NUMBER
        else -> KeyboardPage.TEXT
    }
}
