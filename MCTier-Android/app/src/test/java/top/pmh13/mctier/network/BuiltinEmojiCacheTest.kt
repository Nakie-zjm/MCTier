package top.pmh13.mctier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinEmojiCacheTest {
    @Test
    fun parserExtractsEveryUniqueSafeAssetIdInPageOrder() {
        val html = buildString {
            repeat(567) { index ->
                append("<img src=\"/images/120/telegram/${index.toString(16)}.gif\">")
            }
            append("<img src=\"/images/120/telegram/0.gif\">")
            append("<img src=\"/images/120/other/not-included.gif\">")
        }

        val ids = BuiltinEmojiCache.parseIds(html)

        assertEquals(567, ids.size)
        assertEquals("0", ids.first())
        assertEquals("236", ids.last())
    }

    @Test
    fun gifHeaderValidationRejectsNonGifPayloads() {
        assertTrue(BuiltinEmojiCache.hasGifHeader("GIF87a-content".toByteArray()))
        assertTrue(BuiltinEmojiCache.hasGifHeader("GIF89a-content".toByteArray()))
        assertFalse(BuiltinEmojiCache.hasGifHeader("<html>failure".toByteArray()))
    }
}
