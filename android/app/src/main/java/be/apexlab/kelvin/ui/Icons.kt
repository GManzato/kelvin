package be.apexlab.kelvin.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** 24×24 icons, path data copied from the Claude Design boards (circles/rects expressed as paths). */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    val Bell = stroke("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Sliders = stroke("M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    val LockClosed = stroke(rect(4f, 11f, 16f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4", width = 2f)
    val LockOpen = stroke(rect(4f, 11f, 16f, 10f, 2f), "M8 11V7a4 4 0 0 1 7.9-.9", width = 2f)
    val Close = stroke("M6 6l12 12M18 6 6 18", width = 2f)
    val Check = stroke("M5 12.5l4.5 4.5L19 7.5", width = 2.6f)
    val ChevronUp = stroke("M6 15l6-6 6 6", width = 3f)
    val Back = stroke("M15 18l-6-6 6-6", width = 2f)
    val Share = stroke("M12 3v12", "M7 8l5-5 5 5", "M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")
    val Table = stroke(rect(3f, 4f, 18f, 16f, 2f), "M3 10h18M3 15h18M9 4v16M15 4v16")
    val Split = stroke(rect(3f, 4f, 18f, 16f, 2f), "M12 4v16")
    val Trash = stroke("M4 7h16", "M10 11v6M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M9 7V4h6v3")
    val Bolt: ImageVector = ImageVector.Builder(defaultWidth = 22.dp, defaultHeight = 22.dp, viewportWidth = 22f, viewportHeight = 22f)
        .addPath(pathData = PathParser().parsePathString("M11.8 6.5 8.6 11.6h2.6l-.9 3.9 3.2-5.1h-2.6z").toNodes(), fill = SolidColor(Color.White))
        .build()
    /** Timelapse shutter glyph (design: circle r8 with hands). */
    val TimelapseClock = stroke(circle(12f, 12f, 8f), "M12 7.5V12l3 2", width = 2f)
    val Warning = stroke("M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z", width = 2.2f)
    val Power = stroke("M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0", width = 2f)
    val BatteryLow = stroke(rect(2f, 7f, 17f, 10f, 2f), "M22 11v2", "M5.5 10.5v3", width = 1.9f)
    val Play = fill("M8 5.5v13l11-6.5z")
    val Triangle = fill("M12 3 2 21h20z")
    val Sound = stroke("M11 5 6 9H2v6h4l5 4z", "M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14")
    val Vibrate = stroke(rect(7f, 3f, 10f, 18f, 2f), "M3 9v6M21 9v6")
    val Pip = stroke(rect(3f, 5f, 18f, 14f, 2f), rect(12f, 11f, 7f, 6f, 1f))
    val Clock = stroke(circle(12f, 12f, 9f), "M12 3v9l6.5 4", width = 2f)
    val Copy = stroke(rect(9f, 9f, 12f, 12f, 2f), "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")

    // view modes and measurement tools (Palette & view sheet)
    val Thermal = stroke("M14 14.8V4a2 2 0 0 0-4 0v10.8a4 4 0 1 0 4 0z")
    val Visible = stroke("M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
    val Blend = stroke("M9 16a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM15 20a6 6 0 1 0 0-12 6 6 0 0 0 0 12z")
    val Edges = stroke("M4 18 9 9l4 6 3-4 4 7zM4 4h4M16 4h4")
    val Spot = stroke("M12 3v6M12 15v6M3 12h6M15 12h6")
    val Box = stroke("M4 4h16v16H4zM4 9h2M18 15h2")
    val Delta = stroke("M12 4 3 20h18z")

    private fun stroke(vararg d: String, width: Float = 1.75f): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in d) b.addPath(
            pathData = PathParser().parsePathString(p).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        return b.build()
    }

    private fun fill(d: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White))
            .build()
}

@Composable
fun Icon(icon: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
