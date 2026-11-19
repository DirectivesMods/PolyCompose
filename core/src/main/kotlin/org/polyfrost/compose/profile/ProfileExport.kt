package org.polyfrost.compose.profile

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.CharBuffer
import java.time.Instant
import java.util.Locale

public object ProfileExport {
    private const val PID_CPU = 1
    private const val PID_GPU = 2
    private const val TID_FRAMES = 1
    private const val TID_HOST = 100
    private const val TID_SERIES = 1000
    private const val NO_NUMBER = Long.MIN_VALUE
    private const val EFFECT_GPU_TIMING_KEY = ProfileEnvironment.EFFECT_GPU_TIMING_KEY

    private val CPU_PHASES = arrayOf(ProfilePhase.Compose, ProfilePhase.Layout, ProfilePhase.Record, ProfilePhase.Upload)
    private val ALL_PHASES = ProfilePhase.entries.toTypedArray()
    private val PHASE_NAMES = Array(ALL_PHASES.size) { ALL_PHASES[it].name.lowercase(Locale.ROOT) }

    @JvmStatic
    public fun chromeTrace(capture: ProfileCapture, out: OutputStream) {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        chromeTrace(capture, writer)
        writer.flush()
    }

    @JvmStatic
    public fun summaryText(capture: ProfileCapture, out: OutputStream) {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        summaryText(capture, writer)
        writer.flush()
    }

    @JvmStatic
    public fun summaryJson(capture: ProfileCapture, out: OutputStream) {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        summaryJson(capture, writer)
        writer.flush()
    }

    @JvmStatic
    public fun chromeTrace(capture: ProfileCapture, out: Appendable) {
        val sink = Sink(out)
        writeTrace(capture, sink)
        sink.flush()
    }

    @JvmStatic
    public fun summaryText(capture: ProfileCapture, out: Appendable) {
        val sink = Sink(out)
        writeSummaryText(capture, sink)
        sink.flush()
    }

    @JvmStatic
    public fun summaryJson(capture: ProfileCapture, out: Appendable) {
        val sink = Sink(out)
        writeSummaryJson(capture, sink)
        sink.flush()
    }

    private fun writeTrace(capture: ProfileCapture, out: Sink) {
        val hosts = capture.hosts
        val series = capture.seriesKeys
        val base = capture.frames.firstOrNull()?.let { minOf(it.startNanos, it.endNanos - it.cpuNanos) } ?: 0L
        val w = TraceWriter(out)
        out.str("{\"traceEvents\":[\n")
        w.meta("process_name", PID_CPU, 0, "name", "PolyCompose CPU")
        w.meta("process_sort_index", PID_CPU, 0, "sort_index", PID_CPU)
        w.meta("process_name", PID_GPU, 0, "name", "PolyCompose GPU")
        w.meta("process_sort_index", PID_GPU, 0, "sort_index", PID_GPU)
        w.meta("thread_name", PID_CPU, TID_FRAMES, "name", "Frames")
        w.meta("thread_sort_index", PID_CPU, TID_FRAMES, "sort_index", TID_FRAMES)
        for (i in hosts.indices) {
            val key = hosts[i]
            w.meta("thread_name", PID_CPU, TID_HOST + i, "name", key)
            w.meta("thread_sort_index", PID_CPU, TID_HOST + i, "sort_index", TID_HOST + i)
            w.meta("thread_name", PID_GPU, TID_HOST + i, "name", key)
            w.meta("thread_sort_index", PID_GPU, TID_HOST + i, "sort_index", TID_HOST + i)
        }
        for (i in series.indices) {
            val key = series[i]
            w.meta("thread_name", PID_GPU, TID_SERIES + i, "name", key)
            w.meta("thread_sort_index", PID_GPU, TID_SERIES + i, "sort_index", TID_SERIES + i)
        }
        var previousEnd = 0L
        val frames = capture.frames
        for (i in frames.indices) {
            val frame = frames[i]
            frameEvents(w, out, frame, base, previousEnd, capture)
            previousEnd = frame.endNanos - base
        }
        out.str("\n],\"displayTimeUnit\":\"ms\",\"otherData\":{")
        var first = true
        for ((k, v) in otherData(capture)) {
            if (!first) out.ch(',')
            first = false
            out.json(k)
            out.ch(':')
            out.json(v)
        }
        out.str("}}\n")
    }

