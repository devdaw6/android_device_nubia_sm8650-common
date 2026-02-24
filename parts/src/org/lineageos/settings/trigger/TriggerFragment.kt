/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.trigger

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment

import org.lineageos.settings.R
import org.lineageos.settings.utils.*
import org.lineageos.settings.automation.AutomationActivity
import org.lineageos.settings.automation.Automation
import org.lineageos.settings.automation.AutomationStore

class TriggerFragment : SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private lateinit var mActionPref: ListPreference
    private lateinit var mAppPref: Preference
    private lateinit var mRecorderPref: SwitchPreferenceCompat
    private lateinit var mFlashlightPref: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.trigger_preferences)

        mActionPref = findPreference<ListPreference>(TriggerController.KEY_TRIGGER_ACTION_GREEN)!!.apply {
            value = TriggerController.getAction(requireContext()).toString()
            onPreferenceChangeListener = this@TriggerFragment
        }

        mAppPref = findPreference<Preference>(TriggerController.KEY_TRIGGER_APP)!!.apply {
            onPreferenceClickListener = this@TriggerFragment
        }

        mRecorderPref = findPreference<SwitchPreferenceCompat>(TriggerController.KEY_TRIGGER_RECORDER)!!.apply {
            onPreferenceChangeListener = this@TriggerFragment
        }

        mFlashlightPref = findPreference<SwitchPreferenceCompat>(TriggerController.KEY_TRIGGER_FLASHLIGHT)!!.apply {
            onPreferenceChangeListener = this@TriggerFragment
        }

        updateActionSummary()
        updateModePreferences()

        TriggerController.setTriggerEnabled(requireContext(), true)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        return when (preference.key) {
            TriggerController.KEY_TRIGGER_ACTION_GREEN -> {
                val action = (newValue as String).toInt()
                TriggerController.setAction(requireContext(), action)
                updateActionSummary()
                updateModePreferences()
                true
            }

            TriggerController.KEY_TRIGGER_RECORDER -> {
                val enabled = newValue as Boolean
                if (enabled) {
                    TriggerController.setFlashlightMode(requireContext(), false)
                }
                TriggerController.setRecorderMode(requireContext(), enabled)
                updateModePreferences()
                true
            }

            TriggerController.KEY_TRIGGER_FLASHLIGHT -> {
                val enabled = newValue as Boolean
                if (enabled) {
                    TriggerController.setRecorderMode(requireContext(), false)
                }
                TriggerController.setFlashlightMode(requireContext(), enabled)
                updateModePreferences()
                true
            }

            else -> false
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return when (preference.key) {
            TriggerController.KEY_TRIGGER_APP -> {
                if (TriggerController.getAction(requireContext()) == TriggerController.ACTION_AUTOMATION) {
                    startActivity(Intent(requireContext(), AutomationActivity::class.java))
                } else {
                    showAppPicker()
                }
                true
            }
            else -> false
        }
    }

    override fun onResume() {
        super.onResume()
        TriggerController.setTriggerEnabled(requireContext(), true)
        updateModePreferences()
    }

    private fun updateActionSummary() {
        val idx = mActionPref.findIndexOfValue(mActionPref.value)
        if (idx >= 0) {
            mActionPref.summary = mActionPref.entries[idx]
        }
    }

    private fun updateModePreferences() {
        val recorder = TriggerController.isRecorderMode(requireContext())
        val flashlight = TriggerController.isFlashlightMode(requireContext())
        mRecorderPref.isChecked = recorder
        mFlashlightPref.isChecked = flashlight

        val disableExtras = TriggerController.getAction(requireContext()) == TriggerController.ACTION_AUTOMATION
        mRecorderPref.isEnabled = !flashlight && !disableExtras
        mFlashlightPref.isEnabled = !recorder && !disableExtras

        val modeActive = recorder || flashlight
        val action = TriggerController.getAction(requireContext())
        mActionPref.isEnabled = !modeActive

        val appEnabled = !modeActive && (action == TriggerController.ACTION_APP)
        val automationEnabled = !modeActive && (action == TriggerController.ACTION_AUTOMATION)

        mAppPref.isVisible = action == TriggerController.ACTION_APP
        if (action == TriggerController.ACTION_APP) {
            mAppPref.title = getString(R.string.trigger_choose_app_title)
            mAppPref.isEnabled = appEnabled
            if (!appEnabled) {
                mAppPref.summary = getString(R.string.trigger_app_not_set)
                mAppPref.icon = null
                return
            }

            val component = TriggerController.getApp(requireContext())
            val info = getAppInfo(component)
            if (info == null) {
                mAppPref.summary = getString(R.string.trigger_app_not_set)
                mAppPref.icon = null
            } else {
                mAppPref.summary = info.loadLabel(requireContext().packageManager)
                mAppPref.icon = info.loadIcon(requireContext().packageManager)
            }
        } else if (action == TriggerController.ACTION_AUTOMATION) {
            val bound = AutomationStore.getForBinding(requireContext(), Automation.BINDING_GREEN)
            mAppPref.isVisible = true
            mAppPref.isEnabled = automationEnabled
            mAppPref.title = getString(R.string.automation_title)
            if (bound != null) {
                mAppPref.summary = bound.name
            } else {
                mAppPref.summary = getString(R.string.automation_empty)
            }
            mAppPref.icon = null
        } else {
            mAppPref.isVisible = false
        }
    }

    private fun showAppPicker() {
        val context = requireContext()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        if (apps.isEmpty()) return

        val adapter = AppListAdapter(context, apps)
        AlertDialog.Builder(context)
            .setTitle(R.string.trigger_choose_app_title)
            .setAdapter(adapter) { _, which ->
                val info = apps[which]
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                TriggerController.setApp(context, component.flattenToString())
                updateModePreferences()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun getAppInfo(componentString: String?): ResolveInfo? {
        if (componentString.isNullOrEmpty()) return null
        val component = ComponentName.unflattenFromString(componentString) ?: return null
        val pm = requireContext().packageManager
        return pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component),
            0
        )
    }

    private class AppListAdapter(
        context: Context,
        items: List<ResolveInfo>
    ) : ArrayAdapter<ResolveInfo>(context, android.R.layout.activity_list_item, items) {
        private val inflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(android.R.layout.activity_list_item, parent, false)
            val iconView = view.findViewById<ImageView>(android.R.id.icon)
            val textView = view.findViewById<TextView>(android.R.id.text1)
            val info = getItem(position)!!
            val pm = context.packageManager
            textView.text = info.loadLabel(pm)
            iconView.setImageDrawable(info.loadIcon(pm))
            return view
        }
    }
}
