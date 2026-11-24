package com.local.offlinelocker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class LockStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "offline_lock_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    enum class AuthType { PIN, PASSWORD, PATTERN }

    fun setCredentialForType(type: AuthType, rawSecret: String) {
        prefs.edit()
            .putString("hash_${type.name}", sha256(rawSecret))
            .putString("default_auth_type", type.name)
            .apply()
    }

    fun hasCredential(type: AuthType): Boolean =
        !prefs.getString("hash_${type.name}", null).isNullOrEmpty()

    fun clearCredential(type: AuthType) {
        prefs.edit().remove("hash_${type.name}").apply()
    }

    fun verifyCredential(type: AuthType, input: String): Boolean {
        val saved = prefs.getString("hash_${type.name}", null) ?: return false
        return saved == sha256(input)
    }

    fun getDefaultAuthType(): AuthType {
        val saved = prefs.getString("default_auth_type", AuthType.PIN.name)!!
        val type = AuthType.valueOf(saved)
        if (hasCredential(type)) return type
        return AuthType.entries.firstOrNull { hasCredential(it) } ?: AuthType.PIN
    }

    fun getConfiguredAuthTypes(): List<AuthType> =
        AuthType.entries.filter { hasCredential(it) }

    var isMasterPaused: Boolean
        get() = prefs.getBoolean("master_paused", false)
        set(value) = prefs.edit().putBoolean("master_paused", value).apply()

    var isFingerprintEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", false)
        set(value) = prefs.edit().putBoolean("biometric_enabled", value).apply()

    var isPatternHidden: Boolean
        get() = prefs.getBoolean("hide_pattern_path", false)
        set(value) = prefs.edit().putBoolean("hide_pattern_path", value).apply()

    var isLockOnExitEnabled: Boolean
        get() = prefs.getBoolean("lock_on_exit", true)
        set(value) = prefs.edit().putBoolean("lock_on_exit", value).apply()

    // var isBackgroundBlurEnabled: Boolean
    //     get() = prefs.getBoolean("bg_blur_enabled", true)
    //     set(value) = prefs.edit().putBoolean("bg_blur_enabled", value).apply()

    var idleTimeoutSeconds: Int
        get() = prefs.getInt("idle_timeout_sec", 30)
        set(value) = prefs.edit().putInt("idle_timeout_sec", value).apply()

    fun getLockedApps(): Set<String> =
        prefs.getStringSet("locked_packages", emptySet()) ?: emptySet()

    fun setLockedApps(packages: Set<String>) {
        prefs.edit().putStringSet("locked_packages", packages).apply()
    }

    fun getFakeCrashApps(): Set<String> =
        prefs.getStringSet("fake_crash_packages", emptySet()) ?: emptySet()

    fun setFakeCrashApps(packages: Set<String>) {
        prefs.edit().putStringSet("fake_crash_packages", packages).apply()
    }

    fun getWifiBlockedApps(): Set<String> =
        prefs.getStringSet("wifi_blocked_packages", emptySet()) ?: emptySet()

    fun setWifiBlockedApps(packages: Set<String>) {
        prefs.edit().putStringSet("wifi_blocked_packages", packages).apply()
    }

    fun getMobileBlockedApps(): Set<String> =
        prefs.getStringSet("mobile_blocked_packages", emptySet()) ?: emptySet()

    fun setMobileBlockedApps(packages: Set<String>) {
        prefs.edit().putStringSet("mobile_blocked_packages", packages).apply()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
