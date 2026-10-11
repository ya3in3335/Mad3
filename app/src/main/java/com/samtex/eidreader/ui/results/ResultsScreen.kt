package com.samtex.eidreader.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samtex.eidreader.R
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.security.PrivacyManager
import com.samtex.eidreader.ui.components.DetailField
import com.samtex.eidreader.ui.components.Pill
import com.samtex.eidreader.ui.components.SamtexTopBar
import com.samtex.eidreader.ui.components.SectionCard
import com.samtex.eidreader.ui.components.SectionTitle
import com.samtex.eidreader.ui.components.probeLabel
import com.samtex.eidreader.ui.components.rememberExportFlow
import com.samtex.eidreader.ui.home.ScanViewModel
import com.samtex.eidreader.ui.theme.MonoStyle
import com.samtex.eidreader.ui.theme.SamtexColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Technical Details": every value with hex/ASCII, provenance, explanation and a copy button. */
@Composable
fun ResultsScreen(viewModel: ScanViewModel, onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(context.getString(it)) } }
    val export = rememberExportFlow(viewModel::suggestedFileName, viewModel::export)
    val privacy = remember { PrivacyManager() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    val result = state.result

    Scaffold(
        topBar = { SamtexTopBar(stringResource(R.string.details_title), onBack) },
        containerColor = SamtexColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (result == null) {
            Text(
                stringResource(R.string.details_no_result),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = SamtexColors.Muted,
            )
            return@Scaffold
        }
        val c = result.classification
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = export.start, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.action_export_report))
                    }
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.action_diagnostics))
                    }
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.details_section_card))
                    DetailField(stringResource(R.string.field_card_type), c.cardType, c.cardTypeProvenance, explanation = c.family.label)
                    DetailField(stringResource(R.string.field_iso_standard), c.isoStandards.joinToString("\n"), null)
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.details_section_findings))
                    c.findings.forEach { f ->
                        val isUid = f.key == "uid"
                        val showHex = !(isUid && settings.redactIdentifiers)
                        DetailField(
                            label = f.label,
                            value = if (isUid) (privacy.identifier(result.snapshot.uid, !settings.redactIdentifiers) ?: f.value) else f.value,
                            provenance = f.provenance,
                            hex = if (showHex) f.hex else null,
                            ascii = if (f.key == "historical") c.historicalBytes.ascii else null,
                            explanation = f.explanation,
                            sensitive = isUid,
                        )
                    }
                }
            }
            if (result.probes.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionTitle(stringResource(R.string.details_section_protocols))
                        result.probes.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                Pill(
                                    stringResource(probeLabel(p.status)),
                                    when (p.status) {
                                        ProbeStatus.AVAILABLE -> SamtexColors.Accent
                                        ProbeStatus.ACCESS_RESTRICTED -> SamtexColors.Warning
                                        ProbeStatus.ERROR -> SamtexColors.Error
                                        ProbeStatus.NOT_PRESENT -> SamtexColors.Muted
                                    },
                                )
                            }
                            Text("${p.summary} — ${p.reference}", style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                            p.findings.forEach { f -> DetailField(f.label, f.value, f.provenance, hex = f.hex, explanation = f.explanation) }
                            p.accessNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = SamtexColors.Warning) }
                        }
                    }
                }
            }
            result.error?.let { e ->
                item {
                    SectionCard {
                        SectionTitle(stringResource(R.string.details_section_error))
                        Text("${e.kind}: ${e.title}", color = SamtexColors.Error, fontWeight = FontWeight.SemiBold)
                        Text(e.explanation, style = MaterialTheme.typography.bodySmall)
                        if (e.technicalDetail.isNotBlank()) Text(e.technicalDetail, style = MonoStyle, color = SamtexColors.Muted)
                    }
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.details_section_log))
                    SelectionContainer {
                        Text(
                            result.log.joinToString("\n") { "${timeFormat.format(Date(it.timestampMs))} ${it.level.name.first()} ${it.message}" },
                            style = MonoStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
