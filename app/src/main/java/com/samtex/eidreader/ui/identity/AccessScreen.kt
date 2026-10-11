package com.samtex.eidreader.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samtex.eidreader.R
import com.samtex.eidreader.icao.access.AccessCredentials.Problem
import com.samtex.eidreader.ui.components.SamtexTopBar
import com.samtex.eidreader.ui.components.SectionCard
import com.samtex.eidreader.ui.components.SecureScreen
import com.samtex.eidreader.ui.theme.SamtexColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AccessScreen(viewModel: AccessViewModel, onBack: () -> Unit, onStarted: () -> Unit) {
    SecureScreen()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var showCan by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { SamtexTopBar(stringResource(R.string.access_title), onBack) },
        containerColor = SamtexColors.Background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.access_intro), style = MaterialTheme.typography.bodyMedium, color = SamtexColors.Muted)

            SectionCard {
                OutlinedTextField(
                    value = form.documentNumber,
                    onValueChange = viewModel::setDocumentNumber,
                    label = { Text(stringResource(R.string.access_card_number)) },
                    supportingText = {
                        Text(stringResource(if (Problem.DOCUMENT_NUMBER in form.problems) R.string.access_error_number else R.string.access_card_number_hint))
                    },
                    isError = Problem.DOCUMENT_NUMBER in form.problems,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                DateField(
                    label = stringResource(R.string.access_birth_date),
                    value = form.dateOfBirth,
                    error = if (Problem.DATE_OF_BIRTH in form.problems) stringResource(R.string.access_error_birth) else null,
                    initialYear = 1990,
                    onChange = viewModel::setDateOfBirth,
                )
                DateField(
                    label = stringResource(R.string.access_expiry_date),
                    value = form.dateOfExpiry,
                    error = if (Problem.DATE_OF_EXPIRY in form.problems) stringResource(R.string.access_error_expiry) else null,
                    initialYear = LocalDate.now().year + 5,
                    onChange = viewModel::setDateOfExpiry,
                )
            }

            SectionCard {
                TextButton(onClick = { showCan = !showCan }) { Text(stringResource(R.string.access_can)) }
                if (showCan || form.can.isNotEmpty()) {
                    OutlinedTextField(
                        value = form.can,
                        onValueChange = viewModel::setCan,
                        label = { Text(stringResource(R.string.access_can)) },
                        supportingText = { Text(stringResource(if (Problem.CAN in form.problems) R.string.access_error_can else R.string.access_can_hint)) },
                        isError = Problem.CAN in form.problems,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Text(stringResource(R.string.access_can_hint), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.access_read_portrait), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.access_read_portrait_desc), style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted)
                    }
                    Switch(
                        checked = form.readPortrait,
                        onCheckedChange = viewModel::setReadPortrait,
                        colors = SwitchDefaults.colors(checkedTrackColor = SamtexColors.Electric),
                    )
                }
                Row(
                    Modifier.toggleable(form.consent, role = Role.Checkbox, onValueChange = viewModel::setConsent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = form.consent,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = SamtexColors.Electric),
                    )
                    Text(stringResource(R.string.access_consent), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (Problem.MISSING in form.problems) {
                Text(stringResource(R.string.access_error_missing), color = SamtexColors.Error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { if (viewModel.submit()) onStarted() },
                enabled = form.consent,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SamtexColors.Electric, contentColor = SamtexColors.OnElectric),
            ) { Text(stringResource(R.string.access_start)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate?, error: String?, initialYear: Int, onChange: (LocalDate?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = SamtexColors.Primary)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text(value?.format(formatter) ?: stringResource(R.string.access_choose_date))
        }
        if (error != null) Text(error, color = SamtexColors.Error, style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        val initial = value ?: LocalDate.of(initialYear, 1, 1)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = 1900..2100,
            initialDisplayMode = DisplayMode.Input,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = state, showModeToggle = true)
        }
    }
}
