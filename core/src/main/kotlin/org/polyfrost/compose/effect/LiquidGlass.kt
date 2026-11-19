@file:OptIn(ExperimentalComposeUiApi::class)

package org.polyfrost.compose.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.polyfrost.compose.render.PolyEffect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

public enum class LiquidVariant { Regular, Clear }

public object LiquidGlassSettings {
    public var reduceTransparency: Boolean by mutableStateOf(false)
    public var increaseContrast: Boolean by mutableStateOf(false)
    public var reduceMotion: Boolean by mutableStateOf(false)
}

public fun concentricRadius(parentRadius: Dp, inset: Dp, fallback: Dp): Dp =
    if (parentRadius.isSpecified && inset.isSpecified) max(parentRadius - inset, 0.dp) else fallback

@Composable
public fun rememberLiquidGlassState(interactive: Boolean = true): LiquidGlassState {
    val scope = rememberCoroutineScope()
    return remember(interactive, scope) { LiquidGlassState(interactive, scope) }
}

@Stable
public class LiquidGlassState internal constructor(public val interactive: Boolean, private val scope: CoroutineScope) {
    private val pressX = Animatable(1f)
    private val pressY = Animatable(1f)
    private val offsetAnim = Animatable(Offset.Zero, Offset.VectorConverter)
    private val glowRadiusAnim = Animatable(0f)
    private val glowStrengthAnim = Animatable(0f)
    private val appearAnim = Animatable(1f)
    private var stretchValue by mutableFloatStateOf(0f)
    private var stretchVelocity = 0f
    private var dragVelocity = Velocity.Zero
    private var movedAt = 0L
    private var physics: Job? = null
    private var appearTarget = true
    internal val adaptation = LiquidAdaptation()
    internal var groupAdaptation: LiquidAdaptation? = null
    internal var glowX by mutableFloatStateOf(0f)
    internal var glowY by mutableFloatStateOf(0f)
    internal var density = 1f
    internal var width = 0f
    internal var height = 0f
    internal var minOffset = Offset.Unspecified
    internal var maxOffset = Offset.Unspecified

    public var pressed: Boolean by mutableStateOf(false)
        private set

    public var dragging: Boolean by mutableStateOf(false)
        private set

    public val offset: Offset get() = displayedOffset()

    public val appear: Float get() = appearAnim.value

    public val style: Float get() = (groupAdaptation ?: adaptation).style.value

    public val isDark: Boolean get() = style >= 0.5f

    public val contentColor: Color get() = lerp(LIGHT_CONTENT, DARK_CONTENT, style.coerceIn(0f, 1f))

    public val scaleX: Float get() = pressX.value * exp(stretch()) * squashX()

    public val scaleY: Float get() = pressY.value * exp(-stretch()) * squashY()

    internal val glowRadius: Float get() = glowRadiusAnim.value

    internal val glowStrength: Float get() = glowStrengthAnim.value

    public fun press(position: Offset = Offset.Unspecified) {
        pressed = true
        val calm = LiquidGlassSettings.reduceMotion
        val grow = if (calm) CALM_PRESS_SCALE else 1f + PRESS_GROW * (1f - PRESS_SIZE_DAMP * sizeFactor())
        scope.launch { offsetAnim.stop() }
        scope.launch { pressX.animateTo(grow, if (calm) CALM_SPRING else spring(PRESS_X_DAMPING, PRESS_X_STIFFNESS)) }
        scope.launch { pressY.animateTo(grow, if (calm) CALM_SPRING else spring(PRESS_Y_DAMPING, PRESS_Y_STIFFNESS)) }
        val p = if (position.isSpecified) position else Offset(width * 0.5f, height * 0.5f)
        glowX = p.x
        glowY = p.y
        val start = min(width, height) * GLOW_START
        val end = max(width, height) * GLOW_END
        scope.launch {
            glowRadiusAnim.snapTo(start)
            glowRadiusAnim.animateTo(end, tween(if (calm) CALM_GLOW_MILLIS else GLOW_SPREAD_MILLIS, easing = FastOutSlowInEasing))
        }
        scope.launch { glowStrengthAnim.animateTo(1f, tween(GLOW_IN_MILLIS)) }
        wake()
    }

