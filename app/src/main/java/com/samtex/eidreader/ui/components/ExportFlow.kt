package com.samtex.eidreader.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.samtex.eidreader.R
import com.samtex.eidreader.diagnostics.ReportFormat
import com.samtex.eidreader.diagnostics.ReportOptions
import com.samtex.eidreader.ui.theme.SamtexColors

/** Handle returned by [rememberExportFlow]; call [start] to open the export dialog. */
class ExportFlowHandle(val start: () -> Unit)

/**
 * Export flow: options dialog → (optional) identifier confirmation → Storage Access Framework
 * "create document" picker → write. No storage permission is needed.
 */
@Composable
fun rememberExportFlow(
    fileName: (ReportFormat) -> String,
    onExport: (Uri, ReportOptions) -> Unit,
): ExportFlowHandle {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf(ReportFormat.JSON) }
    var includeIds by rememberSaveable { mutableStateOf(false) }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ReportFormat.JSON.mimeType)) { uri ->
        uri?.let { onExport(it, ReportOptions(ReportFormat.JSON, includeIds)) }
    }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ReportFormat.TXT.mimeType)) { uri ->
        uri?.let { onExport(it, ReportOptions(ReportFormat.TXT, includeIds)) }
    }
    val launch = {
        when (format) {
            ReportFormat.JSON -> jsonLauncher.launch(fileName(ReportFormat.JSON))
            ReportFormat.TXT -> txtLauncher.launch(fileName(ReportFormat.TXT))
        }
    }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text(stringResource(R.string.export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
                    ReportFormat.entries.forEach { f ->
                        Row(
                            Modifier.selectable(selected = format == f, onClick = { format = f }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = format == f, onClick = null)
                            Text(f.name, Modifier)
                        }
                    }
                    Row(
                        Modifier.toggleable(value = includeIds, onValueChange = { includeIds = it }, role = Role.Checkbox),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeIds, onCheckedChange = null)
                        Text(stringResource(R.string.export_include_ids))
                    }
                    if (includeIds) {
                        Text(stringResource(R.string.export_ids_warning), color = SamtexColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptions = false
                    if (includeIds) showConfirm = true else launch()
                }) { Text(stringResource(R.string.action_export)) }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.export_include_ids)) },
            text = { Text(stringResource(R.string.export_ids_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    launch()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    return remember {
        ExportFlowHandle {
            includeIds = false
            showOptions = true
        }
    }
}