    private fun otherData(capture: ProfileCapture): Map<String, String> {
        val data = LinkedHashMap<String, String>()
        data["label"] = capture.label
        data["kind"] = capture.kind.name
        data["startedAt"] = Instant.ofEpochMilli(capture.startedAtEpochMillis).toString()
        data["frames"] = capture.frames.size.toString()
        data["gpuTiming"] = capture.gpuTiming.name
        data["hostGpuTiming"] = capture.gpuTiming.name
        data["effectGpuTiming"] = capture.environment[EFFECT_GPU_TIMING_KEY] ?: "n/a"
        data["recompositionsTracked"] = capture.recompositionsTracked.toString()
        data["layout"] = "phase slices are exclusive durations laid out back to back from the frame start"
        for ((k, v) in capture.environment) data["env." + k] = v
        return data
    }

    private fun frameEvents(w: TraceWriter, out: Sink, frame: FrameRecord, base: Long, previousEnd: Long, capture: ProfileCapture) {
        val end = frame.endNanos - base
        val start = maxOf(frame.startNanos - base, previousEnd)
        w.complete("Frame ", null, frame.index, "frame", PID_CPU, TID_FRAMES, start, end - start)
        w.argsOpen()
        w.ms("frameMs", frame.frameNanos)
        w.ms("cpuMs", frame.cpuNanos)
        w.ms("gpuMs", frame.gpuNanos)
        for (c in COUNTERS) w.arg(c.label, frame.counter(c))
        if (frame.unattributedRecompositions > 0) w.arg("unattributedRecompositions", frame.unattributedRecompositions.toLong())
        val recomposers = frame.recomposers
        for (i in recomposers.indices) {
            val r = recomposers[i]
            w.arg("recompose: ", r.name, " @ ", r.host, r.count.toLong())
        }
        w.argsClose()
        w.counter("frame time (ms)", null, PID_CPU, start)
        w.argsOpen()
        w.ms("frame", frame.frameNanos.coerceAtLeast(0L))
        w.ms("cpu", frame.cpuNanos)
        w.ms("gpu", frame.gpuNanos)
        w.argsClose()
        val hosts = frame.hosts
        for (i in hosts.indices) {
            val host = hosts[i]
            hostEvents(w, host, capture.hosts.indexOf(host.host), end - host.cpuNanos)
        }
        val series = frame.series
        for (i in series.indices) {
            val s = series[i]
            seriesEvents(w, s, capture.seriesKeys.indexOf(s.series), start)
        }
        if (frame.spike) {
            val spike = capture.spikes.firstOrNull { it.frame == frame.index }
            w.instant("Spike", PID_CPU, TID_FRAMES, start)
            w.argsOpen()
            if (spike != null) {
                w.arg("reason", spike.reason.name)
                w.ms("hostMs", spike.hostNanos)
                w.ms("frameMs", spike.frameNanos)
                w.arg("culpritHost", spike.culprit.host ?: "")
                w.arg("culpritPhase", spike.culprit.phase?.name ?: "")
                w.arg("culpritGpu", spike.culprit.gpuCategory ?: "")
            }
            w.argsClose()
        }
        if (out.pending() > FLUSH_THRESHOLD) out.drain()
    }

    private fun hostEvents(w: TraceWriter, host: HostFrame, index: Int, start: Long) {
        val tid = TID_HOST + index
        w.complete(host.host, null, NO_NUMBER, "host", PID_CPU, tid, start, host.cpuNanos)
        w.argsOpen()
        for (c in COUNTERS) w.arg(c.label, host.counter(c))
        w.argsClose()
        var at = start
        for (phase in CPU_PHASES) {
            val dur = host.phase(phase)
            if (dur <= 0L) continue
            w.complete(PHASE_NAMES[phase.ordinal], null, NO_NUMBER, "phase", PID_CPU, tid, at, dur)
            w.noArgs()
            at += dur
        }
        if (host.gpuNanos >= 0L) {
            w.complete("gpu", null, NO_NUMBER, "gpu", PID_GPU, tid, start, host.gpuNanos)
            w.noArgs()
        }
        w.counter(host.host, " counters", PID_CPU, start)
        w.argsOpen()
        for (c in COUNTERS) w.arg(c.label, host.counter(c))
        w.argsClose()
    }

