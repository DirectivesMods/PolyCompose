@file:OptIn(InternalComposeUiApi::class, ExperimentalTextApi::class)

package org.polyfrost.compose.backend

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformParagraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.platform.PlatformText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.ArabicShaping
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyFade
import org.polyfrost.compose.render.PolyFont
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.PolyImageFill
import org.polyfrost.compose.render.ChromaBrush
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.PolyTessellator
import org.polyfrost.compose.text.McFonts
import java.text.Bidi
import java.text.BreakIterator
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private val italicFonts = ConcurrentHashMap.newKeySet<String>()
private val sourceWeights = ConcurrentHashMap<String, Int>()

private const val LIGATURE_PLACEHOLDER = '\uFFFF'

// Minecraft ships ICU4J; without it Arabic is drawn unjoined and right-to-left brackets unmirrored
private object Icu {
    private val shaping = runCatching { ArabicShaping(ArabicShaping.LETTERS_SHAPE or ArabicShaping.LENGTH_FIXED_SPACES_NEAR) }.getOrNull()
    private val mirroring = runCatching { UCharacter.getMirror('('.code) == ')'.code }.getOrDefault(false)

    // fixed length keeps offsets aligned with the logical text, leaving a space for each ligature that's replaced by a placeholder
    fun shape(text: String): String {
        if (shaping == null || text.none { it in '\u0600'..'\u08FF' }) return text
        val shaped = try { shaping.shape(text) } catch (_: Exception) { return text }
        if (shaped.length != text.length || ' ' !in shaped) return shaped
        val out = shaped.toCharArray()
        for (i in out.indices) if (out[i] == ' ' && text[i] != ' ') out[i] = LIGATURE_PLACEHOLDER
        return String(out)
    }

    fun mirror(cp: Int) = if (mirroring) UCharacter.getMirror(cp) else cp
}

// keeps combining marks after their base so they still draw over it
private fun reversedClusters(text: String): String {
    val it = BreakIterator.getCharacterInstance().also { b -> b.setText(text) }
    val out = StringBuilder(text.length)
    var end = it.last()
    var start = it.previous()
    while (start != BreakIterator.DONE) {
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            if (cp != LIGATURE_PLACEHOLDER.code) out.appendCodePoint(Icu.mirror(cp))
            i += Character.charCount(cp)
        }
        end = start
        start = it.previous()
    }
    return out.toString()
}

public class PolyFontSource(public val key: String, private val bytes: () -> ByteArray, override val weight: FontWeight, override val style: FontStyle) : Font {
    internal fun register(): String {
        if (style == FontStyle.Italic) italicFonts += key
        sourceWeights[key] = weight.weight
        if (FontManager.fontData(key) == null) FontManager.register(key, bytes())
        return key
    }

    override fun equals(other: Any?): Boolean = other is PolyFontSource && other.key == key && other.weight == weight && other.style == style
    override fun hashCode(): Int = (key.hashCode() * 31 + weight.hashCode()) * 31 + style.hashCode()
    override fun toString(): String = "PolyFontSource($key, $weight, $style)"
}

public object PolyText : PlatformText {
    public val typefaces: PlatformTypefacesLoader = object : PlatformTypefacesLoader {
        override fun loadBlocking(font: Font): Any? = (font as? PolyFontSource)?.register() ?: (font as? McFonts.Source)?.key
        override suspend fun awaitLoad(font: Font): Any? = loadBlocking(font)
        override val cacheKey: Any? get() = FontManager.familyRevision
        override fun loadPlatformTypes(fontFamily: FontFamily, fontWeight: FontWeight, fontStyle: FontStyle): Any =
            FontManager.resolveWeight(FontManager.DEFAULT_KEY, fontWeight.weight)
        override val fontCollection: Any = Unit
    }

    override fun createParagraph(
        text: String, style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>, maxLines: Int, overflow: TextOverflow, constraints: Constraints,
        density: Density, fontFamilyResolver: FontFamily.Resolver,
    ): PlatformParagraph = createParagraph(createParagraphIntrinsics(text, style, annotations, placeholders, density, fontFamilyResolver), maxLines, overflow, constraints)

    override fun createParagraph(paragraphIntrinsics: ParagraphIntrinsics, maxLines: Int, overflow: TextOverflow, constraints: Constraints): PlatformParagraph =
        PolyParagraph(paragraphIntrinsics as PolyIntrinsics, maxLines, overflow, if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY)

    override fun createParagraphIntrinsics(
        text: String, style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>, density: Density, fontFamilyResolver: FontFamily.Resolver,
    ): ParagraphIntrinsics = Intrinsics.of(text, style, annotations, placeholders, density, fontFamilyResolver)

    override fun createFontFamilyResolver(): FontFamily.Resolver = createPlatformFontFamilyResolver(typefaces)
    override fun createFontFamilyResolver(coroutineContext: CoroutineContext): FontFamily.Resolver = createPlatformFontFamilyResolver(typefaces, coroutineContext)

    override fun findPrecedingBreak(text: String, index: Int): Int =
        BreakIterator.getCharacterInstance().also { it.setText(text) }.preceding(index).let { if (it == BreakIterator.DONE) 0 else it }

    override fun findFollowingBreak(text: String, index: Int): Int =
        BreakIterator.getCharacterInstance().also { it.setText(text) }.following(index).let { if (it == BreakIterator.DONE) text.length else it }

    override val defaultFontRasterizationSettings: FontRasterizationSettings = FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.Slight, subpixelPositioning = true, autoHintingForced = false)
}

internal class TextRun(
    val start: Int, val end: Int, val font: PolyFont, val style: SpanStyle,
    val spanColor: Color = Color.Unspecified, val spanDecoration: TextDecoration? = null, val spanShadow: Shadow? = null, val skew: Float = 0f,
    val scaleX: Float = 1f, val letter: Float = 0f, val bold: Float = 0f, val placeholder: Placeholder? = null,
) {
    val metrics get() = font.metrics
    val tracking get() = letter + bold
}

private const val BOLD_SYNTHESIS_WEIGHT = 600
private const val FAKE_BOLD_MIN_SIZE = 9f
private const val FAKE_BOLD_MAX_SIZE = 36f
private const val FAKE_BOLD_MIN_SCALE = 1f / 24f
private const val FAKE_BOLD_MAX_SCALE = 1f / 32f

