package org.polyfrost.compose.render

import androidx.compose.runtime.Immutable

@Immutable
public sealed class ImageSource {
    internal abstract val key: Any

    internal abstract val description: String

    internal open val untrusted: Boolean get() = false

    final override fun equals(other: Any?): Boolean = other is ImageSource && other.key == key

    final override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = description

    public class Resource(public val path: String, public val loader: ClassLoader? = null) : ImageSource() {
        internal val resolvedLoader: ClassLoader? = loader ?: ImageLoader::class.java.classLoader
        override val key: Any = ImageLoader.ResourceKey(resolvedLoader, path)
        override val description: String get() = path
    }

    public class File(public val path: String) : ImageSource() {
        override val key: Any = ImageLoader.FileKey(path)
        override val description: String get() = path
    }

    public class Bytes(public val bytes: ByteArray, public val cacheKey: String?, public val owner: ClassLoader?) : ImageSource() {
        public constructor(bytes: ByteArray, cacheKey: String? = null) : this(bytes, cacheKey, null)

        override val key: Any = if (cacheKey != null) ImageLoader.BytesKey(owner, cacheKey) else ImageLoader.IdentityKey(bytes)
        override val description: String get() = cacheKey ?: "${bytes.size} bytes"
        override val untrusted: Boolean get() = true
    }

    public class Url(public val url: String) : ImageSource() {
        override val key: Any = ImageLoader.UrlKey(url)
        override val description: String get() = url
        override val untrusted: Boolean get() = true
    }
}