    private fun seriesEvents(w: TraceWriter, s: SeriesFrame, index: Int, start: Long) {
        val tid = TID_SERIES + index
        var at = start
        val timingNames = s.timingNames
        for (i in timingNames.indices) {
            val dur = s.timing(i)
            if (dur <= 0L) continue
            w.complete(timingNames[i], null, NO_NUMBER, "gpu", PID_GPU, tid, at, dur)
            w.noArgs()
            at += dur
        }
        val countNames = s.countNames
        if (countNames.isEmpty()) return
        w.counter(s.series, " counts", PID_GPU, start)
        w.argsOpen()
        for (i in countNames.indices) w.arg(countNames[i], s.count(i))
        w.argsClose()
    }

    private fun writeSummaryText(capture: ProfileCapture, out: Sink) {
        val stats = SummaryStats(capture)
        out.str("PolyCompose profile: ")
        out.str(capture.label)
        out.str(" (")
        out.str(capture.kind.name)
        out.str(")\nStarted: ")
        out.str(Instant.ofEpochMilli(capture.startedAtEpochMillis).toString())
        out.str(", frames: ")
        out.long(capture.frames.size.toLong())
        out.str(", duration: ")
        out.millis(capture.durationNanos)
        out.str(" ms\nGPU timing: host ")
        out.str(capture.gpuTiming.name.lowercase(Locale.ROOT))
        capture.environment[EFFECT_GPU_TIMING_KEY]?.let {
            out.str(", effects ")
            out.str(it)
        }
        out.str(", recompositions tracked: ")
        out.str(capture.recompositionsTracked.toString())
        out.ch('\n')
        for ((k, v) in capture.environment) {
            out.str("  ")
            out.str(k)
            out.str(" = ")
            out.str(v)
            out.ch('\n')
        }
        out.str("\nFrame time: ")
        statsText(out, stats.frame)
        out.str("\nCPU (all hosts): ")
        statsText(out, stats.cpu)
        out.str("\nGPU (all): ")
        statsText(out, stats.gpu)
        out.str("\n\nHosts\n")
        for (host in stats.hosts) {
            out.str("  ")
            out.str(host.key)
            out.str(": ")
            out.long(host.frames.toLong())
            out.str(" frames, total ")
            statsText(out, host.total)
            out.ch('\n')
            for (phase in ALL_PHASES) {
                val p = host.phases[phase.ordinal]
                if (p.samples == 0) continue
                out.str("    ")
                out.str(PHASE_NAMES[phase.ordinal])
                out.str(": ")
                statsText(out, p)
                out.ch('\n')
            }
            out.str("    counters (avg/max per frame):")
            for (c in COUNTERS) {
                out.ch(' ')
                out.str(c.label)
                out.ch(' ')
                out.tenths(host.counterSums[c.ordinal], host.frames)
                out.ch('/')
                out.long(host.counterMax[c.ordinal])
            }
            out.ch('\n')
        }
        if (stats.series.isNotEmpty()) {
            out.str("\nGPU categories\n")
            for ((name, s) in stats.series) {
                out.str("  ")
                out.str(name)
                out.str(": ")
                statsText(out, s)
                out.ch('\n')
            }
        }
        if (stats.recomposers.isNotEmpty()) {
            out.str("\nTop recomposers (total, frames, max per frame)\n")
            for (r in stats.recomposers) {
                out.str("  ")
                out.str(r.name)
                out.str(" @ ")
                out.str(r.host)
                out.str(": ")
                out.long(r.total)
                out.str(", ")
                out.long(r.frames.toLong())
                out.str(", ")
                out.long(r.max.toLong())
                out.ch('\n')
            }
        }
        if (capture.spikes.isNotEmpty()) {
            out.str("\nSpikes\n")
            for (s in capture.spikes) {
                out.str("  frame ")
                out.long(s.frame)
                out.str(" at ")
                out.str(Instant.ofEpochMilli(s.epochMillis).toString())
                out.str(": ")
                out.str(s.reason.name)
                out.str(", hosts ")
                out.millis(s.hostNanos)
                out.str(" ms, frame ")
                out.millis(s.frameNanos)
                out.str(" ms, median ")
                out.millis(s.medianFrameNanos)
                out.str(" ms; culprit ")
                culpritText(out, s.culprit)
                out.ch('\n')
            }
        }
    }

