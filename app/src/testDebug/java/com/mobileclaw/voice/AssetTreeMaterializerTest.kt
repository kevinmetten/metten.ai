package com.mobileclaw.voice

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetTreeMaterializerTest {
    @Test fun `nested assets are copied and repeated preparation repairs files`() {
        val assetDirectory = KittenTtsModelSpec.DATA_DIR
        val assets = mutableMapOf(
            "$assetDirectory/phontab" to "table".toByteArray(),
            "$assetDirectory/lang/en" to byteArrayOf(1, 2, 3),
        )
        val root = Files.createTempDirectory("espeak-test").toFile()
        val materializer = materializer(assets)

        val result = materializer.materialize(assetDirectory, root)
        assertArrayEquals("table".toByteArray(), result.resolve("phontab").readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), result.resolve("lang/en").readBytes())
        result.resolve("phontab").writeText("stale")
        assertTrue(result.resolve("lang/en").delete())

        val repeated = materializer.materialize(assetDirectory, root)
        assertArrayEquals("table".toByteArray(), repeated.resolve("phontab").readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), repeated.resolve("lang/en").readBytes())
        assertTrue(repeated.isAbsolute)
        assertNotEquals(KittenTtsModelSpec.DATA_DIR, repeated.path)
    }

    @Test fun `missing source fails rather than producing a data directory`() {
        val root = Files.createTempDirectory("missing-espeak-test").toFile()
        val materializer = AssetTreeMaterializer(
            list = { emptyArray() },
            open = { throw IllegalArgumentException("Missing asset: $it") },
        )

        assertThrows(IllegalArgumentException::class.java) {
            materializer.materialize("missing", root)
        }
    }

    private fun materializer(files: Map<String, ByteArray>) = AssetTreeMaterializer(
        list = { path ->
            files.keys.asSequence()
                .filter { it.startsWith("$path/") }
                .map { it.removePrefix("$path/").substringBefore('/') }
                .distinct()
                .toList()
                .toTypedArray()
        },
        open = { path -> ByteArrayInputStream(files.getValue(path)) },
    )
}
