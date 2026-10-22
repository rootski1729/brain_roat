package com.brainrot.pet.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brainrot.core.UnlockPolicy
import com.brainrot.pet.AppGraph
import com.brainrot.pet.R
import com.brainrot.pet.data.Permissions
import com.brainrot.pet.service.BrainService
import com.brainrot.pet.ui.theme.BrainRotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BrainRotTheme { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.runtime.setAppInForeground(true)
        if (AppGraph.store.data.value.settings.onboardingDone) BrainService.start(this)
    }

    override fun onPause() {
        AppGraph.runtime.setAppInForeground(false)
        super.onPause()
    }
}

private enum class Tab(val icon: String, val label: Int) {
    BRAIN("🧠", R.string.tab_brain),
    LIMITS("⏳", R.string.tab_limits),
    STATS("📊", R.string.tab_stats),
    SETTINGS("⚙️", R.string.tab_settings),
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val runtime = AppGraph.runtime
    val data by AppGraph.store.data.collectAsStateWithLifecycle()
    val live by runtime.live.collectAsStateWithLifecycle()
    val unlockRequest by runtime.unlockRequest.collectAsStateWithLifecycle()
    var permissions by remember { mutableStateOf(Permissions.state(context)) }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        permissions = Permissions.state(context)
        if (permissions.usageAccess) scope.launch { runtime.refreshUsage() }
        onPauseOrDispose { }
    }

    if (!data.settings.onboardingDone) {
        OnboardingScreen(
            permissions = permissions,
            onPermissionsChanged = { permissions = Permissions.state(context) },
            onFinish = { name ->
                AppGraph.store.update {
                    it.copy(settings = it.settings.copy(onboardingDone = true, brainName = com.brainrot.core.Settings.cleanName(name)))
                }
                BrainService.start(context)
            },
        )
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(Tab.BRAIN.ordinal) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Text(t.icon) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.BRAIN -> HomeScreen(data, live, permissions, snackbar, modifier)
            Tab.LIMITS -> LimitsScreen(data, snackbar, modifier)
            Tab.STATS -> StatsScreen(data, live, modifier)
            Tab.SETTINGS -> SettingsScreen(data, live, permissions, onPermissionsChanged = { permissions = Permissions.state(context) }, modifier = modifier)
        }
    }

    unlockRequest?.let { breach ->
        val wait = UnlockPolicy.waitSeconds(data.today.unlocks)
        MindfulWaitDialog(
            title = stringResource(R.string.unlock_title),
            message = stringResource(R.string.unlock_message, describeBreach(breach), data.today.unlocks),
            waitSeconds = wait,
            confirmLabel = if (breach.isReels) {
                context.resources.getQuantityString(R.plurals.unlock_confirm_reels, UnlockPolicy.EXTRA_REELS, UnlockPolicy.EXTRA_REELS)
            } else {
                context.resources.getQuantityString(R.plurals.unlock_confirm_minutes, UnlockPolicy.EXTRA_MINUTES, UnlockPolicy.EXTRA_MINUTES)
            },
            onConfirm = { runtime.unlock(breach) },
            onDismiss = { runtime.clearUnlockRequest() },
        )
    }
}