private fun fakeBoldWidth(size: Float): Float {
    val t = ((size - FAKE_BOLD_MIN_SIZE) / (FAKE_BOLD_MAX_SIZE - FAKE_BOLD_MIN_SIZE)).coerceIn(0f, 1f)
    return size * (FAKE_BOLD_MIN_SCALE + (FAKE_BOLD_MAX_SCALE - FAKE_BOLD_MIN_SCALE) * t)
}

internal class PolyIntrinsics(
    val text: String, val style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    val placeholders: List<AnnotatedString.Range<Placeholder>>, val density: Density, resolver: FontFamily.Resolver,
) : ParagraphIntrinsics {
    // an em paragraph size has no parent to resolve against, so it's relative to the 14 sp default
    private val baseSize = style.fontSize.let { if (!it.isSpecified) 14f else if (it.isEm) it.value * 14f else with(density) { it.toPx() } }
    private val builtRevision = FontManager.revision
    val runs: List<TextRun>
    val glyphs = Icu.shape(text)
    val advances = FloatArray(text.length)
    // prefix sums keep width lookups O(1) for line breaking and bidi hit testing
    private val prefix = DoubleArray(text.length + 1)
    val lineHeightPx: Float
    val align: TextAlign = style.textAlign

    init {
        val base = style.toSpanStyle()
        val spans = annotations.mapNotNull { r -> (r.item as? SpanStyle)?.let { AnnotatedString.Range(it, r.start.coerceIn(0, text.length), r.end.coerceIn(0, text.length)) } }
        val cuts = sortedSetOf(0, text.length)
        for (s in spans) { cuts += s.start; cuts += s.end }
        for (p in placeholders) { cuts += p.start.coerceIn(0, text.length); cuts += p.end.coerceIn(0, text.length) }
        val bounds = cuts.toList()
        val fontCache = HashMap<Pair<String, Float>, PolyFont>()
        runs = ArrayList()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (a >= b) continue
            var s = base
            var spanColor = Color.Unspecified
            var spanDecoration: TextDecoration? = null
            var spanShadow: Shadow? = null
            for (sp in spans) if (sp.start <= a && sp.end >= b) {
                s = s.merge(sp.item)
                if (sp.item.color.isSpecified) spanColor = sp.item.color
                sp.item.textDecoration?.let { spanDecoration = it }
                sp.item.shadow?.let { spanShadow = it }
            }
            val name = resolver.resolve(s.fontFamily, s.fontWeight ?: FontWeight.Normal, s.fontStyle ?: FontStyle.Normal, s.fontSynthesis ?: FontSynthesis.All).value as? String
                ?: FontManager.DEFAULT_KEY
            val size = if (s.fontSize == style.fontSize) baseSize else px(s.fontSize, baseSize)
            val synthesis = s.fontSynthesis ?: FontSynthesis.All
            // like Skia, slant upright faces when italic is requested and the family has no italic face
            val fakeItalic = s.fontStyle == FontStyle.Italic && name !in italicFonts && (synthesis == FontSynthesis.All || synthesis == FontSynthesis.Style)
            val skew = (s.textGeometricTransform?.skewX ?: 0f) + if (fakeItalic) 0.25f else 0f
            val scaleX = s.textGeometricTransform?.scaleX?.takeIf { it > 0f && it.isFinite() } ?: 1f
            val fakeBold = (s.fontWeight ?: FontWeight.Normal).weight >= BOLD_SYNTHESIS_WEIGHT && faceWeight(name) < BOLD_SYNTHESIS_WEIGHT &&
                !McFonts.isMinecraft(name) && (synthesis == FontSynthesis.All || synthesis == FontSynthesis.Weight)
            val letter = s.letterSpacing.let { if (!it.isSpecified) 0f else if (it.isEm) it.value * size else with(density) { it.toPx() } }
            val placeholder = placeholders.firstOrNull { it.start <= a && it.end >= b }?.item
            runs += TextRun(
                a, b, fontCache.getOrPut(name to size) { PolyFont(name, size) }, s, spanColor, spanDecoration, spanShadow, skew,
                scaleX, letter, if (fakeBold) fakeBoldWidth(size) else 0f, placeholder,
            )
        }
        if (runs.isEmpty()) {
            val name = resolver.resolve(base.fontFamily, base.fontWeight ?: FontWeight.Normal, base.fontStyle ?: FontStyle.Normal).value as? String ?: FontManager.DEFAULT_KEY
            runs += TextRun(0, 0, PolyFont(name, baseSize), base)
        }
        // shortcut: one glyph per code point without OpenType shaping, so scripts that reorder or substitute glyphs
        // (e.g. Devanagari) render unshaped; fixing that needs a shaping engine such as HarfBuzz
        val emojiFace = PolyGlyphPage.rasterizer?.emoji()
        for (run in runs) {
            val placeholder = run.placeholder
            if (placeholder != null) {
                advances[run.start] = placeholderSize(placeholder.width, run)
                continue
            }
            val letter = run.tracking
            val scaleX = run.scaleX
            val mc = McFonts.isMinecraft(run.font.name)
            var i = run.start
            while (i < run.end) {
                val cp = glyphs.codePointAt(i)
                val n = Character.charCount(cp)
                val sequence = if (!mc && emojiFace != null && !PolyGlyphPage.isIgnorable(cp)) PolyGlyphPage.sequence(emojiFace, glyphs, i, run.end) else 0L
                if (sequence != 0L) {
                    val end = sequence.toInt()
                    advances[i] = run.font.measureTextWidth(glyphs.substring(i, end)) * scaleX + letter
                    i = end
                    continue
                }
                val emoji = !mc && i + n < glyphs.length && glyphs.codePointAt(i + n) == PolyGlyphPage.EMOJI_VARIATION
                advances[i] = if (cp == '\n'.code || cp == LIGATURE_PLACEHOLDER.code || PolyGlyphPage.isIgnorable(cp)) 0f else Advances.of(run.font, cp, emoji) * scaleX + letter
                i += n
            }
        }
        for (i in advances.indices) prefix[i + 1] = prefix[i] + advances[i]
        val natural = runs.maxOf { it.metrics.spacing }
        lineHeightPx = if (style.lineHeight.isSpecified) px(style.lineHeight, natural) else round(natural)
    }

    fun px(unit: TextUnit, fallback: Float): Float = if (!unit.isSpecified) fallback else with(density) {
        if (unit.isEm) unit.value * baseSize else unit.toPx()
    }

    fun placeholderSize(unit: TextUnit, run: TextRun): Float = if (!unit.isSpecified) 0f else with(density) {
        if (unit.isEm) unit.value * run.font.size else unit.toPx()
    }

    private fun faceWeight(name: String): Int = sourceWeights[name] ?: FontManager.weightOf(name)

    fun runAt(offset: Int): TextRun = runs.firstOrNull { offset < it.end } ?: runs.last()

    fun width(start: Int, end: Int): Float = if (end <= start) 0f else (prefix[end] - prefix[start]).toFloat()

    override val maxIntrinsicWidth: Float by lazy {
        var best = 0f; var cur = 0f
        for (i in text.indices) { if (text[i] == '\n') { best = max(best, cur); cur = 0f } else cur += advances[i] }
        max(best, cur)
    }

    override val minIntrinsicWidth: Float by lazy {
        val it = BreakIterator.getLineInstance().also { b -> b.setText(text) }
        var best = 0f
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            var e = end
            while (e > start && text[e - 1].isWhitespace()) e--
            best = max(best, width(start, e))
            start = end
            end = it.next()
        }
        best
    }

    override val hasStaleResolvedFonts get() = builtRevision != FontManager.revision
}

