package com.seta.androidbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SetaLightColorScheme = lightColorScheme(
    primary = SetaPrimary,
    onPrimary = SetaOnMain,
    primaryContainer = SetaSurface,
    onPrimaryContainer = SetaOnMain,

    secondary = SetaSecondary,
    onSecondary = SetaOnMain,
    secondaryContainer = SetaSurface,
    onSecondaryContainer = SetaOnMain,

    tertiary = SetaSurfaceVariant,
    onTertiary = SetaOnMain,
    tertiaryContainer = SetaSurfaceVariant,
    onTertiaryContainer = SetaOnMain,

    background = SetaBackground,
    onBackground = SetaOnMain,

    surface = SetaSurface,
    onSurface = SetaOnMain,
    surfaceVariant = SetaSurfaceVariant,
    onSurfaceVariant = SetaOnMain,

    outline = SetaOutline,
    outlineVariant = SetaOutlineVariant,

    error = SetaError,
    onError = SetaOnError,
)

@Composable
fun SetaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SetaLightColorScheme,
        content = content
    )
}
