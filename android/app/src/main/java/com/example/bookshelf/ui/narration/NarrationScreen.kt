@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.bookshelf.ui.narration

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.narration.NarrationController
import com.example.bookshelf.narration.NarrationStatus
import com.example.bookshelf.ui.glass.GlassIconButton
import com.example.bookshelf.ui.glass.GlassSurface
import com.example.bookshelf.ui.glass.FloatingCapsuleContainer
import com.example.bookshelf.ui.glass.GlassTopBar
import com.example.bookshelf.ui.glass.LiquidBackdrop
import com.example.bookshelf.ui.glass.StaggeredReveal

@Composable
fun NarrationScreen(
    controller: NarrationController,
    bookId: String,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val prepared by controller.prepared.collectAsStateWithLifecycle()
    val isThisBook = state.bookId == bookId
    val active = isThisBook && state.isActive
    val bookTitle = state.bookTitle.takeIf { isThisBook && it.isNotBlank() }
        ?: prepared?.takeIf { it.bookId == bookId }?.bookTitle
        ?: "听书模式"
    val chapterTitle = state.chapterTitle.takeIf { isThisBook && it.isNotBlank() }
        ?: prepared?.takeIf { it.bookId == bookId }?.chapterTitle.orEmpty()

    val playing = active && state.status == NarrationStatus.PLAYING

    LiquidBackdrop(Modifier.fillMaxSize()) {
        FloatingCapsuleContainer(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            topBar = {
                GlassTopBar(
                    title = "听书模式",
                    subtitle = "端侧朗读 · 不上传正文",
                    onBack = onBack,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            StaggeredReveal(index = 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    NarrationDisc(playing = playing)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        bookTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                    Text(
                        buildString {
                            append(chapterTitle.ifBlank { "等待开始朗读" })
                            if (isThisBook && state.chapterCount > 0) {
                                append("  ·  ")
                                append(state.chapterIndex + 1)
                                append(" / ")
                                append(state.chapterCount)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 28.dp, end = 28.dp),
                    )
                }
            }

            StaggeredReveal(index = 1) {
                GlassSurface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.large,
                    shadow = true,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(136.dp).padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isThisBook && state.status == NarrationStatus.PREPARING -> Text(
                                "正在准备语音…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            isThisBook && state.status == NarrationStatus.PLAYING -> Text(
                                state.currentText,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            isThisBook && state.status == NarrationStatus.ERROR -> Text(
                                state.error ?: "朗读失败",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            isThisBook && state.status == NarrationStatus.COMPLETED -> Text("本书已朗读完毕")
                            else -> Text("从阅读页的当前位置开始")
                        }
                    }
                }
            }

            StaggeredReveal(index = 2) {
                GlassSurface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    shadow = true,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIconButton(
                            onClick = controller::previousChapter,
                            enabled = active && state.status != NarrationStatus.PREPARING && state.chapterIndex > 0,
                            contentDescription = "上一章",
                            size = 52.dp,
                        ) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = null)
                        }
                        NarrationPlayButton(
                            playing = playing,
                            active = active,
                            prepared = prepared?.bookId == bookId,
                            preparing = state.status == NarrationStatus.PREPARING,
                            onToggle = {
                                if (active && state.status == NarrationStatus.PLAYING) controller.pause()
                                else if (active && state.status == NarrationStatus.PAUSED) controller.resume()
                                else controller.startPrepared()
                            },
                        )
                        GlassIconButton(
                            onClick = controller::nextChapter,
                            enabled = active && state.status != NarrationStatus.PREPARING &&
                                state.chapterCount > 0 && state.chapterIndex < state.chapterCount - 1,
                            contentDescription = "下一章",
                            size = 52.dp,
                        ) {
                            Icon(Icons.Filled.SkipNext, contentDescription = null)
                        }
                        GlassIconButton(
                            onClick = controller::stop,
                            enabled = active,
                            contentDescription = "停止朗读",
                            size = 52.dp,
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                        }
                    }
                }
            }

            StaggeredReveal(index = 3) {
                GlassSurface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    shadow = true,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        NarrationSpeedControls(
                            playbackSpeed = state.playbackSpeed,
                            onPlaybackSpeedChange = controller::setPlaybackSpeed,
                        )
                        HorizontalDivider()
                        NarrationSleepTimerControl(
                            timer = state.sleepTimer.takeIf { isThisBook },
                            endsAtElapsedRealtimeMs = state.sleepTimerEndsAtElapsedRealtimeMs.takeIf { isThisBook },
                            enabled = active,
                            onTimerChange = controller::setSleepTimer,
                        )
                    }
                }
            }

            StaggeredReveal(index = 4) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "朗读引擎与内置女声均在本机运行，朗读时不上传正文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onBack, enabled = isThisBook) {
                        Text("查看朗读正文")
                    }
                }
            }
        }
        }
    }
}

/** A breathing glass record that spins gently while narration is playing. */
@Composable
private fun NarrationDisc(playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "narration-disc")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (playing) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (playing) 14_000 else 90_000, easing = LinearEasing),
        ),
        label = "disc-rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "disc-pulse",
    )
    Box(
        modifier.size(148.dp).graphicsLayer { scaleX = pulse; scaleY = pulse },
        contentAlignment = Alignment.Center,
    ) {
        // Halo ring
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    alpha = 0.5f
                }
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        GlassSurface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = CircleShape,
            specular = true,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer { rotationZ = rotation }
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                ),
                            ),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NarrationPlayButton(
    playing: Boolean,
    active: Boolean,
    prepared: Boolean,
    preparing: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val transition = rememberInfiniteTransition(label = "play-glow")
    val glow by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "play-glow",
    )
    val enabled = !preparing && (active || prepared)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(88.dp)
                .graphicsLayer { scaleX = glow; scaleY = glow; alpha = if (playing) 0.5f else 0.32f }
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), CircleShape),
        )
        GlassSurface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            specular = true,
            shadow = true,
            interactionSource = interaction,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onToggle,
                        enabled = enabled,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停朗读" else "开始朗读",
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(38.dp).clip(CircleShape),
                )
            }
        }
    }
}