private object Intrinsics {
    private const val MAX_CACHED = 256

    private class Key(
        val text: String, val style: TextStyle, val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        val placeholders: List<AnnotatedString.Range<Placeholder>>, val density: Density, val resolver: FontFamily.Resolver, val fonts: Int,
    ) {
        private val hash = ((text.hashCode() * 31 + style.hashCode()) * 31 + annotations.hashCode()) * 31 + placeholders.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Key && hash == other.hash && fonts == other.fonts && placeholders == other.placeholders &&
            resolver === other.resolver && text == other.text && style == other.style && density == other.density && annotations == other.annotations
    }

    private val cache = object : LinkedHashMap<Key, PolyIntrinsics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PolyIntrinsics>?) = size > MAX_CACHED
    }

    fun of(
        text: String, style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>, density: Density, resolver: FontFamily.Resolver,
    ): PolyIntrinsics {
        val key = Key(text, style, annotations, placeholders, density, resolver, FontManager.revision)
        synchronized(cache) { cache[key] }?.let { return it }
        val made = PolyIntrinsics(text, style, annotations, placeholders, density, resolver)
        synchronized(cache) { cache[key] = made }
        return made
    }
}

private object Advances {
    private val cache = ConcurrentHashMap<PolyFont, ConcurrentHashMap<Int, Float>>()
    @Volatile private var fonts = -1
    private const val MAX_FONTS = 128

    fun of(font: PolyFont, cp: Int, emoji: Boolean): Float {
        val revision = FontManager.revision
        if (revision != fonts) {
            cache.clear()
            fonts = revision
        }
        if (cache.size > MAX_FONTS) cache.clear()
        return cache.getOrPut(font) { ConcurrentHashMap() }.getOrPut(if (emoji) cp.inv() else cp) {
            val s = String(Character.toChars(cp))
            font.measureTextWidth(if (emoji) s + String(Character.toChars(PolyGlyphPage.EMOJI_VARIATION)) else s)
        }
    }
}

internal class Seg(val str: String, val x: Float, val w: Float, val run: TextRun)

internal class Line(
    val start: Int, val end: Int, val visibleEnd: Int, val top: Float, val bottom: Float, val baseline: Float, val left: Float, val width: Float, val ellipsis: Boolean,
    val rtl: Boolean = false, val runs: IntArray? = null, val runX: FloatArray? = null, val segs: Array<Seg>? = null,
    val gapStart: Int = -1, val gapEnd: Int = -1, val gapW: Float = 0f,
)

internal class PolyParagraph(private val intr: PolyIntrinsics, maxLines: Int, overflow: TextOverflow, private val maxWidth: Float) : PlatformParagraph {
    private val text = intr.text
    private val lines: List<Line>
    private val levels: ByteArray?
    override val didExceedMaxLines: Boolean
    override val width: Float
    override val height: Float
    override val placeholderRects: List<Rect?>

    init {
        val breaks = layout()
        val limit = if (maxLines <= 0) Int.MAX_VALUE else maxLines
        didExceedMaxLines = breaks.size > limit
        val kept = breaks.take(limit)
        val lh = intr.lineHeightPx
        val ascent = -intr.runs.minOf { it.metrics.ascent }
        val content = ascent + intr.runs.maxOf { it.metrics.descent }
        val boxW = if (maxWidth.isFinite()) maxWidth else kept.maxOfOrNull { intr.width(it.first, it.third) } ?: 0f
        val flags = when (intr.style.textDirection) {
            TextDirection.Ltr -> Bidi.DIRECTION_LEFT_TO_RIGHT
            TextDirection.Rtl -> Bidi.DIRECTION_RIGHT_TO_LEFT
            TextDirection.ContentOrRtl -> Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT
            else -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
        }
        val forcedRtl = flags == Bidi.DIRECTION_RIGHT_TO_LEFT || flags == Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT
        val lv = if (!forcedRtl && text.none { it >= '֐' }) null else ByteArray(text.length)
        var para: Bidi? = null
        var paraStart = 0
        var paraEnd = 0
        val sideEllipsis = overflow == TextOverflow.StartEllipsis || overflow == TextOverflow.MiddleEllipsis
        val baseAbove = (lh - content) / 2f + ascent
        var nextTop = 0f
        lines = kept.mapIndexed { i, (s, kEnd, ve) ->
            val last = didExceedMaxLines && i == kept.lastIndex
            val gap = if (last && sideEllipsis && limit == 1 && lv == null) sideGap(s, overflow == TextOverflow.StartEllipsis) else null
            val e = gap?.get(2) ?: kEnd
            val ellipsize = last && (overflow == TextOverflow.Ellipsis || (sideEllipsis && limit == 1))
            val vEnd = when {
                gap != null -> gap[3]
                ellipsize -> ellipsisEnd(s, ve)
                else -> ve
            }
            val ew = if (gap != null) ellipsisWidth(gapRunOffset(s, gap[0], gap[1])) else if (ellipsize) ellipsisWidth(vEnd) else 0f
            val w = if (gap != null) intr.width(s, gap[0]) + ew + intr.width(gap[1], vEnd) else intr.width(s, vEnd) + ew
            val top = nextTop
            val extents = placeholderExtents(s, vEnd, gap, lh, baseAbove)
            val above = if (extents == null) baseAbove else extents[0]
            val lineH = if (extents == null) lh else extents[0] + extents[1]
            nextTop = top + lineH
            if (lv != null && (para == null || s > paraEnd)) {
                paraStart = text.lastIndexOf('\n', s - 1) + 1
                paraEnd = text.indexOf('\n', s).let { if (it < 0) text.length else it }
                para = Bidi(text.substring(paraStart, paraEnd), flags).also { lv.fill(it.baseLevel.toByte(), paraStart, min(paraEnd + 1, text.length)) }
            }
            val b = para
            val rtl = b != null && !b.baseIsLeftToRight()
            val left = when (intr.align) {
                TextAlign.Center -> (boxW - w) / 2f
                TextAlign.Right -> boxW - w
                TextAlign.End -> if (rtl) 0f else boxW - w
                TextAlign.Left -> 0f
                else -> if (rtl) boxW - w else 0f
            }
            val baseline = top + above
            val line = if (gap != null) Line(s, e, vEnd, top, top + lineH, baseline, max(0f, left), w, true, rtl, gapStart = gap[0], gapEnd = gap[1], gapW = ew)
            else Line(s, e, vEnd, top, top + lineH, baseline, max(0f, left), w, ellipsize, rtl)
            if (b == null || lv == null) line else bidiLine(line, b, paraStart, if (ellipsize) vEnd else min(e, paraEnd), ew, lv)
        }
        levels = lv
        width = if (maxWidth.isFinite()) maxWidth else lines.maxOfOrNull { it.width } ?: 0f
        height = lines.lastOrNull()?.bottom ?: lh
        placeholderRects = placeholderRects(ascent, content - ascent)
    }

