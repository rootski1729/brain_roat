package com.brainrot.pet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brainrot.core.Settings
import com.brainrot.pet.BrainLog
import com.brainrot.pet.R
import com.brainrot.pet.data.PermissionState
import com.brainrot.pet.data.Permissions
import com.brainrot.pet.overlay.BrainLook

@Composable
fun OnboardingScreen(permissions: PermissionState, onPermissionsChanged: () -> Unit, onFinish: (String) -> Unit) {
    var name by remember { mutableStateOf(Settings.DEFAULT_NAME) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrainCanvas(
                look = BrainLook(),
                description = stringResource(R.string.onboarding_brain_cd),
                modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally),
            )
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(Settings.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.onboarding_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PermissionSteps(permissions, onPermissionsChanged)

            Button(
                onClick = { onFinish(name) },
                enabled = permissions.coreReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_start)) }
            if (!permissions.coreReady) {
                Text(stringResource(R.string.onboarding_required_note), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Permission rows shared by onboarding and settings. */
@Composable
fun PermissionSteps(permissions: PermissionState, onPermissionsChanged: () -> Unit) {
    val context = LocalContext.current
    var showDisclosure by remember { mutableStateOf(false) }
    // Set once the user went to Accessibility settings; if it is still off afterwards, show help.
    var triedAccessibility by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissionsChanged() }

    PermissionRow(R.string.perm_usage_title, R.string.perm_usage_desc, permissions.usageAccess, required = true) {
        open(context, Permissions.usageAccessIntent())
    }
    PermissionRow(R.string.perm_overlay_title, R.string.perm_overlay_desc, permissions.overlay, required = true) {
        open(context, Permissions.overlayIntent(context))
    }
    PermissionRow(R.string.perm_accessibility_title, R.string.perm_accessibility_desc, permissions.accessibility, required = false) {
        showDisclosure = true
    }
    if (triedAccessibility && !permissions.accessibility) {
        AccessibilityHelpCard(onOpenAppInfo = { open(context, Permissions.appDetailsIntent(context)) })
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionRow(R.string.perm_notifications_title, R.string.perm_notifications_desc, permissions.notifications, required = false) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    PermissionRow(R.string.perm_battery_title, R.string.perm_battery_desc, permissions.batteryUnrestricted, required = false) {
        open(context, Permissions.batteryIntent())
    }

    if (showDisclosure) {
        AccessibilityDisclosure(
            onAccept = {
                showDisclosure = false
                triedAccessibility = true
                openAccessibility(context)
            },
            onDecline = { showDisclosure = false },
            onRestrictedHelp = { open(context, Permissions.appDetailsIntent(context)) },
        )
    }
}

@Composable
private fun PermissionRow(title: Int, description: Int, granted: Boolean, required: Boolean, onGrant: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(title) + if (required) "" else " · " + stringResource(R.string.perm_optional),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                Text("✅", Modifier.padding(start = 8.dp))
            } else {
                FilledTonalButton(onClick = onGrant, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.perm_grant)) }
            }
        }
    }
}

/**
 * Prominent disclosure required by Google Play before sending the user to enable an
 * accessibility service that is not an accessibility tool. Accept and decline are explicit.
 */
@Composable
private fun AccessibilityDisclosure(onAccept: () -> Unit, onDecline: () -> Unit, onRestrictedHelp: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.disclosure_body))
                Text(stringResource(R.string.a11y_steps_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Permissions.accessibilitySteps()), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.disclosure_restricted), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRestrictedHelp) { Text(stringResource(R.string.disclosure_open_app_info)) }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.disclosure_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.disclosure_decline)) } },
    )
}

/** Shown when the user came back from Accessibility settings and the service is still off. */
@Composable
private fun AccessibilityHelpCard(onOpenAppInfo: () -> Unit) {
    SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(stringResource(R.string.a11y_help_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(Permissions.accessibilitySteps()), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.a11y_help_restricted), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.disclosure_open_app_info)) }
    }
}

private fun openAccessibility(context: Context) {
    try {
        context.startActivity(Permissions.accessibilityIntent(context))
    } catch (e: RuntimeException) {
        BrainLog.w("a11y_details_intent_failed", e)
        open(context, Permissions.accessibilityListIntent(context))
    }
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: RuntimeException) {
        // Some OEM builds lack a specific settings screen; fall back to the app's info page.
        BrainLog.w("settings_intent_failed", e, "action" to intent.action)
        runCatching { context.startActivity(Permissions.appDetailsIntent(context)) }
    }
}
