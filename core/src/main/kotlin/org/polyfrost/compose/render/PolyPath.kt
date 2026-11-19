package org.polyfrost.compose.render

public class PolyPath {
    public var evenOdd: Boolean = false
        set(value) {
            if (field != value) hashed = false
            field = value
        }

    private var ops = IntArray(16)
    private var args = FloatArray(64)
    private var opCount = 0
    private var argCount = 0
    private var hash = 0
    private var hashed = false

    public fun moveTo(x: Float, y: Float): Unit = point(MOVE, x, y)
    public fun lineTo(x: Float, y: Float): Unit = point(LINE, x, y)

    public fun tangentArcTo(x1: Float, y1: Float, x2: Float, y2: Float, radius: Float) {
        op(ARC, 5)
        args[argCount++] = x1; args[argCount++] = y1; args[argCount++] = x2; args[argCount++] = y2; args[argCount++] = radius
    }

    public fun close(): Unit = op(CLOSE, 0)

    public interface Visitor {
        public fun moveTo(x: Float, y: Float)
        public fun lineTo(x: Float, y: Float)
        public fun tangentArcTo(x1: Float, y1: Float, x2: Float, y2: Float, radius: Float)
        public fun close()
    }

    public fun visit(v: Visitor) {
        var a = 0
        for (i in 0 until opCount) {
            when (ops[i]) {
                MOVE -> { v.moveTo(args[a], args[a + 1]); a += 2 }
                LINE -> { v.lineTo(args[a], args[a + 1]); a += 2 }
                ARC -> { v.tangentArcTo(args[a], args[a + 1], args[a + 2], args[a + 3], args[a + 4]); a += 5 }
                CLOSE -> v.close()
            }
        }
    }

    internal fun contentHash(): Int {
        if (hashed) return hash
        var h = if (evenOdd) 1 else 0
        for (i in 0 until opCount) h = h * 31 + ops[i]
        for (i in 0 until argCount) h = h * 31 + args[i].toRawBits()
        hash = h
        hashed = true
        return h
    }

    internal fun contentEquals(other: PolyPath): Boolean {
        if (other === this) return true
        if (evenOdd != other.evenOdd || opCount != other.opCount || argCount != other.argCount) return false
        for (i in 0 until opCount) if (ops[i] != other.ops[i]) return false
        for (i in 0 until argCount) if (args[i].toRawBits() != other.args[i].toRawBits()) return false
        return true
    }

    internal fun snapshot(): PolyPath {
        val p = PolyPath()
        p.evenOdd = evenOdd
        p.ops = ops.copyOf(opCount.coerceAtLeast(1))
        p.args = args.copyOf(argCount)
        p.opCount = opCount
        p.argCount = argCount
        return p
    }

    private fun point(code: Int, x: Float, y: Float) {
        op(code, 2)
        args[argCount++] = x; args[argCount++] = y
    }

    private fun op(op: Int, argSize: Int) {
        if (opCount == ops.size) ops = ops.copyOf(ops.size * 2)
        if (argCount + argSize > args.size) args = args.copyOf(maxOf(args.size * 2, argCount + argSize))
        ops[opCount++] = op
        hashed = false
    }

    private companion object {
        const val MOVE = 0
        const val LINE = 1
        const val ARC = 2
        const val CLOSE = 3
    }
}
