package org.polyfrost.compose.effect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.polyfrost.compose.render.PolyEffect
import kotlin.math.max
import kotlin.math.min

@Composable
public fun LiquidGlassGroup(spacing: Dp = 0.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val group = remember { LiquidGroup() }
    group.spacing = with(LocalDensity.current) { spacing.toPx() }
    LaunchedEffect(group) {
        group.adaptation.track({ group.minDimDp }, { group.clear }, { group.members.isNotEmpty() })
    }
    Box(modifier.onPlaced { group.place(it) }) {
        Spacer(Modifier.matchParentSize().graphicsLayer {}.drawBehind { group.draw(this) })
        CompositionLocalProvider(LocalLiquidGlassGroup provides group, content = content)
    }
}

internal val LocalLiquidGlassGroup = staticCompositionLocalOf<LiquidGroup?> { null }

internal class LiquidGroup {
    val members = mutableStateListOf<LiquidMember>()
    val adaptation = LiquidAdaptation()
    val version = mutableIntStateOf(0)
    var coords: LayoutCoordinates? = null
    private var placedAt = Offset.Unspecified
    private var placedSize = IntSize.Zero
    var spacing by mutableFloatStateOf(0f)
    var minDimDp = 0f
        private set
    var clear = false
        private set
    private val params = ArrayList<LiquidParams>(1)

    fun place(c: LayoutCoordinates) {
        coords = c
        val root = c.positionInRoot()
        if (root == placedAt && c.size == placedSize) return
        placedAt = root
        placedSize = c.size
        version.intValue++
    }
    private var rects = FloatArray(PolyEffect.Liquid.MAX_SHAPES * SHAPE_FLOATS)
    private var ids = arrayOfNulls<Any>(PolyEffect.Liquid.MAX_SHAPES)
    private var owners = arrayOfNulls<LiquidMember>(PolyEffect.Liquid.MAX_SHAPES)
    private var atX = FloatArray(PolyEffect.Liquid.MAX_SHAPES)
    private var atY = FloatArray(PolyEffect.Liquid.MAX_SHAPES)
    private var used = BooleanArray(PolyEffect.Liquid.MAX_SHAPES)
    private var done = BooleanArray(PolyEffect.Liquid.MAX_SHAPES)
    private val scratch = FloatArray(SHAPE_FLOATS)
    private var chunk = 0

    fun add(member: LiquidMember) {
        member.state.groupAdaptation = adaptation
        members += member
    }

    fun remove(member: LiquidMember) {
        member.state.groupAdaptation = null
        members -= member
    }

    fun draw(scope: DrawScope) {
        version.intValue
        val gap = spacing
        val origin = coords?.takeIf { it.isAttached } ?: return
        if (members.isEmpty()) return
        val n = collect(origin)
        if (n == 0) return
        var allClear = true
        var largest = 0f
        for (i in 0 until n) {
            val m = owners[i] ?: continue
            if (m.variant != LiquidVariant.Clear) allClear = false
            largest = max(largest, min(m.state.width, m.state.height))
            done[i] = false
        }
        minDimDp = largest / scope.density
        clear = allClear
        chunk = 0
        for (i in 0 until n) {
            if (done[i]) continue
            val lead = owners[i] ?: continue
            drawMaterial(scope, lead, i, n, gap)
        }
    }

    private fun nextParams(): LiquidParams = (params.getOrNull(chunk) ?: LiquidParams().also { params += it }).also { chunk++ }

    private fun ensure(size: Int) {
        if (size <= owners.size) return
        val cap = max(size, owners.size * 2)
        rects = rects.copyOf(cap * SHAPE_FLOATS)
        ids = ids.copyOf(cap)
        owners = owners.copyOf(cap)
        atX = atX.copyOf(cap)
        atY = atY.copyOf(cap)
        used = used.copyOf(cap)
        done = done.copyOf(cap)
    }

