package top.pmh13.mctier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BuiltinEmojiCacheTest {
    @Test
    fun downloadsUseTenConcurrentSlots() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-concurrency-test").toFile()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstBatch = java.util.concurrent.CountDownLatch(10)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.encodedPath.endsWith("/animation")
            val content = if (page) buildString { repeat(100) { append("/images/120/telegram/$it.gif ") } }
                else {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    try {
                        firstBatch.countDown()
                        check(firstBatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Ten downloads did not start" }
                        "GIF89a-image"
                    } finally { active.decrementAndGet() }
                }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody()).build()
        }.build()
        try {
            assertEquals(100, BuiltinEmojiCache(root, client).sync().size)
            assertEquals(10, peak.get())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedAssetDoesNotHideSuccessfulDownloadsAndRetryResumesOfflineIndex() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-cache-test").toFile()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        var broken = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val page = path.endsWith("/animation")
            if (broken && path.endsWith("/0.gif")) throw IOException("simulated timeout")
            val content = if (page) buildString { repeat(100) { append("/images/120/telegram/$it.gif ") } }
                else "GIF89a-image"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody()).build()
        }.build()
        try {
            val cache = BuiltinEmojiCache(root, client)
            val failure = runCatching { cache.sync() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("99/100"))
            assertEquals(99, cache.cachedItems().size)
            assertFalse(cache.isComplete())
            assertEquals(3, calls["/images/120/telegram/0.gif"]?.get())
            broken = false
            val restored = BuiltinEmojiCache(root, client)
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(100, restored.sync { done, total -> synchronized(progress) { progress += done to total } }.size)
            assertEquals(99 to 100, progress.first())
            assertTrue(restored.isComplete())
            assertEquals(1, calls["/zh-hans/image-emoji-platform/telegram/animation"]?.get())
            assertEquals(1, calls["/images/120/telegram/1.gif"]?.get())
            val totalCalls = calls.values.sumOf { it.get() }
            assertEquals(100, restored.sync().size)
            assertEquals(totalCalls, calls.values.sumOf { it.get() })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectedResponseIsNotCachedAsAnImage() = runBlocking {
        val root = Files.createTempDirectory("mctier-emoji-invalid-test").toFile()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.encodedPath.endsWith("/animation")
            val content = if (page) buildString { repeat(100) { append("/images/120/telegram/$it.gif ") } }
                else "<html>not an image</html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody()).build()
        }.build()
        try {
            val cache = BuiltinEmojiCache(root, client)
            assertTrue(runCatching { cache.sync() }.isFailure)
            assertTrue(cache.cachedItems().isEmpty())
            assertFalse(cache.isComplete())
        } finally { root.deleteRecursively() }
    }

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
