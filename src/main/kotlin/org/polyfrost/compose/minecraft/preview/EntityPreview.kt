package org.polyfrost.compose.minecraft.preview

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.pipeline.RenderTarget
import java.util.UUID
import net.minecraft.client.Minecraft

//? if > 1.8.9 {
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
//?} else {
/*import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.render.platform.GLX
import net.minecraft.client.render.platform.Lighting
import net.minecraft.entity.living.LivingEntity
import net.minecraft.util.math.MathHelper
import org.lwjgl.opengl.GL11
*///?}

//? if >= 26.3
import com.mojang.blaze3d.systems.RenderSystem
//? if > 1.8.9 && < 1.21.4
//import com.mojang.blaze3d.systems.RenderSystem

//? if >= 26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState
//?} elif >= 1.21.10
//import net.minecraft.client.renderer.state.CameraRenderState

//? if >= 1.21.10 {
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.world.phys.Vec3
//?}

//? if > 1.8.9 && < 1.21.10
//import net.minecraft.util.Mth

//? if >= 26.2
import net.minecraft.client.renderer.SubmitNodeStorage

//? if >= 26.3 {
import java.util.Optional
import java.util.OptionalDouble
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
//?}

private const val FULL_BRIGHT = 0xF000F0
private const val OFFSET_Y = 0.0625f
private const val HEIGHT_CATCHUP = 12f
private const val FIT_HEIGHT = 2.1f
private const val FIT_WIDTH = 1.1f
private const val NAME_TAG_HEIGHT = 0.6f
private const val MAX_ANCHOR_GAP_SECONDS = 0.5

private val MinPreviewWidth = 48.dp
private val MinPreviewHeight = 96.dp

@Composable
fun EntityPreview(
    entity: LivingEntity?,
    modifier: Modifier = Modifier,
    yaw: Float = 0f,
    pitch: Float = 0f,
    scale: Float = 1f,
    showNameTag: Boolean = false,
    headYaw: Float? = 0f,
    headPitch: Float? = 0f,
    followBodyYaw: Boolean = false,
    verticalAnchor: Float = 0.5f,
    live: Boolean = true,
    maxPx: Int = McContentDefaults.MAX_PX,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val spec = remember { EntityPreviewSpec() }
    SideEffect {
        spec.resolve = { entity }
        spec.update(yaw, pitch, scale, showNameTag, headYaw, headPitch, followBodyYaw, verticalAnchor)
    }
    val key = listOf(entity, yaw, pitch, scale, showNameTag, headYaw, headPitch, followBodyYaw, verticalAnchor)
    EntityPreviewContent(spec, key, modifier, live, maxPx, fallback)
}

@Composable
fun PlayerPreview(
    playerId: UUID?,
    modifier: Modifier = Modifier,
    yaw: Float = 0f,
    pitch: Float = 0f,
    scale: Float = 1f,
    showNameTag: Boolean = false,
    headYaw: Float? = 0f,
    headPitch: Float? = 0f,
    followBodyYaw: Boolean = false,
    verticalAnchor: Float = 0.5f,
    live: Boolean = true,
    maxPx: Int = McContentDefaults.MAX_PX,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val spec = remember { EntityPreviewSpec() }
    SideEffect {
        spec.resolve = { findPlayer(playerId) }
        spec.update(yaw, pitch, scale, showNameTag, headYaw, headPitch, followBodyYaw, verticalAnchor)
    }
    val key = listOf(playerId, yaw, pitch, scale, showNameTag, headYaw, headPitch, followBodyYaw, verticalAnchor)
    EntityPreviewContent(spec, key, modifier, live, maxPx, fallback)
}

@Composable
private fun EntityPreviewContent(
    spec: EntityPreviewSpec,
    key: Any,
    modifier: Modifier,
    live: Boolean,
    maxPx: Int,
    fallback: @Composable BoxScope.() -> Unit,
) {
    McContent(
        modifier = modifier.defaultMinSize(MinPreviewWidth, MinPreviewHeight),
        live = live,
        maxPx = maxPx,
        key = key,
        fallback = fallback,
    ) { target, _, _ ->
        val entity = spec.resolve()
        if (entity == null) {
            spec.resetAnchor()
            false
        } else {
            renderEntity(spec, target, entity, partialTick())
        }
    }
}

private class EntityPreviewSpec {
    var resolve: () -> LivingEntity? = { null }
    var yaw = 0f
    var pitch = 0f
    var scale = 1f
    var showNameTag = false
    var headYaw: Float? = 0f
    var headPitch: Float? = 0f
    var followBodyYaw = false
    var verticalAnchor = 0.5f

