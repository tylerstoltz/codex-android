package com.local.codexmobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = CodexGreen,
    secondary = CodexGreen,
    background = CodexBg,
    surface = CodexBgAlt
)

@Composable
fun CodexMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography,
        content = content
    )
}