    public fun move(position: Offset, velocity: Velocity) {
        if (position.isSpecified) {
            glowX = position.x
            glowY = position.y
        }
        dragVelocity = velocity
        movedAt = System.nanoTime()
        wake()
    }

    public fun release() {
        pressed = false
        dragging = false
        dragVelocity = Velocity.Zero
        val calm = LiquidGlassSettings.reduceMotion
        scope.launch { pressX.animateTo(1f, if (calm) CALM_SPRING else spring(RELEASE_DAMPING, RELEASE_X_STIFFNESS)) }
        scope.launch { pressY.animateTo(1f, if (calm) CALM_SPRING else spring(RELEASE_DAMPING, RELEASE_Y_STIFFNESS)) }
        scope.launch { glowStrengthAnim.animateTo(0f, tween(if (calm) CALM_GLOW_MILLIS else GLOW_OUT_MILLIS)) }
        wake()
    }

    public suspend fun animateOffsetTo(target: Offset) {
        offsetAnim.animateTo(target, if (LiquidGlassSettings.reduceMotion) CALM_SPRING_OFFSET else spring(PULL_DAMPING, PULL_STIFFNESS))
    }

    public suspend fun snapOffsetTo(target: Offset) {
        offsetAnim.snapTo(target)
    }

    internal fun show(visible: Boolean) {
        if (visible == appearTarget && appearAnim.targetValue == (if (visible) 1f else 0f)) return
        appearTarget = visible
        val spec: AnimationSpec<Float> =
            if (LiquidGlassSettings.reduceMotion) tween(CALM_APPEAR_MILLIS, easing = LinearEasing) else spring(APPEAR_DAMPING, APPEAR_STIFFNESS)
        scope.launch { appearAnim.animateTo(if (visible) 1f else 0f, spec) }
    }

    internal fun sizeFactor(): Float = LiquidMaterial.sizeFactor(min(width, height) / density)

    internal fun displayedOffset(): Offset {
        val raw = offsetAnim.value
        val lo = minOffset
        val hi = maxOffset
        if (!lo.isSpecified || !hi.isSpecified) return raw
        return Offset(raw.x.coerceIn(lo.x, max(lo.x, hi.x)), raw.y.coerceIn(lo.y, max(lo.y, hi.y)))
    }

    internal fun contentShiftX(): Float = overflowShift(offsetAnim.value.x - displayedOffset().x, width)

    internal fun contentShiftY(): Float = overflowShift(offsetAnim.value.y - displayedOffset().y, height)

    internal fun shape(w: Float, h: Float, corners: FloatArray, capsule: Boolean, minScale: Float, out: FloatArray) {
        val a = minScale + (1f - minScale) * appearAnim.value
        val sw = w * scaleX * a
        val sh = h * scaleY * a
        val cx = w * 0.5f + contentShiftX()
        val cy = h * 0.5f + contentShiftY()
        out[0] = cx - sw * 0.5f
        out[1] = cy - sh * 0.5f
        out[2] = sw
        out[3] = sh
        val half = min(sw, sh) * 0.5f
        val k = min(sw / max(w, 1f), sh / max(h, 1f))
        for (i in 0 until PolyEffect.Liquid.CORNER_STRIDE) out[SHAPE_CORNERS + i] = if (capsule) half else min(corners[i] * k, half)
    }

    private fun stretch(): Float = if (LiquidGlassSettings.reduceMotion) 0f else stretchValue

    private fun squashX(): Float = squash(offsetAnim.value.x - displayedOffset().x, width) / squash(offsetAnim.value.y - displayedOffset().y, height)

    private fun squashY(): Float = squash(offsetAnim.value.y - displayedOffset().y, height) / squash(offsetAnim.value.x - displayedOffset().x, width)

    private fun squash(overflow: Float, size: Float): Float {
        if (overflow == 0f || size <= 0f || LiquidGlassSettings.reduceMotion) return 1f
        return 1f - min(abs(overflow) / size * SQUASH_GAIN, MAX_SQUASH)
    }

    private fun overflowShift(overflow: Float, size: Float): Float = sign(overflow) * size * (1f - squash(overflow, size)) * 0.5f

    private fun stretchFor(v: Velocity): Float {
        val speed = hypot(v.x, v.y)
        if (speed <= 0f) return 0f
        val amount = min(speed / density * STRETCH_PER_DP_PER_SECOND, MAX_STRETCH)
        return ln(1f + amount) * (v.x * v.x - v.y * v.y) / (speed * speed)
    }

