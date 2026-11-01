package app.boussole.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette « Taverne » : brun-noir chaud, or, rouge sang, cuir — assortie au logo. */
object Taverne {
    val Night = Color(0xFF1A0E0E)
    val Card = Color(0xFF2A1717)
    val CardHigh = Color(0xFF351E1E)
    val Gold = Color(0xFFE8B33C)
    val GoldSoft = Color(0xFFF3D58A)
    val Blood = Color(0xFFC0392B)
    val Forest = Color(0xFF3E8E5E)
    val Parchment = Color(0xFFF3E9DC)
    val Ash = Color(0xFFCBB8A5)
    val Line = Color(0xFF5C4038)
}

private val colors = darkColorScheme(
    primary = Taverne.Gold,
    onPrimary = Taverne.Night,
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Taverne.GoldSoft,
    secondary = Taverne.Blood,
    onSecondary = Taverne.Parchment,
    tertiary = Taverne.Forest,
    onTertiary = Taverne.Parchment,
    tertiaryContainer = Color(0xFF3D2C12),
    onTertiaryContainer = Taverne.GoldSoft,
    background = Taverne.Night,
    onBackground = Taverne.Parchment,
    surface = Taverne.Night,
    onSurface = Taverne.Parchment,
    surfaceVariant = Taverne.Card,
    onSurfaceVariant = Taverne.Ash,
    surfaceContainerLowest = Taverne.Night,
    surfaceContainerLow = Color(0xFF221313),
    surfaceContainer = Taverne.Card,
    surfaceContainerHigh = Taverne.CardHigh,
    surfaceContainerHighest = Taverne.CardHigh,
    outline = Taverne.Line,
    outlineVariant = Color(0xFF3F2A25),
    error = Color(0xFFE5533F),
    onError = Taverne.Night,
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFD9D2),
)

private val serif = FontFamily.Serif

private val typography = Typography().let { base ->
    base.copy(
        displayLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 58.sp),
        displayMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp),
        displaySmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

/** Thème Butin : toujours sombre, quel que soit le réglage du téléphone. */
@Composable
fun BoussoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
