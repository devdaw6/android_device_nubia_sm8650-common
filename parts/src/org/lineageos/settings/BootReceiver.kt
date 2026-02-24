/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import org.lineageos.settings.memory.RecentsKillService
import org.lineageos.settings.trigger.TriggerController
import org.lineageos.settings.power.PowerCapService
import org.lineageos.settings.automation.AutomationScheduler
import org.lineageos.settings.automation.AutomationEventService
import org.lineageos.settings.utils.getInt

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            TriggerController.restoreSettings(context)
            PowerCapService.startOrStop(context)
            if (getInt(context, "china_killer_enable", 0) == 1) {
                RecentsKillService.start(context)
            }
            AutomationScheduler.reschedule(context)
            AutomationEventService.startOrStop(context)
        }
    }
}