    private fun wake() {
        if (physics?.isActive == true) return
        physics = scope.launch { runPhysics() }
    }

    private suspend fun runPhysics() {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / NANOS_PER_SECOND).coerceIn(0f, MAX_STEP)
            last = now
            val target = when {
                LiquidGlassSettings.reduceMotion -> 0f
                !pressed && offsetAnim.isRunning -> stretchFor(Velocity(offsetAnim.velocity.x, offsetAnim.velocity.y))
                System.nanoTime() - movedAt > STALE_NANOS -> 0f
                else -> stretchFor(dragVelocity)
            }
            var x = stretchValue
            var v = stretchVelocity
            val h = dt / SUBSTEPS
            repeat(SUBSTEPS) {
                v += (-STRETCH_STIFFNESS * (x - target) - STRETCH_DAMPING * v) * h
                x += v * h
            }
            stretchVelocity = v
            if (!pressed && !offsetAnim.isRunning && target == 0f && abs(x) < SETTLE && abs(v) < SETTLE) {
                stretchValue = 0f
                stretchVelocity = 0f
                return
            }
            stretchValue = x
        }
    }

    internal suspend fun gestures(input: PointerInputScope, draggable: Boolean) {
        density = input.density
        input.awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            var absolute = Offset.Zero
            var pull = Offset.Zero
            tracker.addPosition(down.uptimeMillis, absolute)
            press(down.position)
            val id = down.id
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == id } ?: break
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    if (delta == Offset.Zero) continue
                    absolute += delta
                    tracker.addPosition(change.uptimeMillis, absolute)
                    val velocity = tracker.calculateVelocity()
                    if (draggable) {
                        change.consume()
                        dragging = true
                        scope.launch { offsetAnim.snapTo(offsetAnim.value + resisted(delta)) }
                    } else {
                        pull += delta
                        val limit = MAX_PULL_DP * density
                        val target = Offset((pull.x * PULL_FACTOR).coerceIn(-limit, limit), (pull.y * PULL_FACTOR).coerceIn(-limit, limit))
                        scope.launch { offsetAnim.snapTo(target) }
                    }
                    move(change.position, if (draggable) velocity else Velocity(velocity.x * PULL_FACTOR, velocity.y * PULL_FACTOR))
                }
                val velocity = tracker.calculateVelocity()
                released = true
                release()
                if (draggable) fling(velocity) else scope.launch { animateOffsetTo(Offset.Zero) }
            } finally {
                if (!released) release()
            }
        }
    }

    private fun resisted(delta: Offset): Offset {
        val lo = minOffset
        val hi = maxOffset
        if (!lo.isSpecified || !hi.isSpecified) return delta
        val raw = offsetAnim.value
        val dx = if ((raw.x < lo.x && delta.x < 0f) || (raw.x > hi.x && delta.x > 0f)) delta.x * RUBBER else delta.x
        val dy = if ((raw.y < lo.y && delta.y < 0f) || (raw.y > hi.y && delta.y > 0f)) delta.y * RUBBER else delta.y
        return Offset(dx, dy)
    }

    private fun fling(velocity: Velocity) {
        val limit = MAX_FLING_DP * density
        val speed = hypot(velocity.x, velocity.y)
        val k = if (speed > limit) limit / speed else 1f
        val v = Offset(velocity.x * k, velocity.y * k)
        scope.launch {
            val start = offsetAnim.value
            val decay = exponentialDecay<Offset>(FLING_FRICTION)
            val target = decay.calculateTargetValue(Offset.VectorConverter, start, v)
            val lo = minOffset
            val hi = maxOffset
            if (!lo.isSpecified || !hi.isSpecified) {
                offsetAnim.animateDecay(v, decay)
                return@launch
            }
            val clamped = Offset(target.x.coerceIn(lo.x, max(lo.x, hi.x)), target.y.coerceIn(lo.y, max(lo.y, hi.y)))
            val inside = start.x in lo.x..max(lo.x, hi.x) && start.y in lo.y..max(lo.y, hi.y)
            if (inside && clamped == target) offsetAnim.animateDecay(v, decay)
            else {
                val spec = if (LiquidGlassSettings.reduceMotion) CALM_SPRING_OFFSET else spring(WALL_DAMPING, WALL_STIFFNESS)
                offsetAnim.animateTo(clamped, spec, v)
            }
        }
        wake()
    }

    private companion object {
        const val PRESS_GROW = 0.08f
        const val PRESS_SIZE_DAMP = 0.5f
        const val CALM_PRESS_SCALE = 1.02f
        const val PRESS_X_DAMPING = 0.55f
        const val PRESS_X_STIFFNESS = 700f
        const val PRESS_Y_DAMPING = 0.62f
        const val PRESS_Y_STIFFNESS = 480f
        const val RELEASE_DAMPING = 0.5f
        const val RELEASE_X_STIFFNESS = 380f
        const val RELEASE_Y_STIFFNESS = 300f
        const val PULL_DAMPING = 0.5f
        const val PULL_STIFFNESS = 380f
        const val PULL_FACTOR = 0.15f
        const val MAX_PULL_DP = 8f
        const val WALL_DAMPING = 0.6f
        const val WALL_STIFFNESS = 180f
        const val FLING_FRICTION = 1.3f
        const val MAX_FLING_DP = 5000f
        const val RUBBER = 0.35f
        const val SQUASH_GAIN = 0.8f
        const val MAX_SQUASH = 0.25f
        const val STRETCH_PER_DP_PER_SECOND = 1f / 6000f
        const val MAX_STRETCH = 0.25f
        const val STRETCH_STIFFNESS = 225f
        const val STRETCH_DAMPING = 15f
        const val SUBSTEPS = 2
        const val SETTLE = 1e-3f
        const val MAX_STEP = 1f / 20f
        const val NANOS_PER_SECOND = 1e9f
        const val STALE_NANOS = 60_000_000L
        const val GLOW_START = 0.35f
        const val GLOW_END = 1.25f
        const val GLOW_IN_MILLIS = 120
        const val GLOW_SPREAD_MILLIS = 280
        const val GLOW_OUT_MILLIS = 320
        const val CALM_GLOW_MILLIS = 150
        const val APPEAR_DAMPING = 0.82f
        const val APPEAR_STIFFNESS = 260f
        const val CALM_APPEAR_MILLIS = 180
        val CALM_SPRING = spring<Float>(1f, PRESS_X_STIFFNESS)
        val CALM_SPRING_OFFSET = spring<Offset>(1f, PULL_STIFFNESS)
        val LIGHT_CONTENT = Color(0xFF1C1C1E)
        val DARK_CONTENT = Color(0xFFF5F5F7)
    }
}

