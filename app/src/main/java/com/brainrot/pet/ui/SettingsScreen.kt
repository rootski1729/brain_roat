package com.brainrot.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brainrot.core.AppData
import com.brainrot.core.BrainState
import com.brainrot.core.Settings
import com.brainrot.core.SECOND_MS
import com.brainrot.core.UnlockPolicy
import com.brainrot.pet.AppGraph
import com.brainrot.pet.BrainLog
import com.brainrot.pet.BuildConfig
import com.brainrot.pet.R
import com.brainrot.pet.data.PermissionState
import com.brainrot.pet.runtime.LiveState

@Composable
fun SettingsScreen(
    data: AppData,
    live: LiveState,
    permissions: PermissionState,
    onPermissionsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = data.settings
    fun edit(transform: (Settings) -> Settings) = AppGraph.store.update { it.copy(settings = transform(it.settings).sanitized()) }

    var name by remember(settings.brainName) { mutableStateOf(settings.brainName) }
    var bubbleSize by remember(settings.bubbleSizeDp) { mutableFloatStateOf(settings.bubbleSizeDp.toFloat()) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmReelsOff by remember { mutableStateOf(false) }
    val reelLimitsActive = data.limits.enforce && (data.limits.dailyReels != null || data.limits.apps.any { it.dailyReels != null })

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            SectionTitle(stringResource(R.string.settings_pet_title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Settings.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.onboarding_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { edit { s -> s.copy(brainName = Settings.cleanName(name)) } }, enabled = name != settings.brainName) {
                    Text(stringResource(R.string.action_save))
                }
            }
            ToggleRow(R.string.settings_overlay, R.string.settings_overlay_desc, settings.overlayEnabled) { v -> edit { it.copy(overlayEnabled = v) } }
            ToggleRow(R.string.settings_roaming, R.string.settings_roaming_desc, settings.roamingEnabled) { v -> edit { it.copy(roamingEnabled = v) } }
            ToggleRow(R.string.settings_live_counter, R.string.settings_live_counter_desc, settings.liveCounterEnabled) { v -> edit { it.copy(liveCounterEnabled = v) } }
            ToggleRow(R.string.settings_speech, R.string.settings_speech_desc, settings.speechEnabled) { v -> edit { it.copy(speechEnabled = v) } }
            ToggleRow(R.string.settings_reels, R.string.settings_reels_desc, settings.reelCountingEnabled) { v ->
                // Without counting, reel limits stop applying: that is loosening, so it costs the same wait.
                if (!v && reelLimitsActive) confirmReelsOff = true else edit { it.copy(reelCountingEnabled = v) }
            }
            Text(stringResource(R.string.settings_bubble_size, bubbleSize.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = bubbleSize,
                onValueChange = { bubbleSize = it },
                onValueChangeFinished = { edit { it.copy(bubbleSizeDp = bubbleSize.toInt()) } },
                valueRange = Settings.MIN_BUBBLE_DP.toFloat()..Settings.MAX_BUBBLE_DP.toFloat(),
                steps = 5,
            )
            if (settings.bubbleHiddenUntil > System.currentTimeMillis()) {
                OutlinedButton(onClick = { edit { it.copy(bubbleHiddenUntil = 0) } }) { Text(stringResource(R.string.settings_show_bubble_now)) }
            }
        }

        SectionTitle(stringResource(R.string.settings_permissions_title))
        PermissionSteps(permissions, onPermissionsChanged)

        SectionCard {
            SectionTitle(stringResource(R.string.settings_diagnostics_title))
            val now = System.currentTimeMillis()
            StatLine(stringResource(R.string.diag_service), yesNo(live.serviceRunning))
            StatLine(stringResource(R.string.diag_accessibility), yesNo(live.accessibilityConnected))
            StatLine(stringResource(R.string.diag_last_event), ago(live.lastAccessibilityEventAt, now))
            StatLine(stringResource(R.string.diag_last_tick), ago(live.lastTickAt, now))
            StatLine(stringResource(R.string.diag_usage_updated), ago(live.usageUpdatedAt, now))
            StatLine(stringResource(R.string.diag_version), BuildConfig.VERSION_NAME)
            Text(stringResource(R.string.diag_logcat_hint, BrainLog.TAG), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_privacy_title))
            Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_reset_brain))
        }
    }

    if (confirmReelsOff) {
        MindfulWaitDialog(
            title = stringResource(R.string.loosen_title),
            message = stringResource(R.string.reels_off_message),
            waitSeconds = UnlockPolicy.waitSeconds(data.today.unlocks),
            confirmLabel = stringResource(R.string.reels_off_confirm),
            onConfirm = {
                AppGraph.store.update {
                    it.copy(settings = it.settings.copy(reelCountingEnabled = false), today = it.today.copy(unlocks = it.today.unlocks + 1))
                }
                BrainLog.i("reel_counting_disabled", "with_reel_limits" to true)
                confirmReelsOff = false
            },
            onDismiss = { confirmReelsOff = false },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_brain)) },
            text = { Text(stringResource(R.string.settings_reset_brain_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.store.update { it.copy(brain = BrainState()) }
                    BrainLog.i("brain_reset")
                    confirmReset = false
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(title: Int, description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.diag_yes else R.string.diag_no)

@Composable
private fun ago(at: Long, now: Long): String =
    if (at <= 0) stringResource(R.string.diag_never) else stringResource(R.string.diag_seconds_ago, ((now - at) / SECOND_MS).coerceAtLeast(0))
