package com.nexachat.feature.summary

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexachat.R
import com.nexachat.core.ui.components.rememberIsReduceMotionEnabled

/**
 * Shimmering skeleton placeholder rendered while the conversation summary is being generated.
 * Honors system reduced-motion preferences.
 */
@Composable
fun SummarySkeleton(
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberIsReduceMotionEnabled()
    val alpha = if (reduceMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "shimmer_alpha",
        )
        animatedAlpha
    }

    val loadingDescription = stringResource(R.string.summary_loading_content_desc)
    val shimmerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = loadingDescription
            },
    ) {
        // Overview section skeleton card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(shimmerColor),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Key points title skeleton
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerColor),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3 Key points bullet skeletons
        repeat(3) { index ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(shimmerColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index == 1) 0.7f else 0.9f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Decisions title skeleton
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerColor),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2 Decisions skeletons
        repeat(2) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(shimmerColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor),
                )
            }
        }
    }
}
