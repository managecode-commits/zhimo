package dev.shurufa.ime

object PlatformPolicy {
    fun engine(pinyin: Boolean, nativeRimeAvailable: Boolean): String = when {
        !pinyin -> "latin"
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
}
