/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Projectrefresh_rate_config_custom
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.content.Context
import org.lineageos.settings.utils.*

object TriggerController {

    const val KEY_TRIGGER_ENABLE = "trigger_enable"
    const val KEY_TRIGGER_ACTION_GREEN = "trigger_action_green"
    const val KEY_TRIGGER_APP = "trigger_app"
    const val KEY_TRIGGER_RECORDER = "trigger_recorder_mode"
    const val KEY_TRIGGER_FLASHLIGHT = "trigger_flashlight_mode"

    const val ACTION_CAMERA = 0
    const val ACTION_VIBRATION = 1
    const val ACTION_APP = 2

    const val TRIGGER_BUTTON1_ENABLE_NODE = "/proc/nubia_key/sar0/mode_operation"
    const val TRIGGER_BUTTON2_ENABLE_NODE = "/proc/nubia_key/sar1/mode_operation"

    const val TRIGGER_SLEEP_MODE = "2"
    const val TRIGGER_WAKE_MODE = "1"

    /*
     * Enable or disable the trigger buttons
     * @return puts the value in place
     */
    fun setTriggerEnabled(context: Context, enabled: Boolean) {
        putInt(context, KEY_TRIGGER_ENABLE, if (enabled) 1 else 0)
        writeLine(TRIGGER_BUTTON1_ENABLE_NODE, if (enabled) TRIGGER_WAKE_MODE else TRIGGER_SLEEP_MODE)
        writeLine(TRIGGER_BUTTON2_ENABLE_NODE, if (enabled) TRIGGER_WAKE_MODE else TRIGGER_SLEEP_MODE)
    }

    /*
     * Apply trigger buttons state settings
     * @return puts the value in place
     */
    fun applySettings(context: Context, enabled: Boolean) {
        setTriggerEnabled(context, enabled)
    }

    /*
     * Restore settings on boot / resume
     * @return puts the value in place
     */
    fun restoreSettings(context: Context) {
        val triggerEnabled = getInt(context, KEY_TRIGGER_ENABLE, 0) == 1

        applySettings(context, triggerEnabled)
    }

    fun setAction(context: Context, action: Int) {
        putInt(context, KEY_TRIGGER_ACTION_GREEN, action)
    }

    fun getAction(context: Context): Int {
        return getInt(context, KEY_TRIGGER_ACTION_GREEN, ACTION_CAMERA)
    }

    fun setApp(context: Context, component: String?) {
        putString(context, KEY_TRIGGER_APP, component)
    }

    fun getApp(context: Context): String? {
        return getString(context, KEY_TRIGGER_APP)
    }

    fun setRecorderMode(context: Context, enabled: Boolean) {
        putInt(context, KEY_TRIGGER_RECORDER, if (enabled) 1 else 0)
    }

    fun isRecorderMode(context: Context): Boolean {
        return getInt(context, KEY_TRIGGER_RECORDER, 0) == 1
    }

    fun setFlashlightMode(context: Context, enabled: Boolean) {
        putInt(context, KEY_TRIGGER_FLASHLIGHT, if (enabled) 1 else 0)
    }

    fun isFlashlightMode(context: Context): Boolean {
        return getInt(context, KEY_TRIGGER_FLASHLIGHT, 0) == 1
    }
}