    private var anchorHeight = -1f
    private var lastAnchorNanos = 0L

    @Suppress("LongParameterList")
    fun update(yaw: Float, pitch: Float, scale: Float, showNameTag: Boolean, headYaw: Float?, headPitch: Float?, followBodyYaw: Boolean, verticalAnchor: Float) {
        this.yaw = yaw
        this.pitch = pitch
        this.scale = scale
        this.showNameTag = showNameTag
        this.headYaw = headYaw
        this.headPitch = headPitch
        this.followBodyYaw = followBodyYaw
        this.verticalAnchor = verticalAnchor
    }

    fun resetAnchor() {
        anchorHeight = -1f
        lastAnchorNanos = 0L
    }

    fun smoothAnchorHeight(target: Float): Float {
        val now = System.nanoTime()
        val previous = anchorHeight
        val elapsed = if (lastAnchorNanos == 0L) 0.0 else (now - lastAnchorNanos) / 1_000_000_000.0
        lastAnchorNanos = now
        if (previous <= 0f || elapsed <= 0.0 || elapsed > MAX_ANCHOR_GAP_SECONDS) {
            anchorHeight = target
            return target
        }
        val step = (1.0 - Math.exp(-HEIGHT_CATCHUP * elapsed)).toFloat()
        anchorHeight = previous + (target - previous) * step
        return anchorHeight
    }

    val nameTagHeight: Float
        get() = if (showNameTag) NAME_TAG_HEIGHT else 0f

    fun modelSize(width: Int, height: Int): Float = scale * minOf(height / (FIT_HEIGHT + nameTagHeight), width / FIT_WIDTH)
}

private fun findPlayer(id: UUID?): LivingEntity? {
    val client = Minecraft.getInstance()
    val level = client.level ?: return null
    if (id == null) return client.player
    //? if > 1.8.9 {
    return level.getPlayerByUUID(id)
    //?} else
    //return level.getPlayer(id)
}

private fun partialTick(): Float {
    //? if >= 1.21.4 {
    return Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
    //?} elif > 1.8.9 {
    /*return Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(false)
    *///?} else
    //return 1f
}

