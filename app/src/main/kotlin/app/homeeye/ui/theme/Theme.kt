package app.homeeye.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Teal = Color(0xFF14B8A6)
val TealLight = Color(0xFF5EEAD4)
val LiveGreen = Color(0xFF4ADE80)
val WarnAmber = Color(0xFFFBBF24)

private val Dark = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F3D38),
    onPrimaryContainer = TealLight,
    background = Color(0xFF0E1116),
    surface = Color(0xFF0E1116),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C222B),
    surfaceContainerHighest = Color(0xFF232A34),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0D7A6E),
    primaryContainer = Color(0xFFC8F2EC),
    onPrimaryContainer = Color(0xFF00201C),
)

@Composable
fun HomeEyeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
