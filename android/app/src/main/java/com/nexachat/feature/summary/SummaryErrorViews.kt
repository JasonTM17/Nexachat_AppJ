package com.nexachat.feature.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexachat.R
import com.nexachat.summary.SummaryError

/**
 * Typed error cards for summary requests matching Stitch error screens.
 */
@Composable
fun SummaryErrorView(
    error: SummaryError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, title, desc, showRetry) = when (error) {
        SummaryError.TOO_FEW -> Quadruple(
            Icons.Filled.ChatBubbleOutline,
            stringResource(R.string.summary_error_too_few),
            stringResource(R.string.summary_error_too_few_desc),
            false,
        )
        SummaryError.RATE_LIMITED -> Quadruple(
            Icons.Filled.HourglassTop,
            stringResource(R.string.summary_error_rate_limited),
            stringResource(R.string.summary_error_rate_limited_desc),
            true,
        )
        SummaryError.NOT_MEMBER -> Quadruple(
            Icons.Filled.ErrorOutline,
            stringResource(R.string.summary_error_not_member),
            null,
            false,
        )
        SummaryError.NETWORK -> Quadruple(
            Icons.Filled.WifiOff,
            stringResource(R.string.summary_error_offline),
            null,
            true,
        )
        SummaryError.UNKNOWN -> Quadruple(
            Icons.Filled.ErrorOutline,
            stringResource(R.string.summary_error_generic),
            null,
            true,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (error == SummaryError.TOO_FEW) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(40.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        if (desc != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (showRetry) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.summary_action_retry))
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
