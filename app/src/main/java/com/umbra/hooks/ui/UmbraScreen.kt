package com.umbra.hooks.ui

import android.animation.ArgbEvaluator
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R
import com.umbra.hooks.utils.SpacebarIcon
import com.umbra.hooks.utils.SpacebarSettings

@Composable
fun UmbraScreen(
    state: MainViewModel.UiState,
    version: String,
    onLogsChanged: (Boolean) -> Unit,
    onHideIconChanged: (Boolean) -> Unit,
    onDeveloperClick: () -> Unit,
    readClipboardSettings: () -> Pair<Int, Int>?,
    saveClipboardSettings: (Int, Int) -> Boolean,
    readSpacebarSettings: () -> SpacebarSettings? = { null },
    saveSpacebarSettings: (SpacebarSettings) -> Boolean = { false },
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var initialLimit by rememberSaveable { mutableIntStateOf(10) }
    var initialDays by rememberSaveable { mutableIntStateOf(3) }
    var editingSpacebar by rememberSaveable { mutableStateOf(false) }
    var initialSpacebarEnabled by rememberSaveable { mutableStateOf(false) }
    var initialSpacebarIcon by rememberSaveable { mutableIntStateOf(SpacebarIcon.GOOGLE.id) }
    var initialSpacebarCustomImage by rememberSaveable { mutableStateOf<String?>(null) }
    var initialSpacebarCustomTint by rememberSaveable { mutableStateOf(false) }
    val expansion = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        val target = if (expanded) 1f else 0f
        // Match the original cancellable ValueAnimator, including reversal mid-flight.
        val duration = (400 * abs(target - expansion.value)).toInt().coerceAtLeast(50)
        expansion.animateTo(target, tween(duration, easing = CubicBezierEasing(.3f, 0f, .1f, 1f)))
    }
    val fraction = expansion.value
    val progress = remember(expansion) { { expansion.value } }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            Column(Modifier.padding(vertical = 24.dp)) {
                val appName = stringResource(R.string.app_name)
                val titleAccent = MaterialTheme.colorScheme.primary
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = titleAccent)) {
                            append(appName.take(1))
                        }
                        append(appName.drop(1))
                    },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                )
                Text(stringResource(R.string.title_lsposed_module), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusCard(state, version, expanded, progress, { expanded = !expanded }, onDeveloperClick)
            if (expanded || fraction > 0f) {
                RevealHeight(progress, slideFromBehind = true,
                    modifier = Modifier.testTag("module_settings").graphicsLayer { alpha = .3f + .7f * progress() }) {
                    Card(
                        modifier = Modifier.padding(top = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        SettingRow(R.string.title_lsp_logs, R.string.desc_enable_logs, state.logsEnabled,
                            state.isServiceActive, onLogsChanged)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                        SettingRow(R.string.title_hide_icon, R.string.desc_hide_icon, state.isIconHidden,
                            true, onHideIconChanged)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HookRegistry.getHooks().forEach { hook ->
                HookCard(
                    hook,
                    onClick = {
                        readClipboardSettings()?.let { (limit, days) ->
                            initialLimit = limit
                            initialDays = days
                            editing = true
                        }
                    },
                    onSpacebarClick = {
                        readSpacebarSettings()?.let { settings ->
                            initialSpacebarEnabled = settings.enabled
                            initialSpacebarIcon = settings.icon.id
                            initialSpacebarCustomImage = settings.customImage
                            initialSpacebarCustomTint = settings.customTint
                            editingSpacebar = true
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (editing) {
        ClipboardSettingsDialog(initialLimit, initialDays, { editing = false }) { limit, days ->
            if (saveClipboardSettings(limit, days)) editing = false
        }
    }
    if (editingSpacebar) {
        SpacebarSettingsDialog(
            initial = SpacebarSettings(initialSpacebarEnabled, SpacebarIcon.fromId(initialSpacebarIcon),
                initialSpacebarCustomImage, initialSpacebarCustomTint),
            onDismiss = { editingSpacebar = false },
            onSave = { if (saveSpacebarSettings(it)) editingSpacebar = false },
        )
    }
}

@Composable
private fun StatusCard(
    state: MainViewModel.UiState,
    version: String,
    expanded: Boolean,
    progress: () -> Float,
    onClick: () -> Unit,
    onDeveloperClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val activeFraction by animateFloatAsState(
        if (state.isServiceActive) 1f else 0f,
        tween(250, easing = Easing { ((cos((it + 1f) * PI) / 2.0) + .5).toFloat() }),
        label = "status activation",
    )
    val evaluator = remember { ArgbEvaluator() }
    val background = Color(evaluator.evaluate(activeFraction, colors.errorContainer.toArgb(), colors.primaryContainer.toArgb()) as Int)
    val foreground = Color(evaluator.evaluate(activeFraction, colors.onErrorContainer.toArgb(), colors.onPrimaryContainer.toArgb()) as Int)
    val expansionLabel = stringResource(if (expanded) R.string.status_expanded else R.string.status_collapsed)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().zIndex(1f).testTag("module_status").semantics { stateDescription = expansionLabel },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(if (state.isServiceActive) R.string.title_module_activated else R.string.title_module_inactive),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.isServiceActive) stringResource(R.string.desc_module_running_format,
                        state.frameworkName ?: "Unknown", state.frameworkVersion ?: "Unknown",
                        state.frameworkVersionCode?.takeIf { it > 0 }?.toString() ?: "Unknown")
                    else stringResource(R.string.desc_module_enable_reboot),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (expanded || progress() > 0f) {
                    RevealHeight(progress, modifier = Modifier.testTag("module_details")) {
                        Column(Modifier.padding(top = 16.dp).alpha(.9f)) {
                            Text(stringResource(R.string.developer_info),
                                modifier = Modifier.clickable(role = Role.Button, onClick = onDeveloperClick),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.app_version_format, version), Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // A decorative watermark must not impose its 100dp height on the card.
            // Otherwise the first part of the details reveal is swallowed by that minimum.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 24.dp, y = 24.dp)
                    .wrapContentSize(Alignment.BottomEnd, unbounded = true)) {
                    StatusWatermark(state.isServiceActive, foreground,
                        Modifier.requiredSize(100.dp).testTag("status_watermark").alpha(.6f))
                }
            }
        }
    }
}

/** One measured height and one shared progress value, as in the original View animator.
 * Details reveal without fading; settings slide out from behind the higher status card.
 */
@Composable
private fun RevealHeight(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    slideFromBehind: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(
        content = { Box { content() } },
        modifier = if (slideFromBehind) modifier else modifier.clipToBounds(),
    ) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val progress = fraction().coerceIn(0f, 1f)
        val revealed = (child.height * progress).roundToInt()
        layout(child.width, constraints.constrainHeight(revealed)) {
            val offset = if (slideFromBehind) -(child.height * (1f - progress)).roundToInt() else 0
            child.placeRelative(0, offset)
        }
    }
}

/** Draw and morph the original cross/check paths without embedding a View. */
@Composable
private fun StatusWatermark(active: Boolean, color: Color, modifier: Modifier) {
    val fraction by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "status symbol")
    Canvas(modifier) {
        val scale = size.minDimension / 100f
        drawCircle(color, radius = 42f * scale, center = Offset(50f * scale, 50f * scale), style = Stroke(8f * scale))
        val off = listOf(Offset(35f,35f), Offset(65f,65f), Offset(50f,50f), Offset(65f,35f), Offset(35f,65f))
        val on = listOf(Offset(28f,52f), Offset(43f,65f), Offset(43f,65f), Offset(72f,34f), Offset(43f,65f))
        val path = Path()
        off.zip(on).forEachIndexed { index, (a, b) ->
            val p = (a + (b - a) * fraction) * scale
            if (index == 0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)
        }
        drawPath(path, color, style = Stroke(8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun SettingRow(title: Int, description: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun HookCard(hook: HookItem, onClick: () -> Unit, onSpacebarClick: () -> Unit) {
    val hint = stringResource(R.string.gboard_customize_hint)
    Card(
        Modifier.fillMaxWidth().then(if (hook.settingsAvailable) Modifier.clickable(role = Role.Button, onClickLabel = hint, onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(hook.titleResId), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (hook.settingsAvailable) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(hook.descriptionResId), Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hook.settingsAvailable) {
                HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                TextButton(onClick = onSpacebarClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_spacebar), contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.spacebar_icon_title), modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun ClipboardSettingsDialog(initialLimit: Int, initialDays: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var limit by rememberSaveable { mutableStateOf(initialLimit.toString()) }
    var days by rememberSaveable { mutableStateOf(initialDays.toString()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val invalidLimit = submitted && limit.toIntOrNull()?.let { it in 1..1000 } != true
    val invalidDays = submitted && days.toIntOrNull()?.let { it in 1..3650 } != true
    val focusManager = LocalFocusManager.current
    val save = {
        submitted = true
        val parsedLimit = limit.toIntOrNull()
        val parsedDays = days.toIntOrNull()
        if (parsedLimit != null && parsedLimit in 1..1000 && parsedDays != null && parsedDays in 1..3650) {
            focusManager.clearFocus()
            onSave(parsedLimit, parsedDays)
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_clipboard), contentDescription = null, modifier = Modifier.size(26.dp))
                    }
                }
                Text(stringResource(R.string.gboard_settings_title), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.gboard_settings_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { limit = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.gboard_clipboard_limit_label)) },
                        suffix = { Text(stringResource(R.string.gboard_entries_unit)) },
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        isError = invalidLimit,
                        supportingText = { Text(stringResource(if (invalidLimit) R.string.gboard_limit_invalid else R.string.gboard_limit_range)) },
                    )
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.gboard_retention_days_label)) },
                        suffix = { Text(stringResource(R.string.gboard_days_unit)) },
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        isError = invalidDays,
                        supportingText = { Text(stringResource(if (invalidDays) R.string.gboard_days_invalid else R.string.gboard_days_range)) },
                    )
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)) {
                    Text(stringResource(R.string.gboard_restart_note), modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            Button(onClick = save, shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun InactivePreview() {
    UmbraTheme { UmbraScreen(MainViewModel.UiState(), "3.6.0", {}, {}, {}, { 10 to 3 }, { _, _ -> true }) }
}
