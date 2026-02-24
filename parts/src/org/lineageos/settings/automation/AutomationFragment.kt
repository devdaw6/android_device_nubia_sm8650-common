/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.automation

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R
import org.lineageos.settings.automation.AutomationScheduler
import org.lineageos.settings.automation.AutomationEventService

class AutomationFragment : SettingsBasePreferenceFragment(),
    Preference.OnPreferenceClickListener {

    private lateinit var listCategory: PreferenceCategory
    private lateinit var addPref: Preference
    private lateinit var presetsPref: Preference
    private var iconPickerCallback: ((String) -> Unit)? = null
    private val iconPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            iconPickerCallback?.invoke(uri.toString())
        }
    }

    private fun getWifiState(): String {
        val wm = context?.applicationContext?.getSystemService(android.net.wifi.WifiManager::class.java)
        return if (wm?.isWifiEnabled == true) getString(R.string.automation_wifi_on) else getString(R.string.automation_wifi_off)
    }

    private fun getDndState(): String {
        val nm = context?.getSystemService(android.app.NotificationManager::class.java)
        return if (nm?.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
            getString(R.string.automation_dnd_on) else getString(R.string.automation_dnd_off)
    }

    private fun formatMinute(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        return String.format("%02d:%02d", h, m)
    }

    private fun showPresetsDialog() {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.automation_presets_dialog, null)
        val morningEnable = view.findViewById<CheckBox>(R.id.preset_morning_enable)
        val morningTimeBtn = view.findViewById<Button>(R.id.preset_morning_time_btn)
        val morningTimeVal = view.findViewById<TextView>(R.id.preset_morning_time_val)
        val morningUrl = view.findViewById<EditText>(R.id.preset_morning_url)
        val eveningEnable = view.findViewById<CheckBox>(R.id.preset_evening_enable)
        val eveningTimeBtn = view.findViewById<Button>(R.id.preset_evening_time_btn)
        val eveningTimeVal = view.findViewById<TextView>(R.id.preset_evening_time_val)
        val reminderEnable = view.findViewById<CheckBox>(R.id.preset_reminder_enable)
        val reminderTimeBtn = view.findViewById<Button>(R.id.preset_reminder_time_btn)
        val reminderTimeVal = view.findViewById<TextView>(R.id.preset_reminder_time_val)
        val reminderText = view.findViewById<EditText>(R.id.preset_reminder_text)
        val reminderPhone = view.findViewById<EditText>(R.id.preset_reminder_phone)

        var morningMinute = 7 * 60 + 30
        var eveningMinute = 22 * 60 + 30
        var reminderMinute = 12 * 60 + 0

        fun setLabel(tv: TextView, m: Int) {
            tv.text = formatMinute(m)
        }
        setLabel(morningTimeVal, morningMinute)
        setLabel(eveningTimeVal, eveningMinute)
        setLabel(reminderTimeVal, reminderMinute)

        morningTimeBtn.setOnClickListener {
            val h = morningMinute / 60
            val m = morningMinute % 60
            android.app.TimePickerDialog(ctx, { _, hh, mm ->
                morningMinute = hh * 60 + mm
                setLabel(morningTimeVal, morningMinute)
            }, h, m, true).show()
        }
        eveningTimeBtn.setOnClickListener {
            val h = eveningMinute / 60
            val m = eveningMinute % 60
            android.app.TimePickerDialog(ctx, { _, hh, mm ->
                eveningMinute = hh * 60 + mm
                setLabel(eveningTimeVal, eveningMinute)
            }, h, m, true).show()
        }
        reminderTimeBtn.setOnClickListener {
            val h = reminderMinute / 60
            val m = reminderMinute % 60
            android.app.TimePickerDialog(ctx, { _, hh, mm ->
                reminderMinute = hh * 60 + mm
                setLabel(reminderTimeVal, reminderMinute)
            }, h, m, true).show()
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.automation_presets_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (morningEnable.isChecked) {
                    AutomationStore.upsert(ctx, Automation(
                        id = "preset_morning",
                        name = getString(R.string.automation_preset_morning),
                        action = AutomationExecutor.ACTION_OPEN_URL,
                        url = morningUrl.text.toString().ifBlank { "https://news.ycombinator.com" },
                        pinShortcut = false,
                        scheduleEnabled = true,
                        scheduleMinuteOfDay = morningMinute,
                        scheduleRepeatDaily = true
                    ))
                }
                if (eveningEnable.isChecked) {
                    AutomationStore.upsert(ctx, Automation(
                        id = "preset_evening",
                        name = getString(R.string.automation_preset_evening),
                        action = AutomationExecutor.ACTION_TOGGLE_DND,
                        scheduleEnabled = true,
                        scheduleMinuteOfDay = eveningMinute,
                        scheduleRepeatDaily = true
                    ))
                    // Also dim screen via auto-brightness toggle off
                    AutomationStore.upsert(ctx, Automation(
                        id = "preset_evening_brightness",
                        name = getString(R.string.automation_action_autobrightness),
                        action = AutomationExecutor.ACTION_TOGGLE_AUTO_BRIGHTNESS,
                        scheduleEnabled = true,
                        scheduleMinuteOfDay = eveningMinute,
                        scheduleRepeatDaily = true
                    ))
                }
                if (reminderEnable.isChecked) {
                    AutomationStore.upsert(ctx, Automation(
                        id = "preset_reminder",
                        name = getString(R.string.automation_preset_reminder),
                        action = AutomationExecutor.ACTION_SEND_MESSAGE,
                        messageText = reminderText.text.toString().ifBlank { getString(R.string.automation_preset_reminder) },
                        messageTarget = reminderPhone.text.toString().ifBlank { null },
                        messageIsSms = true,
                        scheduleEnabled = true,
                        scheduleMinuteOfDay = reminderMinute,
                        scheduleRepeatDaily = true
                    ))
                }
                AutomationScheduler.reschedule(ctx)
                AutomationEventService.startOrStop(ctx)
                rebuildList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.automation_preferences)

        listCategory = findPreference("automation_list")!!
        addPref = findPreference("automation_add")!!
        presetsPref = findPreference("automation_presets")!!
        addPref.onPreferenceClickListener = this
        presetsPref.onPreferenceClickListener = this

        rebuildList()
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference.key == addPref.key) {
            showEditDialog(null)
            return true
        }
        if (preference.key == presetsPref.key) {
            showPresetsDialog()
            return true
        }
        val automation = AutomationStore.getAll(requireContext()).firstOrNull { it.id == preference.key }
        automation?.let { showEditDialog(it) }
        return true
    }

    private fun rebuildList() {
        listCategory.removeAll()
        val automations = AutomationStore.getAll(requireContext())
        if (automations.isEmpty()) {
            val empty = Preference(requireContext()).apply {
                isSelectable = false
                title = getString(R.string.automation_empty)
            }
            listCategory.addPreference(empty)
        } else {
            automations.forEach { addAutomationPref(it) }
        }
    }

    private fun addAutomationPref(automation: Automation) {
        val pref = Preference(requireContext()).apply {
            key = automation.id
            title = automation.name
            summary = buildSummary(automation)
            onPreferenceClickListener = this@AutomationFragment
        }
        listCategory.addPreference(pref)
    }

    private fun buildSummary(automation: Automation): String {
        val actionLabel = when (automation.action) {
            AutomationExecutor.ACTION_OPEN_URL -> getString(R.string.automation_action_url)
            AutomationExecutor.ACTION_COPY_TEXT -> getString(R.string.automation_action_copy)
            AutomationExecutor.ACTION_TOGGLE_DND -> getString(R.string.automation_action_dnd)
            AutomationExecutor.ACTION_TOGGLE_AUTO_BRIGHTNESS -> getString(R.string.automation_action_autobrightness)
            AutomationExecutor.ACTION_TOGGLE_WIFI -> getString(R.string.automation_action_wifi)
            AutomationExecutor.ACTION_MEDIA_PLAY_PAUSE -> getString(R.string.automation_action_media)
            else -> getString(R.string.automation_action_unknown)
        }
        val detail = when (automation.action) {
            AutomationExecutor.ACTION_OPEN_URL -> automation.url ?: ""
            AutomationExecutor.ACTION_COPY_TEXT -> automation.clipboardText?.take(24) ?: ""
            AutomationExecutor.ACTION_TOGGLE_WIFI -> getWifiState()
            AutomationExecutor.ACTION_TOGGLE_DND -> getDndState()
            else -> ""
        }.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
        val triggerLabel = when (automation.triggerBinding) {
            Automation.BINDING_GREEN -> getString(R.string.automation_trigger_green)
            Automation.BINDING_RED -> getString(R.string.automation_trigger_red)
            else -> getString(R.string.automation_trigger_none)
        }
        return "$actionLabel$detail • $triggerLabel"
    }

    private fun showEditDialog(existing: Automation?) {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.automation_edit_dialog, null)

        val nameInput = view.findViewById<EditText>(R.id.automation_name)
        val spinner = view.findViewById<Spinner>(R.id.automation_action_spinner)
        val triggerSpinner = view.findViewById<Spinner>(R.id.automation_trigger_spinner)
        val urlInput = view.findViewById<EditText>(R.id.automation_url)
        val textInput = view.findViewById<EditText>(R.id.automation_text)
        val pinShortcut = view.findViewById<CheckBox>(R.id.automation_pin_shortcut)
        val shortcutLabel = view.findViewById<EditText>(R.id.automation_shortcut_label)
        val shortcutIcon = view.findViewById<Spinner>(R.id.automation_shortcut_icon)
        val customIconRow = view.findViewById<View>(R.id.automation_custom_icon_row)
        val customIconPick = view.findViewById<Button>(R.id.automation_pick_icon)
        val customIconStatus = view.findViewById<TextView>(R.id.automation_icon_status)
        val customIconClear = view.findViewById<ImageButton>(R.id.automation_icon_clear)
        val appRow = view.findViewById<View>(R.id.app_picker_row)
        val appSummary = view.findViewById<TextView>(R.id.automation_app_summary)
        val chooseAppBtn = view.findViewById<Button>(R.id.automation_choose_app)
        val triggerAppRow = view.findViewById<View>(R.id.automation_trigger_app_row)
        val triggerAppBtn = view.findViewById<Button>(R.id.automation_trigger_app_btn)
        val triggerAppSummary = view.findViewById<TextView>(R.id.automation_trigger_app_summary)
        val bindTrigger = view.findViewById<CheckBox>(R.id.automation_bind_trigger)
        val triggerGroup = view.findViewById<RadioGroup>(R.id.automation_trigger_group)
        val triggerGreen = view.findViewById<RadioButton>(R.id.automation_trigger_green)
        val triggerRed = view.findViewById<RadioButton>(R.id.automation_trigger_red)
        val phoneInput = view.findViewById<EditText>(R.id.automation_phone)
        val packageInput = view.findViewById<EditText>(R.id.automation_package)
        val isSmsCheck = view.findViewById<CheckBox>(R.id.automation_is_sms)
        val broadcastActionInput = view.findViewById<EditText>(R.id.automation_broadcast_action)
        val broadcastExtraKeyInput = view.findViewById<EditText>(R.id.automation_broadcast_extra_key)
        val broadcastExtraValueInput = view.findViewById<EditText>(R.id.automation_broadcast_extra_value)
        val scheduleEnable = view.findViewById<CheckBox>(R.id.automation_schedule_enable)
        val scheduleTimeBtn = view.findViewById<Button>(R.id.automation_schedule_time)
        val scheduleTimeValue = view.findViewById<TextView>(R.id.automation_schedule_time_value)
        val scheduleRepeat = view.findViewById<CheckBox>(R.id.automation_schedule_repeat)

        val actionEntries = resources.getStringArray(R.array.automation_action_entries)
        val actionValues = intArrayOf(
            AutomationExecutor.ACTION_OPEN_URL,
            AutomationExecutor.ACTION_COPY_TEXT,
            AutomationExecutor.ACTION_TOGGLE_DND,
            AutomationExecutor.ACTION_TOGGLE_AUTO_BRIGHTNESS,
            AutomationExecutor.ACTION_TOGGLE_WIFI,
            AutomationExecutor.ACTION_MEDIA_PLAY_PAUSE,
            AutomationExecutor.ACTION_SEND_MESSAGE,
            AutomationExecutor.ACTION_BROADCAST,
            AutomationExecutor.ACTION_FORCE_STOP
        )
        val triggerEntries = resources.getStringArray(R.array.automation_trigger_entries)
        val triggerValues = intArrayOf(
            Automation.TRIGGER_NONE,
            Automation.TRIGGER_APP_OPEN,
            Automation.TRIGGER_APP_CLOSE,
            Automation.TRIGGER_CALL_END
        )
        val iconEntries = resources.getStringArray(R.array.automation_icon_entries)
        val iconValues = resources.getStringArray(R.array.automation_icon_values)
        val iconAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, iconEntries).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        shortcutIcon.adapter = iconAdapter

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, actionEntries).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter

        val triggerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, triggerEntries).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        triggerSpinner.adapter = triggerAdapter

        var selectedComponent: String? = existing?.appComponent
        var selectedCustomIcon: String? = existing?.shortcutIconUri
        var selectedTriggerApp: String? = existing?.triggerApp
        urlInput.setText(existing?.url ?: "")
        textInput.setText(existing?.clipboardText ?: "")
        pinShortcut.isChecked = existing?.pinShortcut == true
        shortcutLabel.setText(existing?.shortcutLabel ?: "")
        existing?.shortcutIcon?.let {
            val idx = iconValues.indexOf(it).takeIf { i -> i >= 0 } ?: -1
            if (idx >= 0) shortcutIcon.setSelection(idx)
        }
        customIconStatus.text = selectedCustomIcon?.let { uri -> uri.substringAfterLast('/') } ?: getString(R.string.automation_icon_none)
        phoneInput.setText(existing?.messageTarget ?: "")
        packageInput.setText(existing?.messageTarget ?: "")
        isSmsCheck.isChecked = existing?.messageIsSms ?: true
        broadcastActionInput.setText(existing?.broadcastAction ?: "")
        broadcastExtraKeyInput.setText(existing?.broadcastExtraKey ?: "")
        broadcastExtraValueInput.setText(existing?.broadcastExtraValue ?: "")
        var scheduleMinute: Int? = existing?.scheduleMinuteOfDay
        scheduleEnable.isChecked = existing?.scheduleEnabled ?: false
        scheduleRepeat.isChecked = existing?.scheduleRepeatDaily ?: true
        fun updateScheduleLabel() {
            scheduleTimeValue.text = scheduleMinute?.let { formatMinute(it) } ?: getString(R.string.automation_schedule_not_set)
        }
        updateScheduleLabel()
        triggerAppSummary.text = selectedTriggerApp?.let { loadAppLabel(ctx, it) }
            ?: getString(R.string.automation_trigger_app_not_set)

        fun updateFieldVisibility() {
            when (actionValues[spinner.selectedItemPosition]) {
                AutomationExecutor.ACTION_OPEN_URL -> {
                    urlInput.visibility = View.VISIBLE
                    pinShortcut.visibility = View.VISIBLE
                    val pinVisible = pinShortcut.isChecked
                    shortcutLabel.visibility = if (pinVisible) View.VISIBLE else View.GONE
                    shortcutIcon.visibility = if (pinVisible) View.VISIBLE else View.GONE
                    customIconRow.visibility = if (pinVisible) View.VISIBLE else View.GONE
                    textInput.visibility = View.GONE
                    appRow.visibility = View.GONE
                    phoneInput.visibility = View.GONE
                    packageInput.visibility = View.GONE
                    isSmsCheck.visibility = View.GONE
                    broadcastActionInput.visibility = View.GONE
                    broadcastExtraKeyInput.visibility = View.GONE
                    broadcastExtraValueInput.visibility = View.GONE
                }
                AutomationExecutor.ACTION_COPY_TEXT -> {
                    urlInput.visibility = View.GONE
                    pinShortcut.visibility = View.GONE
                    shortcutLabel.visibility = View.GONE
                    shortcutIcon.visibility = View.GONE
                    customIconRow.visibility = View.GONE
                    textInput.visibility = View.VISIBLE
                    appRow.visibility = View.GONE
                    phoneInput.visibility = View.GONE
                    packageInput.visibility = View.GONE
                    isSmsCheck.visibility = View.GONE
                    broadcastActionInput.visibility = View.GONE
                    broadcastExtraKeyInput.visibility = View.GONE
                    broadcastExtraValueInput.visibility = View.GONE
                }
                AutomationExecutor.ACTION_SEND_MESSAGE -> {
                    urlInput.visibility = View.GONE
                    pinShortcut.visibility = View.GONE
                    shortcutLabel.visibility = View.GONE
                    shortcutIcon.visibility = View.GONE
                    customIconRow.visibility = View.GONE
                    textInput.visibility = View.VISIBLE
                    appRow.visibility = View.GONE
                    phoneInput.visibility = View.VISIBLE
                    packageInput.visibility = View.VISIBLE
                    isSmsCheck.visibility = View.VISIBLE
                    broadcastActionInput.visibility = View.GONE
                    broadcastExtraKeyInput.visibility = View.GONE
                    broadcastExtraValueInput.visibility = View.GONE
                }
                AutomationExecutor.ACTION_BROADCAST -> {
                    urlInput.visibility = View.GONE
                    pinShortcut.visibility = View.GONE
                    shortcutLabel.visibility = View.GONE
                    shortcutIcon.visibility = View.GONE
                    customIconRow.visibility = View.GONE
                    textInput.visibility = View.GONE
                    appRow.visibility = View.GONE
                    phoneInput.visibility = View.GONE
                    packageInput.visibility = View.GONE
                    isSmsCheck.visibility = View.GONE
                    broadcastActionInput.visibility = View.VISIBLE
                    broadcastExtraKeyInput.visibility = View.VISIBLE
                    broadcastExtraValueInput.visibility = View.VISIBLE
                }
                else -> {
                    urlInput.visibility = View.GONE
                    pinShortcut.visibility = View.GONE
                    shortcutLabel.visibility = View.GONE
                    shortcutIcon.visibility = View.GONE
                    customIconRow.visibility = View.GONE
                    textInput.visibility = View.GONE
                    appRow.visibility = View.GONE
                    phoneInput.visibility = View.GONE
                    packageInput.visibility = View.GONE
                    isSmsCheck.visibility = View.GONE
                    broadcastActionInput.visibility = View.GONE
                    broadcastExtraKeyInput.visibility = View.GONE
                    broadcastExtraValueInput.visibility = View.GONE
                }
            }
            val triggerNeedsApp = triggerValues[triggerSpinner.selectedItemPosition] == Automation.TRIGGER_APP_OPEN ||
                    triggerValues[triggerSpinner.selectedItemPosition] == Automation.TRIGGER_APP_CLOSE
            triggerAppRow.visibility = if (triggerNeedsApp) View.VISIBLE else View.GONE
        }

        fun updateTriggerEnabled() {
            val enabled = bindTrigger.isChecked
            for (i in 0 until triggerGroup.childCount) {
                triggerGroup.getChildAt(i).isEnabled = enabled
            }
        }

        nameInput.setText(existing?.name ?: "")
        val actionIdx = existing?.let { actionValues.indexOf(it.action).takeIf { i -> i >= 0 } } ?: 0
        spinner.setSelection(actionIdx)
        updateFieldVisibility()
        appSummary.text = selectedComponent?.let { loadAppLabel(ctx, it) } ?: getString(R.string.trigger_app_not_set)

        val binding = existing?.triggerBinding ?: Automation.BINDING_NONE
        bindTrigger.isChecked = binding != Automation.BINDING_NONE
        when (binding) {
            Automation.BINDING_GREEN -> triggerGreen.isChecked = true
            Automation.BINDING_RED -> triggerRed.isChecked = true
        }
        val triggerType = existing?.triggerType ?: Automation.TRIGGER_NONE
        val triggerIdx = triggerValues.indexOf(triggerType).takeIf { it >= 0 } ?: 0
        triggerSpinner.setSelection(triggerIdx)
        updateTriggerEnabled()
        updateFieldVisibility()
        updateFieldVisibility()

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        triggerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        bindTrigger.setOnCheckedChangeListener { _, _ -> updateTriggerEnabled() }
        pinShortcut.setOnCheckedChangeListener { _, _ -> updateFieldVisibility() }
        scheduleEnable.setOnCheckedChangeListener { _, _ -> updateFieldVisibility() }
        scheduleTimeBtn.setOnClickListener {
            val now = java.util.Calendar.getInstance()
            val hour = scheduleMinute?.div(60) ?: now.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = scheduleMinute?.rem(60) ?: now.get(java.util.Calendar.MINUTE)
            android.app.TimePickerDialog(ctx, { _, h, m ->
                scheduleMinute = h * 60 + m
                updateScheduleLabel()
            }, hour, minute, true).show()
        }
        customIconPick.setOnClickListener {
            iconPickerCallback = { uriString ->
                selectedCustomIcon = uriString
                customIconStatus.text = Uri.parse(uriString).lastPathSegment ?: uriString
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }
            iconPicker.launch(intent)
        }
        customIconClear.setOnClickListener {
            selectedCustomIcon = null
            customIconStatus.text = getString(R.string.automation_icon_none)
        }

        chooseAppBtn.setOnClickListener {
            showAppPicker { component, label ->
                selectedComponent = component
                appSummary.text = label
            }
        }
        triggerAppBtn.setOnClickListener {
            showAppPicker { component, label ->
                val pkg = ComponentName.unflattenFromString(component)?.packageName ?: component
                selectedTriggerApp = pkg
                triggerAppSummary.text = label
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) R.string.automation_add_title else R.string.automation_edit_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.automation_delete) { _, _ ->
                        AutomationStore.delete(ctx, existing.id)
                        rebuildList()
                        AutomationEventService.startOrStop(ctx)
                    }
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, R.string.automation_error_name, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val action = actionValues[spinner.selectedItemPosition]
                if (action == AutomationExecutor.ACTION_OPEN_URL && urlInput.text.toString().trim()
                        .isEmpty()) {
                    Toast.makeText(ctx, R.string.automation_field_url, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (action == AutomationExecutor.ACTION_COPY_TEXT && textInput.text.toString().isEmpty()) {
                    Toast.makeText(ctx, R.string.automation_field_text, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val triggerTypeValue = triggerValues[triggerSpinner.selectedItemPosition]
                val triggerNeedsApp = triggerTypeValue == Automation.TRIGGER_APP_OPEN || triggerTypeValue == Automation.TRIGGER_APP_CLOSE
                if (triggerNeedsApp && selectedTriggerApp == null) {
                    Toast.makeText(ctx, R.string.automation_trigger_app_not_set, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val bindingValue = if (bindTrigger.isChecked) {
                    when {
                        triggerGreen.isChecked -> Automation.BINDING_GREEN
                        triggerRed.isChecked -> Automation.BINDING_RED
                        else -> Automation.BINDING_NONE
                    }
                } else {
                    Automation.BINDING_NONE
                }
                val updated = (existing ?: Automation(name = name, action = action)).apply {
                    this.name = name
                    this.action = action
                    this.appComponent = selectedComponent
                    this.url = urlInput.text.toString().trim().ifEmpty { null }
                    this.clipboardText = textInput.text.toString()
                    this.pinShortcut = pinShortcut.isChecked
                    this.shortcutLabel = shortcutLabel.text.toString().trim().ifEmpty { null }
                    this.shortcutIcon = iconValues.getOrNull(shortcutIcon.selectedItemPosition)
                    this.shortcutIconUri = selectedCustomIcon
                    this.messageText = textInput.text.toString()
                    this.messageTarget = phoneInput.text.toString().ifBlank { packageInput.text.toString().ifBlank { null } }
                    this.messageIsSms = isSmsCheck.isChecked
                    this.broadcastAction = broadcastActionInput.text.toString().trim().ifEmpty { null }
                    this.broadcastExtraKey = broadcastExtraKeyInput.text.toString().trim().ifEmpty { null }
                    this.broadcastExtraValue = broadcastExtraValueInput.text.toString().trim().ifEmpty { null }
                    this.scheduleEnabled = scheduleEnable.isChecked && scheduleMinute != null
                    this.scheduleMinuteOfDay = scheduleMinute
                    this.scheduleRepeatDaily = scheduleRepeat.isChecked
                    this.triggerType = triggerTypeValue
                    this.triggerApp = selectedTriggerApp
                    this.triggerBinding = bindingValue
                }
                AutomationStore.upsert(ctx, updated)
                rebuildList()
                AutomationScheduler.reschedule(ctx)
                AutomationEventService.startOrStop(ctx)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun loadAppLabel(context: Context, componentOrPackage: String): String {
        val pm = context.packageManager
        val cn = ComponentName.unflattenFromString(componentOrPackage)
        if (cn != null) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            val info = pm.resolveActivity(intent, 0)
            return info?.loadLabel(pm)?.toString() ?: componentOrPackage
        }
        val intent = pm.getLaunchIntentForPackage(componentOrPackage)
        val info = intent?.let { pm.resolveActivity(it, 0) }
        return info?.loadLabel(pm)?.toString() ?: componentOrPackage
    }

    private fun showAppPicker(callback: (component: String, label: String) -> Unit) {
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
                callback(component.flattenToString(), info.loadLabel(pm).toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class AppListAdapter(
        context: Context,
        items: List<ResolveInfo>
    ) : ArrayAdapter<ResolveInfo>(context, android.R.layout.activity_list_item, items) {
        private val inflater = LayoutInflater.from(context)
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
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
