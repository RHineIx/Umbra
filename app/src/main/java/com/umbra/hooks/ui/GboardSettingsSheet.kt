package com.umbra.hooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R
import kotlinx.coroutines.launch

/** Compact bottom sheets share one scrollable body and optional, fixed save actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GboardSettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 560.dp,
        containerColor = colors.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(title, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            if (onSave != null) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { scope.launch { sheetState.hide(); onDismiss() } },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(onClick = onSave, modifier = Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GboardRestartNote() {
    Text(stringResource(R.string.gboard_restart_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun GboardSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    description: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onCheckedChange = null)
    }
}