//? if >= 1.21.10 {
private fun renderEntity(spec: EntityPreviewSpec, rt: RenderTarget, entity: LivingEntity, partialTick: Float): Boolean {
    val client = Minecraft.getInstance()
    val width = rt.width
    val height = rt.height

    val state = PreviewNameTag.during(spec.showNameTag) {
        client.entityRenderDispatcher.extractEntity(entity, partialTick)
    } as? LivingEntityRenderState ?: return false

    state.lightCoords = FULL_BRIGHT
    if (!spec.showNameTag) state.nameTag = null
    val liveHeadYaw = state.yRot
    state.bodyRot = 180f + spec.yaw - (if (spec.followBodyYaw) liveHeadYaw else 0f)
    state.yRot = spec.headYaw ?: liveHeadYaw
    state.xRot = spec.headPitch ?: state.xRot
    val entityScale = state.scale.coerceAtLeast(0.0001f)
    val boxHeight = state.boundingBoxHeight / entityScale
    state.boundingBoxWidth /= entityScale
    state.boundingBoxHeight = boxHeight
    state.scale = 1f

    val anchor = spec.smoothAnchorHeight(boxHeight)
    val size = spec.modelSize(width, height)
    val tilt = Math.toRadians(spec.pitch.toDouble()).toFloat()
    val rotation = Quaternionf().rotateZ(Math.PI.toFloat())
    if (tilt != 0f) rotation.mul(Quaternionf().rotateX(tilt))

    val pose = PoseStack()
    pose.translate(width / 2f, height * spec.verticalAnchor, 0f)
    pose.scale(size, size, -size)
    pose.translate(0f, (anchor + spec.nameTagHeight) / 2f + OFFSET_Y, 0f)
    //? if >= 26.3 {
    pose.rotate(rotation)
    //? } else {
    /*pose.mulPose(rotation)
    *///? }

    //? if >= 26.2 {
    client.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI)
    //? } else {
    /*client.gameRenderer.lighting.setupFor(Lighting.Entry.ENTITY_IN_UI)
    *///? }

    val camera = CameraRenderState().apply {
        orientation = Quaternionf().rotateY(Math.PI.toFloat()).apply { if (tilt != 0f) mul(Quaternionf().rotateX(tilt)) }
        pos = Vec3.ZERO
        //? if < 26.1 {
        /*entityPos = Vec3.ZERO
        *///? }
    }

    //? if >= 26.3 {
    val colorView = rt.colorTextureView ?: return false
    val depthView = rt.depthTextureView ?: return false
    val storage = SubmitNodeStorage()
    client.entityRenderDispatcher.submit(state, camera, 0.0, 0.0, 0.0, pose, storage)
    client.gameRenderer.featureRenderDispatcher().prepareFrame(storage).use { frame ->
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "polycompose_entity_preview" },
            colorView,
            Optional.empty(),
            depthView,
            OptionalDouble.empty(),
        ).use { pass ->
            RenderSystem.bindDefaultUniforms(pass)
            FeatureRenderDispatcher.renderAllFeatures(pass, frame)
        }
    }
    //? } else if >= 26.2 {
    /*val storage = SubmitNodeStorage()
    client.entityRenderDispatcher.submit(state, camera, 0.0, 0.0, 0.0, pose, storage)
    client.gameRenderer.featureRenderDispatcher().renderAllFeatures(storage)
    *///? } else {
    /*val features = client.gameRenderer.featureRenderDispatcher
    client.entityRenderDispatcher.submit(state, camera, 0.0, 0.0, 0.0, pose, features.submitNodeStorage)
    features.renderAllFeatures()
    client.renderBuffers().bufferSource().endBatch()
    *///? }
    return true
}
//?} else if > 1.8.9 {
/*@Suppress("DEPRECATION")
private fun renderEntity(spec: EntityPreviewSpec, rt: RenderTarget, entity: LivingEntity, partialTick: Float): Boolean {
    val client = Minecraft.getInstance()
    val width = rt.width
    val height = rt.height
    val entityScale = entity.scale.coerceAtLeast(0.0001f)
    val liveBodyRot = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
    val liveHeadYaw = Mth.wrapDegrees(Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) - liveBodyRot)
    val bodyRot = 180f + spec.yaw - (if (spec.followBodyYaw) liveHeadYaw else 0f)
    val headRot = bodyRot + (spec.headYaw ?: liveHeadYaw)
    val headPitch = spec.headPitch ?: Mth.lerp(partialTick, entity.xRotO, entity.xRot)

    val anchor = spec.smoothAnchorHeight(entity.bbHeight / entityScale)
    val size = spec.modelSize(width, height)
    val modelScale = size / entityScale
    val tilt = Math.toRadians(spec.pitch.toDouble()).toFloat()
    val cameraRotation = Quaternionf().rotateX(tilt)
    val rotation = Quaternionf().rotateZ(Math.PI.toFloat()).mul(cameraRotation)

    val pose = PoseStack()
    pose.translate(width / 2f, height * spec.verticalAnchor, 0f)
    pose.scale(modelScale, modelScale, -modelScale)
    pose.translate(0f, entityScale * ((anchor + spec.nameTagHeight) / 2f + OFFSET_Y), 0f)
    pose.mulPose(rotation)

    val savedBodyRot = entity.yBodyRot
    val savedBodyRotO = entity.yBodyRotO
    val savedHeadRot = entity.yHeadRot
    val savedHeadRotO = entity.yHeadRotO
    val savedYRot = entity.yRot
    val savedXRot = entity.xRot
    val savedXRotO = entity.xRotO

    val dispatcher = client.entityRenderDispatcher
    val buffers = client.renderBuffers().bufferSource()

    //? if = 1.21.8
    val savedHitboxes = dispatcher.shouldRenderHitBoxes()

    try {
        PreviewNameTag.rendering = true
        PreviewNameTag.show = spec.showNameTag
        entity.yBodyRot = bodyRot
        entity.yBodyRotO = bodyRot
        entity.yHeadRot = headRot
        entity.yHeadRotO = headRot
        entity.yRot = headRot
        entity.xRot = headPitch
        entity.xRotO = headPitch
        //? if >= 1.21.8 {
        client.gameRenderer.lighting.setupFor(Lighting.Entry.ENTITY_IN_UI)
        //?} else
        //Lighting.setupForEntityInInventory()
        dispatcher.overrideCameraOrientation(cameraRotation.conjugate(Quaternionf()).rotateY(Math.PI.toFloat()))
        dispatcher.setRenderShadow(false)
        //? if = 1.21.8
        dispatcher.setRenderHitBoxes(false)

        //? if >= 1.21.4 {
        dispatcher.render(entity, 0.0, 0.0, 0.0, partialTick, pose, buffers, FULL_BRIGHT)
        //?} else
        //RenderSystem.runAsFancy { dispatcher.render(entity, 0.0, 0.0, 0.0, 0f, partialTick, pose, buffers, FULL_BRIGHT) }
        buffers.endBatch()
    } finally {
        dispatcher.setRenderShadow(true)
        //? if = 1.21.8
        dispatcher.setRenderHitBoxes(savedHitboxes)
        //? if < 1.21.8
        //Lighting.setupFor3DItems()
        entity.yBodyRot = savedBodyRot
        entity.yBodyRotO = savedBodyRotO
        entity.yHeadRot = savedHeadRot
        entity.yHeadRotO = savedHeadRotO
        entity.yRot = savedYRot
        entity.xRot = savedXRot
        entity.xRotO = savedXRotO
        PreviewNameTag.rendering = false
        PreviewNameTag.show = false
    }
    return true
}
*///?} else {
/*private fun renderEntity(spec: EntityPreviewSpec, rt: RenderTarget, entity: LivingEntity, partialTick: Float): Boolean {
    val client = Minecraft.getInstance()
    val width = rt.width
    val height = rt.height
    val liveBodyRot = entity.lastBodyYaw + partialTick * MathHelper.wrapDegrees(entity.bodyYaw - entity.lastBodyYaw)
    val liveHeadRot = entity.lastHeadYaw + partialTick * MathHelper.wrapDegrees(entity.headYaw - entity.lastHeadYaw)
    val liveHeadYaw = MathHelper.wrapDegrees(liveHeadRot - liveBodyRot)
    val bodyRot = spec.yaw - (if (spec.followBodyYaw) liveHeadYaw else 0f)
    val headRot = bodyRot + (spec.headYaw ?: liveHeadYaw)
    val headPitch = spec.headPitch ?: (entity.lastPitch + partialTick * (entity.pitch - entity.lastPitch))

    val anchor = spec.smoothAnchorHeight(entity.height)
    val size = spec.modelSize(width, height)

    val savedBodyRot = entity.bodyYaw
    val savedBodyRotO = entity.lastBodyYaw
    val savedHeadRot = entity.headYaw
    val savedHeadRotO = entity.lastHeadYaw
    val savedYRot = entity.yaw
    val savedYRotO = entity.lastYaw
    val savedXRot = entity.pitch
    val savedXRotO = entity.lastPitch

    val dispatcher = client.entityRenderDispatcher

    GL11.glEnable(GL11.GL_NORMALIZE)
    GlStateManager.enableColorMaterial()
    GlStateManager.pushMatrix()
    try {
        PreviewNameTag.rendering = true
        PreviewNameTag.show = spec.showNameTag
        entity.bodyYaw = bodyRot
        entity.lastBodyYaw = bodyRot
        entity.headYaw = headRot
        entity.lastHeadYaw = headRot
        entity.yaw = headRot
        entity.lastYaw = headRot
        entity.pitch = headPitch
        entity.lastPitch = headPitch
        GlStateManager.translatef(width / 2f, height * spec.verticalAnchor, 50f)
        GlStateManager.scalef(-size, size, size)
        GlStateManager.translatef(0f, (anchor + spec.nameTagHeight) / 2f + OFFSET_Y, 0f)
        GlStateManager.rotatef(180f, 0f, 0f, 1f)
        if (spec.pitch != 0f) GlStateManager.rotatef(spec.pitch, 1f, 0f, 0f)
        GlStateManager.rotatef(135f, 0f, 1f, 0f)
        Lighting.turnOn()
        GlStateManager.rotatef(-135f, 0f, 1f, 0f)
        dispatcher.setCameraYaw(180f)
        dispatcher.setRenderShadow(false)
        dispatcher.render(entity, 0.0, 0.0, 0.0, 0f, partialTick, true)
    } finally {
        dispatcher.setRenderShadow(true)
        GlStateManager.popMatrix()
        Lighting.turnOff()
        GlStateManager.disableRescaleNormal()
        GlStateManager.activeTexture(GLX.GL_TEXTURE1)
        GlStateManager.disableTexture()
        GlStateManager.activeTexture(GLX.GL_TEXTURE0)
        GL11.glDisable(GL11.GL_NORMALIZE)
        entity.bodyYaw = savedBodyRot
        entity.lastBodyYaw = savedBodyRotO
        entity.headYaw = savedHeadRot
        entity.lastHeadYaw = savedHeadRotO
        entity.yaw = savedYRot
        entity.lastYaw = savedYRotO
        entity.pitch = savedXRot
        entity.lastPitch = savedXRotO
        PreviewNameTag.rendering = false
        PreviewNameTag.show = false
    }
    return true
}
*///?}
