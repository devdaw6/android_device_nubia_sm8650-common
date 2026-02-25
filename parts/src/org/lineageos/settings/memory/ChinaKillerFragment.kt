/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.memory

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import java.util.Locale
import org.lineageos.settings.R
import org.lineageos.settings.utils.getInt
import org.lineageos.settings.utils.getString
import org.lineageos.settings.utils.putInt
import org.lineageos.settings.utils.putString

class ChinaKillerFragment : SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private lateinit var enablePref: SwitchPreferenceCompat
    private lateinit var whitelistPref: Preference
    private var availableApps: List<AppEntry> = emptyList()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.china_killer_preferences)

        enablePref = findPreference("china_killer_enable")!!
        whitelistPref = findPreference("china_killer_whitelist")!!

        enablePref.onPreferenceChangeListener = this
        whitelistPref.onPreferenceClickListener = this

        loadAvailableApps()
        loadState()
    }

    override fun onResume() {
        super.onResume()
        updateWhitelistState(readWhitelist().size)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (preference.key == "china_killer_enable") {
            val enabled = newValue as Boolean
            putInt(requireContext(), "china_killer_enable", if (enabled) 1 else 0)
            val persisted = getInt(requireContext(), "china_killer_enable", 0) == 1
            enablePref.isChecked = persisted
            RecentsKillService.applyChinaMode(requireContext(), persisted)
            updateWhitelistState(readWhitelist().size)
        }
        return true
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference.key != "china_killer_whitelist" || !enablePref.isChecked) {
            return false
        }
        showWhitelistDialog()
        return true
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

    private fun loadState() {
        val enabled = getInt(requireContext(), "china_killer_enable", 0) == 1
        enablePref.isChecked = enabled
        RecentsKillService.applyChinaMode(requireContext(), enabled)
        updateWhitelistState(readWhitelist().size)
    }

    private fun readWhitelist(): Set<String> {
        return getString(requireContext(), "china_killer_whitelist")
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
        putString(requireContext(), "china_killer_whitelist", normalized.joinToString(","))
        RecentsKillService.applyWhitelist(requireContext())
        updateWhitelistState(normalized.size)
    }

    private fun updateWhitelistState(selectedCount: Int) {
        val enabled = enablePref.isChecked
        whitelistPref.isEnabled = enabled
        whitelistPref.summary = if (!enabled) {
            getString(R.string.china_killer_whitelist_disabled)
        } else if (selectedCount > 0) {
            getString(R.string.china_killer_whitelist_summary_count, selectedCount)
        } else {
            getString(R.string.china_killer_whitelist_summary)
        }
    }

    private fun showWhitelistDialog() {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.china_killer_whitelist_dialog, null)
        val search = view.findViewById<EditText>(R.id.china_killer_search)
        val list = view.findViewById<ListView>(R.id.china_killer_app_list)
        val empty = view.findViewById<TextView>(R.id.china_killer_empty)
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
            .setTitle(R.string.china_killer_whitelist_title)
            .setView(view)
            .setPositiveButton(R.string.china_killer_whitelist_save) { _, _ ->
                persistWhitelist(adapter.getSelectedPackages())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                R.layout.china_killer_whitelist_item,
                parent,
                false
            )
            val item = getItem(position)

            row.findViewById<ImageView>(R.id.china_killer_item_icon).setImageDrawable(item.icon)
            row.findViewById<TextView>(R.id.china_killer_item_label).text = item.label
            row.findViewById<TextView>(R.id.china_killer_item_package).text = item.packageName
            row.findViewById<android.widget.CheckBox>(R.id.china_killer_item_checkbox).isChecked =
                selectedPackages.contains(item.packageName)
            return row
        }
    }
}
