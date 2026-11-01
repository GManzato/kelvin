package com.gilles.flirone.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Click without ripple (the design has none; pressed states are explicit). */
fun Modifier.tap(onClick: () -> Unit): Modifier = composedTap(onClick)

private fun Modifier.composedTap(onClick: () -> Unit) = this.then(
    Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick),
)

@Composable
fun GlassPill(modifier: Modifier = Modifier, height: Dp = 34.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(C.Glass)
            .border(1.dp, C.Hairline, RoundedCornerShape(height / 2))
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
fun RoundButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.Text, badge: Color? = null) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(C.Glass).tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint)
        if (badge != null) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(10.dp)
                .clip(CircleShape).background(Color(0xFF14141A)).padding(2.dp).clip(CircleShape).background(badge),
        )
    }
}

@Composable
fun Toggle(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) C.Accent else C.Track)
            .tap(onToggle).padding(horizontal = 4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) C.OnAccent else C.Knob))
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, height: Dp = 38.dp, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Well).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val sel = value == selected
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) C.Surface3 else Color.Transparent).tap { onPick(value) },
                contentAlignment = Alignment.Center,
            ) { BasicText(label, style = text(13.sp, FontWeight.SemiBold, if (sel) C.Text else C.Secondary)) }
        }
    }
}

@Composable
fun Spinner(color: Color, size: Dp = 22.dp, stroke: Dp = 2.dp) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2
        drawCircle(color.copy(alpha = 0.25f), radius = this.size.minDimension / 2 - inset, style = Stroke(w))
        rotate(angle) {
            drawArc(color, -90f, 90f, false, Offset(inset, inset), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun BatteryRing(percent: Int, charging: Boolean, color: Color) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val w = 2.4.dp.toPx()
            val r = 8.dp.toPx()
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0x29FFFFFF), r, c, style = Stroke(w))
            drawArc(color, -90f, 360f * percent / 100f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
        }
        if (charging) Icon(Ic.Bolt, color, 22.dp)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(C.Accent).tap(onClick),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = text(16.sp, FontWeight.SemiBold, C.OnAccent)) }
}

@Composable
fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 52.dp) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(height / 2))
            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(height / 2)).tap(onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = text(15.sp, FontWeight.SemiBold)) }
}

@Composable
fun TextLink(label: String, onClick: () -> Unit, color: Color = C.Accent, modifier: Modifier = Modifier) {
    Box(modifier.height(44.dp).tap(onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = text(14.sp, FontWeight.Medium, color))
    }
}

/** Bottom sheet frame: scrim, 28 dp top corners, drag handle, title row. Scrolls when taller than the screen (landscape). */
@Composable
fun Sheet(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x2E000000)).tap(onDismiss).statusBarsPadding()) {
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .shadow(40.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(C.Surface)
                .tap { } // swallow taps so they don't close the sheet
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A44)))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(title, style = text(20.sp, FontWeight.SemiBold))
                    if (subtitle != null) BasicText(subtitle, style = text(12.sp, color = C.Secondary), modifier = Modifier.padding(top = 2.dp))
                }
                trailing()
            }
            content()
        }
    }
}

@Composable
fun SectionLabel(label: String, top: Dp = 18.dp) {
    BasicText(label, style = Label, modifier = Modifier.padding(top = top))
}

@Composable
fun Spacer(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun CenteredText(s: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) =
    BasicText(s, style = style.copy(textAlign = TextAlign.Center), modifier = modifier)

@Composable
fun Banner(border: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(Color(0xEB1C1C24)).border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Chip(modifier: Modifier = Modifier, bg: Color = Color(0xD114141A), borderColor: Color = Color(0x1AFFFFFF), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(bg).border(1.dp, borderColor, RoundedCornerShape(15.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

