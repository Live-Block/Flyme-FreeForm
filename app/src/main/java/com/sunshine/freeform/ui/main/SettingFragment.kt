package com.sunshine.freeform.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.service.ForegroundService
import com.sunshine.freeform.service.KeepAliveService
import com.sunshine.freeform.ui.choose_apps.ChooseAppsActivity
import com.sunshine.freeform.ui.freeform.FreeformView
import com.sunshine.freeform.ui.permission.PermissionActivity
import com.sunshine.freeform.ui.view.IntegerSimpleMenuPreference
import com.sunshine.freeform.utils.PermissionUtils
import kotlin.math.min
import kotlin.math.roundToInt

class SettingFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {

    private lateinit var sp: SharedPreferences
    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MiFreeform.APP_SETTINGS_NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        sp = requireActivity().getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(0)
            } else {
                sp.edit().putInt("service_type", ForegroundService.SERVICE_TYPE).apply()
                findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(1)
                requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
            }
        }

        findPreference<Preference>(QUICK_FLOATING_APP)!!.onPreferenceClickListener = this
        findPreference<Preference>(NOTIFICATION_FREEFORM_APPS)!!.onPreferenceClickListener = this
        findPreference<Preference>(RESET_OVERLAY_SETTING)!!.onPreferenceClickListener = this
        findPreference<SwitchPreference>(SHOW_FLOATING)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(NOTIFY_FREEFORM)!!.onPreferenceChangeListener = this
        findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.onPreferenceChangeListener = this
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when(preference.key) {
            QUICK_FLOATING_APP -> {
                requireActivity().startActivity(Intent(requireActivity(), ChooseAppsActivity::class.java).putExtra("type", 1))
            }
            NOTIFICATION_FREEFORM_APPS -> {
                requireActivity().startActivity(Intent(requireActivity(), ChooseAppsActivity::class.java).putExtra("type", 2))
            }
            RESET_OVERLAY_SETTING -> {
                sp.edit().apply {
                    val screenWidth = min(requireContext().resources.displayMetrics.heightPixels, requireContext().resources.displayMetrics.widthPixels)

                    putInt("floating_position_portrait_y", 0)
                    putInt("floating_position_landscape_y", 0)
                    putInt(FreeformView.REMEMBER_X, 0)
                    putInt(FreeformView.REMEMBER_Y, 0)
                    putInt(FreeformView.REMEMBER_LAND_X, 0)
                    putInt(FreeformView.REMEMBER_LAND_Y, 0)
                    putInt(FreeformView.REMEMBER_HEIGHT, (screenWidth * 1.6 * 0.75).roundToInt())
                    putInt(FreeformView.REMEMBER_LAND_HEIGHT, (screenWidth * 0.8).roundToInt())
                    apply()
                }
                Snackbar.make(requireView(), getString(R.string.reset_success), Snackbar.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when(preference.key) {
            SHOW_FLOATING -> {
                if (newValue as Boolean) {
                    when(sp.getInt(SERVICE_TYPE, KeepAliveService.SERVICE_TYPE)) {
                        KeepAliveService.SERVICE_TYPE -> {
                            if (!PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                                Toast.makeText(requireContext(), getString(R.string.require_accessibility), Toast.LENGTH_SHORT).show()
                                startActivity(Intent(requireActivity(), PermissionActivity::class.java))
                                requireActivity().finish()
                                return false
                            }
                        }
                        ForegroundService.SERVICE_TYPE -> {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }
            NOTIFY_FREEFORM -> {
                if (newValue as Boolean && !PermissionUtils.checkNotificationListenerPermission(requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.require_notification), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(requireActivity(), PermissionActivity::class.java))
                    return false
                }
            }
            SERVICE_TYPE -> {
                when(newValue as Int) {
                    KeepAliveService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            requireContext().stopService(Intent(requireContext(), ForegroundService::class.java))
                        } else {
                            accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), getString(R.string.require_start_accessibility), Toast.LENGTH_SHORT).show()
                            return false
                        }
                    }
                    ForegroundService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), getString(R.string.require_stop_accessibility), Toast.LENGTH_SHORT).show()
                            return false
                        } else {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
            findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(0)
        }
    }

    companion object {
        private const val QUICK_FLOATING_APP = "quick_floating_app"
        private const val NOTIFICATION_FREEFORM_APPS = "notification_freeform_apps"
        private const val SHOW_FLOATING = "show_floating"
        private const val NOTIFY_FREEFORM = "notify_freeform"
        private const val RESET_OVERLAY_SETTING = "reset_overlay_setting"
        private const val SERVICE_TYPE = "service_type"
    }
}