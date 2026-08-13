@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.example.bookshelf.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/**
 * A floating Liquid Glass capsule top bar. The capsule sits BELOW the system status bar (the
 * inset is applied outside the glass), so the bar's background never covers the system top
 * bar. Place it over scrollable content to let content pass underneath the translucent
 * material.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(30.dp),
        shadow = true,
        material = GlassMaterial.ReaderChrome,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassIconButton(
                    onClick = onBack,
                    contentDescription = "返回",
                    size = 40.dp,
                    tint = MaterialTheme.colorScheme.onSurface,
                    icon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) },
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) 8.dp else 14.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() }
        }
    }
}

/** A glass action button with a tactile spring press. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressProgress = rememberPressProgress(interaction)
    val scale = 1f - pressProgress * 0.018f
    val pressOffset = with(LocalDensity.current) { 1.dp.toPx() }
    GlassSurface(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = pressOffset * pressProgress
        },
        shape = RoundedCornerShape(18.dp),
        tint = if (filled) MaterialTheme.colorScheme.primary else null,
        tintOpacity = if (filled) 0.14f + pressProgress * 0.04f else null,
        interactionSource = interaction,
        enabled = enabled,
        shadow = false,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            border = null,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/** A circular glass icon button with a tactile spring press. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    glassTint: Color? = null,
    glassTintOpacity: Float? = null,
    performance: GlassPerformance = GlassPerformance.Auto,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressProgress = rememberPressProgress(interaction)
    val scale = 1f - pressProgress * 0.018f
    val pressOffset = with(LocalDensity.current) { 1.dp.toPx() }
    GlassSurface(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = pressOffset * pressProgress
            },
        shape = CircleShape,
        tint = glassTint,
        tintOpacity = glassTintOpacity,
        specular = true,
        shadow = false,
        interactionProgress = pressProgress,
        performance = performance,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        ) {
            CompositionLocalProvider(LocalContentColor provides tint) { icon() }
        }
    }
}

/** A rounded glass filter chip. */
@Composable
fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val palette = glassPalette()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else palette.fill,
            labelColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            iconColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = palette.strokeStrong,
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            disabledSelectedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

/** A frosted glass text field. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    performance: GlassPerformance = GlassPerformance.Auto,
) {
    val shape = RoundedCornerShape(20.dp)
    GlassSurface(
        modifier = modifier,
        shape = shape,
        material = GlassMaterial.ReaderChrome,
        shadow = false,
        performance = performance,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier,
                ),
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            singleLine = singleLine,
            isError = isError,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            shape = shape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                unfocusedBorderColor = Color.Transparent,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** A glass dialog on a dimmed backdrop. */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.Standard,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismissRequest) {
        GlassSurface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            material = material,
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                title?.invoke()
                text?.invoke()
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}

/** A frosted modal bottom sheet. */
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    material: GlassMaterial = GlassMaterial.Standard,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = MaterialTheme.colorScheme.scrim,
        shape = shape,
        dragHandle = null,
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            material = material,
            borderWidth = 1.1.dp,
            shadow = false,
            sampleMotionOffset = sheetState::requireOffset,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 12.dp)
                        .size(width = 44.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                content()
            }
        }
    }
}

/** A small glass section title with a leading accent dot. */
@Composable
fun GlassSectionTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Convenience icon helper that avoids importing the Icons factory everywhere. */
@Composable
fun GlassIcon(imageVector: ImageVector, contentDescription: String?, tint: Color = androidx.compose.material3.LocalContentColor.current, modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(imageVector = imageVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/**
 * A scaffold that floats a [GlassTopBar]-style capsule over scrollable content. The content
 * is inset below the capsule and scrolls behind it, so the translucent material composites
 * the moving content every frame — the Liquid Glass "responds to scroll" behaviour.
 */
@Composable
fun FloatingCapsuleContainer(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 8.dp,
    topBar: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    var barHeightPx by remember {
        mutableIntStateOf((statusTop + with(density) { 64.dp.toPx() }).roundToInt())
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = with(density) { barHeightPx.toDp() } + contentPadding),
        ) {
            content()
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { barHeightPx = it.size.height },
        ) {
            topBar()
        }
    }
}
