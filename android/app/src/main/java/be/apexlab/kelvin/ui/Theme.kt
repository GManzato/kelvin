package be.apexlab.kelvin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import be.apexlab.kelvin.R

/** Tokens from the "Thermal · components" sheet in Claude Design. */
object C {
    val Background = Color(0xFF0B0B0F)
    val Surface = Color(0xFF15151B)
    val Surface2 = Color(0xFF1C1C24)
    val Surface3 = Color(0xFF262630)
    val Well = Color(0xFF0F0F14)
    val Text = Color(0xFFF2F2F5)
    val Text2 = Color(0xFFE6E6EA)
    val Secondary = Color(0xFFA3A3AE)
    val Muted = Color(0xFF8E8E9A)
    val Body = Color(0xFFB4B4BE)
    val Soft = Color(0xFFCFCFD6)
    val Accent = Color(0xFF4FD1E8)
    val OnAccent = Color(0xFF06232A)
    val Connected = Color(0xFF3DDC84)
    val Hot = Color(0xFFFF4D4D)
    val Cold = Color(0xFF4DA3FF)
    val Warning = Color(0xFFF5B83D)
    val Isotherm = Color(0xFFD4FF3A)
    val OnIsotherm = Color(0xFF14160A)
    val Glass = Color(0xB814141A) // rgba(20,20,26,0.72)
    val Hairline = Color(0x14FFFFFF)
    val Track = Color(0xFF2A2A33)
    val Knob = Color(0xFF8E8E9A)
    val RadioOff = Color(0xFF5A5A66)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val Mono = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_semibold, FontWeight.SemiBold),
)

/** Readings drop a soft shadow so they hold on white-hot and iron alike. */
val ReadingShadow = Shadow(Color(0xE6000000), Offset(0f, 1f), 6f)

fun text(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.Text,
    spacing: TextUnit = TextUnit.Unspecified,
    shadow: Shadow? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = Inter,
    fontSize = size,
    fontWeight = weight,
    color = color,
    letterSpacing = spacing,
    shadow = shadow,
    lineHeight = lineHeight,
    fontFeatureSettings = "tnum",
)

fun mono(size: TextUnit = 11.sp, color: Color = C.Text2, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Mono,
    fontSize = size,
    fontWeight = weight,
    color = color,
    letterSpacing = 0.06.em,
)

/** Section label: 11 / 600, +0.14 em, uppercase. */
val Label = text(11.sp, FontWeight.SemiBold, C.Muted, 0.14.em)
