package dev.shurufa.ime

object NativeIme {
    const val CAP_NATIVE_LIBRIME: Long = 1L shl 4

    init {
        System.loadLibrary("shurufa_android")
    }

    external fun create(
        dataDirectory: String,
        rimeSharedDirectory: String,
        rimeUserDirectory: String,
    ): Long
    external fun capabilities(): Long
    external fun handwritingOpen(path: String): Long
    external fun handwritingClose(handle: Long)
    external fun handwritingRecognize(handle: Long, ink: String): ByteArray?
    external fun destroy(handle: Long)
    external fun feed(handle: Long, text: String): Int
    external fun command(handle: Long, command: Int): Int
    external fun switchEngine(handle: Long, engine: String): Int
    external fun setScope(handle: Long, scope: Int): Int
    external fun setPrivacy(handle: Long, learningAllowed: Boolean, networkAllowed: Boolean): Int
    external fun setApplicationId(handle: Long, applicationId: String): Int
    external fun select(handle: Long, candidate: String): Int
    external fun actions(handle: Long): String
    external fun flush(handle: Long): Int
    external fun speechResult(handle: Long, text: String, language: String, confidence: Float, finalResult: Boolean): Int
}
