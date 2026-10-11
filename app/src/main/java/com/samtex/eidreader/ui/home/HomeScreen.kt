package com.samtex.eidreader.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samtex.eidreader.R
import com.samtex.eidreader.data.scan.ScanUiState
import com.samtex.eidreader.domain.model.IsoDepStatus
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.domain.model.ScanResult
import com.samtex.eidreader.nfc.classifier.Provenance
import com.samtex.eidreader.nfc.discovery.NfcAvailability
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.security.PrivacyManager
import com.samtex.eidreader.ui.components.InfoRow
import com.samtex.eidreader.ui.components.NfcPulseIcon
import com.samtex.eidreader.ui.components.Pill
import com.samtex.eidreader.ui.components.SectionCard
import com.samtex.eidreader.ui.components.SectionTitle
import com.samtex.eidreader.ui.components.isoDepLabel
import com.samtex.eidreader.ui.components.phaseLabel
import com.samtex.eidreader.ui.components.probeLabel
import com.samtex.eidreader.ui.components.rememberExportFlow
import com.samtex.eidreader.ui.theme.SamtexColors

@Composable
fun HomeScreen(
    viewModel: ScanViewModel,
    onOpenDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onReadIdentity: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    val export = rememberExportFlow(viewModel::suggestedFileName, viewModel::export)

    Scaffold(
        containerColor = SamtexColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state.nfc, onOpenSettings)
            Spacer(Modifier.height(28.dp))
            NfcPulseIcon(state.phase)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(phaseLabel(state.phase)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = SamtexColors.Primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    when (state.phase) {
                        ScanPhase.NFC_DISABLED -> R.string.hint_nfc_disabled
                        ScanPhase.NFC_UNAVAILABLE -> R.string.hint_nfc_unavailable
                        ScanPhase.CARD_DETECTED, ScanPhase.CONNECTING, ScanPhase.READING -> R.string.hint_keep_still
                        else -> R.string.instruction_hold
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = SamtexColors.Muted,
                textAlign = TextAlign.Center,
            )
            if (state.phase == ScanPhase.NFC_DISABLED) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenNfcSettings, shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.action_open_nfc_settings))
                }
            }
            if (state.nfc != NfcAvailability.UNSUPPORTED) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onReadIdentity,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
                ) { Text(stringResource(R.string.action_read_identity)) }
            }
            state.error?.takeIf { state.result == null }?.let { error ->
                Spacer(Modifier.height(16.dp))
                SectionCard {
                    Text(error.title, color = SamtexColors.Error, fontWeight = FontWeight.SemiBold)
                    Text(error.suggestion, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                }
            }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(
                visible = state.result != null,
                enter = fadeIn() + slideInVertically { it / 4 },
            ) {
                state.result?.let { result ->
                    ResultsPanel(
                        state = state,
                        result = result,
                        redact = settings.redactIdentifiers,
                        onScanAgain = viewModel::scanAgain,
                        onDetails = onOpenDetails,
                        onExport = export.start,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Header(nfc: NfcAvailability, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = SamtexColors.Primary)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = SamtexColors.Muted)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings), tint = SamtexColors.Primary)
        }
    }
    Spacer(Modifier.height(12.dp))
    val (label, color) = when (nfc) {
        NfcAvailability.ENABLED -> R.string.nfc_status_enabled to SamtexColors.Accent
        NfcAvailability.DISABLED -> R.string.nfc_status_disabled to SamtexColors.Warning
        NfcAvailability.UNSUPPORTED -> R.string.nfc_status_unavailable to SamtexColors.Error
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(SamtexColors.SecondaryBackground, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = SamtexColors.Primary)
    }
}

