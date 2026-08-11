package com.example.bookshelf.ui.auth

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bookshelf.ui.StartupState
import com.example.bookshelf.ui.glass.GlassButton
import com.example.bookshelf.ui.glass.GlassSurface
import com.example.bookshelf.ui.glass.LiquidBackdrop
import com.example.bookshelf.ui.glass.StaggeredReveal

@Composable
fun StartupScreen(state: StartupState, onRetry: () -> Unit, onEditServer: () -> Unit) {
    LiquidBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StaggeredReveal(index = 0) { BrandMedallion() }
            StaggeredReveal(index = 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "页架",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Page Shelf · 你的私人书库",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(44.dp))
            when (state) {
                StartupState.Checking, StartupState.Connected -> {
                    StaggeredReveal(index = 2) {
                        GlassSurface(
                            shape = MaterialTheme.shapes.extraLarge,
                            shadow = false,
                            specular = true,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Text(
                                    "正在连接书架…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                is StartupState.NeedsLogin -> {
                    StaggeredReveal(index = 2) {
                        GlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            shadow = true,
                        ) {
                            Column(
                                Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    state.message ?: "需要连接服务器",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                GlassButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                    Text("重试")
                                }
                                GlassButton(onClick = onEditServer, filled = false, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                    Text("修改服务器设置")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A quiet book medallion: a frosted seal ringed by a thin, warm light — one gentle breath. */
@Composable
private fun BrandMedallion(modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val transition = rememberInfiniteTransition(label = "medallion")
    val pulse by transition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "medallion-pulse",
    )
    Box(
        modifier = modifier.size(size).graphicsLayer { scaleX = pulse; scaleY = pulse },
        contentAlignment = Alignment.Center,
    ) {
        // Thin static ring using the backend forest and live-status greens.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.sweepGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        ),
                    ),
                    CircleShape,
                ),
        )
        GlassSurface(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            shape = CircleShape,
            specular = true,
            shadow = true,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "页",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
