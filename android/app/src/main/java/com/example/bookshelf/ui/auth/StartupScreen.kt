package com.example.bookshelf.ui.auth

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.example.bookshelf.ui.glass.glassPalette
import kotlin.math.cos
import kotlin.math.sin

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
            StaggeredReveal(index = 0) { BrandOrb() }
            StaggeredReveal(index = 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(26.dp))
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
            Spacer(Modifier.height(42.dp))
            when (state) {
                StartupState.Checking, StartupState.Connected -> {
                    StaggeredReveal(index = 2) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            GlassProgressRing()
                            Text(
                                "正在连接书架…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is StartupState.NeedsLogin -> {
                    StaggeredReveal(index = 2) {
                        GlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(
                                Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                Text(
                                    state.message ?: "需要连接服务器",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                GlassButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                                    Text("重试")
                                }
                                GlassButton(onClick = onEditServer, filled = false, modifier = Modifier.fillMaxWidth()) {
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

/** The pulsing liquid-glass brand orb: a slowly rotating light ring around a frosted "页" seal. */
@Composable
private fun BrandOrb(modifier: Modifier = Modifier, size: Dp = 108.dp) {
    val transition = rememberInfiniteTransition(label = "brand-orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 11_000, easing = LinearEasing)),
        label = "orb-ring-rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-pulse",
    )
    val palette = glassPalette()
    val ringBrush = Brush.sweepGradient(
        listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
            Color.Transparent,
        ),
    )
    Box(
        modifier = modifier.size(size).graphicsLayer { scaleX = pulse; scaleY = pulse },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .background(ringBrush, CircleShape),
        )
        GlassSurface(
            modifier = Modifier.fillMaxSize().padding(7.dp),
            shape = CircleShape,
            specular = true,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "页",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.graphicsLayer {
                        val t = rotation / 360f
                        val a = 2.0 * Math.PI * t
                        val dx = 1.5f * cos(a).toFloat()
                        val dy = 1.5f * sin(a).toFloat()
                        translationX = dx
                        translationY = dy
                    },
                )
            }
        }
    }
}

/** A glass progress ring that gently breathes while the connection is checked. */
@Composable
private fun GlassProgressRing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "progress-ring")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "progress-pulse",
    )
    Box(
        Modifier.size(64.dp).graphicsLayer { scaleX = pulse; scaleY = pulse },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.5.dp,
        )
        Box(
            Modifier.size(20.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
        )
    }
}
