package top.pmh13.mctier.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class BuiltinEmojiCacheTest {
    @Test
    fun unpackCreatesGifCacheAndSecondSyncReusesMarker() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-pack-test").toFile()
        var opens = 0
        val pack = packOf((0 until 100).map { "id$it" to gifBytes() })
        try {
            val cache = BuiltinEmojiCache.fromTestInput(root) { opens += 1; ByteArrayInputStream(pack) }
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(100, cache.sync { done, total -> progress += done to total }.size)
            assertTrue(cache.isComplete())
            assertEquals(1, opens)
            assertEquals(0 to 100, progress.first())
            assertEquals(100 to 100, progress.last())
            assertEquals(100, BuiltinEmojiCache.fromTestInput(root) { opens += 1; ByteArrayInputStream(pack) }.sync().size)
            assertEquals(1, opens)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun damagedCacheIsRebuiltFromEmbeddedPack() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-repair-test").toFile()
        val pack = packOf((0 until 100).map { "id$it" to gifBytes() })
        try {
            BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.sync()
            java.io.File(root, "builtin/id0.gif").writeText("broken")
            assertFalse(BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.isComplete())
            assertEquals(100, BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.sync().size)
            assertTrue(BuiltinEmojiCache.fromTestInput(root) { ByteArrayInputStream(pack) }.isComplete())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun invalidGifIsRejected() {
        assertTrue(BuiltinEmojiCache.hasGifHeader(gifBytes()))
        assertFalse(BuiltinEmojiCache.hasGifHeader("RIFF00".toByteArray()))
    }

    private fun packOf(items: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("MCTIER_EMOJI_PACK_V3\u0000".toByteArray(Charsets.US_ASCII))
        body.writeU32(items.size)
        items.forEach { (id, bytes) ->
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            body.writeU16(idBytes.size)
            body.writeU32(bytes.size)
            body.write(idBytes)
            body.write(bytes)
        }
        return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(body.toByteArray()) } }.toByteArray()
    }

    private fun gifBytes(): ByteArray = "GIF89a".toByteArray(Charsets.US_ASCII)

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 255)
        write((value ushr 8) and 255)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        repeat(4) { index -> write((value ushr (index * 8)) and 255) }
    }
}
