@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.bookshelf.ui.narration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bookshelf.narration.DEFAULT_PLAYBACK_SPEED
import com.example.bookshelf.narration.NarrationController
import com.example.bookshelf.narration.normalizePlaybackSpeed
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NarrationSpeedControls(
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var presetsExpanded by remember { mutableStateOf(false) }
    val canDecrease = playbackSpeed > NarrationController.MIN_PLAYBACK_SPEED
    val canIncrease = playbackSpeed < NarrationController.MAX_PLAYBACK_SPEED

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("朗读速度", fontWeight = FontWeight.Medium)
                Text(
                    "语速 ${formatCurrentSpeed(playbackSpeed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onPlaybackSpeedChange(DEFAULT_PLAYBACK_SPEED) },
                enabled = !sameSpeed(playbackSpeed, DEFAULT_PLAYBACK_SPEED),
            ) {
                Text("重置 1.0×")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    onPlaybackSpeedChange(
                        normalizePlaybackSpeed(playbackSpeed - NarrationController.PLAYBACK_SPEED_STEP),
                    )
                },
                enabled = canDecrease,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "语速降低 0.05 倍")
            }
            Slider(
                value = playbackSpeed,
                onValueChange = { onPlaybackSpeedChange(normalizePlaybackSpeed(it)) },
                valueRange = NarrationController.MIN_PLAYBACK_SPEED..NarrationController.MAX_PLAYBACK_SPEED,
                steps = (
                    (NarrationController.MAX_PLAYBACK_SPEED - NarrationController.MIN_PLAYBACK_SPEED) /
                        NarrationController.PLAYBACK_SPEED_STEP
                    ).roundToInt() - 1,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "朗读速度 ${formatCurrentSpeed(playbackSpeed)}"
                },
            )
            IconButton(
                onClick = {
                    onPlaybackSpeedChange(
                        normalizePlaybackSpeed(playbackSpeed + NarrationController.PLAYBACK_SPEED_STEP),
                    )
                },
                enabled = canIncrease,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "语速提高 0.05 倍")
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NarrationController.QUICK_PLAYBACK_SPEEDS.forEach { speed ->
                FilterChip(
                    selected = sameSpeed(playbackSpeed, speed),
                    onClick = { onPlaybackSpeedChange(speed) },
                    label = { Text(formatPresetSpeed(speed)) },
                )
            }
        }

        Box {
            TextButton(onClick = { presetsExpanded = true }) {
                Text("更多预设")
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = presetsExpanded,
                onDismissRequest = { presetsExpanded = false },
            ) {
                NarrationController.PRESET_PLAYBACK_SPEEDS.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(formatPresetSpeed(speed)) },
                        onClick = {
                            presetsExpanded = false
                            onPlaybackSpeedChange(speed)
                        },
                        trailingIcon = {
                            if (sameSpeed(playbackSpeed, speed)) {
                                Text("当前", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun sameSpeed(first: Float, second: Float): Boolean = abs(first - second) < 0.001f

private fun formatCurrentSpeed(speed: Float): String = String.format(Locale.ROOT, "%.2f×", speed)

private fun formatPresetSpeed(speed: Float): String = when {
    sameSpeed(speed, speed.roundToInt().toFloat()) -> String.format(Locale.ROOT, "%.1f×", speed)
    sameSpeed(speed * 10, (speed * 10).roundToInt().toFloat()) -> String.format(Locale.ROOT, "%.1f×", speed)
    else -> String.format(Locale.ROOT, "%.2f×", speed)
}
