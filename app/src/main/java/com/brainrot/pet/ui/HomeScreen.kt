package com.brainrot.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brainrot.core.AppData
import com.brainrot.core.Breach
import com.brainrot.core.BreachKind
import com.brainrot.core.BrainEngine
import com.brainrot.core.BrainVoice
import com.brainrot.core.CareResult
import com.brainrot.core.DetectionHealth
import com.brainrot.core.Food
import com.brainrot.core.LimitEvaluator
import com.brainrot.core.LimitProgress
import com.brainrot.core.MINUTE_MS
import com.brainrot.core.formatDuration
import com.brainrot.pet.AppGraph
import com.brainrot.pet.R
import com.brainrot.pet.data.PermissionState
import com.brainrot.pet.overlay.BrainLook
import com.brainrot.pet.overlay.BrainView
import com.brainrot.pet.overlay.Reaction
import com.brainrot.pet.overlay.SpeechLines
import com.brainrot.pet.runtime.LiveState
import com.brainrot.pet.service.BrainService
import kotlinx.coroutines.launch
import kotlin.math.ceil

@Composable
fun HomeScreen(
    data: AppData,
    live: LiveState,
    permissions: PermissionState,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val runtime = AppGraph.runtime
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val mood = BrainEngine.mood(data.brain, now - live.sessionStartedAt)
    val stageLabel = stringResource(SpeechLines.stageLabel(mood.stage))
    val topic = BrainVoice.ambientTopic(mood)
    val line = remember(topic) { SpeechLines.pick(context.resources, topic) }
    var brainView by remember { mutableStateOf<BrainView?>(null) }

    fun care(result: CareResult, reaction: Reaction) {
        if (result is CareResult.Accepted) brainView?.react(reaction) else brainView?.react(Reaction.FLINCH)
        val message = when (result) {
            is CareResult.Accepted -> SpeechLines.pick(context.resources, BrainVoice.careTopic(result, reaction == Reaction.DRINK))
            is CareResult.TooSoon -> context.getString(R.string.care_too_soon, ceil(result.retryInMs / MINUTE_MS.toDouble()).toInt())
            CareResult.Full -> SpeechLines.pick(context.resources, com.brainrot.core.SpeechTopic.FULL)
        }
        scope.launch { snackbar.showSnackbar(message) }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Warnings(data, live, permissions)

        SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
            Text(data.settings.brainName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                stringResource(R.string.home_stage_health, stageLabel, mood.health),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            BrainCanvas(
                look = BrainLook.of(data.brain, mood),
                description = stringResource(R.string.bubble_cd, data.settings.brainName, stageLabel),
                modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                onView = { brainView = it },
            )
            Text("“$line”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        SectionCard {
            SectionTitle(stringResource(R.string.home_care_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { care(runtime.feed(Food.WALNUT), Reaction.EAT) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.food_walnut)) }
                FilledTonalButton(onClick = { care(runtime.feed(Food.BLUEBERRIES), Reaction.EAT) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.food_blueberries)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { care(runtime.feed(Food.SALMON), Reaction.EAT) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.food_salmon)) }
                Button(onClick = { care(runtime.water(), Reaction.DRINK) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_water)) }
            }
            MeterRow(stringResource(R.string.meter_rot), data.brain.rot, Color(0xFFB86B7A))
            MeterRow(stringResource(R.string.meter_food), data.brain.nourishment, Color(0xFFC08A4A))
            MeterRow(stringResource(R.string.meter_water), data.brain.hydration, Color(0xFF3D9BE0))
            MeterRow(stringResource(R.string.meter_energy), data.brain.energy, Color(0xFF8A6FD6))
            Text(stringResource(R.string.home_rest_tip), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard {
            SectionTitle(stringResource(R.string.home_today_title))
            StatLine(stringResource(R.string.stat_reels), data.today.totalReels.toString())
            StatLine(stringResource(R.string.stat_screen_time), formatDuration(live.totalScreenMs))
            StatLine(stringResource(R.string.stat_short_video), formatDuration(data.today.shortVideoMs))
            StatLine(stringResource(R.string.stat_rest), formatDuration(data.today.restMs))
            StatLine(stringResource(R.string.stat_blocks), data.today.blocks.toString())
        }

        val progress = LimitEvaluator.progress(data.limits, data.today, live.snapshot(data))
        if (progress.isNotEmpty()) {
            SectionCard {
                SectionTitle(stringResource(R.string.home_limits_title))
                progress.forEach { p -> LimitProgressRow(p) }
            }
        }
    }
}

@Composable
private fun LimitProgressRow(p: LimitProgress) {
    val runtime = AppGraph.runtime
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(limitLabel(p), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(limitAmount(p), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { p.fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = if (p.fraction >= 1.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        if (p.exceeded) {
            TextButton(onClick = { runtime.openApp(unlockFor = p.toBreach()) }) {
                Text(stringResource(R.string.unlock_button))
            }
        }
    }
}

@Composable
private fun Warnings(data: AppData, live: LiveState, permissions: PermissionState) {
    val context = LocalContext.current
    if (!permissions.coreReady || !permissions.accessibility) {
        SectionCard(container = MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.warn_permissions), color = MaterialTheme.colorScheme.onErrorContainer)
            if (!permissions.accessibility) Text(stringResource(R.string.warn_no_accessibility), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.warn_fix_in_settings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    } else if (!live.serviceRunning) {
        SectionCard(container = MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.warn_service_stopped), color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = { BrainService.start(context) }) { Text(stringResource(R.string.action_restart)) }
        }
    }
    if (permissions.accessibility && data.settings.reelCountingEnabled) {
        val suspects = DetectionHealth.suspects(live.appMs, data.today.reelsByApp)
        if (suspects.isNotEmpty()) {
            SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(stringResource(R.string.warn_detection, suspects.joinToString { it.label }), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Human label for a limit row: "Instagram · reels" or "All apps · screen time". */
@Composable
fun limitLabel(p: LimitProgress): String {
    val kind = stringResource(SpeechLines.breachLabel(p.kind))
    val app = p.label ?: return kind
    return stringResource(R.string.nudge_label_app, kind, app)
}

@Composable
fun limitAmount(p: LimitProgress): String = when (p.kind) {
    BreachKind.APP_REELS, BreachKind.TOTAL_REELS -> stringResource(R.string.limit_amount_reels, p.used, p.limit)
    else -> stringResource(R.string.limit_amount_time, formatDuration(p.used), formatDuration(p.limit))
}

@Composable
fun describeBreach(b: Breach): String = SpeechLines.describe(LocalContext.current, b)
