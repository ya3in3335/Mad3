package com.samtex.eidreader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samtex.eidreader.BuildConfig
import com.samtex.eidreader.R
import com.samtex.eidreader.data.trust.ImportResult
import com.samtex.eidreader.ui.components.SamtexTopBar
import com.samtex.eidreader.ui.components.SectionCard
import com.samtex.eidreader.ui.components.SectionTitle
import com.samtex.eidreader.ui.theme.MonoStyle
import com.samtex.eidreader.ui.theme.SamtexColors
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.historyItems.collectAsStateWithLifecycle()
    val openReport by viewModel.openReport.collectAsStateWithLifecycle()
    val anchors by viewModel.anchors.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCertificate)
    }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Scaffold(
        topBar = { SamtexTopBar(stringResource(R.string.settings_title), onBack) },
        containerColor = SamtexColors.Background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.settings_privacy))
                    SwitchRow(
                        stringResource(R.string.settings_history_consent),
                        stringResource(R.string.settings_history_consent_desc),
                        settings.historyConsent,
                    ) { enable -> if (enable) showConsent = true else viewModel.revokeHistoryConsent() }
                    SwitchRow(
                        stringResource(R.string.settings_redact),
                        stringResource(R.string.settings_redact_desc),
                        settings.redactIdentifiers,
                        viewModel::setRedact,
                    )
                    SwitchRow(
                        stringResource(R.string.settings_probe),
                        stringResource(R.string.settings_probe_desc),
                        settings.publicProbe,
                        viewModel::setPublicProbe,
                    )
                }
            }
            item {
                SectionCard {
                    Text(stringResource(R.string.settings_identity_privacy), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                }
            }
            item { LanguageSection() }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.settings_trust))
                    Text(stringResource(R.string.settings_trust_desc), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                    if (anchors.isEmpty()) {
                        Text(stringResource(R.string.settings_trust_empty), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Warning)
                    }
                    anchors.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.subject, style = MaterialTheme.typography.bodySmall)
                                Text("SHA-256 " + a.fingerprint.take(16) + "… · " + dateFormat.format(a.notAfter), style = MonoStyle, color = SamtexColors.Muted)
                            }
                            TextButton(onClick = { viewModel.removeAnchor(a.fingerprint) }) {
                                Text(stringResource(R.string.settings_trust_remove), color = SamtexColors.Error)
                            }
                        }
                    }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/x-x509-ca-cert", "application/pkix-cert", "application/x-pem-file", "application/octet-stream", "*/*")) }) {
                        Text(stringResource(R.string.settings_trust_import))
                    }
                    importResult?.let { r ->
                        Text(
                            when (r) {
                                is ImportResult.Imported -> stringResource(R.string.settings_trust_imported, r.added, r.rejectedNonCa)
                                ImportResult.NotACertificate -> stringResource(R.string.settings_trust_invalid)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r is ImportResult.Imported && r.added > 0) SamtexColors.Success else SamtexColors.Warning,
                        )
                    }
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.settings_history))
                    if (history.isEmpty()) {
                        Text(stringResource(R.string.settings_history_empty), color = SamtexColors.Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        history.forEach { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.open(item.id) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(item.signature ?: item.cardType, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${dateFormat.format(Date(item.timestampMs))} · ${item.technologies} · ${item.status}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SamtexColors.Muted,
                                )
                            }
                        }
                        TextButton(onClick = viewModel::clearHistory) {
                            Text(stringResource(R.string.settings_history_clear), color = SamtexColors.Error)
                        }
                    }
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.settings_about))
                    Text(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                }
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text(stringResource(R.string.settings_history_dialog_title)) },
            text = { Text(stringResource(R.string.settings_history_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    viewModel.grantHistoryConsent()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    openReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::closeReport,
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(report.text ?: stringResource(R.string.history_report_unavailable), style = MonoStyle)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeReport) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = SamtexColors.Electric),
        )
    }
}

@Composable
private fun LanguageSection() {
    val options = listOf(
        "" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_en),
        "ar" to stringResource(R.string.language_ar),
        "fr" to stringResource(R.string.language_fr),
    )
    var current by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')) }
    SectionCard {
        SectionTitle(stringResource(R.string.settings_language))
        options.forEach { (tag, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = current == tag, role = Role.RadioButton) {
                        current = tag
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = current == tag, onClick = null)
                Text(label)
            }
        }
    }
}
