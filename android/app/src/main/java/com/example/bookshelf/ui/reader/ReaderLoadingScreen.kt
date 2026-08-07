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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bookshelf.ui.glass.GlassSurface
import com.example.bookshelf.ui.glass.LiquidBackdrop

/**
 * A self-contained glass loading surface. It owns no repository or reader state, so its animation
 * can draw before the EPUB reader is created and remains isolated from all file/network operations.
 */
@Composable
internal fun ReaderLoadingScreen(
    message: String,
    detail: String,
    modifier: Modifier = Modifier,
    completed: Int = 0,
    total: Int = 0,
) {
    val transition = rememberInfiniteTransition(label = "reader-loading")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reader-loading-pulse",
    )
    val status = if (total > 0) "$message，加载进度 $completed / $total 章" else "$message，$detail"
    LiquidBackdrop(
        modifier = modifier
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
    ) {
        GlassSurface(
            modifier = Modifier.padding(32.dp),
            shape = MaterialTheme.shapes.extraLarge,
            shadow = true,
        ) {
            Column(
                Modifier.padding(horizontal = 36.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(76.dp).graphicsLayer { scaleX = pulse; scaleY = pulse },
                    contentAlignment = Alignment.Center,
                ) {
                    GlassSurface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        specular = true,
                        shadow = false,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize().padding(5.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                Text(message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (total > 0) {
                    Text(
                        "加载进度 $completed / $total 章",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
