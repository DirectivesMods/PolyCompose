package org.polyfrost.compose.host

import kotlin.math.abs

public object BackdropSample {
    private const val INTERVAL_NANOS = 250_000_000L
    private const val WANTED_NANOS = 1_000_000_000L
    private const val MAX_BACKOFF_STEPS = 3
    private const val TINT_HYSTERESIS = 10

    @Volatile
    private var requested = false

    @Volatile
    private var requestedAt = 0L

    @Volatile
    private var sampledAt = 0L

    public val columns: Int = 8

    public val rows: Int = 8

    @Volatile
    public var pixels: IntArray = IntArray(0)
        private set

    @Volatile
    public var version: Int = 0
        private set

    public var current: Region? = null

    public fun request() {
        requestedAt = System.nanoTime()
        requested = true
    }

    public fun due(): Boolean {
        if (!requested) return false
        val now = System.nanoTime()
        if (now - requestedAt > WANTED_NANOS) {
            requested = false
            return false
        }
        if (version != 0 && now - sampledAt < INTERVAL_NANOS) return false
        sampledAt = now
        return true
    }

    public fun publish(argb: IntArray) {
        pixels = argb
        version++
    }

    public class Region {
        @Volatile
        private var requestedAt = 0L

        @Volatile
        private var sampledAt = 0L

        @Volatile
        private var interval = INTERVAL_NANOS

        private var stable = 0
        private var lastRed = -1
        private var lastGreen = -1
        private var lastBlue = -1

        @Volatile
        public var pixels: IntArray = IntArray(0)
            private set

        @Volatile
        public var version: Int = 0
            private set

        @Volatile
        public var tintOnly: Boolean = false

        public fun request() {
            requestedAt = System.nanoTime()
        }

        public fun due(): Boolean {
            if (requestedAt == 0L) return false
            val now = System.nanoTime()
            if (now - requestedAt > WANTED_NANOS) return false
            if (version != 0 && now - sampledAt < interval) return false
            sampledAt = now
            return true
        }

        public fun publish(argb: IntArray) {
            if (tintOnly && version != 0 && unchanged(argb)) {
                if (stable < MAX_BACKOFF_STEPS) stable++
                interval = INTERVAL_NANOS shl stable
                return
            }
            stable = 0
            interval = INTERVAL_NANOS
            pixels = argb
            version++
        }

        private fun unchanged(argb: IntArray): Boolean {
            if (argb.isEmpty()) return false
            var r = 0L
            var g = 0L
            var b = 0L
            for (p in argb) {
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            val n = argb.size
            val red = (r / n).toInt()
            val green = (g / n).toInt()
            val blue = (b / n).toInt()
            val same = lastRed >= 0 && abs(red - lastRed) <= TINT_HYSTERESIS && abs(green - lastGreen) <= TINT_HYSTERESIS &&
                abs(blue - lastBlue) <= TINT_HYSTERESIS
            if (!same) {
                lastRed = red
                lastGreen = green
                lastBlue = blue
            }
            return same
        }
    }
}
