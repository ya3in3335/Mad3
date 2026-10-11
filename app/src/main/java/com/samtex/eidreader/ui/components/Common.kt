package com.samtex.eidreader.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samtex.eidreader.R
import com.samtex.eidreader.nfc.classifier.Provenance
import com.samtex.eidreader.ui.theme.MonoStyle
import com.samtex.eidreader.ui.theme.SamtexColors
import kotlinx.coroutines.delay

/** Copies text to the clipboard; identifiers are flagged as sensitive so Android hides previews. */
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SamtexColors.SecondaryBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = SamtexColors.Primary)
}

@Composable
fun ProvenanceBadge(provenance: Provenance) {
    val (label, color) = when (provenance) {
        Provenance.VERIFIED -> stringResource(R.string.provenance_verified) to SamtexColors.Accent
        Provenance.INFERRED -> stringResource(R.string.provenance_inferred) to SamtexColors.Info
        Provenance.RESTRICTED -> stringResource(R.string.provenance_restricted) to SamtexColors.Warning
    }
    Pill(label, color)
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Compact label/value row used in the results panel. */
@Composable
fun InfoRow(label: String, value: String, provenance: Provenance? = null, mono: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = SamtexColors.Muted,
            modifier = Modifier.weight(0.38f).padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(0.62f), horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = SamtexColors.Primary,
            )
            if (provenance != null || trailing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    provenance?.let { ProvenanceBadge(it) }
                }
            }
        }
    }
}

/** Detailed field with HEX/ASCII rendering, explanation and copy button. */
@Composable
fun DetailField(
    label: String,
    value: String,
    provenance: Provenance?,
    hex: String? = null,
    ascii: String? = null,
    explanation: String? = null,
    sensitive: Boolean = false,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SamtexColors.Background, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = SamtexColors.Primary, modifier = Modifier.weight(1f))
            provenance?.let { ProvenanceBadge(it) }
            TextButton(onClick = {
                copyToClipboard(context, label, hex ?: value, sensitive)
                copied = true
            }) {
                Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy))
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = SamtexColors.Primary)
                if (hex != null && hex != value) {
                    Text("${stringResource(R.string.details_hex)}  ${hex.chunked(2).joinToString(" ")}", style = MonoStyle, color = SamtexColors.Primary)
                }
                if (ascii != null) {
                    Text("${stringResource(R.string.details_ascii)}  $ascii", style = MonoStyle, color = SamtexColors.Primary)
                }
            }
        }
        explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SamtexColors.Muted) }
    }
}
