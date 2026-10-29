package com.umbra.hooks.ui

import android.graphics.RectF
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R
import com.umbra.hooks.utils.SpacebarIcon
import com.umbra.hooks.utils.SpacebarIconRenderer
import com.umbra.hooks.utils.SpacebarSettings

@Composable
internal fun SpacebarSettingsDialog(initial: SpacebarSettings, onDismiss: () -> Unit, onSave: (SpacebarSettings) -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var selectedId by rememberSaveable { mutableIntStateOf(initial.icon.id) }
    val selected = SpacebarIcon.fromId(selectedId)
    val colors = MaterialTheme.colorScheme
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
                            if (enabled) SpacebarGlyph(selected, colors.onSurface, Modifier.size(22.dp), .6f)
                            else Text(stringResource(R.string.spacebar_preview_language), style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant)
                        }
                    }
                    Text(stringResource(R.string.spacebar_preview_details), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpacebarIcon.entries.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { icon ->
                                val chosen = icon == selected
                                val shape = RoundedCornerShape(16.dp)
                                Surface(
                                    modifier = Modifier.weight(1f).clip(shape)
                                        .selectable(chosen, role = Role.RadioButton, onClick = { selectedId = icon.id }),
                                    shape = shape,
                                    color = if (chosen) colors.primaryContainer else colors.surfaceColorAtElevation(1.dp),
                                    contentColor = if (chosen) colors.onPrimaryContainer else colors.onSurface,
                                    border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant),
                                ) {
                                    Column(Modifier.heightIn(min = 92.dp).padding(horizontal = 4.dp, vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SpacebarGlyph(icon, LocalContentColor.current, Modifier.size(26.dp))
                                        Text(stringResource(icon.labelRes), style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Center, maxLines = 2)
                                    }
                                }
                            }
                        }
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
            Button(onClick = { onSave(SpacebarSettings(enabled, selected)) }, shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun SpacebarGlyph(icon: SpacebarIcon, tint: Color, modifier: Modifier, opacity: Float = 1f) {
    val renderer = remember(icon) { SpacebarIconRenderer(icon) }
    val argb = tint.toArgb()
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, RectF(0f, 0f, size.width, size.height), argb, opacity)
        }
    }
}
