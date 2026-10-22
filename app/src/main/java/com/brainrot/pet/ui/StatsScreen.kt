package com.brainrot.pet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brainrot.core.AppData
import com.brainrot.core.DayKey
import com.brainrot.core.DayStats
import com.brainrot.core.MINUTE_MS
import com.brainrot.core.formatDuration
import com.brainrot.pet.AppGraph
import com.brainrot.pet.R
import com.brainrot.pet.runtime.LiveState

private const val CHART_DAYS = 14

@Composable
fun StatsScreen(data: AppData, live: LiveState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val days = lastDays(data, live)
    val reels = days.map { it.totalReels.toFloat() }
    val minutes = days.map { (it.screenMs / MINUTE_MS).toFloat() }
    val labels = days.map { DayKey.parse(it.day)?.dayOfMonth?.toString() ?: "" }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            SectionTitle(stringResource(R.string.stats_reels_title))
            Text(stringResource(R.string.stats_average, average(reels).toInt().toString()), style = MaterialTheme.typography.bodySmall)
            BarChart(reels, labels, MaterialTheme.colorScheme.primary, stringResource(R.string.stats_reels_title))
        }
        SectionCard {
            SectionTitle(stringResource(R.string.stats_screen_title))
            Text(stringResource(R.string.stats_average, formatDuration((average(minutes) * MINUTE_MS).toLong())), style = MaterialTheme.typography.bodySmall)
            BarChart(minutes, labels, MaterialTheme.colorScheme.tertiary, stringResource(R.string.stats_screen_title))
        }
        SectionCard {
            SectionTitle(stringResource(R.string.stats_apps_title))
            val top = live.appMs.entries.sortedByDescending { it.value }.take(10)
            if (top.isEmpty()) Text(stringResource(R.string.stats_apps_empty), style = MaterialTheme.typography.bodySmall)
            top.forEach { (pkg, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    AppIcon(pkg, 28)
                    Text(AppGraph.catalog.label(pkg), Modifier.weight(1f).padding(start = 10.dp))
                    val appReels = data.today.reelsByApp[pkg] ?: 0
                    if (appReels > 0) Text(context.resources.getQuantityString(R.plurals.stats_app_reels, appReels, appReels), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    Text(formatDuration(ms), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        SectionCard {
            SectionTitle(stringResource(R.string.stats_care_title))
            StatLine(stringResource(R.string.stat_feeds), data.today.feeds.toString())
            StatLine(stringResource(R.string.stat_waters), data.today.waters.toString())
            StatLine(stringResource(R.string.stat_unlocks), data.today.unlocks.toString())
        }
    }
}

/** Oldest to newest, today last with live screen time; missing days are zero. */
private fun lastDays(data: AppData, live: LiveState): List<DayStats> {
    val today = DayKey.parse(data.today.day) ?: return listOf(data.today)
    val byDay = data.history.associateBy { it.day }
    return (CHART_DAYS - 1 downTo 0).map { back ->
        val key = today.minusDays(back.toLong()).toString()
        when {
            back == 0 -> data.today.copy(screenMs = maxOf(live.totalScreenMs, data.today.screenMs))
            else -> byDay[key] ?: DayStats(day = key)
        }
    }
}

private fun average(values: List<Float>): Float {
    val nonZero = values.filter { it > 0f }
    return if (nonZero.isEmpty()) 0f else nonZero.sum() / nonZero.size
}

@Composable
private fun BarChart(values: List<Float>, labels: List<String>, color: Color, description: String) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "$description: " + values.zip(labels).joinToString { "${it.second}: ${it.first.toInt()}" }
        },
    ) {
        val slot = size.width / values.size
        val barWidth = slot * 0.62f
        values.forEachIndexed { i, v ->
            val left = i * slot + (slot - barWidth) / 2
            drawRoundRect(track, Offset(left, 0f), Size(barWidth, size.height), CornerRadius(6f, 6f))
            val h = size.height * (v / max)
            if (h > 0f) {
                drawRoundRect(
                    color = if (i == values.lastIndex) color else color.copy(alpha = 0.6f),
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(6f, 6f),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
    }
}
