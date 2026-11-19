package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import org.lwjgl.system.MemoryStack
import java.nio.ByteBuffer

internal fun interface EffectUniforms {
    fun buffer(data: FloatArray, count: Int): GpuBuffer
}

internal class OwnedUniforms : EffectUniforms {
    private val owned = ArrayList<GpuBuffer>()

    override fun buffer(data: FloatArray, count: Int): GpuBuffer = uniformBuffer(data, count).also { owned.add(it) }

    fun close() {
        for (b in owned) b.close()
        owned.clear()
    }
}

internal class UniformSlots : EffectUniforms {
    private val buffers = ArrayList<GpuBuffer>()
    private val contents = ArrayList<FloatArray>()
    private var next = 0

    override fun buffer(data: FloatArray, count: Int): GpuBuffer {
        val i = next++
        if (i < buffers.size) {
            val old = contents[i]
            val buf = buffers[i]
            if (!buf.isClosed && old.size == count) {
                if (sameFloats(old, data, count)) return buf
                if (write(buf, data, count)) {
                    data.copyInto(old, 0, 0, count)
                    return buf
                }
            }
            buf.close()
            buffers[i] = uniformBuffer(data, count, WRITABLE)
            contents[i] = data.copyOf(count)
            return buffers[i]
        }
        return uniformBuffer(data, count, WRITABLE).also { buffers.add(it); contents.add(data.copyOf(count)) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun write(buf: GpuBuffer, data: FloatArray, count: Int): Boolean = try {
        MemoryStack.stackPush().use { stack -> RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buf.slice(), floats(stack, data, count)) }
        true
    } catch (e: Exception) {
        false
    }

    fun rewind() {
        next = 0
    }

    fun release() {
        for (b in buffers) b.close()
        buffers.clear()
        contents.clear()
        next = 0
    }

    private fun sameFloats(a: FloatArray, b: FloatArray, n: Int): Boolean {
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }

    private companion object {
        const val WRITABLE = GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST
    }
}

private fun floats(stack: MemoryStack, data: FloatArray, count: Int): ByteBuffer {
    val bytes = stack.malloc(count * 4)
    for (i in 0 until count) bytes.putFloat(data[i])
    bytes.flip()
    return bytes
}

internal fun uniformBuffer(data: FloatArray, count: Int, usage: Int = GpuBuffer.USAGE_UNIFORM): GpuBuffer = MemoryStack.stackPush().use { stack ->
    RenderSystem.getDevice().createBuffer({ "PolyCompose effect uniforms" }, usage, floats(stack, data, count))
}
//?}
