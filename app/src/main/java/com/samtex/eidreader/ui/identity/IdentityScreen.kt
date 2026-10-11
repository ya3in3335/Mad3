package com.samtex.eidreader.ui.identity

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samtex.eidreader.R
import com.samtex.eidreader.data.scan.IdentityPhase
import com.samtex.eidreader.data.scan.IdentityUiState
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.icao.IdentityReadResult
import com.samtex.eidreader.icao.ReadOutcome
import com.samtex.eidreader.icao.ReadStep
import com.samtex.eidreader.icao.lds.PortraitImage
import com.samtex.eidreader.icao.pa.Authenticity
import com.samtex.eidreader.icao.pa.CheckStatus
import com.samtex.eidreader.ui.components.NfcPulseIcon
import com.samtex.eidreader.ui.components.Pill
import com.samtex.eidreader.ui.components.SamtexTopBar
import com.samtex.eidreader.ui.components.SectionCard
import com.samtex.eidreader.ui.components.SectionTitle
import com.samtex.eidreader.ui.components.SecureScreen
import com.samtex.eidreader.ui.theme.MonoStyle
import com.samtex.eidreader.ui.theme.SamtexColors

@Composable
fun IdentityScreen(viewModel: IdentityViewModel, onBack: () -> Unit, onRetry: () -> Unit) {
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SamtexTopBar(stringResource(R.string.identity_title)) {
                if (state.phase == IdentityPhase.WAITING_FOR_CARD || state.phase == IdentityPhase.READING) viewModel.cancel()
                onBack()
            }
        },
        containerColor = SamtexColors.Background,
    ) { padding ->
        AnimatedContent(targetState = state.phase, label = "identityPhase", modifier = Modifier.padding(padding)) { phase ->
            when (phase) {
                IdentityPhase.IDLE -> Waiting(ScanPhase.WAITING, null) { onBack() }
                IdentityPhase.WAITING_FOR_CARD -> Waiting(ScanPhase.WAITING, null) {
                    viewModel.cancel()
                    onBack()
                }
                IdentityPhase.READING -> Waiting(ScanPhase.READING, state.step) {
                    viewModel.cancel()
                    onBack()
                }
                IdentityPhase.FINISHED -> {
                    val result = state.result
                    if (result == null || result.outcome != ReadOutcome.READING_SUCCESSFUL) {
                        Failure(result, onRetry = onRetry, onClose = {
                            viewModel.endSession()
                            onBack()
                        })
                    } else {
                        Success(state, result, viewModel, onClose = {
                            viewModel.endSession()
                            onBack()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Waiting(phase: ScanPhase, step: ReadStep?, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        NfcPulseIcon(phase)
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(if (step == null) R.string.identity_waiting else R.string.identity_keep_still),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = SamtexColors.Primary,
        )
        if (step != null) {
            Spacer(Modifier.height(20.dp))
            val steps = ReadStep.entries
            LinearProgressIndicator(
                progress = { (steps.indexOf(step) + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = SamtexColors.Electric,
                trackColor = SamtexColors.Elevated,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(stepLabel(step)), color = SamtexColors.Muted)
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.identity_cancel)) }
    }
}

@Composable
private fun Failure(result: IdentityReadResult?, onRetry: () -> Unit, onClose: () -> Unit) {
    val outcome = result?.outcome ?: ReadOutcome.PROTOCOL_UNSUPPORTED
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard {
            Text(stringResource(outcomeTitle(outcome)), style = MaterialTheme.typography.titleLarge, color = SamtexColors.Error)
            Text(stringResource(outcomeExplanation(outcome)), style = MaterialTheme.typography.bodyMedium, color = SamtexColors.Primary)
        }
        result?.let { TrustPanel(it) }
        result?.let { TechnicalPanel(it) }
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
        ) { Text(stringResource(R.string.identity_retry)) }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun Success(state: IdentityUiState, result: IdentityReadResult, viewModel: IdentityViewModel, onClose: () -> Unit) {
    val record = result.record ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard {
            Text(stringResource(R.string.outcome_reading_successful_title), style = MaterialTheme.typography.titleLarge, color = SamtexColors.Success)
            Text(stringResource(R.string.outcome_reading_successful), style = MaterialTheme.typography.bodyMedium)
        }
        TrustPanel(result)

        SectionCard {
            if (!state.revealed) {
                Text(stringResource(R.string.identity_hidden), style = MaterialTheme.typography.bodyMedium, color = SamtexColors.Muted)
                Button(
                    onClick = { viewModel.reveal(true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
                ) { Text(stringResource(R.string.identity_show)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.identity_title))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.reveal(false) }) { Text(stringResource(R.string.identity_hide)) }
                }
                record.portrait?.let { portrait ->
                    if (state.portraitRevealed) Portrait(portrait) else {
                        OutlinedButton(onClick = { viewModel.revealPortrait(true) }, shape = RoundedCornerShape(14.dp)) {
                            Text(stringResource(R.string.identity_show_photo))
                        }
                    }
                }
                if (record.mrz?.allChecksValid == false) {
                    Text(stringResource(R.string.identity_mrz_checks_failed), color = SamtexColors.Warning, style = MaterialTheme.typography.bodySmall)
                }
                record.fields.forEach { f ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(SamtexColors.Elevated, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(fieldLabel(f.key)), style = MaterialTheme.typography.labelMedium, color = SamtexColors.Muted, modifier = Modifier.weight(1f))
                            Pill(f.source.label, SamtexColors.Info)
                        }
                        Text(f.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = SamtexColors.Primary)
                        when (f.hashVerified) {
                            true -> Text(stringResource(R.string.identity_hash_ok), style = MaterialTheme.typography.labelSmall, color = SamtexColors.Success)
                            false -> Text(stringResource(R.string.identity_hash_bad), style = MaterialTheme.typography.labelSmall, color = SamtexColors.Error)
                            null -> Unit
                        }
                    }
                }
                if (record.undecodedDataGroups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.identity_undecoded, record.undecodedDataGroups.joinToString { "DG$it" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = SamtexColors.Muted,
                    )
                }
                Text(stringResource(R.string.identity_not_read_note), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
            }
        }

        TechnicalPanel(result)

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Elevated, contentColor = SamtexColors.Primary),
        ) { Text(stringResource(R.string.identity_end_session)) }
    }
}

