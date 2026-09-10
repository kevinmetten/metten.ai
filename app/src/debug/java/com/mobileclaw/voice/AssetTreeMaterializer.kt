package com.mobileclaw.voice

import java.io.File
import java.io.InputStream

/** Copies an Android asset directory to storage while preserving its relative tree. */
internal class AssetTreeMaterializer(
    private val list: (String) -> Array<out String>,
    private val open: (String) -> InputStream,
) {
    fun materialize(assetDirectory: String, storageRoot: File): File {
        require(assetDirectory.isNotBlank() && !assetDirectory.startsWith('/')) {
            "The asset directory must be a relative path."
        }
        val destination = File(storageRoot, assetDirectory)
        var copiedFiles = 0

        fun copy(path: String, target: File) {
            val children = list(path)
            if (children.isNotEmpty()) {
                check(target.mkdirs() || target.isDirectory) { "Cannot create asset directory: $target" }
                children.forEach { child -> copy("$path/$child", File(target, child)) }
            } else {
                val parent = checkNotNull(target.parentFile)
                check(parent.mkdirs() || parent.isDirectory) { "Cannot create asset parent: $parent" }
                open(path).use { input ->
                    target.outputStream().use(input::copyTo)
                }
                copiedFiles++
            }
        }

        copy(assetDirectory, destination)
        check(copiedFiles > 0 && destination.isDirectory) {
            "No files were materialized from asset directory: $assetDirectory"
        }
        return destination.absoluteFile
    }
}
