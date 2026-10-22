package com.brainrot.pet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brainrot.pet.AppGraph

/** Brings the pet back after a reboot or an app update (specialUse services may start from boot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppGraph.store.data.value.settings.onboardingDone) return
        BrainService.start(context)
    }
}
