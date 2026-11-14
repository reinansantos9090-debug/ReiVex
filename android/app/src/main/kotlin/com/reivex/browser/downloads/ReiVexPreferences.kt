package com.reivex.browser.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small, app-local preferences store for browser protection and transfer settings. */
internal class ReiVexPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("reivex_preferences", Context.MODE_PRIVATE)

    suspend fun getPreference(key: String, defaultValue: String): String = withContext(Dispatchers.IO) {
        preferences.getString(key, defaultValue) ?: defaultValue
    }

    suspend fun setPreference(key: String, value: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(key, value).commit()) { "Could not persist preference" }
    }
}