public fun Modifier.liquidGlassUnion(id: Any): Modifier = modifierLocalProvider(LiquidUnionKey) { id }

public fun Modifier.liquidGlass(
    state: LiquidGlassState,
    shape: Shape = CapsuleShape,
    variant: LiquidVariant = LiquidVariant.Regular,
    tint: Color = Color.Unspecified,
    draggable: Boolean = false,
    visible: Boolean = true,
    dim: Float = 0f,
): Modifier = composed {
    val group = LocalLiquidGlassGroup.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val member = remember(state) { LiquidMember(state) }
    member.update(variant, tint, dim, shape, draggable)
    if (group != null) {
        DisposableEffect(group, member) {
            group.add(member)
            onDispose { group.remove(member) }
        }
    } else {
        LaunchedEffect(state, variant) {
            state.adaptation.track({ min(state.width, state.height) / state.density }, { variant == LiquidVariant.Clear }, { state.appear > 0f })
        }
    }
    LaunchedEffect(state, visible) { state.show(visible) }
    val params = remember { LiquidParams() }
    var chain = Modifier
        .modifierLocalConsumer { member.unionId = LiquidUnionKey.current }
        .onPlaced { member.place(it, density, direction) }
        .absoluteOffset {
            val o = state.displayedOffset()
            IntOffset(o.x.roundToInt(), o.y.roundToInt())
        }
    if (state.interactive) chain = chain.pointerInput(state, draggable) { state.gestures(this, draggable) }
    chain = this.then(chain)
    if (group == null) chain = chain.graphicsLayer {}.drawBehind { member.drawAlone(this, params) }
    chain.graphicsLayer {
            scaleX = state.scaleX
            scaleY = state.scaleY
            translationX = state.contentShiftX()
            translationY = state.contentShiftY()
        }
}

