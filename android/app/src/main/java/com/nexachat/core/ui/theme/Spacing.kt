package com.nexachat.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Spacing tokens for NexaChat.
 * Adheres to 8pt/4pt baseline grid.
 */
object NexaSpacing {
    val spaceXs: Dp = 4.dp
    val spaceSm: Dp = 8.dp
    val spaceMd: Dp = 16.dp
    val spaceLg: Dp = 24.dp
    val spaceXl: Dp = 32.dp

    /** Max content container width for large screens / landscape layouts (centered). */
    val maxContentWidth: Dp = 640.dp

    /** Minimum touch target size per Material 3 accessibility guidelines. */
    val minTouchTarget: Dp = 48.dp
}
