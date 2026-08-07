package com.example.bookshelf.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * Reveals content with a soft glass entrance (fade + rise + scale). When several items share a
 * list, pass a monotonically increasing [index] to create a gentle staggered cascade.
 */
@Composable
fun StaggeredReveal(
    visible: Boolean = true,
    index: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember(index) { mutableStateOf(!visible) }
    LaunchedEffect(visible, index) {
        if (visible) {
            delay(minOf(index * 40L, 520L))
            shown = true
        }
    }
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = glassEnter(),
        exit = glassExit(),
    ) {
        content()
    }
}
