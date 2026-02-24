/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.memory

import android.os.Bundle
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putInt
import org.lineageos.settings.utils.putString

class ChinaKillerFragment : SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private lateinit var enablePref: SwitchPreferenceCompat
    private lateinit var whitelistPref: MultiSelectListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.china_killer_preferences)

        enablePref = findPreference("china_killer_enable")!!
        whitelistPref = findPreference("china_killer_whitelist")!!

        enablePref.onPreferenceChangeListener = this
        whitelistPref.onPreferenceChangeListener = this

        populateWhitelist()
        loadState()
    }

    override fun onResume() {
        super.onResume()
        updateWhitelistState()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            "china_killer_enable" -> {
                val on = newValue as Boolean
                putInt(requireContext(), "china_killer_enable", if (on) 1 else 0)
                val persisted = getInt(requireContext(), "china_killer_enable", 0) == 1
                enablePref.isChecked = persisted
                RecentsKillService.applyChinaMode(requireContext(), persisted)
                updateWhitelistState()
            }
            "china_killer_whitelist" -> {
                val set = newValue as Set<*>
                putString(requireContext(), "china_killer_whitelist", set.joinToString(","))
                RecentsKillService.applyWhitelist(requireContext())
            }
        }
        return true
    }

    private fun populateWhitelist() {
        val pm = requireContext().packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString() }
        whitelistPref.entries = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        whitelistPref.entryValues = apps.map { it.packageName }.toTypedArray()
    }

    private fun loadState() {
        val enabled = getInt(requireContext(), "china_killer_enable", 0) == 1
        enablePref.isChecked = enabled
        val current = getString(requireContext(), "china_killer_whitelist")?.split(",")
            ?.filter { it.isNotBlank() }?.toSet()
        if (current != null) whitelistPref.values = current
        RecentsKillService.applyChinaMode(requireContext(), enabled)
        updateWhitelistState()
    }

    private fun updateWhitelistState() {
        val enabled = enablePref.isChecked
        whitelistPref.isEnabled = enabled
        whitelistPref.summary = if (enabled) {
            getString(R.string.china_killer_whitelist_summary)
        } else {
            getString(R.string.china_killer_whitelist_disabled)
        }
    }
}
