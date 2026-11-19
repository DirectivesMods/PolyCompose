package org.polyfrost.compose.render

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException

public fun interface ImageFetcher {
    @Throws(IOException::class)
    public fun fetch(url: String, maxBytes: Long): ByteArray?

    public companion object {
        @JvmStatic
        @Throws(IOException::class)
        public fun readCapped(input: InputStream, maxBytes: Long, source: String): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw ImageLoadException("$source is larger than $maxBytes bytes", retryable = false)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        private const val BUFFER = 16 * 1024
    }
}

public class ImageLoadException(
    message: String,
    public val retryable: Boolean = true,
    cause: Throwable? = null,
) : IOException(message, cause)

internal object UrlConnectionFetcher : ImageFetcher {
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000
    private const val HTTP_TIMEOUT = 408
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_SERVER_ERROR = 500

    override fun fetch(url: String, maxBytes: Long): ByteArray {
        val target = try {
            URI(url).takeIf { it.scheme?.lowercase().let { scheme -> scheme == "http" || scheme == "https" } }?.toURL()
        } catch (failure: URISyntaxException) {
            throw ImageLoadException("Malformed image URL $url", retryable = false, cause = failure)
        } catch (failure: IllegalArgumentException) {
            throw ImageLoadException("Malformed image URL $url", retryable = false, cause = failure)
        } catch (failure: MalformedURLException) {
            throw ImageLoadException("Malformed image URL $url", retryable = false, cause = failure)
        } ?: throw ImageLoadException("Unsupported image URL scheme in $url", retryable = false)
        val connection = target.openConnection() as? HttpURLConnection
            ?: throw ImageLoadException("Unsupported image URL $url", retryable = false)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PolyCompose")
            connection.setRequestProperty("Accept", "image/*")
            val status = connection.responseCode
            if (status !in 200..299) {
                val retryable = status >= HTTP_SERVER_ERROR || status == HTTP_TIMEOUT || status == HTTP_TOO_MANY
                throw ImageLoadException("HTTP $status for $url", retryable)
            }
            val length = connection.contentLengthLong
            if (length > maxBytes) throw ImageLoadException("$url is $length bytes, over the $maxBytes byte limit", retryable = false)
            return connection.inputStream.use { ImageFetcher.readCapped(it, maxBytes, url) }
        } finally {
            connection.disconnect()
        }
    }
}
