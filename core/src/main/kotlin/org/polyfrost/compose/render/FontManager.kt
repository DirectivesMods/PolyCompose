@file:OptIn(ExperimentalVersionOverloading::class)

package org.polyfrost.compose.render

import androidx.compose.runtime.mutableIntStateOf
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

public object FontManager {
    private val fontData = ConcurrentHashMap<String, ByteArray>()
    private val pixelFonts = ConcurrentHashMap.newKeySet<String>()
    private val families = ConcurrentHashMap<String, TreeMap<Int, String>>()
    private val weights = ConcurrentHashMap<String, Int>()

    @Volatile
    private var defaultSource: String? = null

    public const val DEFAULT_KEY: String = "__default__"
    public const val NORMAL_WEIGHT: Int = 400

    @Volatile
    public var revision: Int = 0
        private set

    internal val observedRevision = mutableIntStateOf(0)

    @Volatile
    internal var familyRevision: Int = 0
        private set

    private fun bump() {
        val next = revision + 1
        revision = next
        observedRevision.intValue = next
    }

    public fun invalidateMetrics() {
        bump()
    }

    /** @throws IllegalArgumentException if [bytes] isn't a TrueType or OpenType font */
    @JvmOverloads
    public fun register(name: String, bytes: ByteArray, pixelAligned: Boolean = false) {
        require(isReadableFont(bytes)) { "$name is not a TrueType or OpenType font" }
        fontData[name] = bytes
        if (pixelAligned) pixelFonts.add(name) else pixelFonts.remove(name)
        if (name == DEFAULT_KEY || name in GENERIC_FAMILIES || weights.containsKey(name)) familyRevision++
        bump()
    }

    public fun loadFromResource(path: String, name: String = DEFAULT_KEY, loader: ClassLoader? = null, @IntroducedAt("1.2.15") pixelAligned: Boolean = false): Boolean {
        val cl = loader ?: FontManager::class.java.classLoader
        val bytes = (cl?.getResourceAsStream(path) ?: FontManager::class.java.getResourceAsStream(path))
            ?.use { it.readBytes() }?.takeIf(::isReadableFont) ?: return false
        register(name, bytes, pixelAligned)
        return true
    }

    public fun setDefault(name: String) {
        val bytes = fontData[name] ?: return
        defaultSource = name
        familyRevision++
        register(DEFAULT_KEY, bytes, name in pixelFonts)
    }

    public fun registerWeight(family: String, weight: Int, name: String) {
        val faces = families.getOrPut(family) { TreeMap() }
        synchronized(faces) { faces[weight.coerceIn(1, 1000)] = name }
        weights[name] = weight.coerceIn(1, 1000)
        familyRevision++
        bump()
    }

    public fun weightOf(name: String): Int {
        weights[name]?.let { return it }
        if (name == DEFAULT_KEY) defaultFamily()?.let { source -> weights[source]?.let { return it } }
        return NORMAL_WEIGHT
    }

    public fun resolveWeight(family: String, weight: Int): String {
        val faces = families[family] ?: (if (family == DEFAULT_KEY) defaultFamily()?.let { families[it] } else null) ?: return family
        val candidates = TreeMap<Int, String>()
        synchronized(faces) { candidates.putAll(faces) }
        candidates.putIfAbsent(weightOf(family), family)
        candidates[weight]?.let { return it }
        val lighter = candidates.lowerEntry(weight)
        val heavier = candidates.higherEntry(weight)
        val pick = when {
            weight in NORMAL_WEIGHT..MEDIUM_WEIGHT -> candidates.subMap(weight, true, MEDIUM_WEIGHT, true).firstEntry()?.value ?: lighter?.value ?: heavier?.value
            weight < NORMAL_WEIGHT -> lighter?.value ?: heavier?.value
            else -> heavier?.value ?: lighter?.value
        }
        return pick ?: family
    }

    private fun defaultFamily(): String? {
        val source = defaultSource ?: return null
        val bytes = fontData[source] ?: return null
        return source.takeIf { fontData[DEFAULT_KEY] === bytes }
    }

    public fun fontData(name: String): ByteArray? = fontData[name]

    public fun isPixelAligned(name: String): Boolean = name in pixelFonts

    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    @JvmName("loadFromResource")
    public fun loadDefaultFromResource(path: String) {
        loadFromResource(path)
    }

    private const val MEDIUM_WEIGHT = 500
    private val GENERIC_FAMILIES = setOf("sans-serif", "serif", "monospace", "cursive")
}
