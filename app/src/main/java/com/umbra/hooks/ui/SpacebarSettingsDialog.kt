package com.umbra.hooks.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
internal fun SpacebarSettingsDialog(initial: SpacebarSettings, onDismiss: () -> Unit, onSave: (SpacebarSettings) -> Unit) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surfaceColorAtElevation(6.dp),
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(48.dp), shape = RoundedCornerShape(16.dp),
                    color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_spacebar), contentDescription = null, modifier = Modifier.size(26.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_gboard), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Text(stringResource(R.string.spacebar_icon_title), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .toggleable(enabled, role = Role.Switch, onValueChange = { enabled = it })
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.spacebar_icon_enable), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.spacebar_icon_description), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Switch(enabled, onCheckedChange = null)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.spacebar_preview), style = MaterialTheme.typography.labelLarge)
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        color = colors.surfaceColorAtElevation(1.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) {
                            if (enabled && (selected != SpacebarIcon.CUSTOM || bitmap != null)) {
                                SpacebarGlyph(selected, colors.onSurface, Modifier.size(22.dp), .6f, bitmap, customTint)
                            } else Text(stringResource(R.string.spacebar_preview_language), style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant)
                        }
                    }
                    Text(stringResource(R.string.spacebar_preview_details), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpacebarIcon.entries.filter { it != SpacebarIcon.CUSTOM }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { icon ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                        .selectable(icon == selected, enabled = !importing, role = Role.RadioButton,
                                            onClick = { selectedId = icon.id }).padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SpacebarIconSquare(icon, icon == selected)
                                    // Labels are outside the icon's bordered square.
                                    Text(stringResource(icon.labelRes), style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center, maxLines = 2, color = colors.onSurface)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .selectable(selected == SpacebarIcon.CUSTOM, enabled = !importing, role = Role.RadioButton,
                                onClick = { if (bitmap == null) chooseFile() else selectedId = SpacebarIcon.CUSTOM.id })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpacebarIconSquare(SpacebarIcon.CUSTOM, selected == SpacebarIcon.CUSTOM, bitmap, customTint)
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.spacebar_icon_custom), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.spacebar_file_hint), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                            TextButton(onClick = chooseFile, enabled = !importing, contentPadding = PaddingValues(0.dp)) {
                                if (importing) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(if (bitmap == null) R.string.spacebar_choose_file else R.string.spacebar_change_file))
                            }
                        }
                    }
                }
                importError?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
                if (selected == SpacebarIcon.CUSTOM && bitmap != null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .toggleable(customTint, role = Role.Switch, onValueChange = { customTint = it })
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.spacebar_custom_tint), Modifier.weight(1f).padding(end = 12.dp),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(customTint, onCheckedChange = null)
                    }
                }
                Text(stringResource(R.string.spacebar_compatibility_note), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(14.dp), color = colors.secondaryContainer.copy(alpha = .45f)) {
                    Text(stringResource(R.string.gboard_restart_note), modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            Button(
                onClick = { onSave(SpacebarSettings(enabled, selected, customImage, customTint)) },
                enabled = !importing && (!enabled || selected != SpacebarIcon.CUSTOM || bitmap != null),
                shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun SpacebarIconSquare(icon: SpacebarIcon, chosen: Boolean, bitmap: Bitmap? = null, tint: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val foreground = if (chosen) colors.onPrimaryContainer else colors.onSurface
    Surface(
        Modifier.size(72.dp), shape = RoundedCornerShape(16.dp),
        color = if (chosen) colors.primaryContainer else colors.surfaceColorAtElevation(1.dp),
        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != SpacebarIcon.CUSTOM || bitmap != null) {
                SpacebarGlyph(icon, foreground, Modifier.size(30.dp), customBitmap = bitmap, customTint = tint)
            } else {
                Canvas(Modifier.size(26.dp)) {
                    drawLine(foreground, Offset(size.width / 2, size.height * .2f), Offset(size.width / 2, size.height * .8f), 2.dp.toPx())
                    drawLine(foreground, Offset(size.width * .2f, size.height / 2), Offset(size.width * .8f, size.height / 2), 2.dp.toPx())
                }
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