private val CapsuleShape = RoundedCornerShape(percent = 50)

internal const val SHAPE_CORNERS = 4
internal const val SHAPE_FLOATS = SHAPE_CORNERS + PolyEffect.Liquid.CORNER_STRIDE

internal val LiquidUnionKey = modifierLocalOf<Any?> { null }

internal class LiquidMember(val state: LiquidGlassState) {
    var variant by mutableStateOf(LiquidVariant.Regular)
        private set
    var tint by mutableStateOf(Color.Unspecified)
        private set
    var dim by mutableFloatStateOf(0f)
        private set
    var unionId: Any? by mutableStateOf(null)
    var coords: LayoutCoordinates? = null
        private set
    val corners = FloatArray(PolyEffect.Liquid.CORNER_STRIDE)
    var capsule = true
        private set
    val version = mutableIntStateOf(0)
    private var shape: Shape = CapsuleShape
    private var draggable = false
    private val scratch = FloatArray(SHAPE_FLOATS)
    private var outlineSize = Size.Unspecified
    private var placedAt = Offset.Unspecified
    private var placedSize = Size.Unspecified
    private var placedMin = Offset.Unspecified
    private var placedMax = Offset.Unspecified

    fun update(variant: LiquidVariant, tint: Color, dim: Float, shape: Shape, draggable: Boolean) {
        this.variant = variant
        this.tint = tint
        this.dim = dim
        if (shape != this.shape) {
            this.shape = shape
            outlineSize = Size.Unspecified
        }
        this.draggable = draggable
    }

    fun place(c: LayoutCoordinates, density: Density, direction: LayoutDirection) {
        coords = c
        val w = c.size.width.toFloat()
        val h = c.size.height.toFloat()
        state.density = density.density
        state.width = w
        state.height = h
        val size = Size(w, h)
        if (size != outlineSize) {
            outlineSize = size
            LiquidMaterial.corners(shape.createOutline(size, direction, density), corners)
            val half = min(w, h) * 0.5f - 0.5f
            capsule = corners.all { it >= half }
        }
        val parent = c.parentLayoutCoordinates
        if (draggable && parent != null && parent.isAttached) {
            val base = parent.localPositionOf(c, Offset.Zero)
            state.minOffset = Offset(-base.x, -base.y)
            state.maxOffset = Offset(parent.size.width - w - base.x, parent.size.height - h - base.y)
        } else {
            state.minOffset = Offset.Unspecified
            state.maxOffset = Offset.Unspecified
        }
        val root = c.positionInRoot()
        if (root == placedAt && size == placedSize && state.minOffset == placedMin && state.maxOffset == placedMax) return
        placedAt = root
        placedSize = size
        placedMin = state.minOffset
        placedMax = state.maxOffset
        version.intValue++
    }

    fun shapeInto(out: FloatArray, minScale: Float) {
        state.shape(state.width, state.height, corners, capsule, minScale, out)
    }

    fun drawAlone(scope: DrawScope, params: LiquidParams) {
        version.intValue
        val appear = state.appear
        if (appear <= 0f) return
        params.clearShapes()
        shapeInto(scratch, STANDALONE_MIN_SCALE)
        params.addShape(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch[5], scratch[6], scratch[7])
        configure(params, scope.density, state.adaptation, state.sizeFactor(), appear)
        params.spacing = 0f
        params.glowX = state.glowX
        params.glowY = state.glowY
        params.glowRadius = state.glowRadius
        params.glowStrength = state.glowStrength
        params.draw(scope, Color.Unspecified, 1f)
    }

    fun configure(params: LiquidParams, density: Float, adaptation: LiquidAdaptation, sizeFactor: Float, appear: Float) {
        params.thickness = LiquidMaterial.THICKNESS_DP * density
        params.refraction = 1f
        params.dispersion = LiquidMaterial.DISPERSION
        params.specular = 1f + LiquidMaterial.SPECULAR_BOOST * state.glowStrength
        params.clear = variant == LiquidVariant.Clear
        params.tint = tint
        params.blur = Float.NaN
        params.dim = dim
        params.appear = appear
        params.style = adaptation.style.value
        params.shadow = adaptation.shadow.value
        params.sizeFactor = sizeFactor
        params.sampleId = adaptation.id
    }

    private companion object {
        const val STANDALONE_MIN_SCALE = 0.9f
    }
}

