package com.example.bookshelf.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A self-contained loading surface. It owns no repository or reader state, so its animation can
 * draw before the EPUB reader is created and remains isolated from all file/network operations.
 */
@Composable
internal fun ReaderLoadingScreen(
    message: String,
    detail: String,
    modifier: Modifier = Modifier,
    completed: Int = 0,
    total: Int = 0,
) {
    val transition = rememberInfiniteTransition(label = "reader-loading-gradient")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reader-loading-phase",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reader-loading-pulse",
    )
    val warm = lerp(Color(0xFFF2D38A), Color(0xFFF7E7B2), phase)
    val green = lerp(Color(0xFF78906A), Color(0xFFA8B99A), phase)
    val status = if (total > 0) "$message，加载进度 $completed / $total 章" else "$message，$detail"
    Box(
        modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(warm, lerp(warm, green, 0.48f + phase * 0.08f), green),
                ),
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = status
                stateDescription = "加载中"
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(32.dp),
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFFFFF9E8).copy(alpha = 0.88f),
            contentColor = Color(0xFF23382B),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(68.dp)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .background(Color(0xFF496D50).copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        color = Color(0xFF3F6748),
                        trackColor = Color(0xFFD7B968).copy(alpha = 0.48f),
                        strokeWidth = 4.dp,
                    )
                }
                Text(message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (total > 0) {
                    Text(
                        "加载进度 $completed / $total 章",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF496250),
                    )
                }
            }
        }
    }
}
