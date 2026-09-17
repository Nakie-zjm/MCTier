package top.pmh13.mctier.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import top.pmh13.mctier.data.CustomEmojiItem
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BuiltinEmojiCache internal constructor(private val root: File, private val client: OkHttpClient) {
    constructor(context: Context) : this(File(context.filesDir, "emoji-library-v1"), defaultClient())
    private val cacheDirectory = File(root, "builtin")
    private val marker = File(cacheDirectory, "complete-v1.txt")
    private val index = File(cacheDirectory, "index-v1.txt")
    private val syncMutex = Mutex()

    private fun readIndex(file: File): List<String> {
        if (!file.isFile || file.length() !in 1..MaxMarkerBytes.toLong()) return emptyList()
        val ids = runCatching { file.readLines() }.getOrNull()
            ?.filter { it.isNotBlank() }
            ?: return emptyList()
        if (ids.size !in MinEmojiCount..MaxEmojiCount || ids.distinct().size != ids.size) return emptyList()
        if (ids.any { !SafeId.matches(it) }) return emptyList()
        return ids
    }

    fun cachedItems(): List<CustomEmojiItem> = items(
        readIndex(index).ifEmpty { readIndex(marker) }.filter { validGif(File(cacheDirectory, "$it.gif")) }
    )

    fun isComplete(): Boolean = readIndex(marker).let { ids ->
        ids.isNotEmpty() && ids.all { validGif(File(cacheDirectory, "$it.gif")) }
    }

    suspend fun sync(onProgress: (downloaded: Int, total: Int) -> Unit = { _, _ -> }): List<CustomEmojiItem> = syncMutex.withLock {
        cachedItems().takeIf { isComplete() }?.let {
            onProgress(it.size, it.size)
            return@withLock it
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) { "无法创建内置表情缓存目录" }

        val ids = readIndex(index).ifEmpty {
            parseIds(withRetry { download(SourcePage, MaxPageBytes) }.toString(Charsets.UTF_8)).also {
                val temporaryIndex = File(cacheDirectory, "index-v1.tmp")
                temporaryIndex.writeText(it.joinToString("\n"))
                check(replace(temporaryIndex, index)) { "无法保存内置表情下载索引" }
            }
        }
        val pending = ids.filterNot { validGif(File(cacheDirectory, "$it.gif")) }
        val completed = AtomicInteger(ids.size - pending.size)
        onProgress(completed.get(), ids.size)
        val failures = coroutineScope {
            val semaphore = Semaphore(DownloadConcurrency)
            pending.map { id ->
                async(Dispatchers.IO) {
                    try {
                        semaphore.withPermit { withRetry { downloadGif(id) } }
                        synchronized(completed) { onProgress(completed.incrementAndGet(), ids.size) }
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error
                    }
                }
            }.awaitAll().filterNotNull()
        }
        check(failures.isEmpty()) { "已缓存 ${completed.get()}/${ids.size} 个表情，${failures.size} 个待重试：${failures.first().message}" }

        val temporaryMarker = File(cacheDirectory, "complete-v1.tmp")
        temporaryMarker.writeText(ids.joinToString("\n"))
        check(replace(temporaryMarker, marker)) { "无法提交内置表情缓存索引" }
        items(ids)
    }

    private fun downloadGif(id: String) {
        val destination = File(cacheDirectory, "$id.gif")
        if (validGif(destination)) return
        val bytes = download("$AssetRoot/$id.gif", MaxGifBytes)
        check(hasGifHeader(bytes)) { "远程资源不是有效 GIF" }
        val temporary = File(cacheDirectory, "$id.part")
        temporary.writeBytes(bytes)
        check(replace(temporary, destination)) { "无法提交内置表情缓存" }
    }

    private fun download(url: String, maximumBytes: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MCTier/3.4 emoji-cache")
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 408 || response.code == 429 || response.code >= 500) throw IOException("HTTP ${response.code}")
            check(response.isSuccessful) { "下载内置表情失败: HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "下载内置表情失败: 空响应" }
            val contentLength = body.contentLength()
            check(contentLength < 0 || contentLength <= maximumBytes) { "远程表情资源超过大小限制" }
            body.byteStream().use { readLimited(it, maximumBytes) }
        }
    }

    private fun items(ids: List<String>): List<CustomEmojiItem> = ids.map { id ->
        CustomEmojiItem(
            id = "builtin-$id",
            categoryId = "builtin",
            name = id,
            mime = "image/gif",
            fileName = "builtin/$id.gif",
            createdAt = 0L,
        )
    }

    private fun validGif(file: File): Boolean {
        if (!file.isFile || file.length() !in 6..MaxGifBytes.toLong()) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(6)
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) return@use false
                    offset += read
                }
                hasGifHeader(header)
            }
        }.getOrDefault(false)
    }

    private fun replace(source: File, destination: File): Boolean {
        if (destination.exists() && !destination.delete()) return false
        return source.renameTo(destination)
    }

    internal companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private suspend fun <T> withRetry(action: () -> T): T {
            repeat(2) { attempt ->
                try { return action() } catch (error: IOException) { delay(500L * (attempt + 1)) }
            }
            return action()
        }
        private const val SourcePage = "https://www.emojiall.com/zh-hans/image-emoji-platform/telegram/animation"
        private const val AssetRoot = "https://www.emojiall.com/images/120/telegram"
        private const val MaxPageBytes = 2 * 1024 * 1024
        private const val MaxGifBytes = 4 * 1024 * 1024
        private const val MaxMarkerBytes = 128 * 1024
        private const val MinEmojiCount = 100
        private const val MaxEmojiCount = 2_000
        private const val DownloadConcurrency = 10
        private val SafeId = Regex("^[A-Za-z0-9_-]+$")
        private val AssetPattern = Regex("/images/120/telegram/([A-Za-z0-9_-]+)\\.gif")

        internal fun parseIds(html: String): List<String> {
            val ids = LinkedHashSet<String>()
            AssetPattern.findAll(html).forEach { match ->
                ids += match.groupValues[1]
                check(ids.size <= MaxEmojiCount) { "远程表情数量异常" }
            }
            check(ids.size >= MinEmojiCount) { "未能从页面解析出完整表情列表" }
            return ids.toList()
        }

        internal fun hasGifHeader(bytes: ByteArray): Boolean =
            bytes.size >= 6 && (
                bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                    bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
                )

        private fun readLimited(input: InputStream, maximumBytes: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
            val buffer = ByteArray(8 * 1024)
            while (output.size() <= maximumBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            check(output.size() <= maximumBytes) { "远程表情资源超过大小限制" }
            return output.toByteArray()
        }
    }
}
