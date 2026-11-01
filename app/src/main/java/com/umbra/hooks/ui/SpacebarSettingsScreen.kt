package com.umbra.hooks.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R
import com.umbra.hooks.utils.CustomSpacebarIcon
import com.umbra.hooks.utils.IconImportException
import com.umbra.hooks.utils.SpacebarIcon
import com.umbra.hooks.utils.SpacebarIconRenderer
import com.umbra.hooks.utils.SpacebarSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SpacebarSettingsScreen(initial: SpacebarSettings, onDismiss: () -> Unit, onSave: (SpacebarSettings) -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var selectedId by rememberSaveable { mutableIntStateOf(initial.icon.id) }
    var customImage by rememberSaveable { mutableStateOf(initial.customImage) }
    var customTint by rememberSaveable { mutableStateOf(initial.customTint) }
    var importError by rememberSaveable { mutableStateOf<Int?>(null) }
    var importing by remember { mutableStateOf(false) }
    val selected = SpacebarIcon.fromId(selectedId)
    val bitmap = remember(customImage) { CustomSpacebarIcon.decodeStored(customImage) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            importError = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(CustomSpacebarIcon::importIcon) ?: throw IOException()
                }
                customImage = imported.image
                customTint = imported.tint
                selectedId = SpacebarIcon.CUSTOM.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: IconImportException) {
                importError = e.messageRes
            } catch (_: Exception) {
                importError = R.string.spacebar_file_unreadable
            } finally {
                importing = false
            }
        }
    }
    val chooseFile = { picker.launch(arrayOf("image/svg+xml", "image/png")) }
    var choosingIcon by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.title_gboard), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Text(stringResource(R.string.spacebar_icon_title), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { onSave(SpacebarSettings(enabled, selected, customImage, customTint)) },
                        enabled = !importing && (!enabled || selected != SpacebarIcon.CUSTOM || bitmap != null),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SpacebarPreviewCard(selected, enabled, bitmap, customTint)
            GboardSwitchRow(
                title = stringResource(R.string.spacebar_icon_enable),
                description = stringResource(R.string.spacebar_icon_description),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
            Surface(onClick = { choosingIcon = true }, enabled = !importing,
                shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SpacebarIconThumbnail(selected, bitmap, customTint)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.spacebar_icon_style), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Text(stringResource(selected.labelRes), style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                        modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
            if (importing && selected != SpacebarIcon.CUSTOM) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (selected == SpacebarIcon.CUSTOM) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.spacebar_file_hint), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                    OutlinedButton(onClick = chooseFile, enabled = !importing, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)) {
                        if (importing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (bitmap == null) R.string.spacebar_choose_file else R.string.spacebar_change_file))
                    }
                }
                if (bitmap != null) {
                    GboardSwitchRow(title = stringResource(R.string.spacebar_custom_tint), checked = customTint,
                        onCheckedChange = { customTint = it })
                }
            }
            importError?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GboardRestartNote()
                Text(stringResource(R.string.spacebar_compatibility_note), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
    }
    if (choosingIcon) {
        SpacebarIconPickerSheet(selected, bitmap, customTint, importing,
            onDismiss = { choosingIcon = false },
            onChoose = { icon ->
                if (icon == SpacebarIcon.CUSTOM && bitmap == null) chooseFile() else {
                    selectedId = icon.id
                    importError = null
                }
            })
    }
}

@Composable
private fun SpacebarPreviewCard(icon: SpacebarIcon, enabled: Boolean, bitmap: Bitmap?, customTint: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.spacebar_preview), style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .6f))) {
                Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) {
                    if (enabled && (icon != SpacebarIcon.CUSTOM || bitmap != null)) {
                        SpacebarGlyph(icon, colors.onSurface, Modifier.size(22.dp), .6f, bitmap, customTint)
                    } else Text(stringResource(R.string.spacebar_preview_language),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpacebarIconPickerSheet(selected: SpacebarIcon, bitmap: Bitmap?, customTint: Boolean,
    importing: Boolean, onDismiss: () -> Unit, onChoose: (SpacebarIcon) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var closing by remember { mutableStateOf(false) }
    GboardSettingsSheet(title = stringResource(R.string.spacebar_icon_section), onDismiss = onDismiss,
        sheetState = sheetState) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpacebarIcon.entries.forEach { icon ->
                val chosen = selected == icon
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .selectable(chosen, enabled = !importing && !closing, role = Role.RadioButton, onClick = {
                            closing = true
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                                onChoose(icon)
                            }
                        }),
                    shape = RoundedCornerShape(20.dp),
                    color = if (chosen) colors.primaryContainer else colors.surfaceContainerHigh,
                    contentColor = if (chosen) colors.onPrimaryContainer else colors.onSurface,
                ) {
                    Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RadioButton(selected = chosen, onClick = null, enabled = !importing && !closing)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(icon.labelRes), style = MaterialTheme.typography.titleSmall)
                            if (icon == SpacebarIcon.CUSTOM) Text(stringResource(R.string.spacebar_file_hint),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        SpacebarIconThumbnail(icon, bitmap, customTint, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpacebarIconThumbnail(icon: SpacebarIcon, bitmap: Bitmap?, customTint: Boolean,
    modifier: Modifier = Modifier) {
    val foreground = LocalContentColor.current
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (icon != SpacebarIcon.CUSTOM || bitmap != null) {
            SpacebarGlyph(icon, foreground, Modifier.size(26.dp), customBitmap = bitmap, customTint = customTint)
        } else {
            Canvas(Modifier.size(22.dp)) {
                drawLine(foreground, Offset(size.width / 2, size.height * .2f),
                    Offset(size.width / 2, size.height * .8f), 2.dp.toPx())
                drawLine(foreground, Offset(size.width * .2f, size.height / 2),
                    Offset(size.width * .8f, size.height / 2), 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun SpacebarGlyph(icon: SpacebarIcon, tint: Color, modifier: Modifier, opacity: Float = 1f,
                          customBitmap: Bitmap? = null, customTint: Boolean = false) {
    val renderer = remember(icon, customBitmap, customTint) { SpacebarIconRenderer(icon, customBitmap, customTint) }
    val argb = tint.toArgb()
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, RectF(0f, 0f, size.width, size.height), argb, opacity)
        }
    }
}
