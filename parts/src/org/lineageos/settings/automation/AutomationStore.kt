/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putString

object AutomationStore {

    private const val KEY_AUTOMATIONS = "automation_list"
    private const val TAG = "AutomationStore"

    fun getAll(context: Context): List<Automation> {
        val raw = getString(context, KEY_AUTOMATIONS) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(Automation.fromJson(obj))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse automations, resetting", e)
            emptyList()
        }
    }

    private fun saveAll(context: Context, items: List<Automation>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        putString(context, KEY_AUTOMATIONS, arr.toString())
    }

    fun upsert(context: Context, automation: Automation): List<Automation> {
        val items = getAll(context).toMutableList()

        // Ensure unique trigger binding per direction
        if (automation.triggerBinding != Automation.BINDING_NONE) {
            items.forEach {
                if (it.id != automation.id && it.triggerBinding == automation.triggerBinding) {
                    it.triggerBinding = Automation.BINDING_NONE
                }
            }
        }

        val idx = items.indexOfFirst { it.id == automation.id }
        if (idx >= 0) {
            items[idx] = automation
        } else {
            items.add(automation)
        }
        saveAll(context, items)
        return items
    }

    fun delete(context: Context, id: String) {
        val filtered = getAll(context).filterNot { it.id == id }
        saveAll(context, filtered)
    }

    fun getForBinding(context: Context, binding: Int): Automation? {
        return getAll(context).firstOrNull { it.triggerBinding == binding }
    }
}
