package com.umbra.hooks.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umbra.hooks.R
import com.umbra.hooks.billing.BillingAccountProvider as Billing

@Composable
fun GooglePlaySettingsCard(serviceActive: Boolean) {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.billing_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.billing_summary), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
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
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                Column {
                    BillingAccountRow(stringResource(R.string.billing_default), selected == null, true) {
                        prefs.edit().remove(Billing.SELECTED).apply()
                    }
                    accounts.forEach { account ->
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                        BillingAccountRow(account, selected == account, enabled && serviceActive) {
                            prefs.edit().putString(Billing.SELECTED, account).apply()
                        }
                    }
                }
            }
            if (accounts.isEmpty()) Text(stringResource(R.string.billing_empty),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (selected != null && selected !in accounts) Text(stringResource(R.string.billing_missing),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
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
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.billing_reset)) }
                Button(onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(Billing.STORE)
                    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                        Toast.makeText(context, R.string.billing_store_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.billing_open_store)) }
            }
        }
    }
}

@Composable
private fun BillingAccountRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
