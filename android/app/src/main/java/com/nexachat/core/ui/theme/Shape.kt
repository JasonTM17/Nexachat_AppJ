package com.nexachat.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shape & Corner tokens for NexaChat.
 */
object NexaShapes {
    val roundedXs = RoundedCornerShape(4.dp)
    val roundedSm = RoundedCornerShape(8.dp)
    val roundedMd = RoundedCornerShape(12.dp)
    val roundedLg = RoundedCornerShape(16.dp)
    val roundedXl = RoundedCornerShape(24.dp)
    val roundedFull = CircleShape

    /** Floating Action Button squircle radius (16dp). */
    val fabShape = RoundedCornerShape(16.dp)

    /** Message composer input pill. */
    val composerPill = RoundedCornerShape(24.dp)

    /** Search bar pill. */
    val searchBarPill = RoundedCornerShape(24.dp)
}