@Composable
private fun ResultsPanel(
    state: ScanUiState,
    result: ScanResult,
    redact: Boolean,
    onScanAgain: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
) {
    val c = result.classification
    val s = result.snapshot
    val privacy = remember { PrivacyManager() }
    var reveal by rememberSaveable(result.id) { mutableStateOf(!redact) }
    val notAvailable = stringResource(R.string.value_not_available)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            SectionTitle(stringResource(R.string.results_title))
            c.signature?.let { sig ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SamtexColors.Accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.field_signature), style = MaterialTheme.typography.labelMedium, color = SamtexColors.Muted)
                        Text(sig.text, style = MaterialTheme.typography.titleMedium, color = SamtexColors.Primary)
                    }
                    Pill(stringResource(R.string.provenance_verified), SamtexColors.Accent)
                }
            }
            InfoRow(stringResource(R.string.field_card_type), c.cardType, c.cardTypeProvenance)
            InfoRow(stringResource(R.string.field_technology), c.technologies.joinToString(", ").ifEmpty { notAvailable })
            InfoRow(stringResource(R.string.field_iso_standard), c.isoStandards.joinToString("\n").ifEmpty { notAvailable })
            InfoRow(stringResource(R.string.field_manufacturer), c.manufacturer.value, c.manufacturer.provenance)
            val uidText = privacy.identifier(s.uid, reveal)
            InfoRow(
                stringResource(R.string.field_tag_id),
                uidText ?: notAvailable,
                mono = uidText != null,
                trailing = if (s.uid != null) {
                    {
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(stringResource(if (reveal) R.string.action_hide else R.string.action_reveal))
                        }
                    }
                } else {
                    null
                },
            )
            if (c.atqb != null) {
                val b = c.atqb
                InfoRow(
                    stringResource(R.string.field_atqb),
                    s.nfcB!!.protocolInfo.hex(" ") + "\n" +
                        (if (b.iso14443_4Compliant) "ISO/IEC 14443-4" else "—") + (b.maxFrameSize?.let { " · $it B" } ?: ""),
                    Provenance.VERIFIED,
                    mono = true,
                )
            } else {
                InfoRow(stringResource(R.string.field_atqa), c.atqa?.hex ?: notAvailable, mono = c.atqa != null)
                InfoRow(stringResource(R.string.field_sak), c.sak?.hex ?: notAvailable, mono = c.sak != null)
            }
            if (s.isoDep != null && s.nfcA != null) {
                InfoRow(stringResource(R.string.field_ats), stringResource(R.string.value_ats_not_exposed), Provenance.RESTRICTED)
                val hist = c.historicalBytes
                InfoRow(
                    stringResource(R.string.field_historical),
                    if (hist.raw.isEmpty()) notAvailable else hist.raw.hex(" ") + (hist.ascii?.let { "\n\"$it\"" } ?: ""),
                    if (hist.raw.isEmpty()) null else Provenance.VERIFIED,
                    mono = !hist.raw.isEmpty(),
                )
            }
            InfoRow(
                stringResource(R.string.field_isodep_status),
                stringResource(isoDepLabel(result.isoDep.status)),
                trailing = {
                    Pill(
                        result.isoDep.status.name.replace('_', ' '),
                        when (result.isoDep.status) {
                            IsoDepStatus.ESTABLISHED -> SamtexColors.Accent
                            IsoDepStatus.NOT_SUPPORTED -> SamtexColors.Muted
                            else -> SamtexColors.Error
                        },
                    )
                },
            )
        }

        if (result.probes.isNotEmpty() || c.isoDepCapable) {
            SectionCard {
                SectionTitle(stringResource(R.string.field_accessible_info))
                result.probes.forEach { probe ->
                    InfoRow(
                        probe.name,
                        probe.summary,
                        trailing = {
                            Pill(
                                stringResource(probeLabel(probe.status)),
                                when (probe.status) {
                                    ProbeStatus.AVAILABLE -> SamtexColors.Accent
                                    ProbeStatus.NOT_PRESENT -> SamtexColors.Muted
                                    ProbeStatus.ACCESS_RESTRICTED -> SamtexColors.Warning
                                    ProbeStatus.ERROR -> SamtexColors.Error
                                },
                            )
                        },
                    )
                }
                Text(stringResource(R.string.restricted_notice), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
            }
        }

        result.error?.let { error ->
            SectionCard {
                Text(error.title, color = SamtexColors.Error, fontWeight = FontWeight.SemiBold)
                Text(error.explanation, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Primary)
                Text(error.suggestion, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
            }
        }

        Button(
            onClick = onScanAgain,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
            enabled = state.phase !in setOf(ScanPhase.CARD_DETECTED, ScanPhase.CONNECTING, ScanPhase.READING),
        ) { Text(stringResource(R.string.action_scan_again)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.action_technical_details), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.action_export_report), textAlign = TextAlign.Center)
            }
        }
    }
}
