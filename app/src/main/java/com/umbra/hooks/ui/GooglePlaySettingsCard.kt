package com.umbra.hooks.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R
import com.umbra.hooks.billing.BillingAccountProvider as Billing

@Composable
internal fun GooglePlaySettingsContent(serviceActive: Boolean) {
    val context = LocalContext.current
    val prefs = remember(context) { Billing.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = remember(revision) { prefs.getBoolean(Billing.ENABLED, false) }
    val selected = remember(revision) { prefs.getString(Billing.SELECTED, null) }
    val accounts = remember(revision) { prefs.getStringSet(Billing.ACCOUNTS, emptySet()).orEmpty().sorted() }
    val status = remember(revision) { prefs.getString(Billing.STATUS, null) }
    val version = remember(revision) { prefs.getString(Billing.VERSION, null) }
    val colors = MaterialTheme.colorScheme

    val canChoose = enabled && serviceActive

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.billing_enable), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = {
                    prefs.edit().putBoolean(Billing.ENABLED, it).apply()
                }, enabled = serviceActive || enabled)
            }
            if (!serviceActive) Text(stringResource(R.string.billing_service_off),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
            Text(stringResource(R.string.billing_accounts), style = MaterialTheme.typography.labelLarge)
            if (!enabled) Text(stringResource(R.string.billing_enable_to_choose),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                Column(Modifier.alpha(if (canChoose) 1f else .45f)) {
                    BillingAccountRow(stringResource(R.string.billing_default), selected == null, canChoose) {
                        prefs.edit().remove(Billing.SELECTED).apply()
                    }
                    accounts.forEach { account ->
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                        BillingAccountRow(account, selected == account, canChoose) {
                            prefs.edit().putString(Billing.SELECTED, account).apply()
                        }
                    }
                }
            }
            if (accounts.isEmpty()) Text(stringResource(R.string.billing_empty),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (selected != null && selected !in accounts) Text(stringResource(R.string.billing_missing),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
            Text(stringResource(R.string.billing_status_title), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(when (status) {
                "ready" -> R.string.billing_ready
                "unsupported" -> R.string.billing_unsupported
                else -> R.string.billing_pending
            }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (version != null) Text(stringResource(R.string.billing_version, version),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(R.string.billing_verify), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    prefs.edit().remove(Billing.SELECTED).putBoolean(Billing.ENABLED, false).apply()
                }, enabled = enabled || selected != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.billing_reset)) }
                Button(onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(Billing.STORE)
                    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                        Toast.makeText(context, R.string.billing_store_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.billing_open_store)) }
            }
    }
}

@Composable
private fun BillingAccountRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The list parent dims both labels and radios uniformly.
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