@Composable
private fun Portrait(portrait: PortraitImage) {
    if (portrait.isJpeg2000) {
        Text(stringResource(R.string.identity_photo_jp2), color = SamtexColors.Warning, style = MaterialTheme.typography.bodySmall)
        return
    }
    val bitmap = remember(portrait) { BitmapFactory.decodeByteArray(portrait.data, 0, portrait.data.size)?.asImageBitmap() }
    if (bitmap == null) {
        Text(stringResource(R.string.identity_photo_invalid), color = SamtexColors.Warning, style = MaterialTheme.typography.bodySmall)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.identity_show_photo),
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(16.dp)),
        )
    }
}

@Composable
private fun TrustRow(label: String, ok: Boolean?, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val color = when (ok) {
            true -> SamtexColors.Success
            false -> SamtexColors.Error
            null -> SamtexColors.Muted
        }
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            when (ok) {
                true -> "✓"
                false -> "✗"
                null -> "—"
            } + (detail?.let { " $it" } ?: ""),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun CheckStatus.asOk(): Boolean? = when (this) {
    CheckStatus.VALID -> true
    CheckStatus.INVALID -> false
    CheckStatus.UNAVAILABLE -> null
}

@Composable
private fun TrustPanel(result: IdentityReadResult) {
    val t = result.trust
    val pa = result.passiveAuth
    SectionCard {
        SectionTitle(stringResource(R.string.trust_title))
        TrustRow(stringResource(R.string.trust_connection), t.nfcConnection)
        TrustRow(stringResource(R.string.trust_access), t.accessAuthenticated, result.technical.protocol?.label)
        TrustRow(stringResource(R.string.trust_data_read), t.dataRead)
        if (pa != null) {
            val hashes: Boolean? = when {
                pa.hashes.isEmpty() -> null
                pa.hashesMatched -> true
                else -> false
            }
            TrustRow(stringResource(R.string.trust_hashes), hashes)
            TrustRow(stringResource(R.string.trust_signature), pa.signature.asOk())
            TrustRow(stringResource(R.string.trust_chain), pa.chain.asOk())
            val (text, color) = when (pa.authenticity) {
                Authenticity.ESTABLISHED -> stringResource(R.string.trust_established) to SamtexColors.Success
                Authenticity.NOT_ESTABLISHED -> stringResource(R.string.trust_not_established) to SamtexColors.Warning
                Authenticity.FAILED -> stringResource(R.string.trust_failed) to SamtexColors.Error
            }
            Spacer(Modifier.height(4.dp))
            Text(text, color = color, style = MaterialTheme.typography.titleMedium)
            if (pa.authenticity == Authenticity.NOT_ESTABLISHED) {
                Text(stringResource(R.string.outcome_verification_unavailable), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                if (pa.chain == CheckStatus.UNAVAILABLE) {
                    Text(stringResource(R.string.trust_import_hint), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Info)
                }
            }
        }
    }
}

@Composable
private fun TechnicalPanel(result: IdentityReadResult) {
    var open by rememberSaveable { mutableStateOf(false) }
    val tech = result.technical
    SectionCard {
        TextButton(onClick = { open = !open }) { Text(stringResource(R.string.identity_technical)) }
        if (open) {
            tech.protocol?.let { Text(stringResource(R.string.identity_protocol, it.label), style = MaterialTheme.typography.bodySmall) }
            tech.paceProtocolOid?.let { Text("PACE OID $it", style = MonoStyle, color = SamtexColors.Muted) }
            Text(stringResource(R.string.identity_apdus, tech.apduCount, tech.durationMs.toInt()), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
            tech.files.forEach { (name, status) ->
                Row {
                    Text(name, style = MonoStyle, modifier = Modifier.width(120.dp))
                    Text(status.name, style = MonoStyle, color = if (status.name == "READ") SamtexColors.Success else SamtexColors.Muted)
                }
            }
            result.passiveAuth?.notes?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted) }
            result.detail?.let { Text(it, style = MonoStyle, color = SamtexColors.Muted) }
            if (tech.log.isNotEmpty()) {
                Text(tech.log.joinToString("\n"), style = MonoStyle, color = Color(0xFFB8C4E0))
            }
        }
    }
}
