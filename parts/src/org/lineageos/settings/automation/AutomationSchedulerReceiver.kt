/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutomationSchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id")
        AutomationScheduler.handleAlarm(context, id)
    }
}