    private fun sideGap(s: Int, start: Boolean): IntArray? {
        if (!maxWidth.isFinite()) return null
        val newline = text.indexOf('\n', s)
        val end = if (newline < 0) text.length else newline + 1
        var visible = if (newline < 0) text.length else newline
        while (visible > s && text[visible - 1].isWhitespace()) visible--
        if (intr.width(s, visible) <= maxWidth) return null
        var a = s
        if (!start) {
            val runFont = intr.runAt(s)
            val half = (maxWidth - runFont.font.measureTextWidth(ELLIPSIS) * runFont.scaleX) / 2f
            while (a < visible) {
                val next = nextBoundary(a)
                if (intr.width(s, next) > half) break
                a = next
            }
        }
        val available = maxWidth - ellipsisWidth(gapRunOffset(s, a, a)) - intr.width(s, a)
        var b = a
        while (b < visible && intr.width(b, visible) > available) b = nextBoundary(b)
        return intArrayOf(a, b, end, visible)
    }

    private fun nextBoundary(i: Int): Int {
        var n = i + Character.charCount(text.codePointAt(i))
        while (n < text.length && intr.advances[n] == 0f && text[n] != '\n' && !Character.isWhitespace(text[n])) n++
        return n
    }

    private fun gapRunOffset(s: Int, gapStart: Int, gapEnd: Int) = if (gapStart <= s) gapEnd + 1 else gapStart

    private fun placeholderExtents(s: Int, vEnd: Int, gap: IntArray?, lh: Float, baseAbove: Float): FloatArray? {
        if (intr.placeholders.isEmpty()) return null
        var above = baseAbove
        var below = lh - baseAbove
        var boxed = 0f
        var boxAlign: PlaceholderVerticalAlign? = null
        val ascent = -intr.runs.minOf { it.metrics.ascent }
        val descent = intr.runs.maxOf { it.metrics.descent }
        var any = false
        for (p in intr.placeholders) {
            if (!placeholderVisible(p.start, s, vEnd, gap)) continue
            any = true
            val h = intr.placeholderSize(p.item.height, intr.runAt(p.start))
            when (p.item.placeholderVerticalAlign) {
                PlaceholderVerticalAlign.AboveBaseline -> above = max(above, h)
                PlaceholderVerticalAlign.TextTop -> below = max(below, h - ascent)
                PlaceholderVerticalAlign.TextBottom -> above = max(above, h - descent)
                PlaceholderVerticalAlign.TextCenter -> {
                    val center = (descent - ascent) / 2f
                    above = max(above, h / 2f - center)
                    below = max(below, h / 2f + center)
                }
                else -> if (h > boxed) {
                    boxed = h
                    boxAlign = p.item.placeholderVerticalAlign
                }
            }
        }
        if (!any) return null
        val short = boxed - (above + below)
        if (short > 0f) when (boxAlign) {
            PlaceholderVerticalAlign.Top -> below += short
            PlaceholderVerticalAlign.Bottom -> above += short
            else -> { above += short / 2f; below += short / 2f }
        }
        return floatArrayOf(above, below)
    }

    private fun placeholderVisible(at: Int, s: Int, vEnd: Int, gap: IntArray?): Boolean =
        at in s until vEnd && (gap == null || at < gap[0] || at >= gap[1])

    private fun placeholderRects(ascent: Float, descent: Float): List<Rect?> {
        val placeholders = intr.placeholders
        if (placeholders.isEmpty()) return emptyList()
        return placeholders.map { p ->
            val l = lines.firstOrNull { p.start >= it.start && p.start < it.end } ?: return@map null
            if (!placeholderVisible(p.start, l.start, l.visibleEnd, if (l.gapStart >= 0) intArrayOf(l.gapStart, l.gapEnd) else null)) return@map null
            val run = intr.runAt(p.start)
            val h = intr.placeholderSize(p.item.height, run)
            val box = getBoundingBox(p.start)
            val top = when (p.item.placeholderVerticalAlign) {
                PlaceholderVerticalAlign.AboveBaseline -> l.baseline - h
                PlaceholderVerticalAlign.Top -> l.top
                PlaceholderVerticalAlign.Bottom -> l.bottom - h
                PlaceholderVerticalAlign.Center -> (l.top + l.bottom - h) / 2f
                PlaceholderVerticalAlign.TextTop -> l.baseline - ascent
                PlaceholderVerticalAlign.TextBottom -> l.baseline + descent - h
                else -> l.baseline + (descent - ascent) / 2f - h / 2f
            }
            Rect(box.left, top, box.right, top + h)
        }
    }

    private fun lineX(l: Line, offset: Int): Float = when {
        l.gapStart < 0 || offset <= l.gapStart -> l.left + intr.width(l.start, offset)
        offset < l.gapEnd -> l.left + intr.width(l.start, l.gapStart)
        else -> l.left + intr.width(l.start, l.gapStart) + l.gapW + intr.width(l.gapEnd, offset)
    }

