package org.polyfrost.compose.render

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.HostErrors

public object ImageLoader {
    private val LOGGER = LogManager.getLogger("PolyCompose/Images")

    private const val MAX_LOADED = 256

    private const val MAX_LOADED_BYTES = 128L * 1024 * 1024

    private const val WORKERS = 2

    private const val FETCH_WORKERS = 2

    private const val WORKER_IDLE_SECONDS = 30L

    private const val FIRST_RETRY_NANOS = 2_000_000_000L

    private const val MAX_RETRY_NANOS = 300_000_000_000L

    private const val NEVER = Long.MAX_VALUE

    internal data class ResourceKey(val loader: ClassLoader?, val path: String)

    internal data class FileKey(val path: String)

    internal data class UrlKey(val url: String)

    internal data class BytesKey(val owner: ClassLoader?, val key: String)

    private data class CustomKey(val owner: ClassLoader?, val key: String)

    internal class IdentityKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.bytes === bytes
        override fun hashCode(): Int = System.identityHashCode(bytes)
    }

    private class Failure(val error: Throwable, val retryAt: Long, val attempts: Int)

    private val custom = ConcurrentHashMap<CustomKey, PolyBitmap>()

    private val loaded = LinkedHashMap<Any, Any>(64, 0.75f, true)

    private val inFlight = HashMap<Any, CompletableFuture<PolyBitmap>>()

    private var loadedBytes = 0L

    private var generation = 0L

    private val fetchers = CopyOnWriteArrayList<ImageFetcher>()

    @JvmStatic
    @Volatile
    public var maxDownloadBytes: Long = 16L * 1024 * 1024

    @JvmStatic
    @Volatile
    public var maxImagePixels: Long = 4096L * 4096L

    private val executor: ExecutorService by lazy { newPool("PolyCompose Images", WORKERS) }

    private val fetchExecutor: ExecutorService by lazy { newPool("PolyCompose Image Fetch", FETCH_WORKERS) }

    private fun newPool(name: String, size: Int): ExecutorService {
        val count = AtomicInteger()
        val loader = ImageLoader::class.java.classLoader
        return ThreadPoolExecutor(size, size, WORKER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "$name #${count.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                contextClassLoader = loader
            }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun load(key: Any, source: String, decode: () -> PolyBitmap?): PolyBitmap? {
        synchronized(loaded) { loaded[key] }?.let { return it as? PolyBitmap }
        val image = try {
            decode().also { if (it == null) LOGGER.warn("Image {} was not found or could not be decoded", source) }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            HostErrors.warn("Failed to load image $source", failure, LOGGER)
            null
        }
        synchronized(loaded) { store(key, image ?: Failure(ImageLoadException("Image $source could not be loaded", retryable = false), NEVER, 1)) }
        return image
    }

    private fun store(key: Any, value: Any) {
        loaded.put(key, value)?.let { loadedBytes -= sizeOf(it) }
        loadedBytes += sizeOf(value)
        val entries = loaded.entries.iterator()
        while ((loaded.size > MAX_LOADED || loadedBytes > MAX_LOADED_BYTES) && loaded.size > 1 && entries.hasNext()) {
            loadedBytes -= sizeOf(entries.next().value)
            entries.remove()
        }
    }

    private fun sizeOf(value: Any): Long = (value as? PolyBitmap)?.pixels?.size?.toLong() ?: 0L

    public fun fromResource(path: String, loader: ClassLoader? = null): PolyBitmap? {
        val cl = loader ?: ImageLoader::class.java.classLoader
        return load(ResourceKey(cl, path), path) {
            (cl?.getResourceAsStream(path) ?: ImageLoader::class.java.getResourceAsStream(path))
                ?.use { fromBytes(it.readBytes()) }
        }
    }

    public fun fromFile(path: String): PolyBitmap? =
        load(FileKey(path), path) { fromBytes(File(path).readBytes()) }

    public fun fromBytes(bytes: ByteArray): PolyBitmap? =
        PolyBitmap.decode(bytes)

    public fun put(key: String, image: PolyBitmap) {
        custom[CustomKey(null, key)] = image
    }

    public fun put(owner: ClassLoader, key: String, image: PolyBitmap) {
        custom[CustomKey(owner, key)] = image
    }

    public fun get(key: String): PolyBitmap? = custom[CustomKey(null, key)]

    public fun get(owner: ClassLoader, key: String): PolyBitmap? = custom[CustomKey(owner, key)]

    public fun registerFetcher(fetcher: ImageFetcher): AutoCloseable {
        fetchers.add(0, fetcher)
        return AutoCloseable { fetchers.remove(fetcher) }
    }

    public fun cached(source: ImageSource): PolyBitmap? = synchronized(loaded) { loaded[source.key] as? PolyBitmap }

    public fun loadBlocking(source: ImageSource): PolyBitmap? = try {
        loadAsync(source).get()
    } catch (failure: ExecutionException) {
        null
    }

    public fun loadAsync(source: ImageSource): CompletableFuture<PolyBitmap> {
        val key = source.key
        synchronized(loaded) {
            when (val hit = loaded[key]) {
                is PolyBitmap -> return CompletableFuture.completedFuture(hit)
                is Failure -> if (System.nanoTime() - hit.retryAt < 0L || hit.retryAt == NEVER) return CompletableFuture.failedFuture(hit.error)
            }
            inFlight[key]?.let { return it.copy() }
            val future = CompletableFuture<PolyBitmap>()
            inFlight[key] = future
            val started = generation
            (if (source is ImageSource.Url) fetchExecutor else executor).execute { resolve(source, future, started) }
            return future.copy()
        }
    }

    public fun retryDelayMillis(source: ImageSource): Long? {
        val failure = synchronized(loaded) { loaded[source.key] as? Failure } ?: return 0L
        if (failure.retryAt == NEVER) return null
        return TimeUnit.NANOSECONDS.toMillis((failure.retryAt - System.nanoTime()).coerceAtLeast(0L))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolve(source: ImageSource, future: CompletableFuture<PolyBitmap>, started: Long) {
        val key = source.key
        val result: Any = try {
            decode(source, read(source))
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                synchronized(loaded) { inFlight.remove(key, future) }
                future.completeExceptionally(failure)
                throw failure
            }
            val previous = synchronized(loaded) { loaded[key] as? Failure }?.attempts ?: 0
            val retryable = source is ImageSource.Url && (failure as? ImageLoadException)?.retryable ?: (failure is IOException)
            val retryAt = if (retryable) System.nanoTime() + backoff(previous) else NEVER
            if (previous == 0) HostErrors.warn("Failed to load image ${source.description}", failure, LOGGER)
            Failure(failure, retryAt, previous + 1)
        }
        synchronized(loaded) {
            inFlight.remove(key, future)
            if (generation == started) store(key, result)
        }
        if (result is PolyBitmap) future.complete(result) else future.completeExceptionally((result as Failure).error)
    }

    private fun backoff(attempts: Int): Long = (FIRST_RETRY_NANOS shl (attempts * 2).coerceAtMost(8)).coerceAtMost(MAX_RETRY_NANOS)

    private fun read(source: ImageSource): ByteArray = when (source) {
        is ImageSource.Resource -> {
            val stream = source.resolvedLoader?.getResourceAsStream(source.path) ?: ImageLoader::class.java.getResourceAsStream(source.path)
            stream?.use { it.readBytes() } ?: throw ImageLoadException("Image resource ${source.path} was not found", retryable = false)
        }
        is ImageSource.File -> {
            val file = File(source.path)
            if (!file.isFile) throw ImageLoadException("Image file ${source.path} was not found", retryable = false)
            file.readBytes()
        }
        is ImageSource.Bytes -> source.bytes
        is ImageSource.Url -> fetch(source.url)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fetch(url: String): ByteArray {
        val limit = maxDownloadBytes
        for (fetcher in fetchers) {
            if (HostCallbacks.isDisabled(fetcher)) continue
            val bytes = try {
                fetcher.fetch(url, limit)
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                HostCallbacks.failed(fetcher, "Image fetcher", failure)
                throw ImageLoadException("Image fetcher ${fetcher.javaClass.name} failed for $url", cause = failure)
            } ?: continue
            if (bytes.size > limit) throw ImageLoadException("$url is ${bytes.size} bytes, over the $limit byte limit", retryable = false)
            return bytes
        }
        return UrlConnectionFetcher.fetch(url, limit)
    }

    private fun decode(source: ImageSource, bytes: ByteArray): PolyBitmap {
        if (source.untrusted) checkDimensions(source, bytes)
        val image = fromBytes(bytes) ?: throw ImageLoadException("Image ${source.description} could not be decoded", retryable = false)
        if (source.untrusted && image.width.toLong() * image.height > maxImagePixels) {
            throw ImageLoadException("Image ${source.description} is ${image.width}x${image.height}, over the pixel limit", retryable = false)
        }
        return image
    }

    private fun checkDimensions(source: ImageSource, bytes: ByteArray) {
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use {
            val reader = ImageIO.getImageReaders(it).takeIf { readers -> readers.hasNext() }?.next() ?: return
            val pixels = try {
                reader.input = it
                reader.getWidth(0).toLong() * reader.getHeight(0)
            } catch (failure: IOException) {
                return
            } finally {
                reader.dispose()
            }
            if (pixels > maxImagePixels) throw ImageLoadException("Image ${source.description} has $pixels pixels, over the pixel limit", retryable = false)
        }
    }

    public fun evict(resourcePath: String) {
        synchronized(loaded) {
            generation++
            val entries = loaded.entries.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                val key = entry.key
                val matches = (key is ResourceKey && key.path == resourcePath) || (key is FileKey && key.path == resourcePath) ||
                    (key is UrlKey && key.url == resourcePath)
                if (!matches) continue
                loadedBytes -= sizeOf(entry.value)
                entries.remove()
            }
            inFlight.keys.removeIf { key ->
                (key is ResourceKey && key.path == resourcePath) || (key is FileKey && key.path == resourcePath) ||
                    (key is UrlKey && key.url == resourcePath)
            }
        }
    }

    public fun evict(source: ImageSource) {
        synchronized(loaded) {
            generation++
            loaded.remove(source.key)?.let { loadedBytes -= sizeOf(it) }
            inFlight.remove(source.key)
        }
    }

    public fun evictKey(key: String) {
        custom.remove(CustomKey(null, key))
    }

    public fun evictKey(owner: ClassLoader, key: String) {
        custom.remove(CustomKey(owner, key))
    }

    @Deprecated("Clears every mod's images. Use clear(owner) to clear only your own.", ReplaceWith("clear(owner)"))
    public fun clear() {
        custom.clear()
        synchronized(loaded) {
            generation++
            loaded.clear()
            inFlight.clear()
            loadedBytes = 0L
        }
    }

    public fun clear(owner: ClassLoader) {
        custom.keys.removeIf { it.owner === owner }
        synchronized(loaded) {
            generation++
            val entries = loaded.entries.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                val key = entry.key
                val owned = (key is ResourceKey && key.loader === owner) || (key is BytesKey && key.owner === owner)
                if (!owned) continue
                loadedBytes -= sizeOf(entry.value)
                entries.remove()
            }
            inFlight.keys.removeIf { key -> (key is ResourceKey && key.loader === owner) || (key is BytesKey && key.owner === owner) }
        }
    }
}
