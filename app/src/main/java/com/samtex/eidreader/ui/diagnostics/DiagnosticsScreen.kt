package com.samtex.eidreader.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samtex.eidreader.R
import com.samtex.eidreader.domain.usecase.DiagnosticOperation
import com.samtex.eidreader.ui.components.SamtexTopBar
import com.samtex.eidreader.ui.components.SectionCard
import com.samtex.eidreader.ui.components.SectionTitle
import com.samtex.eidreader.ui.theme.MonoStyle
import com.samtex.eidreader.ui.theme.SamtexColors

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val enabled = state.authorized && !state.running && scan.cardAvailable

    Scaffold(
        topBar = { SamtexTopBar(stringResource(R.string.diag_title), onBack) },
        containerColor = SamtexColors.Background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Text(stringResource(R.string.diag_intro), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                    Row(
                        Modifier.toggleable(state.authorized, role = Role.Checkbox, onValueChange = viewModel::setAuthorized),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.authorized, onCheckedChange = null)
                        Text(stringResource(R.string.diag_authorize), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (scan.cardAvailable) stringResource(R.string.diag_card_ready, scan.cardLabel ?: "") else stringResource(R.string.diag_card_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (scan.cardAvailable) SamtexColors.Accent else SamtexColors.Warning,
                    )
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.diag_operations))
                    DiagnosticOperation.entries.forEach { op ->
                        OutlinedButton(
                            onClick = { viewModel.run(op) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(op.title, fontWeight = FontWeight.Medium)
                                Text(op.reference, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                            }
                        }
                    }
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.diag_custom))
                    OutlinedTextField(
                        value = state.customInput,
                        onValueChange = viewModel::setCustomInput,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MonoStyle,
                        placeholder = { Text(stringResource(R.string.diag_custom_hint), style = MonoStyle) },
                        isError = state.customError != null,
                        supportingText = state.customError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = viewModel::sendCustom,
                        enabled = enabled && state.customInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
                    ) { Text(stringResource(R.string.diag_send)) }
                }
            }
            item {
                SectionCard {
                    SectionTitle(stringResource(R.string.diag_decoder))
                    OutlinedTextField(
                        value = state.decoderInput,
                        onValueChange = viewModel::setDecoderInput,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MonoStyle,
                        placeholder = { Text(stringResource(R.string.diag_decoder_hint), style = MonoStyle) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = viewModel::decode,
                        enabled = state.decoderInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
                    ) { Text(stringResource(R.string.diag_decode)) }
                    state.decoderOutput?.let { SelectionContainer { Text(it, style = MonoStyle) } }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.diag_output))
                    if (state.running) {
                        CircularProgressIndicator(Modifier.padding(start = 12.dp).then(Modifier), strokeWidth = 2.dp)
                    }
                    Column(Modifier.weight(1f)) {}
                    if (state.entries.isNotEmpty()) {
                        TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
            items(state.entries, key = { it.id }) { entry -> ConsoleEntryCard(entry) }
        }
    }
}

@Composable
private fun ConsoleEntryCard(entry: ConsoleEntry) {
    SectionCard {
        Text(entry.title, style = MaterialTheme.typography.labelLarge)
        entry.error?.let { e ->
            Text("${e.title}: ${e.technicalDetail.ifBlank { e.explanation }}", color = SamtexColors.Error, style = MaterialTheme.typography.bodySmall)
        }
        entry.exchanges.forEach { x ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SamtexColors.Background, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(x.label, style = MaterialTheme.typography.labelMedium, color = SamtexColors.Muted)
                SelectionContainer {
                    Column {
                        Text("→ ${x.command}", style = MonoStyle)
                        Text(x.commandInfo, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                        Text("← ${x.response}", style = MonoStyle)
                        Text("${x.status} · ${x.durationMs} ms", style = MaterialTheme.typography.bodySmall, color = if (x.success) SamtexColors.Accent else SamtexColors.Warning)
                        x.ascii?.let { Text("ASCII \"$it\"", style = MonoStyle) }
                        x.tlv?.let { Text(it, style = MonoStyle) }
                        x.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Warning) }
                    }
                }
            }
        }
    }
}