    private fun bidiLine(l: Line, para: Bidi, paraStart: Int, limit: Int, ellipsisW: Float, levels: ByteArray): Line {
        val s = l.start
        val line = if (limit > s) para.createLineBidi(s - paraStart, limit - paraStart) else null
        if (!l.rtl && (line == null || line.isLeftToRight)) return l
        if (line == null) return Line(s, l.end, l.visibleEnd, l.top, l.bottom, l.baseline, l.left, l.width, l.ellipsis, true, IntArray(0), FloatArray(0), emptyArray())
        val n = line.runCount
        val order = Array<Any>(n) { it }
        Bidi.reorderVisually(ByteArray(n) { line.getRunLevel(it).toByte() }, 0, order, 0, n)
        val runs = IntArray(n * 3)
        val runX = FloatArray(n)
        val segs = ArrayList<Seg>()
        var x = if (l.rtl) l.left + ellipsisW - intr.width(l.visibleEnd, limit) else l.left
        for (v in 0 until n) {
            val k = order[v] as Int
            val rs = s + line.getRunStart(k)
            val re = s + line.getRunLimit(k)
            val level = line.getRunLevel(k)
            levels.fill(level.toByte(), rs, re)
            runs[v * 3] = rs
            runs[v * 3 + 1] = re
            runs[v * 3 + 2] = level
            runX[v] = x
            for (run in intr.runs) {
                val a = max(run.start, rs)
                val e = min(min(run.end, re), l.visibleEnd)
                if (a >= e) continue
                val glyphs = intr.glyphs.substring(a, e)
                segs += if (level and 1 == 0) Seg(glyphs, x + intr.width(rs, a), intr.width(a, e), run)
                else Seg(reversedClusters(glyphs), x + intr.width(e, re), intr.width(a, e), run)
            }
            x += intr.width(rs, re)
        }
        return Line(s, l.end, l.visibleEnd, l.top, l.bottom, l.baseline, l.left, l.width, l.ellipsis, l.rtl, runs, runX, segs.toTypedArray())
    }

