/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class CloudPreferences(context: Context, name: String = "opentv_cloud") {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val status = MutableStateFlow("Cloud sync is off")
    val lastSuccess = MutableStateFlow(prefs.getLong("last_success", 0))
    val url get() = prefs.getString("url", "").orEmpty()
    val publishableKey get() = prefs.getString("publishable_key", "").orEmpty()
    val nickname get() = prefs.getString("nickname", android.os.Build.MODEL).orEmpty()
    val deviceId: String get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("device_id", it).commit())
    }
    fun configure(url: String, key: String, nickname: String) {
        check(!enabled.value) { "Disable cloud sync before changing the backend." }
        check(prefs.edit().putString("url", url.trim().trimEnd('/')).putString("publishable_key", key.trim()).putString("nickname", nickname.take(80)).commit())
    }
    fun setEnabled(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()); enabled.value = value }
    fun categories(): Set<CloudCategory> = prefs.getStringSet("categories", null)?.map { CloudCategory.valueOf(it) }?.toSet() ?: CloudCategory.entries.toSet()
    fun setCategories(values: Set<CloudCategory>) { prefs.edit().putStringSet("categories", values.map { it.name }.toSet()).apply() }
    fun success() { val now = System.currentTimeMillis(); prefs.edit().putLong("last_success", now).apply(); lastSuccess.value = now }
    fun newDevice() { check(prefs.edit().putString("device_id", UUID.randomUUID().toString()).commit()) }
}
