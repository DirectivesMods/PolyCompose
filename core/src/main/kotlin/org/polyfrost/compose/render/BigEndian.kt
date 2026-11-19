package org.polyfrost.compose.render

import java.nio.ByteBuffer

internal fun ByteBuffer.u8(o: Int): Int = get(o).toInt() and 0xFF

internal fun ByteBuffer.u16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)

internal fun ByteBuffer.s16(o: Int): Int = u16(o).toShort().toInt()

internal fun ByteBuffer.u32(o: Int): Int = (u16(o) shl 16) or u16(o + 2)
