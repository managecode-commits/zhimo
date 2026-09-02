package dev.shurufa.ime

import android.content.Context
import java.io.File

object RimeAssets {
    data class Directories(val shared: File, val user: File)

    fun prepare(context: Context): Directories {
        val root = File(context.filesDir, "rime")
        val shared = File(root, "shared")
        val user = File(root, "user")
        val packagedVersion = context.assets.open("rime/version.txt").bufferedReader().use {
            it.readText().trim()
        }
        val installedVersion = File(shared, "version.txt").takeIf(File::isFile)?.readText()?.trim()
        if (packagedVersion != installedVersion) {
            val staging = File(root, "shared-staging")
            val backup = File(root, "shared-backup")
            staging.deleteRecursively()
            backup.deleteRecursively()
            copyDirectory(context, "rime", staging)
            if (shared.exists()) {
                check(shared.renameTo(backup)) { "unable to preserve installed Rime data" }
            }
            if (!staging.renameTo(shared)) {
                if (backup.exists()) backup.renameTo(shared)
                error("unable to activate bundled Rime data")
            }
            backup.deleteRecursively()
        }
        check(user.exists() || user.mkdirs()) { "unable to create Rime user directory" }
        return Directories(shared, user)
    }

    private fun copyDirectory(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        check(destination.exists() || destination.mkdirs()) {
            "unable to create ${destination.absolutePath}"
        }
        children.forEach { child ->
            copyDirectory(context, "$assetPath/$child", File(destination, child))
        }
    }
}
