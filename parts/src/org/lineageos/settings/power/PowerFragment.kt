/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.power

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.lineageos.settings.R
import org.lineageos.settings.power.PowerCapService
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.putInt
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putString

class PowerFragment : PreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener {

    private lateinit var whitelistPref: MultiSelectListPreference
    private lateinit var enablePref: SwitchPreferenceCompat
    private lateinit var allowFullPref: SwitchPreferenceCompat
    private lateinit var respectSystemModesPref: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.power_preferences)

        enablePref = findPreference("powercap_enable")!!
        allowFullPref = findPreference("powercap_allow_full")!!
        respectSystemModesPref = findPreference("powercap_respect_system_modes")!!
        whitelistPref = findPreference("powercap_whitelist")!!

        enablePref.onPreferenceChangeListener = this
        allowFullPref.onPreferenceChangeListener = this
        respectSystemModesPref.onPreferenceChangeListener = this
        whitelistPref.onPreferenceChangeListener = this

        refreshWhitelistEntries()
        loadState()

        PowerCapService.startOrStop(requireContext())
    }

    override fun onResume() {
        super.onResume()
        maybePromptUsageAccess()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            "powercap_enable" -> {
                putInt(requireContext(), "powercap_enable", if (newValue as Boolean) 1 else 0)
                PowerCapService.startOrStop(requireContext())
            }
            "powercap_allow_full" -> {
                putInt(requireContext(), "powercap_allow_full", if (newValue as Boolean) 1 else 0)
                PowerCapService.startOrStop(requireContext())
            }
            "powercap_respect_system_modes" -> {
                putInt(requireContext(), "powercap_respect_system_modes", if (newValue as Boolean) 1 else 0)
                PowerCapService.startOrStop(requireContext())
            }
            "powercap_whitelist" -> {
                val set = newValue as Set<*>
                putString(requireContext(), "powercap_whitelist", set.joinToString(","))
                PowerCapService.startOrStop(requireContext())
            }
        }
        return true
    }

    private fun loadState() {
        enablePref.isChecked = getInt(requireContext(), "powercap_enable", 1) == 1
        allowFullPref.isChecked = getInt(requireContext(), "powercap_allow_full", 1) == 1
        respectSystemModesPref.isChecked = getInt(requireContext(), "powercap_respect_system_modes", 1) == 1
        val current = getString(requireContext(), "powercap_whitelist")?.split(",")?.filter { it.isNotBlank() }?.toSet()
        if (current != null) whitelistPref.values = current
    }

    private fun refreshWhitelistEntries() {
        val pm = requireContext().packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString() }
        val entries = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        val values = apps.map { it.packageName }.toTypedArray()
        whitelistPref.entries = entries
        whitelistPref.entryValues = values
    }

    private fun maybePromptUsageAccess() {
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            requireContext().packageName
        )
        if (mode != AppOpsManager.MODE_ALLOWED) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }
}