    private fun collect(origin: LayoutCoordinates): Int {
        var n = 0
        for (m in members) {
            m.version.intValue
            val c = m.coords?.takeIf { it.isAttached } ?: continue
            if (m.state.appear <= 0f) continue
            ensure(n + 1)
            val at = origin.localPositionOf(c, Offset.Zero) + m.state.displayedOffset()
            m.shapeInto(scratch, 0f)
            val o = n * SHAPE_FLOATS
            scratch.copyInto(rects, o)
            rects[o] += at.x
            rects[o + 1] += at.y
            ids[n] = m.unionId
            owners[n] = m
            atX[n] = at.x
            atY[n] = at.y
            n++
        }
        for (i in n until owners.size) owners[i] = null
        return n
    }

    private fun drawMaterial(scope: DrawScope, lead: LiquidMember, from: Int, n: Int, gap: Float) {
        var appear = 0f
        var glow = -1
        var g: LiquidGlassState? = null
        for (j in from until n) {
            val m = owners[j] ?: continue
            if (done[j] || !sameMaterial(m, lead)) continue
            done[j] = true
            used[j] = false
            appear = max(appear, m.state.appear)
            if (g == null || m.state.glowStrength > g.glowStrength) {
                g = m.state
                glow = j
            }
        }
        if (g == null) return
        var out = nextParams()
        out.clearShapes()
        for (i in from until n) {
            val mi = owners[i] ?: continue
            if (used[i] || !sameMaterial(mi, lead)) continue
            used[i] = true
            val oi = i * SHAPE_FLOATS
            var x0 = rects[oi]
            var y0 = rects[oi + 1]
            var x1 = x0 + rects[oi + 2]
            var y1 = y0 + rects[oi + 3]
            var tl = rects[oi + SHAPE_CORNERS]
            var tr = rects[oi + SHAPE_CORNERS + 1]
            var br = rects[oi + SHAPE_CORNERS + 2]
            var bl = rects[oi + SHAPE_CORNERS + 3]
            val id = ids[i]
            if (id != null) {
                for (j in i + 1 until n) {
                    val mj = owners[j] ?: continue
                    if (used[j] || ids[j] != id || !sameMaterial(mj, lead)) continue
                    used[j] = true
                    val oj = j * SHAPE_FLOATS
                    x0 = min(x0, rects[oj])
                    y0 = min(y0, rects[oj + 1])
                    x1 = max(x1, rects[oj] + rects[oj + 2])
                    y1 = max(y1, rects[oj + 1] + rects[oj + 3])
                    tl = min(tl, rects[oj + SHAPE_CORNERS])
                    tr = min(tr, rects[oj + SHAPE_CORNERS + 1])
                    br = min(br, rects[oj + SHAPE_CORNERS + 2])
                    bl = min(bl, rects[oj + SHAPE_CORNERS + 3])
                }
            }
            if (out.full) {
                finish(scope, lead, out, appear, gap, g, glow)
                out = nextParams()
                out.clearShapes()
            }
            out.addShape(x0, y0, x1 - x0, y1 - y0, tl, tr, br, bl)
        }
        finish(scope, lead, out, appear, gap, g, glow)
    }

    private fun finish(scope: DrawScope, lead: LiquidMember, out: LiquidParams, appear: Float, gap: Float, g: LiquidGlassState, glow: Int) {
        lead.configure(out, scope.density, adaptation, LiquidMaterial.sizeFactor(minDimDp), appear)
        out.specular = 1f + LiquidMaterial.SPECULAR_BOOST * g.glowStrength
        out.spacing = gap
        out.glowX = atX[glow] + g.glowX
        out.glowY = atY[glow] + g.glowY
        out.glowRadius = g.glowRadius
        out.glowStrength = g.glowStrength
        out.draw(scope, Color.Unspecified, 1f)
    }

    private fun sameMaterial(a: LiquidMember, b: LiquidMember): Boolean =
        a === b || (a.variant == b.variant && a.tint == b.tint && a.dim == b.dim)
}
