package top.izuna.foliamajor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import top.izuna.foliamajor.theme.DefaultTheme
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.toThemeColor

/**
 * The theme every composable should read its colours from.
 *
 * A CompositionLocal rather than a parameter on forty composables: the theme is constant for a
 * whole screen and changes only when a song's AI theme is applied, which is exactly the shape a
 * local is for. Passing it explicitly would thread a value through every helper — including ones
 * that only draw a divider — and a forgotten parameter would silently keep the default colours,
 * which is a far worse failure than the implicit dependency.
 *
 * `staticCompositionLocalOf` is correct here because a change re-renders the whole subtree anyway;
 * there is nothing to skip.
 */
val LocalFoliaTheme = staticCompositionLocalOf<Theme> { DefaultTheme }

/**
 * Wraps the app in the Folia theme.
 *
 * The palette follows [theme]. With the default theme that is the desktop app's #09090b surface;
 * with an AI theme it is whatever the model chose. The Material scheme is derived from the same
 * values so any standard component dropped in later still matches.
 */
@Composable
fun FoliaTheme(
    theme: Theme = DefaultTheme,
    content: @Composable () -> Unit,
) {
    val background = theme.backgroundColor.toThemeColor()
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()

    val scheme = darkColorScheme(
        background = background,
        surface = background,
        onBackground = primary,
        onSurface = primary,
        primary = accent,
        secondary = theme.secondaryColor.toThemeColor(),
        onPrimary = background,
    )

    CompositionLocalProvider(LocalFoliaTheme provides theme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The active theme, for composables that need the hex strings rather than Compose colours. */
@Composable
fun foliaTheme(): Theme = LocalFoliaTheme.current

/** Kept for the eventual light/dark theme pair; the desktop app stores both per AI theme. */
@Suppress("unused")
@Composable
fun isDark(): Boolean = isSystemInDarkTheme()
