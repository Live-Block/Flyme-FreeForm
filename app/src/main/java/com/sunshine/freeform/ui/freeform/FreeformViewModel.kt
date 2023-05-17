package com.sunshine.freeform.ui.freeform

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import com.sunshine.freeform.app.MiFreeform

class FreeformViewModel(context: Context) {

    private val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)

    fun getBooleanSp(key: String, defaultValue: Boolean): Boolean {
        return sp.getBoolean(key, defaultValue)
    }

    fun getIntSp(key: String, defaultValue: Int): Int {
        return sp.getInt(key, defaultValue)
    }

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }
}