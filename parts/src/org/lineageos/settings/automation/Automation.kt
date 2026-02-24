/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import org.json.JSONObject
import java.util.UUID

/**
 * Simple model for user-created automation (shortcut).
 */
data class Automation(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var action: Int,
    var appComponent: String? = null,
    var url: String? = null,
    var clipboardText: String? = null,
    var pinShortcut: Boolean = false,
    var shortcutLabel: String? = null,
    var shortcutIcon: String? = null,
    var shortcutIconUri: String? = null,
    // Messaging intent
    var messageText: String? = null,
    var messageTarget: String? = null, // phone or package
    var messageIsSms: Boolean = true,
    // Broadcast
    var broadcastAction: String? = null,
    var broadcastExtraKey: String? = null,
    var broadcastExtraValue: String? = null,
    // Schedule
    var scheduleEnabled: Boolean = false,
    var scheduleMinuteOfDay: Int? = null,
    var scheduleRepeatDaily: Boolean = true,
    // Event triggers
    var triggerType: Int = TRIGGER_NONE,
    var triggerApp: String? = null,
    var triggerBinding: Int = BINDING_NONE,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("action", action)
        put("appComponent", appComponent)
        put("url", url)
        put("clipboardText", clipboardText)
        put("pinShortcut", pinShortcut)
        put("shortcutLabel", shortcutLabel)
        put("shortcutIcon", shortcutIcon)
        put("shortcutIconUri", shortcutIconUri)
        put("messageText", messageText)
        put("messageTarget", messageTarget)
        put("messageIsSms", messageIsSms)
        put("broadcastAction", broadcastAction)
        put("broadcastExtraKey", broadcastExtraKey)
        put("broadcastExtraValue", broadcastExtraValue)
        put("scheduleEnabled", scheduleEnabled)
        put("scheduleMinuteOfDay", scheduleMinuteOfDay)
        put("scheduleRepeatDaily", scheduleRepeatDaily)
        put("triggerType", triggerType)
        put("triggerApp", triggerApp)
        put("triggerBinding", triggerBinding)
    }

    companion object {
        const val BINDING_NONE = 0
        const val BINDING_GREEN = 1
        const val BINDING_RED = 2
        const val TRIGGER_NONE = 0
        const val TRIGGER_APP_OPEN = 1
        const val TRIGGER_APP_CLOSE = 2
        const val TRIGGER_CALL_END = 3

        fun fromJson(obj: JSONObject): Automation = Automation(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", ""),
            action = obj.optInt("action", AutomationExecutor.ACTION_OPEN_URL),
            appComponent = obj.optString("appComponent", null),
            url = obj.optString("url", null),
            clipboardText = obj.optString("clipboardText", null),
            pinShortcut = obj.optBoolean("pinShortcut", false),
            shortcutLabel = obj.optString("shortcutLabel", null),
            shortcutIcon = obj.optString("shortcutIcon", null),
            shortcutIconUri = obj.optString("shortcutIconUri", null),
            messageText = obj.optString("messageText", null),
            messageTarget = obj.optString("messageTarget", null),
            messageIsSms = obj.optBoolean("messageIsSms", true),
            broadcastAction = obj.optString("broadcastAction", null),
            broadcastExtraKey = obj.optString("broadcastExtraKey", null),
            broadcastExtraValue = obj.optString("broadcastExtraValue", null),
            scheduleEnabled = obj.optBoolean("scheduleEnabled", false),
            scheduleMinuteOfDay = obj.optInt("scheduleMinuteOfDay").let { if (obj.has("scheduleMinuteOfDay")) it else null },
            scheduleRepeatDaily = obj.optBoolean("scheduleRepeatDaily", true),
            triggerType = obj.optInt("triggerType", TRIGGER_NONE),
            triggerApp = obj.optString("triggerApp", null),
            triggerBinding = obj.optInt("triggerBinding", BINDING_NONE),
        )
    }
}
