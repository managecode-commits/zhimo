// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import java.io.File

/** Delete only the two obsolete, reproducible app-installed models, never user audio/data. */
internal object LegacySpeechCache {
    val names = listOf(
        "base-q5_1-422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898.bin",
        "silero-29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf.bin",
    )
    fun remove(noBackupDirectory: File) {
        runCatching {
            val parent = noBackupDirectory.canonicalFile
            val directory = File(parent, "speech")
            if (directory.canonicalFile != directory) return
            for (name in names) {
                val file = File(directory, name)
                if (file.canonicalFile == file && file.isFile) file.delete()
            }
        }
    }
}
