package io.github.zhuquan7237.musechat.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("muse_preferences")
data class UserPreferences(val mode: String = "auto", val maxCalls: Int = 2, val maxImages: Int = 1, val optimize: Boolean = true, val theme: String = "system", val dynamic: Boolean = true, val chatId: String = "", val imageId: String = "", val sessionId: String = "")
class PreferenceRepository(private val context: Context) {
    val flow = context.dataStore.data.map { p -> UserPreferences(
        p[stringPreferencesKey("mode")] ?: "auto", p[intPreferencesKey("maxCalls")] ?: 2,
        p[intPreferencesKey("maxImages")] ?: 1, p[booleanPreferencesKey("optimize")] ?: true,
        p[stringPreferencesKey("theme")] ?: "system", p[booleanPreferencesKey("dynamic")] ?: true,
        p[stringPreferencesKey("chatId")] ?: "", p[stringPreferencesKey("imageId")] ?: "", p[stringPreferencesKey("sessionId")] ?: ""
    ) }
    suspend fun string(name: String, value: String) { context.dataStore.edit { it[stringPreferencesKey(name)] = value } }
    suspend fun boolean(name: String, value: Boolean) { context.dataStore.edit { it[booleanPreferencesKey(name)] = value } }
    suspend fun number(name: String, value: Int) { context.dataStore.edit { it[intPreferencesKey(name)] = value } }
}
