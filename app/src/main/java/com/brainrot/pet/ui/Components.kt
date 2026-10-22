package com.brainrot.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.brainrot.pet.R
import com.brainrot.pet.overlay.BrainLook
import com.brainrot.pet.overlay.BrainView
import kotlinx.coroutines.delay

/** The same animated brain as the overlay, hosted in Compose. */
@Composable
fun BrainCanvas(look: BrainLook, description: String, modifier: Modifier = Modifier, onView: (BrainView) -> Unit = {}) {
    AndroidView(
        factory = { ctx -> BrainView(ctx).also(onView) },
        update = { it.look = look },
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun MeterRow(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text("${value.toInt()}", modifier = Modifier.width(32.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The cost of loosening a limit: a breathing countdown that has to run out before the confirm
 * button works. Leaving the dialog cancels it. Each unlock today doubles the next wait.
 */
@Composable
fun MindfulWaitDialog(
    title: String,
    message: String,
    waitSeconds: Int,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var remaining by remember(waitSeconds) { mutableIntStateOf(waitSeconds) }
    LaunchedEffect(waitSeconds) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    val breath = if (((waitSeconds - remaining) / 4) % 2 == 0) R.string.wait_breathe_in else R.string.wait_breathe_out
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                if (remaining > 0) {
                    Text(stringResource(breath), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(
                        progress = { 1f - remaining / waitSeconds.toFloat().coerceAtLeast(1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.wait_seconds_left, remaining), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = remaining <= 0) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.wait_keep_limit)) }
        },
    )
}
