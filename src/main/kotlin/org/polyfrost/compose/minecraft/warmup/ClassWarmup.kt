package org.polyfrost.compose.minecraft.warmup

import androidx.collection.MutableScatterMap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Job
import org.polyfrost.compose.backend.PolyCompose
import org.slf4j.LoggerFactory

internal object ClassWarmup {
    class Result(val loaded: Int, val failed: Int, val nanos: Long)

    private val LOG = LoggerFactory.getLogger("PolyCompose/Warmup")

    private val PREFIXES = arrayOf(
        "androidx/compose/runtime/", "androidx/compose/ui/", "androidx/compose/foundation/",
        "androidx/compose/animation/core/", "androidx/collection/", "kotlinx/coroutines/", "org/polyfrost/compose/",
    )

    private val SKIPPED = arrayOf(
        "/awt/", "/window/", "/skia/", "/skiko/", "scene/ComposeSceneMediator.class", "/swing/", "/a11y/", "/l10n/",
        "/draganddrop/", "/viewinterop/", "/autofill/", "/tooling/", "/text/input/", "/text/selection/",
        "/contextmenu/", "/handwriting/", "/pager/", "/lazy/grid/",
        "/lazy/staggeredgrid/", "foundation/style/", "kotlinx/coroutines/debug/", "kotlinx/coroutines/future/",
        "kotlinx/coroutines/stream/", "kotlinx/coroutines/time/", "org/polyfrost/compose/minecraft/",
    )

    private val LOW_PREFIXES = arrayOf("androidx/compose/runtime/", "org/polyfrost/compose/")

    private fun lowAnchors(): Array<Class<*>> = arrayOf(Composer::class.java, PolyCompose::class.java)

    private fun anchors(): Array<Class<*>> = arrayOf(
        Composer::class.java, Modifier::class.java, Color::class.java, TextStyle::class.java, Dp::class.java,
        Offset::class.java, BorderStroke::class.java, Arrangement::class.java, Animatable::class.java,
        MutableScatterMap::class.java, Job::class.java, PolyCompose::class.java,
    )

    fun run(loader: ClassLoader?): Result {
        val begin = System.nanoTime()
        val profile = WarmupProfile.current
        val low = profile == WarmupProfile.LOW
        val prefixes = if (low) LOW_PREFIXES else PREFIXES
        val names = LinkedHashSet<String>()
        val sources = HashSet<String>()
        for (anchor in if (low) lowAnchors() else anchors()) {
            val location = anchor.protectionDomain?.codeSource?.location ?: continue
            if (!sources.add(location.toString())) continue
            runCatching { collect(Paths.get(location.toURI()), prefixes, names) }
                .onFailure { LOG.debug("Can't list the classes in {}", location, it) }
        }
        val ordered = names.sortedBy(::priority).let { if (it.size > profile.maxClasses) it.subList(0, profile.maxClasses) else it }
        var loaded = 0
        var failed = 0
        val failures = ArrayList<String>()
        for (name in ordered) {
            val problem = load(name, loader)
            if (problem == null) loaded++ else {
                failed++
                failures += "$name ($problem)"
            }
            if (profile.yieldEvery > 0 && (loaded + failed) % profile.yieldEvery == 0) Thread.yield()
        }
        if (failures.isNotEmpty()) LOG.debug("Warm-up could not load {} classes: {}", failures.size, failures)
        return Result(loaded, failed, System.nanoTime() - begin)
    }

    private fun priority(name: String): Int {
        val path = name.replace('.', '/')
        val index = PREFIXES.indexOfFirst { path.startsWith(it) }
        return if (index < 0) PREFIXES.size else index
    }

    private fun collect(path: Path, prefixes: Array<String>, out: MutableSet<String>) {
        if (Files.isDirectory(path)) {
            scan(path, prefixes, out)
        } else {
            FileSystems.newFileSystem(path, null as ClassLoader?).use { scan(it.getPath("/"), prefixes, out) }
        }
    }

    private fun scan(root: Path, prefixes: Array<String>, out: MutableSet<String>) {
        Files.walk(root).use { paths ->
            for (path in paths.iterator()) {
                val entry = root.relativize(path).toString().replace('\\', '/')
                if (wanted(entry, prefixes)) out += entry.substring(0, entry.length - ".class".length).replace('/', '.')
            }
        }
    }

    private fun wanted(entry: String, prefixes: Array<String>): Boolean {
        if (!entry.endsWith(".class") || entry.endsWith("module-info.class") || entry.startsWith("META-INF/")) return false
        if (prefixes.none { entry.startsWith(it) }) return false
        return SKIPPED.none { it in entry }
    }

    private fun load(name: String, loader: ClassLoader?): String? = try {
        Class.forName(name, false, loader)
        null
    } catch (e: Exception) {
        e.javaClass.simpleName
    } catch (e: LinkageError) {
        e.javaClass.simpleName
    }
}