    private fun writeSummaryJson(capture: ProfileCapture, out: Sink) {
        val stats = SummaryStats(capture)
        out.str("{\"label\":")
        out.json(capture.label)
        out.str(",\"kind\":")
        out.json(capture.kind.name)
        out.str(",\"startedAt\":")
        out.json(Instant.ofEpochMilli(capture.startedAtEpochMillis).toString())
        out.str(",\"frames\":")
        out.long(capture.frames.size.toLong())
        out.str(",\"durationMs\":")
        out.millis(capture.durationNanos)
        out.str(",\"gpuTiming\":")
        out.json(capture.gpuTiming.name)
        out.str(",\"hostGpuTiming\":")
        out.json(capture.gpuTiming.name)
        out.str(",\"effectGpuTiming\":")
        out.json(capture.environment[EFFECT_GPU_TIMING_KEY] ?: "n/a")
        out.str(",\"recompositionsTracked\":")
        out.str(capture.recompositionsTracked.toString())
        out.str(",\"environment\":{")
        var first = true
        for ((k, v) in capture.environment) {
            if (!first) out.ch(',')
            first = false
            out.json(k)
            out.ch(':')
            out.json(v)
        }
        out.str("},\"frameTime\":")
        statsJson(out, stats.frame)
        out.str(",\"cpu\":")
        statsJson(out, stats.cpu)
        out.str(",\"gpu\":")
        statsJson(out, stats.gpu)
        out.str(",\"hosts\":[")
        for ((i, host) in stats.hosts.withIndex()) {
            if (i > 0) out.ch(',')
            out.str("{\"key\":")
            out.json(host.key)
            out.str(",\"frames\":")
            out.long(host.frames.toLong())
            out.str(",\"total\":")
            statsJson(out, host.total)
            for (phase in ALL_PHASES) {
                out.str(",\"")
                out.str(PHASE_NAMES[phase.ordinal])
                out.str("\":")
                statsJson(out, host.phases[phase.ordinal])
            }
            out.str(",\"counters\":{")
            for (c in COUNTERS) {
                if (c.ordinal > 0) out.ch(',')
                out.ch('"')
                out.str(c.label)
                out.str("\":{\"avg\":")
                out.tenths(host.counterSums[c.ordinal], host.frames)
                out.str(",\"max\":")
                out.long(host.counterMax[c.ordinal])
                out.ch('}')
            }
            out.str("}}")
        }
        out.str("],\"gpuCategories\":{")
        first = true
        for ((name, s) in stats.series) {
            if (!first) out.ch(',')
            first = false
            out.json(name)
            out.ch(':')
            statsJson(out, s)
        }
        out.str("},\"recomposers\":[")
        for ((i, r) in stats.recomposers.withIndex()) {
            if (i > 0) out.ch(',')
            out.str("{\"name\":")
            out.json(r.name)
            out.str(",\"host\":")
            out.json(r.host)
            out.str(",\"total\":")
            out.long(r.total)
            out.str(",\"frames\":")
            out.long(r.frames.toLong())
            out.str(",\"max\":")
            out.long(r.max.toLong())
            out.ch('}')
        }
        out.str("],\"spikes\":[")
        for ((i, s) in capture.spikes.withIndex()) {
            if (i > 0) out.ch(',')
            out.str("{\"frame\":")
            out.long(s.frame)
            out.str(",\"at\":")
            out.json(Instant.ofEpochMilli(s.epochMillis).toString())
            out.str(",\"reason\":")
            out.json(s.reason.name)
            out.str(",\"hostMs\":")
            out.millis(s.hostNanos)
            out.str(",\"frameMs\":")
            out.millis(s.frameNanos)
            out.str(",\"medianMs\":")
            out.millis(s.medianFrameNanos)
            out.str(",\"culprit\":")
            out.json(culpritString(s.culprit))
            out.ch('}')
        }
        out.str("]}\n")
    }

    private fun culpritString(c: SpikeCulprit): String {
        val text = StringBuilder()
        val sink = Sink(text, SMALL_BUFFER_CHARS)
        culpritText(sink, c)
        sink.flush()
        return text.toString()
    }

    private fun culpritText(out: Sink, c: SpikeCulprit) {
        out.str(c.host ?: "unknown host")
        val phase = c.phase
        if (phase != null) {
            out.ch(' ')
            out.str(PHASE_NAMES[phase.ordinal])
            out.ch(' ')
            out.millis(c.phaseNanos)
            out.str(" ms")
        }
        if (c.gpuCategory != null) {
            out.str(", gpu ")
            out.str(c.gpuCategory)
            out.ch(' ')
            out.millis(c.gpuCategoryNanos)
            out.str(" ms")
        }
        if (c.recomposers.isNotEmpty()) {
            out.str(", recomposed ")
            for ((i, r) in c.recomposers.withIndex()) {
                if (i > 0) out.str(", ")
                out.str(r.name)
                out.str(" x")
                out.long(r.count.toLong())
            }
        }
    }