    private fun layout(): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val it = BreakIterator.getLineInstance().also { b -> b.setText(text) }
        var lineStart = 0
        var lineW = 0f
        var pos = it.first()
        var next = it.next()
        fun trimmedEnd(s: Int, e: Int): Int { var v = e; while (v > s && text[v - 1].isWhitespace()) v--; return v }
        while (next != BreakIterator.DONE) {
            val segW = intr.width(pos, trimmedEnd(pos, next))
            val segFull = intr.width(pos, next)
            if (lineW + segW > maxWidth + 0.01f && pos > lineStart) {
                out += Triple(lineStart, pos, trimmedEnd(lineStart, pos))
                lineStart = pos
                lineW = 0f
            }
            if (segW > maxWidth && pos == lineStart) {
                var e = pos
                var w = 0f
                while (e < next) {
                    val step = min(next, e + Character.charCount(text.codePointAt(e)))
                    var advance = 0f
                    for (i in e until step) advance += intr.advances[i]
                    if (w + advance > maxWidth && e > lineStart) {
                        out += Triple(lineStart, e, e)
                        lineStart = e
                        w = 0f
                    }
                    w += advance
                    e = step
                }
                lineW = w
            } else lineW += segFull
            if (next > 0 && text[next - 1] == '\n') {
                out += Triple(lineStart, next, if (next - 1 > lineStart && text[next - 2] == '\r') next - 2 else next - 1)
                lineStart = next
                lineW = 0f
            }
            pos = next
            next = it.next()
        }
        if (lineStart < text.length || out.isEmpty() || text.endsWith('\n')) out += Triple(lineStart, text.length, trimmedEnd(lineStart, text.length))
        return out
    }

    private fun ellipsisWidth(at: Int) = intr.runAt(max(0, at - 1)).let { it.font.measureTextWidth(ELLIPSIS) * it.scaleX }

    private fun ellipsisEnd(start: Int, end: Int): Int {
        if (!maxWidth.isFinite()) return end
        var e = end
        var run: TextRun? = null
        var ellipsis = 0f
        while (e > start) {
            val r = intr.runAt(max(0, e - 1))
            if (r !== run) {
                run = r
                ellipsis = r.font.measureTextWidth(ELLIPSIS) * r.scaleX
            }
            if (intr.width(start, e) + ellipsis <= maxWidth) break
            e--
        }
        return e
    }

    private fun editEnd(l: Line) = when {
        l.ellipsis -> l.visibleEnd
        l.end > l.start && text[l.end - 1] == '\n' -> l.end - 1
        else -> l.end
    }

    private fun lineFor(offset: Int): Int {
        for (i in lines.indices) if (offset < lines[i].end) return i
        return lines.lastIndex.coerceAtLeast(0)
    }

    override val minIntrinsicWidth get() = intr.minIntrinsicWidth
    override val maxIntrinsicWidth get() = intr.maxIntrinsicWidth
    override val firstBaseline get() = lines.firstOrNull()?.baseline ?: 0f
    override val lastBaseline get() = lines.lastOrNull()?.baseline ?: 0f
    override val lineCount get() = lines.size

    private val ascent get() = -intr.runs.minOf { it.metrics.ascent }
    private val descent get() = intr.runs.maxOf { it.metrics.descent }

    override fun getLineAscent(lineIndex: Int) = -ascent
    override fun getLineDescent(lineIndex: Int) = descent
    override fun getLineLeft(lineIndex: Int) = lines[lineIndex].left
    override fun getLineRight(lineIndex: Int) = lines[lineIndex].left + lines[lineIndex].width
    override fun getLineTop(lineIndex: Int) = lines[lineIndex].top
    override fun getLineBaseline(lineIndex: Int) = lines[lineIndex].baseline
    override fun getLineBottom(lineIndex: Int) = lines[lineIndex].bottom
    override fun getLineHeight(lineIndex: Int) = lines[lineIndex].bottom - lines[lineIndex].top
    override fun getLineWidth(lineIndex: Int) = lines[lineIndex].width
    override fun getLineStart(lineIndex: Int) = lines[lineIndex].start
    override fun getLineEnd(lineIndex: Int, visibleEnd: Boolean) = if (visibleEnd) lines[lineIndex].visibleEnd else lines[lineIndex].end
    override fun isLineEllipsized(lineIndex: Int) = lines[lineIndex].ellipsis
    override fun getLineForOffset(offset: Int) = lineFor(offset)

    override fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float {
        if (lines.isEmpty()) return 0f
        val l = lines[lineFor(offset)]
        if (l.runs != null) return bidiX(l, offset.coerceIn(l.start, editEnd(l)), usePrimaryDirection)
        return lineX(l, offset.coerceIn(l.start, editEnd(l)))
    }

    private fun runIndex(runs: IntArray, i: Int): Int {
        var k = 0
        while (k < runs.size) {
            if (i >= runs[k] && i < runs[k + 1]) return k
            k += 3
        }
        return -1
    }

    private fun edge(l: Line, k: Int, offset: Int): Float {
        val runs = l.runs!!
        val x = l.runX!![k / 3]
        return if (runs[k + 2] and 1 == 0) x + intr.width(runs[k], offset) else x + intr.width(offset, runs[k + 1])
    }

    private fun bidiX(l: Line, offset: Int, primary: Boolean): Float {
        val runs = l.runs!!
        val runX = l.runX!!
        if (runs.isEmpty()) return if (l.rtl) l.left + l.width else l.left
        val visLeft = runX[0]
        val visRight = runX[runX.size - 1] + intr.width(runs[runs.size - 3], runs[runs.size - 2])
        val paraLevel = if (l.rtl) 1 else 0
        val before = if (offset > l.start) runIndex(runs, offset - 1) else -1
        val at = runIndex(runs, offset)
        val levelBefore = if (before < 0) paraLevel else runs[before + 2]
        val levelAt = if (at < 0) paraLevel else runs[at + 2]
        val trailing = (levelBefore < levelAt) == primary
        return when {
            trailing && before >= 0 -> edge(l, before, offset)
            !trailing && at >= 0 -> edge(l, at, offset)
            trailing -> if (l.rtl) visRight else visLeft
            else -> if (l.rtl) visLeft else visRight
        }
    }

    override fun getCursorRect(offset: Int): Rect {
        val x = getHorizontalPosition(offset, true)
        val l = lines.getOrNull(lineFor(offset)) ?: return Rect(x, 0f, x, height)
        return Rect(x, l.top, x, l.bottom)
    }

    override fun getParagraphDirection(offset: Int) =
        if (lines.isNotEmpty() && lines[lineFor(offset)].rtl) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr

    override fun getBidiRunDirection(offset: Int): ResolvedTextDirection {
        val lv = levels ?: return ResolvedTextDirection.Ltr
        if (lv.isEmpty()) return getParagraphDirection(offset)
        return if (lv[offset.coerceIn(0, lv.size - 1)].toInt() and 1 == 0) ResolvedTextDirection.Ltr else ResolvedTextDirection.Rtl
    }

    override fun getLineForVerticalPosition(vertical: Float): Int {
        for (i in lines.indices) if (vertical < lines[i].bottom) return i
        return lines.lastIndex.coerceAtLeast(0)
    }

    override fun getOffsetForPosition(position: Offset): Int {
        if (lines.isEmpty()) return 0
        val l = lines[getLineForVerticalPosition(position.y)]
        var x = l.left
        val end = editEnd(l)
        if (l.runs != null) {
            val hi = if (l === lines.last() || end < l.end) end else max(l.start, end - 1)
            var best = l.start
            var bestD = Float.POSITIVE_INFINITY
            for (o in l.start..hi) {
                if (o > l.start && o < text.length && Character.isLowSurrogate(text[o]) && Character.isHighSurrogate(text[o - 1])) continue
                val d = abs(bidiX(l, o, true) - position.x)
                if (d < bestD) { bestD = d; best = o }
            }
            return best
        }
        var i = l.start
        while (i < end) {
            if (i == l.gapStart) {
                if (position.x < x + l.gapW / 2f) return i
                x += l.gapW
                i = max(l.gapEnd, i + 1)
                continue
            }
            val a = intr.advances[i]
            if (a != 0f) {
                if (position.x < x + a / 2f) return i
                x += a
            }
            i++
        }
        return if (l === lines.last() || end < l.end) end else max(l.start, end - 1)
    }

    override fun getRangeForRect(rect: Rect, granularity: TextGranularity, inclusionStrategy: TextInclusionStrategy): TextRange {
        val a = getOffsetForPosition(rect.topLeft)
        val b = getOffsetForPosition(rect.bottomRight)
        return if (a < b) TextRange(a, b) else if (b < a) TextRange(b, a) else TextRange.Zero
    }

    override fun getBoundingBox(offset: Int): Rect {
        val l = lines.getOrNull(lineFor(offset)) ?: return Rect.Zero
        val runs = l.runs
        val k = if (runs != null) runIndex(runs, offset) else -1
        if (k >= 0) {
            val a = edge(l, k, offset)
            val b = edge(l, k, offset + 1)
            return Rect(min(a, b), l.top, max(a, b), l.bottom)
        }
        val x = getHorizontalPosition(offset, true)
        val hidden = l.gapStart >= 0 && offset >= l.gapStart && offset < l.gapEnd
        return Rect(x, l.top, x + (if (hidden) 0f else intr.advances.getOrNull(offset) ?: 0f), l.bottom)
    }

    override fun fillBoundingBoxes(range: TextRange, array: FloatArray, arrayStart: Int) {
        var k = arrayStart
        for (i in range.min until range.max) {
            val b = getBoundingBox(i)
            array[k++] = b.left; array[k++] = b.top; array[k++] = b.right; array[k++] = b.bottom
        }
    }

    override fun getWordBoundary(offset: Int): TextRange {
        val it = BreakIterator.getWordInstance().also { b -> b.setText(text) }
        val o = offset.coerceIn(0, text.length)
        val end = if (it.isBoundary(o) && o < text.length) it.following(o) else it.following(o.coerceAtMost(max(0, text.length - 1)))
        val start = it.preceding(if (end == BreakIterator.DONE) text.length else end)
        return TextRange(if (start == BreakIterator.DONE) 0 else start, if (end == BreakIterator.DONE) text.length else end)
    }

    override fun getPathForRange(start: Int, end: Int): Path {
        val p = PolyComposePath()
        for (l in lines) {
            val runs = l.runs
            if (runs != null) {
                var k = 0
                while (k < runs.size) {
                    val a = max(start, runs[k])
                    val b = min(end, runs[k + 1])
                    if (a < b) {
                        val x0 = edge(l, k, a)
                        val x1 = edge(l, k, b)
                        p.addRect(Rect(min(x0, x1), l.top, max(x0, x1), l.bottom), Path.Direction.CounterClockwise)
                    }
                    k += 3
                }
                continue
            }
            val s = max(start, l.start)
            val e = min(end, l.visibleEnd)
            if (s >= e && !(start < l.end && end > l.visibleEnd && s <= l.visibleEnd)) continue
            // measured from this line's start because a wrapped line's end offset resolves to the next line
            val x0 = lineX(l, s)
            val x1 = if (end > l.visibleEnd && l.end > l.visibleEnd) lineX(l, l.visibleEnd) + intr.advances.getOrElse(l.visibleEnd) { 0f } else lineX(l, e)
            p.addRect(Rect(x0, l.top, max(x1, x0), l.bottom), Path.Direction.CounterClockwise)
        }
        return p
    }

    override fun paint(canvas: Canvas, color: Color, shadow: Shadow?, textDecoration: TextDecoration?) =
        draw(canvas, if (color.isSpecified) color else null, 1f, shadow, textDecoration)

    override fun paint(canvas: Canvas, color: Color, shadow: Shadow?, textDecoration: TextDecoration?, drawStyle: DrawStyle?, blendMode: BlendMode) =
        draw(canvas, if (color.isSpecified) color else null, 1f, shadow, textDecoration)

    override fun paint(canvas: Canvas, brush: Brush, alpha: Float, shadow: Shadow?, textDecoration: TextDecoration?, drawStyle: DrawStyle?, blendMode: BlendMode) {
        val a = if (alpha.isNaN()) 1f else alpha
        if (brush is ChromaBrush) return drawChroma(canvas, brush, a, shadow, textDecoration)
        if (brush is SolidColor || brush !is ShaderBrush) return draw(canvas, (brush as? SolidColor)?.value, a, shadow, textDecoration)
        val shader = brush.createShader(Size(width, height)).platformShader
        val fade = (shader as? GradientShader)?.fade(a, true)
        if (fade != null) return draw(canvas, Color.White, 1f, shadow, textDecoration, fade)
        val fill = brushFill(brush, shader) ?: return draw(canvas, (shader as? GradientShader)?.let { Color(it.brush.sample(0.5f)) }, a, shadow, textDecoration)
        draw(canvas, Color.White, a, shadow, textDecoration, fill = fill)
    }

    private var fillBrush: Brush? = null
    private var fillSize = Size.Zero
    private var fillCached: PolyImageFill? = null

    private fun brushFill(brush: Brush, shader: PlatformShader): PolyImageFill? {
        val size = Size(width, height)
        if (brush == fillBrush && size == fillSize) return fillCached
        val fill = ShaderRaster.fill(shader, 0f, 0f, width, height, 1f, 1f)
        fillBrush = brush
        fillSize = size
        fillCached = fill
        return fill
    }

    private fun draw(canvas: Canvas, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, fade: PolyFade? = null, fill: PolyImageFill? = null) {
        FontManager.observedRevision.intValue
        (canvas as? PolyDrawCanvas)?.polyOp { pc ->
            val r = pc.renderer
            if (fill != null) {
                val t = r as? PolyTessellator ?: return@polyOp drawLines(r, Color(fill.colors[0].argb), alpha, shadow, decoration, PASS_ALL)
                drawLines(r, color, alpha, shadow, decoration, PASS_UNDER)
                t.textFill(fill)
                try {
                    drawLines(r, color, alpha, shadow, decoration, PASS_OVER)
                } finally {
                    t.textFill(null)
                }
                return@polyOp
            }
            if (fade == null) return@polyOp drawLines(r, color, alpha, shadow, decoration, PASS_ALL)
            drawLines(r, color, alpha, shadow, decoration, PASS_UNDER)
            r.saveLayerFade(fade)
            drawLines(r, color, alpha, shadow, decoration, PASS_OVER)
            r.restore()
        }
    }

    private fun drawChroma(canvas: Canvas, brush: ChromaBrush, alpha: Float, shadow: Shadow?, decoration: TextDecoration?) {
        val pad = intr.lineHeightPx
        FontManager.observedRevision.intValue
        (canvas as? PolyDrawCanvas)?.polyOp { pc ->
            pc.renderer.live(-pad, -pad, width + pad * 2f, height + pad * 2f, brush::argb) { r ->
                drawLines(r, Color(brush.argb()), alpha, shadow, decoration, PASS_ALL)
            }
        }
    }

    private fun drawLines(r: PolyRenderer, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int) {
        for (l in lines) {
            var x = l.left
            val segs = l.segs
            if (segs != null) {
                for (sg in segs) {
                    drawRun(r, l, sg.run, sg.str, sg.x, sg.w, color, alpha, shadow, decoration, pass)
                    x = max(x, sg.x + sg.w)
                }
                if (l.rtl) x = l.left
            } else if (l.gapStart >= 0) {
                x = drawRange(r, l, l.start, l.gapStart, x, color, alpha, shadow, decoration, pass)
                if (pass != PASS_UNDER) drawEllipsis(r, l, intr.runAt(max(0, gapRunOffset(l.start, l.gapStart, l.gapEnd) - 1)), x, color, alpha)
                x = drawRange(r, l, l.gapEnd, l.visibleEnd, x + l.gapW, color, alpha, shadow, decoration, pass)
            } else x = drawRange(r, l, l.start, l.visibleEnd, x, color, alpha, shadow, decoration, pass)
            if (l.ellipsis && l.gapStart < 0 && pass != PASS_UNDER) drawEllipsis(r, l, intr.runAt(max(0, l.visibleEnd - 1)), x, color, alpha)
        }
    }

    private fun drawRange(r: PolyRenderer, l: Line, from: Int, to: Int, x0: Float, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int): Float {
        var x = x0
        for (run in intr.runs) {
            val s = max(run.start, from)
            val e = min(run.end, to)
            if (s >= e) continue
            val w = intr.width(s, e)
            drawRun(r, l, run, intr.glyphs.substring(s, e), x, w, color, alpha, shadow, decoration, pass)
            x += w
        }
        return x
    }

    private fun drawEllipsis(r: PolyRenderer, l: Line, run: TextRun, x: Float, color: Color?, alpha: Float) {
        val base = run.spanColor.takeIf { it.isSpecified } ?: color ?: run.style.color.takeIf { it.isSpecified } ?: Color.Black
        runText(r, run, ELLIPSIS, x, l.baseline, base.copy(alpha = base.alpha * alpha).toArgb(), false)
    }

    private fun runText(r: PolyRenderer, run: TextRun, str: String, x: Float, baseline: Float, color: Int, mcShadow: Boolean) {
        val sx = run.scaleX
        val scaled = sx != 1f
        if (scaled) {
            r.save()
            r.translate(x, 0f)
            r.scale(sx, 1f)
        }
        val at = if (scaled) 0f else x
        val tracking = run.tracking / sx
        if (McFonts.isMinecraft(run.font.name)) mcRun(r, run, str, at, baseline, color, tracking, mcShadow)
        else if (run.bold <= 0f) r.text(str, at, baseline, color, run.font, tracking, run.skew / sx)
        else {
            val translucent = color ushr 24 < 0xFF
            if (translucent) r.saveLayerAlpha((color ushr 24) / 255f)
            val c = if (translucent) color or OPAQUE else color
            r.text(str, at, baseline, c, run.font, tracking, run.skew / sx)
            emboldenPass(r, run, str, at + run.bold / sx, baseline, c, tracking, run.skew / sx)
            if (translucent) r.restore()
        }
        if (scaled) r.restore()
    }

    private fun emojiEnd(str: String, i: Int, face: PolyGlyphPage.Face?): Int {
        val cp = str.codePointAt(i)
        val after = i + Character.charCount(cp)
        if (face != null && !PolyGlyphPage.isIgnorable(cp)) {
            val sequence = PolyGlyphPage.sequence(face, str, i, str.length)
            if (sequence != 0L) return sequence.toInt()
        }
        val next = if (after < str.length) str.codePointAt(after) else 0
        if (next == PolyGlyphPage.EMOJI_VARIATION && Character.isEmoji(cp)) return after + Character.charCount(next)
        return if (Character.isEmojiPresentation(cp)) after else -1
    }

    private fun emboldenPass(r: PolyRenderer, run: TextRun, str: String, x: Float, baseline: Float, color: Int, tracking: Float, skew: Float) {
        val face = PolyGlyphPage.rasterizer?.emoji()
        var i = 0
        var hasEmoji = false
        while (i < str.length) {
            if (emojiEnd(str, i, face) >= 0) { hasEmoji = true; break }
            i += Character.charCount(str.codePointAt(i))
        }
        if (!hasEmoji) return r.text(str, x, baseline, color, run.font, tracking, skew)
        var segStart = 0
        var segX = x
        var pen = x
        i = 0
        while (i < str.length) {
            val end = emojiEnd(str, i, face)
            if (end < 0) {
                val cp = str.codePointAt(i)
                if (!PolyGlyphPage.isIgnorable(cp)) pen += Advances.of(run.font, cp, false) + tracking
                i += Character.charCount(cp)
                continue
            }
            if (i > segStart) r.text(str.substring(segStart, i), segX, baseline, color, run.font, tracking, skew)
            pen += run.font.measureTextWidth(str.substring(i, end)) + tracking
            i = end
            segStart = end
            segX = pen
        }
        if (segStart < str.length) r.text(str.substring(segStart), segX, baseline, color, run.font, tracking, skew)
    }

    private fun drawRun(r: PolyRenderer, l: Line, run: TextRun, str: String, x: Float, w: Float, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int) {
        if (run.placeholder != null) return
        val base = run.spanColor.takeIf { it.isSpecified } ?: color ?: run.style.color.takeIf { it.isSpecified } ?: Color.Black
        val c = base.copy(alpha = base.alpha * alpha).toArgb()
        val sh = run.spanShadow ?: shadow ?: run.style.shadow
        val mcShadow = McFonts.isShadow(sh)
        val mc = McFonts.isMinecraft(run.font.name)
        if (pass != PASS_OVER) {
            if (run.style.background.isSpecified && run.style.background.alpha > 0f) r.rect(x, l.top, w, l.bottom - l.top, run.style.background.toArgb(), 0f)
            val o = McFonts.scale(run.font)
            when {
                mcShadow && mc -> if (pass == PASS_UNDER) runText(r, run, str, x + o, l.baseline + o, McFonts.shadowArgb(c), false)
                mcShadow -> runText(r, run, str, x + o, l.baseline + o, McFonts.shadowArgb(c), false)
                sh != null && sh != Shadow.None && sh.color.alpha > 0f -> runText(r, run, str, x + sh.offset.x, l.baseline + sh.offset.y, sh.color.toArgb(), false)
            }
        }
        if (pass == PASS_UNDER) return
        runText(r, run, str, x, l.baseline, c, mc && mcShadow && pass == PASS_ALL)
        val deco = run.spanDecoration ?: decoration ?: run.style.textDecoration
        if (deco != null) {
            val m = run.metrics
            val t = max(1f, m.underlineThickness)
            if (TextDecoration.Underline in deco) r.rect(x, l.baseline + m.underlinePosition, w, t, c, 0f)
            if (TextDecoration.LineThrough in deco) r.rect(x, l.baseline + m.strikeoutPosition, w, max(1f, m.strikeoutThickness), c, 0f)
        }
    }

    private fun mcRun(r: PolyRenderer, run: TextRun, str: String, x: Float, baseline: Float, color: Int, letter: Float, shadow: Boolean) {
        val scale = McFonts.scale(run.font)
        val top = baseline - McFonts.ASCENT * scale
        val prefix = (if (run.font.name == McFonts.BOLD) "§l" else "") + (if (run.skew != 0f) "§o" else "")
        val formatted = prefix.isNotEmpty() && '§' !in str
        if (letter == 0f) {
            if (formatted) r.mcText(prefix + str, x, top, color, shadow, scale, false, false)
            else r.mcText(str, x, top, color, shadow, scale, true, false)
            return
        }
        var cx = x
        var i = 0
        while (i < str.length) {
            val n = Character.charCount(str.codePointAt(i))
            val ch = str.substring(i, i + n)
            if (formatted) r.mcText(prefix + ch, cx, top, color, shadow, scale, false, false)
            else r.mcText(ch, cx, top, color, shadow, scale, true, false)
            cx += run.font.measureTextWidth(ch) + letter
            i += n
        }
    }

    private companion object {
        const val PASS_ALL = 0
        const val PASS_UNDER = 1
        const val PASS_OVER = 2
        const val ELLIPSIS = "…"
        const val OPAQUE = -0x1000000
    }
}
