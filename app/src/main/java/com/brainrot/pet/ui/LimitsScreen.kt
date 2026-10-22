package com.brainrot.pet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.brainrot.core.AppData
import com.brainrot.core.AppLimit
import com.brainrot.core.LimitConfig
import com.brainrot.core.LimitInput
import com.brainrot.core.MAX_MINUTES_LIMIT
import com.brainrot.core.MAX_REEL_LIMIT
import com.brainrot.core.ShortVideoApps
import com.brainrot.core.UnlockPolicy
import com.brainrot.pet.AppGraph
import com.brainrot.pet.R
import com.brainrot.pet.data.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editable copy of one app limit; fields stay text until saved so typing is never fought. */
private class AppLimitDraft(val packageName: String, val label: String, minutes: String, reels: String) {
    var minutes by mutableStateOf(minutes)
    var reels by mutableStateOf(reels)
}

@Composable
fun LimitsScreen(data: AppData, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = data.limits

    // Drafts reset whenever the saved config changes (e.g. after a save).
    var enforce by remember(saved) { mutableStateOf(saved.enforce) }
    var reels by remember(saved) { mutableStateOf(saved.dailyReels?.toString() ?: "") }
    var minutes by remember(saved) { mutableStateOf(saved.dailyScreenMinutes?.toString() ?: "") }
    val apps = remember(saved) {
        mutableStateListOf<AppLimitDraft>().apply {
            saved.apps.forEach { add(AppLimitDraft(it.packageName, it.label, it.dailyMinutes?.toString() ?: "", it.dailyReels?.toString() ?: "")) }
        }
    }
    var showPicker by remember { mutableStateOf(false) }
    var pendingLoosen by remember { mutableStateOf<LimitConfig?>(null) }

    val reelsInput = LimitInput.parse(reels, 1..MAX_REEL_LIMIT)
    val minutesInput = LimitInput.parse(minutes, 1..MAX_MINUTES_LIMIT)

    fun build(): LimitConfig? {
        if (reelsInput is LimitInput.Invalid || minutesInput is LimitInput.Invalid) return null
        val appLimits = apps.map { draft ->
            val m = LimitInput.parse(draft.minutes, 1..MAX_MINUTES_LIMIT)
            val r = LimitInput.parse(draft.reels, 1..MAX_REEL_LIMIT)
            if (m is LimitInput.Invalid || r is LimitInput.Invalid) return null
            AppLimit(draft.packageName, draft.label, (m as? LimitInput.Value)?.value, (r as? LimitInput.Value)?.value)
        }
        return LimitConfig(
            dailyReels = (reelsInput as? LimitInput.Value)?.value,
            dailyScreenMinutes = (minutesInput as? LimitInput.Value)?.value,
            apps = appLimits,
            enforce = enforce,
        )
    }

    fun save() {
        val config = build()
        if (config == null) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.limits_invalid)) }
            return
        }
        if (config.loosens(saved)) {
            pendingLoosen = config
        } else {
            AppGraph.runtime.saveLimits(config, countsAsUnlock = false)
            scope.launch { snackbar.showSnackbar(context.getString(R.string.limits_saved)) }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(stringResource(R.string.limits_enforce))
                    Text(stringResource(R.string.limits_enforce_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enforce, onCheckedChange = { enforce = it })
            }
        }

        SectionCard {
            SectionTitle(stringResource(R.string.limits_daily_title))
            NumberField(reels, { reels = it }, stringResource(R.string.limits_daily_reels), reelsInput is LimitInput.Invalid)
            NumberField(minutes, { minutes = it }, stringResource(R.string.limits_daily_minutes), minutesInput is LimitInput.Invalid)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reels = "30"; minutes = "180" }) { Text(stringResource(R.string.limits_preset_gentle)) }
                OutlinedButton(onClick = { reels = "10"; minutes = "120" }) { Text(stringResource(R.string.limits_preset_strict)) }
            }
            Text(stringResource(R.string.limits_reels_note), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard {
            SectionTitle(stringResource(R.string.limits_apps_title))
            if (apps.isEmpty()) Text(stringResource(R.string.limits_apps_empty), style = MaterialTheme.typography.bodySmall)
            apps.forEach { draft ->
                AppLimitEditor(draft, onRemove = { apps.remove(draft) })
            }
            OutlinedButton(onClick = { showPicker = true }) { Text(stringResource(R.string.limits_add_app)) }
        }

        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.limits_save)) }
        Text(stringResource(R.string.limits_loosen_note), style = MaterialTheme.typography.bodySmall)
    }

    if (showPicker) {
        AppPickerDialog(
            exclude = apps.map { it.packageName }.toSet(),
            onPick = { app ->
                val defaultReels = if (ShortVideoApps.find(app.packageName) != null) "20" else ""
                apps.add(AppLimitDraft(app.packageName, app.label, if (defaultReels.isEmpty()) "30" else "", defaultReels))
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    pendingLoosen?.let { config ->
        MindfulWaitDialog(
            title = stringResource(R.string.loosen_title),
            message = stringResource(R.string.loosen_message),
            waitSeconds = UnlockPolicy.waitSeconds(data.today.unlocks),
            confirmLabel = stringResource(R.string.limits_save),
            onConfirm = {
                AppGraph.runtime.saveLimits(config, countsAsUnlock = true)
                pendingLoosen = null
                scope.launch { snackbar.showSnackbar(context.getString(R.string.limits_saved)) }
            },
            onDismiss = { pendingLoosen = null },
        )
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(6)) },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.limits_no_limit)) },
        isError = isError,
        supportingText = if (isError) ({ Text(stringResource(R.string.limits_field_invalid)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppLimitEditor(draft: AppLimitDraft, onRemove: () -> Unit) {
    val isShortVideo = ShortVideoApps.find(draft.packageName) != null
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(draft.packageName)
            Text(draft.label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onRemove) { Text(stringResource(R.string.limits_remove)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val minutesBad = LimitInput.parse(draft.minutes, 1..MAX_MINUTES_LIMIT) is LimitInput.Invalid
            OutlinedTextField(
                value = draft.minutes,
                onValueChange = { draft.minutes = it.take(6) },
                label = { Text(stringResource(R.string.limits_app_minutes)) },
                isError = minutesBad,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            if (isShortVideo) {
                val reelsBad = LimitInput.parse(draft.reels, 1..MAX_REEL_LIMIT) is LimitInput.Invalid
                OutlinedTextField(
                    value = draft.reels,
                    onValueChange = { draft.reels = it.take(6) },
                    label = { Text(stringResource(R.string.limits_app_reels)) },
                    isError = reelsBad,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, sizeDp: Int = 32) {
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.Default) {
            AppGraph.catalog.icon(packageName)?.toBitmap(96, 96)?.asImageBitmap()
        }
    }
    icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(sizeDp.dp)) }
}

@Composable
private fun AppPickerDialog(exclude: Set<String>, onPick: (InstalledApp) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.Default) { AppGraph.catalog.launchableApps() }
    }
    val shortVideo = remember(all) { all.orEmpty().filter { ShortVideoApps.find(it.packageName) != null } }
    val filtered = all.orEmpty()
        .filter { it.packageName !in exclude }
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedByDescending { it in shortVideo }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (all == null) {
                    Text(stringResource(R.string.picker_loading), Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 10.dp),
                            ) {
                                AppIcon(app.packageName)
                                Text(app.label, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