    private fun statsText(out: Sink, s: PhaseStats) {
        if (s.samples == 0) {
            out.str("n/a")
            return
        }
        out.str("p50 ")
        out.millis(s.p50Nanos)
        out.str(" p95 ")
        out.millis(s.p95Nanos)
        out.str(" p99 ")
        out.millis(s.p99Nanos)
        out.str(" max ")
        out.millis(s.maxNanos)
        out.str(" ms")
    }

    private fun statsJson(out: Sink, s: PhaseStats) {
        out.str("{\"samples\":")
        out.long(s.samples.toLong())
        out.str(",\"p50Ms\":")
        out.millis(s.p50Nanos)
        out.str(",\"p95Ms\":")
        out.millis(s.p95Nanos)
        out.str(",\"p99Ms\":")
        out.millis(s.p99Nanos)
        out.str(",\"maxMs\":")
        out.millis(s.maxNanos)
        out.ch('}')
    }

    private const val FLUSH_THRESHOLD = 16_384

    private class Sink(private val target: Appendable, size: Int = BUFFER_CHARS) {
        private val buf = CharArray(size)
        private var n = 0

        fun pending(): Int = n

        fun drain() {
            if (n == 0) return
            when (target) {
                is Writer -> target.write(buf, 0, n)
                is StringBuilder -> target.append(buf, 0, n)
                else -> target.append(CharBuffer.wrap(buf, 0, n))
            }
            n = 0
        }

        fun flush() {
            drain()
        }

        private fun reserve(count: Int) {
            if (buf.size - n < count) drain()
        }

        fun ch(c: Char) {
            if (n == buf.size) drain()
            buf[n++] = c
        }

        fun str(s: String) {
            var from = 0
            val len = s.length
            while (from < len) {
                if (n == buf.size) drain()
                val take = minOf(len - from, buf.size - n)
                s.toCharArray(buf, n, from, from + take)
                n += take
                from += take
            }
        }

        fun long(v: Long) {
            reserve(MAX_LONG_CHARS)
            if (v < 0L) {
                if (v == Long.MIN_VALUE) {
                    str("-9223372036854775808")
                    return
                }
                buf[n++] = '-'
                digits(-v, 1)
            } else {
                digits(v, 1)
            }
        }

        fun millis(nanos: Long) {
            reserve(MAX_FIXED_CHARS)
            if (nanos < 0L) buf[n++] = '-'
            val abs = if (nanos < 0L) (-nanos).coerceAtLeast(0L) else nanos
            val micros = abs / NANOS_PER_MICRO + if (abs % NANOS_PER_MICRO >= HALF_MICRO) 1L else 0L
            digits(micros / MICROS_PER_MILLI, 1)
            buf[n++] = '.'
            digits(micros % MICROS_PER_MILLI, 3)
        }

        fun micros(nanos: Long) {
            reserve(MAX_FIXED_CHARS)
            if (nanos < 0L) buf[n++] = '-'
            val abs = if (nanos < 0L) (-nanos).coerceAtLeast(0L) else nanos
            digits(abs / NANOS_PER_MICRO, 1)
            buf[n++] = '.'
            digits(abs % NANOS_PER_MICRO, 3)
        }

        fun tenths(sum: Long, count: Int) {
            if (count == 0) {
                ch('0')
                return
            }
            reserve(MAX_FIXED_CHARS)
            val t = (sum * 20L + count) / (2L * count)
            digits(t / 10L, 1)
            buf[n++] = '.'
            digits(t % 10L, 1)
        }

        fun json(s: String) {
            ch('"')
            jsonRaw(s)
            ch('"')
        }

        fun jsonRaw(s: String) {
            for (i in 0 until s.length) {
                val c = s[i]
                when {
                    c == '"' -> pair('"')
                    c == '\\' -> pair('\\')
                    c == '\n' -> pair('n')
                    c == '\r' -> pair('r')
                    c == '\t' -> pair('t')
                    c < ' ' -> unicode(c)
                    else -> ch(c)
                }
            }
        }

        private fun pair(c: Char) {
            reserve(2)
            buf[n++] = '\\'
            buf[n++] = c
        }

