@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.bookshelf.ui.narration

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bookshelf.narration.NarrationSleepTimer
import kotlinx.coroutines.delay

@Composable
internal fun NarrationSleepTimerControl(
    timer: NarrationSleepTimer?,
    endsAtElapsedRealtimeMs: Long?,
    enabled: Boolean,
    onTimerChange: (NarrationSleepTimer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsedRealtimeMs by remember(endsAtElapsedRealtimeMs) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }

    LaunchedEffect(endsAtElapsedRealtimeMs) {
        val endsAt = endsAtElapsedRealtimeMs ?: return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            elapsedRealtimeMs = now
            val remaining = endsAt - now
            if (remaining <= 0L) break
            delay(minOf(1_000L, remaining))
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("定时关闭", fontWeight = FontWeight.Medium)
                Text(
                    sleepTimerSummary(timer, endsAtElapsedRealtimeMs, elapsedRealtimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = timer == null,
                onClick = { onTimerChange(null) },
                enabled = enabled,
                label = { Text("关闭") },
            )
            NarrationSleepTimer.entries.forEach { option ->
                FilterChip(
                    selected = timer == option,
                    onClick = { onTimerChange(option) },
                    enabled = enabled,
                    label = { Text(option.displayName) },
                )
            }
        }
    }
}

internal fun sleepTimerSummary(
    timer: NarrationSleepTimer?,
    endsAtElapsedRealtimeMs: Long?,
    elapsedRealtimeMs: Long,
): String = when {
    timer == null -> "未设置"
    timer == NarrationSleepTimer.END_OF_CHAPTER -> timer.displayName
    endsAtElapsedRealtimeMs != null -> {
        "${timer.displayName} · ${formatRemainingTime(endsAtElapsedRealtimeMs - elapsedRealtimeMs)}"
    }
    else -> timer.displayName
}

internal fun formatRemainingTime(remainingMillis: Long): String {
    val totalSeconds = ((remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
