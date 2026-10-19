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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R

@Composable
fun UmbraScreen(
    state: MainViewModel.UiState,
    version: String,
    onLogsChanged: (Boolean) -> Unit,
    onHideIconChanged: (Boolean) -> Unit,
    onDeveloperClick: () -> Unit,
    readClipboardSettings: () -> Pair<Int, Int>?,
    saveClipboardSettings: (Int, Int) -> Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var initialLimit by rememberSaveable { mutableIntStateOf(10) }
    var initialDays by rememberSaveable { mutableIntStateOf(3) }
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
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
                HookCard(hook) {
                    readClipboardSettings()?.let { (limit, days) ->
                        initialLimit = limit
                        initialDays = days
                        editing = true
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (editing) {
        ClipboardSettingsDialog(initialLimit, initialDays, { editing = false }) { limit, days ->
            if (saveClipboardSettings(limit, days)) editing = false
        }
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
private fun HookCard(hook: HookItem, onClick: () -> Unit) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gboard_settings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(limit, { limit = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gboard_clipboard_limit_label)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalidLimit, supportingText = { if (invalidLimit) Text(stringResource(R.string.gboard_limit_invalid)) })
                OutlinedTextField(days, { days = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gboard_retention_days_label)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalidDays, supportingText = { if (invalidDays) Text(stringResource(R.string.gboard_days_invalid)) })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = {
                submitted = true
                val parsedLimit = limit.toIntOrNull()
                val parsedDays = days.toIntOrNull()
                if (parsedLimit != null && parsedLimit in 1..1000 && parsedDays != null && parsedDays in 1..3650) onSave(parsedLimit, parsedDays)
            }) { Text(stringResource(R.string.action_save)) }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun InactivePreview() {
    UmbraTheme { UmbraScreen(MainViewModel.UiState(), "3.2.0", {}, {}, {}, { 10 to 3 }, { _, _ -> true }) }
}