        private fun unicode(c: Char) {
            reserve(UNICODE_CHARS)
            buf[n++] = '\\'
            buf[n++] = 'u'
            val code = c.code
            buf[n++] = HEX[(code shr 12) and 0xF]
            buf[n++] = HEX[(code shr 8) and 0xF]
            buf[n++] = HEX[(code shr 4) and 0xF]
            buf[n++] = HEX[code and 0xF]
        }

        private fun digits(value: Long, minWidth: Int) {
            var len = 1
            var t = value
            while (t >= 10L) {
                t /= 10L
                len++
            }
            val width = maxOf(len, minWidth)
            var pos = n + width
            n = pos
            var y = value
            for (i in 0 until width) {
                buf[--pos] = '0' + (y % 10L).toInt()
                y /= 10L
            }
        }
    }

    private const val BUFFER_CHARS = 32_768
    private const val SMALL_BUFFER_CHARS = 256
    private const val MAX_LONG_CHARS = 24
    private const val MAX_FIXED_CHARS = 48
    private const val UNICODE_CHARS = 6
    private const val NANOS_PER_MICRO = 1000L
    private const val HALF_MICRO = 500L
    private const val MICROS_PER_MILLI = 1000L
    private val HEX = "0123456789abcdef".toCharArray()

    private class TraceWriter(private val out: Sink) {
        private var first = true
        private var args = 0

        fun meta(name: String, pid: Int, tid: Int, key: String, value: String) {
            open(name, null, NO_NUMBER, "M", pid, tid)
            out.str(",\"args\":{")
            out.json(key)
            out.ch(':')
            out.json(value)
            out.str("}}")
        }

        fun meta(name: String, pid: Int, tid: Int, key: String, value: Int) {
            open(name, null, NO_NUMBER, "M", pid, tid)
            out.str(",\"args\":{")
            out.json(key)
            out.ch(':')
            out.long(value.toLong())
            out.str("}}")
        }

        fun complete(name: String, suffix: String?, number: Long, cat: String, pid: Int, tid: Int, startNanos: Long, durNanos: Long) {
            open(name, suffix, number, "X", pid, tid)
            out.str(",\"cat\":")
            out.json(cat)
            out.str(",\"ts\":")
            out.micros(startNanos)
            out.str(",\"dur\":")
            out.micros(durNanos.coerceAtLeast(0L))
        }

        fun counter(name: String, suffix: String?, pid: Int, startNanos: Long) {
            open(name, suffix, NO_NUMBER, "C", pid, 0)
            out.str(",\"ts\":")
            out.micros(startNanos)
        }

        fun instant(name: String, pid: Int, tid: Int, startNanos: Long) {
            open(name, null, NO_NUMBER, "i", pid, tid)
            out.str(",\"s\":\"g\",\"ts\":")
            out.micros(startNanos)
        }

        fun argsOpen() {
            out.str(",\"args\":{")
            args = 0
        }

        fun argsClose() {
            out.str("}}")
        }

        fun noArgs() {
            out.ch('}')
        }

        fun arg(key: String, value: Long) {
            if (args++ > 0) out.ch(',')
            out.json(key)
            out.ch(':')
            out.long(value)
        }

        fun arg(a: String, b: String, c: String, d: String, value: Long) {
            if (args++ > 0) out.ch(',')
            out.ch('"')
            out.jsonRaw(a)
            out.jsonRaw(b)
            out.jsonRaw(c)
            out.jsonRaw(d)
            out.str("\":")
            out.long(value)
        }

        fun arg(key: String, value: String) {
            if (args++ > 0) out.ch(',')
            out.json(key)
            out.ch(':')
            out.json(value)
        }

        fun ms(key: String, nanos: Long) {
            if (args++ > 0) out.ch(',')
            out.json(key)
            out.ch(':')
            out.millis(nanos)
        }

        private fun open(name: String, suffix: String?, number: Long, ph: String, pid: Int, tid: Int) {
            if (!first) out.str(",\n")
            first = false
            out.str("{\"name\":\"")
            out.jsonRaw(name)
            if (number != NO_NUMBER) out.long(number)
            if (suffix != null) out.jsonRaw(suffix)
            out.str("\",\"ph\":\"")
            out.str(ph)
            out.str("\",\"pid\":")
            out.long(pid.toLong())
            out.str(",\"tid\":")
            out.long(tid.toLong())
        }
    }
}
