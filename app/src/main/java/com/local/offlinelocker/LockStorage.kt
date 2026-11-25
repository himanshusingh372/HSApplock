package com.local.offlinelocker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class LockStorage(context: Context) {

    enum class AuthType { PIN, PASSWORD, PATTERN }

  private val prefs: SharedPreferences = try {
        val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
        )
        EncryptedSharedPreferences.create(
            "hs_applock_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_GCM,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("hs_applock_fallback_prefs", Context.MODE_PRIVATE)
    }

    var isInstantLockEnabled: Boolean
        get() = prefs.getBoolean("instant_lock_enabled", true)
        set(value) = prefs.edit().putBoolean("instant_lock_enabled", value).apply()

    var isSelfLockEnabled: Boolean
        get() = prefs.getBoolean("self_lock_enabled", true)
        set(value) = prefs.edit().putBoolean("self_lock_enabled", value).apply()

    var isSettingsLockEnabled: Boolean
        get() = prefs.getBoolean("settings_lock_enabled", false)
        set(value) = prefs.edit().putBoolean("settings_lock_enabled", value).apply()

    var isAppInfoLockEnabled: Boolean
        get() = prefs.getBoolean("app_info_lock_enabled", false)
        set(value) = prefs.edit().putBoolean("app_info_lock_enabled", value).apply()

    var isMasterPaused: Boolean
        get() = prefs.getBoolean("master_paused", false)
        set(value) = prefs.edit().putBoolean("master_paused", value).apply()

    var isAntiUninstallEnabled: Boolean
        get() = prefs.getBoolean("anti_uninstall_enabled", false)
        set(value) = prefs.edit().putBoolean("anti_uninstall_enabled", value).apply()

    var isFingerprintEnabled: Boolean
        get() = prefs.getBoolean("fingerprint_enabled", true)
        set(value) = prefs.edit().putBoolean("fingerprint_enabled", value).apply()

    var isLockOnExitEnabled: Boolean
        get() = prefs.getBoolean("lock_on_exit_enabled", true)
        set(value) = prefs.edit().putBoolean("lock_on_exit_enabled", value).apply()

    var isPatternHidden: Boolean
        get() = prefs.getBoolean("pattern_hidden", false)
        set(value) = prefs.edit().putBoolean("pattern_hidden", value).apply()

    var idleTimeoutSeconds: Int
        get() = prefs.getInt("idle_timeout_seconds", 0)
        set(value) = prefs.edit().putInt("idle_timeout_seconds", value).apply()

    fun getLockedApps(): Set<String> =
        prefs.getStringSet("locked_apps", emptySet())?.toSet() ?: emptySet()

    fun setLockedApps(apps: Set<String>) {
        prefs.edit().putStringSet("locked_apps", apps.toSet()).apply()
    }

    fun getWifiBlockedApps(): Set<String> =
        prefs.getStringSet("wifi_blocked_apps", emptySet())?.toSet() ?: emptySet()

    fun setWifiBlockedApps(apps: Set<String>) {
        prefs.edit().putStringSet("wifi_blocked_apps", apps.toSet()).apply()
    }

    fun getMobileBlockedApps(): Set<String> =
        prefs.getStringSet("mobile_blocked_apps", emptySet())?.toSet() ?: emptySet()

    fun setMobileBlockedApps(apps: Set<String>) {
        prefs.edit().putStringSet("mobile_blocked_apps", apps.toSet()).apply()
    }

    fun getFakeCrashApps(): Set<String> =
        prefs.getStringSet("fake_crash_apps", emptySet())?.toSet() ?: emptySet()

    fun setFakeCrashApps(apps: Set<String>) {
        prefs.edit().putStringSet("fake_crash_apps", apps.toSet()).apply()
    }

    fun setCredentialForType(type: AuthType, rawValue: String) {
        prefs.edit().putString("cred_${type.name}", hash(rawValue)).apply()
    }

    fun hasCredential(type: AuthType): Boolean =
        !prefs.getString("cred_${type.name}", null).isNullOrEmpty()

    fun verifyCredential(type: AuthType, rawValue: String): Boolean {
        val stored = prefs.getString("cred_${type.name}", null) ?: return false
        return stored == hash(rawValue)
    }

    fun markAppUnlocked(key: String) {
        prefs.edit().putLong("unlocked_at_$key", System.currentTimeMillis()).apply()
    }

    fun clearAppUnlocked(key: String) {
        prefs.edit().remove("unlocked_at_$key").remove("unlocked_at_APP_$key").apply()
    }

    fun clearAllUnlockedApps() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("unlocked_at_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun isAppUnlocked(key: String): Boolean {
        if (isInstantLockEnabled) return false
        val unlockedAt = prefs.getLong("unlocked_at_$key", 0L)
        if (unlockedAt == 0L) return false
        val timeoutSec = idleTimeoutSeconds
        if (timeoutSec <= 0) return true
        return (System.currentTimeMillis() - unlockedAt) < (timeoutSec * 1000L)
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
