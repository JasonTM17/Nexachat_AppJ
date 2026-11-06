package com.nexachat.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.nexachat.core.common.AppConfig
import com.nexachat.feature.media.rememberAuthedImageLoader

import com.nexachat.core.ui.MessageUiRules

data class AvatarColorPair(
    val container: Color,
    val onContainer: Color,
)

// 8 harmonized tonal hues: Indigo, Blue, Cyan, Teal, Emerald, Amber, Rose, Purple
// Contrast ratios tested & guaranteed >= 4.5:1 (WCAG AA/AAA)
val AvatarPaletteLight = listOf(
    AvatarColorPair(Color(0xFFE0E7FF), Color(0xFF3730A3)), // Indigo (8.06:1)
    AvatarColorPair(Color(0xFFDBEAFE), Color(0xFF1E40AF)), // Blue (7.15:1)
    AvatarColorPair(Color(0xFFCFFAFE), Color(0xFF155E75)), // Cyan (6.49:1)
    AvatarColorPair(Color(0xFFCCFBF1), Color(0xFF115E59)), // Teal (6.73:1)
    AvatarColorPair(Color(0xFFD1FAE5), Color(0xFF065F46)), // Emerald (6.78:1)
    AvatarColorPair(Color(0xFFFEF3C7), Color(0xFF92400E)), // Amber (6.37:1)
    AvatarColorPair(Color(0xFFFFE4E6), Color(0xFF9F1239)), // Rose (6.68:1)
    AvatarColorPair(Color(0xFFF3E8FF), Color(0xFF6B21A8)), // Purple (7.39:1)
)

val AvatarPaletteDark = listOf(
    AvatarColorPair(Color(0xFF312E81), Color(0xFFE0E7FF)), // Indigo (9.27:1)
    AvatarColorPair(Color(0xFF1E3A8A), Color(0xFFDBEAFE)), // Blue (8.49:1)
    AvatarColorPair(Color(0xFF164E63), Color(0xFFCFFAFE)), // Cyan (8.14:1)
    AvatarColorPair(Color(0xFF134E4A), Color(0xFFCCFBF1)), // Teal (8.41:1)
    AvatarColorPair(Color(0xFF064E3B), Color(0xFFD1FAE5)), // Emerald (8.57:1)
    AvatarColorPair(Color(0xFF78350F), Color(0xFFFEF3C7)), // Amber (8.15:1)
    AvatarColorPair(Color(0xFF881337), Color(0xFFFFE4E6)), // Rose (7.97:1)
    AvatarColorPair(Color(0xFF581C87), Color(0xFFF3E8FF)), // Purple (9.22:1)
)

/**
 * Pure UI presentation rule: computes a deterministic palette index 0..7 from a user name.
 */
fun avatarPaletteIndex(name: String): Int = AvatarPalette.avatarPaletteIndex(name)

/**
 * Pure UI presentation rule: returns the deterministic container & text colors for [name].
 */
fun avatarColorsForName(name: String, isDark: Boolean): AvatarColorPair {
    val index = avatarPaletteIndex(name)
    val palette = if (isDark) AvatarPaletteDark else AvatarPaletteLight
    return palette[index]
}

/**
 * Pure UI presentation rule: extracts 1-2 uppercase letters from [name] for avatar initials.
 * Single word -> 1 letter. Multiple words -> first letter of first word + first letter of last word.
 * Delegates to [MessageUiRules.extractInitials] (Java rule helper).
 */
fun extractInitials(name: String): String = MessageUiRules.extractInitials(name)

/**
 * Central design-system Avatar component.
 * - Circular shape (CircleShape).
 * - Deterministic tonal palette for fallback background and text.
 * - Image loading via Coil [SubcomposeAsyncImage] with initials fallback on error / loading.
 * - WCAG AA contrast ratio >= 4.5:1 for initials.
 */
@Composable
fun Avatar(
    name: String,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: (() -> Unit)? = null,
) {
    val isDark = isSystemInDarkTheme()
    val colors = avatarColorsForName(name, isDark)
    val initials = extractInitials(name)

    val clickableMod = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Box(
        modifier = clickableMod
            .size(size)
            .clip(CircleShape)
            .background(colors.container)
            .semantics(mergeDescendants = true) {
                contentDescription = name
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = AppConfig.absoluteMediaUrl(avatarUrl),
                imageLoader = rememberAuthedImageLoader(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    InitialsText(initials, colors.onContainer, size)
                },
                error = {
                    InitialsText(initials, colors.onContainer, size)
                },
                success = {
                    SubcomposeAsyncImageContent()
                },
            )
        } else {
            InitialsText(initials, colors.onContainer, size)
        }
    }
}

@Composable
private fun InitialsText(
    initials: String,
    color: Color,
    size: Dp,
) {
    val fontSize = when {
        size >= 72.dp -> 26.sp
        size >= 56.dp -> 20.sp
        size >= 40.dp -> 15.sp
        size >= 32.dp -> 12.sp
        else -> 10.sp
    }
    Text(
        text = initials,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}
