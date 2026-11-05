package com.nexachat.core.ui.components

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Checks whether system-wide animations are disabled or reduced.
 */
@Composable
fun rememberIsReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Motion specifications for subtle micro-interactions (<= 150ms).
 */
object NexaMotion {
    const val DURATION_FAST = 150

    val messageAppearFade: AnimationSpec<Float> = tween(
        durationMillis = DURATION_FAST,
        easing = FastOutSlowInEasing,
    )

    val reactionPopSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
