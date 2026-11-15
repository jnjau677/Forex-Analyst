package com.devcode940.ForexAnalyst.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.model.DataOrigin
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider

/**
 * Tells the user where the prices on screen came from.
 *
 * WHY this component exists: the app ships with a simulator so it is useful with no
 * API key and no network. That is only acceptable while the two are visibly different.
 * A chart of invented prices that looks like a chart of real ones is how somebody
 * trades on a number that was never a price.
 *
 * WHY it is always rendered, including for live data: a badge that only appears when
 * something is wrong teaches the user nothing about the good case, and "I did not see
 * a warning" is not the same as "I saw that this was live". The positive state is the
 * one worth confirming.
 *
 * WHY real text rather than an icon: an icon is not readable by TalkBack without a
 * description, is ambiguous at 16 dp, and cannot carry the reason a feed was skipped.
 *
 * @param origin provenance published by the repository for the last candle fetch.
 */
@Composable
fun DataSourceBadge(origin: DataOrigin, modifier: Modifier = Modifier) {
    val label = labelFor(origin)
    val tone = when {
        origin.notice != null -> MaterialTheme.colorScheme.errorContainer
        origin.provider.isLive -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onTone = when {
        origin.notice != null -> MaterialTheme.colorScheme.onErrorContainer
        origin.provider.isLive -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .padding(horizontal = 12.dp)
            // WHY the whole badge is one semantics node: reading "Simulated" and then
            // "Showing generated prices because..." as two unrelated announcements
            // loses the causal link that makes the message useful.
            .semantics { contentDescription = spokenDescription(origin) }
    ) {
        Surface(
            color = tone,
            contentColor = onTone,
            shape = RoundedCornerShape(percent = 40)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }

        val notice = origin.notice
        if (!notice.isNullOrBlank()) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * The badge text.
 *
 * WHY the cached state is named: a free-tier feed answers from the provider's own
 * cache for up to an hour, so "FCS API" alone would imply a price more current than it
 * is. Saying "cached" costs four characters and removes a false impression.
 */
private fun labelFor(origin: DataOrigin): String = when (origin.provider) {
    MarketDataProvider.SIMULATED -> "Simulated data"
    else -> if (origin.isCached) {
        "${origin.provider.displayName} · cached"
    } else {
        "Live · ${origin.provider.displayName}"
    }
}

private fun spokenDescription(origin: DataOrigin): String = buildString {
    append("Price source: ")
    append(labelFor(origin))
    append('.')
    if (origin.provider == MarketDataProvider.SIMULATED) {
        append(" These prices are generated on this device, not taken from a market.")
    }
    origin.notice?.let {
        append(' ')
        append(it)
    }
}
