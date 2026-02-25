/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.power

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Locale
import org.lineageos.settings.R
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putInt
import org.lineageos.settings.utils.putString

class PowerFragment : PreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private lateinit var whitelistPref: Preference
    private lateinit var enablePref: SwitchPreferenceCompat
    private lateinit var allowFullPref: SwitchPreferenceCompat
    private lateinit var respectSystemModesPref: SwitchPreferenceCompat
    private var availableApps: List<AppEntry> = emptyList()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.power_preferences)

        enablePref = findPreference("powercap_enable")!!
        allowFullPref = findPreference("powercap_allow_full")!!
        respectSystemModesPref = findPreference("powercap_respect_system_modes")!!
        whitelistPref = findPreference("powercap_whitelist")!!

        enablePref.onPreferenceChangeListener = this
        allowFullPref.onPreferenceChangeListener = this
        respectSystemModesPref.onPreferenceChangeListener = this
        whitelistPref.onPreferenceClickListener = this

        loadAvailableApps()
        loadState()

        PowerCapService.startOrStop(requireContext())
    }

    override fun onResume() {
        super.onResume()
        maybePromptUsageAccess()
        updateWhitelistSummary(readWhitelist().size)
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
        }
        return true
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference.key != "powercap_whitelist") {
            return false
        }
        showWhitelistDialog()
        return true
    }

    private fun loadState() {
        enablePref.isChecked = getInt(requireContext(), "powercap_enable", 1) == 1
        allowFullPref.isChecked = getInt(requireContext(), "powercap_allow_full", 1) == 1
        respectSystemModesPref.isChecked = getInt(requireContext(), "powercap_respect_system_modes", 1) == 1
        updateWhitelistSummary(readWhitelist().size)
    }

    private fun loadAvailableApps() {
        val pm = requireContext().packageManager
        availableApps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                val label = it.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { it.packageName }
                AppEntry(
                    label = label,
                    packageName = it.packageName,
                    icon = it.loadIcon(pm),
                    searchable = "$label ${it.packageName}".lowercase(Locale.getDefault())
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun readWhitelist(): Set<String> {
        return getString(requireContext(), "powercap_whitelist")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    private fun persistWhitelist(packages: Set<String>) {
        val normalized = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        putString(requireContext(), "powercap_whitelist", normalized.joinToString(","))
        PowerCapService.startOrStop(requireContext())
        updateWhitelistSummary(normalized.size)
    }

    private fun updateWhitelistSummary(selectedCount: Int) {
        whitelistPref.summary = if (selectedCount > 0) {
            getString(R.string.powercap_whitelist_summary_count, selectedCount)
        } else {
            getString(R.string.powercap_whitelist_summary)
        }
    }

    private fun showWhitelistDialog() {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.power_whitelist_dialog, null)
        val search = view.findViewById<EditText>(R.id.power_whitelist_search)
        val list = view.findViewById<ListView>(R.id.power_whitelist_app_list)
        val empty = view.findViewById<TextView>(R.id.power_whitelist_empty)
        list.emptyView = empty

        val selectedPackages = readWhitelist().toMutableSet()
        val adapter = WhitelistAdapter(context, availableApps, selectedPackages)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.toggle(position)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })

        AlertDialog.Builder(context)
            .setTitle(R.string.powercap_whitelist_title)
            .setView(view)
            .setPositiveButton(R.string.powercap_whitelist_save) { _, _ ->
                persistWhitelist(adapter.getSelectedPackages())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: Drawable,
        val searchable: String
    )

    private class WhitelistAdapter(
        context: Context,
        private val allApps: List<AppEntry>,
        private val selectedPackages: MutableSet<String>
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        private var filteredApps: List<AppEntry> = allApps

        override fun getCount(): Int = filteredApps.size

        override fun getItem(position: Int): AppEntry = filteredApps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        fun toggle(position: Int) {
            val pkg = getItem(position).packageName
            if (!selectedPackages.add(pkg)) {
                selectedPackages.remove(pkg)
            }
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            val normalized = query.trim().lowercase(Locale.getDefault())
            filteredApps = if (normalized.isEmpty()) {
                allApps
            } else {
                allApps.filter { it.searchable.contains(normalized) }
            }
            notifyDataSetChanged()
        }

        fun getSelectedPackages(): Set<String> = selectedPackages.toSet()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(
                R.layout.power_whitelist_item,
                parent,
                false
            )
            val item = getItem(position)

            row.findViewById<ImageView>(R.id.power_whitelist_item_icon).setImageDrawable(item.icon)
            row.findViewById<TextView>(R.id.power_whitelist_item_label).text = item.label
            row.findViewById<TextView>(R.id.power_whitelist_item_package).text = item.packageName
            row.findViewById<android.widget.CheckBox>(R.id.power_whitelist_item_checkbox).isChecked =
                selectedPackages.contains(item.packageName)
            return row
        }
    }
}
