package com.example.bookshelf.ui.library

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.narration.NarrationController
import com.example.bookshelf.narration.NarrationStatus
import com.example.bookshelf.ui.glass.GlassIconButton
import com.example.bookshelf.ui.glass.GlassSurface

@Composable
internal fun NarrationMiniPlayer(
    controller: NarrationController,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (!state.isActive || state.bookTitle.isBlank()) return

    val playing = state.status == NarrationStatus.PLAYING
    val swipeThreshold = 72.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = with(density) { swipeThreshold.toPx() }

    GlassSurface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "正在朗读：${state.bookTitle}" }
            .clickable(onClick = onOpen)
            .pointerInput(state.bookId, state.chapterIndex) {
                var dragDistance = 0f
                var chapterChanged = false
                detectDragGestures(
                    onDragEnd = {},
                    onDragCancel = {},
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragDistance += dragAmount.x
                        if (!chapterChanged && kotlin.math.abs(dragDistance) >= thresholdPx) {
                            chapterChanged = true
                            if (dragDistance < 0) controller.nextChapter() else controller.previousChapter()
                        }
                    },
                )
            },
        shape = MaterialTheme.shapes.large,
        shadow = true,
        specular = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(
                onClick = onOpen,
                contentDescription = "打开朗读界面",
                size = 42.dp,
            ) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null)
            }
            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = state.bookTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.chapterTitle.ifBlank { "正在准备朗读" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            GlassIconButton(
                onClick = {
                    if (playing) controller.pause() else controller.resume()
                },
                contentDescription = if (playing) "暂停朗读" else "继续朗读",
                size = 46.dp,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
            }
        }
    }
}
