package com.umbra.hooks.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalContext

// The original screen used platform TextViews: natural font metrics and font padding,
// rather than Compose's larger explicit Material line-height boxes.
private fun TextStyle.legacyMetrics() = copy(
    lineHeight = TextUnit.Unspecified,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

private val UmbraTypography = Typography().let {
    it.copy(
        displaySmall = it.displaySmall.legacyMetrics(),
        titleLarge = it.titleLarge.legacyMetrics(),
        titleMedium = it.titleMedium.legacyMetrics(),
        bodyMedium = it.bodyMedium.legacyMetrics(),
        bodySmall = it.bodySmall.legacyMetrics(),
    )
}

/** Preserve system day/night and wallpaper colors, with the original text metrics. */
@Composable
fun UmbraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = UmbraTypography, content = content)
}
