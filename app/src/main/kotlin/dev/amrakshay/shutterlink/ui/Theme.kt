package dev.amrakshay.shutterlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The palette from `design/README.md`.
 *
 * There is no light theme and there will not be one. The app is used beside a
 * running camera, usually in dim light: a bright screen on the far phone lights
 * the subject, and a bright screen in your hand ruins your own night vision
 * while you are trying to frame. `isSystemInDarkTheme()` is deliberately ignored.
 */
object Ink {
    val Ground = Color(0xFF0B0E12)
    val Surface = Color(0xFF161B21)
    val SurfaceDim = Color(0xFF12171C)
    val Line = Color(0xFF262F38)
    val Text = Color(0xFFF2F5F7)
    val Muted = Color(0xFFA3AFBA)
    val Faint = Color(0xFF7D8B97)

    /** Connected, live, "this is the thing to press". */
    val Accent = Color(0xFF5AC8E8)
    val AccentInk = Color(0xFF06181F)
    val AccentWell = Color(0xFF11262F)

    /** Warnings only. Used nowhere else, so that it still reads when it appears. */
    val Warning = Color(0xFFF2A33C)
    val Positive = Color(0xFF5FD39B)
    val Destructive = Color(0xFFE8857A)
}

private val scheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Ink.AccentInk,
    background = Ink.Ground,
    onBackground = Ink.Text,
    surface = Ink.Surface,
    onSurface = Ink.Text,
    surfaceVariant = Ink.SurfaceDim,
    onSurfaceVariant = Ink.Muted,
    outline = Ink.Line,
    error = Ink.Destructive,
)

@Composable
fun ShutterLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
