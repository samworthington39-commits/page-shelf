@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.bookshelf.ui.narration

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    var showOptions by rememberSaveable { mutableStateOf(false) }
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

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("定时关闭", fontWeight = FontWeight.Medium)
                Text(
                    sleepTimerSummary(timer, endsAtElapsedRealtimeMs, elapsedRealtimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { showOptions = true }, enabled = enabled) {
                Icon(Icons.Outlined.Timer, contentDescription = null)
                Text(if (timer == null) "设置" else "更改", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    "定时关闭",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                NarrationSleepTimer.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(
                                selected = timer == option,
                                onClick = {
                                    onTimerChange(option)
                                    showOptions = false
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = timer == option, onClick = null)
                        Text(option.displayName, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (timer != null) {
                    TextButton(
                        onClick = {
                            onTimerChange(null)
                            showOptions = false
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text("关闭定时")
                    }
                }
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
