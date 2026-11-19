package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.polyfrost.compose.profile.ProfilePhase

internal object ProfilerTheme {
    val Panel = Color(0xD8101015)
    val PanelRaised = Color(0xFF1C1C24)
    val GraphBackground = Color(0x66000000)
    val Grid = Color(0x22FFFFFF)
    val Ink = Color(0xFFEDEDF2)
    val Muted = Color(0xFF9A9AA6)
    val Accent = Color(0xFF56B4E9)
    val Threshold = Color(0xCCFFFFFF)
    val Spike = Color(0xFFD55E00)
    val Selection = Color(0x55FFFFFF)

    val Compose = Color(0xFF0072B2)
    val Layout = Color(0xFF56B4E9)
    val Record = Color(0xFFE69F00)
    val Upload = Color(0xFFF0E442)
    val Gpu = Color(0xFF009E73)
    val Effects = Color(0xFFCC79A7)

    val Phases: Array<Color> = arrayOf(Compose, Layout, Record, Upload, Gpu, Effects)
    val PhaseNames: Array<String> = arrayOf("compose", "layout", "record", "upload", "gpu", "effects")

    val Small = TextStyle(color = Ink, fontSize = 7.sp)
    val SmallMuted = TextStyle(color = Muted, fontSize = 7.sp)
    val Body = TextStyle(color = Ink, fontSize = 8.sp)
    val BodyMuted = TextStyle(color = Muted, fontSize = 8.sp)
    val Title = TextStyle(color = Ink, fontSize = 10.sp)

    val Corner = RoundedCornerShape(3.dp)

    fun phaseColor(phase: ProfilePhase): Color = Phases[phase.ordinal]
}

internal fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.2f", nanos / NANOS_PER_MILLI)

internal fun ms1(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_MILLI)

internal fun compact(value: Long): String = when {
    value >= MEGA -> String.format(Locale.ROOT, "%.1fM", value / MEGA.toDouble())
    value >= KILO -> String.format(Locale.ROOT, "%.1fk", value / KILO.toDouble())
    else -> value.toString()
}

internal const val NANOS_PER_MILLI = 1_000_000.0
private const val KILO = 1_000L
private const val MEGA = 1_000_000L

@Composable
internal fun ProfilerButton(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val background = if (active) ProfilerTheme.Accent.copy(alpha = 0.35f) else ProfilerTheme.PanelRaised
    val base = Modifier.background(background, ProfilerTheme.Corner)
    val modifier = if (enabled) base.clickable(onClick = onClick) else base
    Box(modifier.padding(horizontal = 5.dp, vertical = 2.dp)) {
        BasicText(label, style = if (enabled) ProfilerTheme.Body else ProfilerTheme.BodyMuted)
    }
}

@Composable
internal fun ProfilerToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable { onChange(!value) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(8.dp)
                .background(if (value) ProfilerTheme.Accent else ProfilerTheme.PanelRaised, ProfilerTheme.Corner),
        )
        BasicText(label, style = ProfilerTheme.Body)
    }
}

@Composable
internal fun Swatch(color: Color) {
    Box(Modifier.size(6.dp).background(color, ProfilerTheme.Corner))
}

@Composable
internal fun Cell(text: String, width: Dp, style: TextStyle = ProfilerTheme.Small, end: Boolean = true) {
    BasicText(
        text,
        modifier = Modifier.width(width),
        style = if (end) style.copy(textAlign = TextAlign.End) else style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
