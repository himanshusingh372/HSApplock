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

    fun setCredentials(type: AuthType, rawSecret: String) {
        prefs.edit()
            .putString("auth_type", type.name)
            .putString("secret_hash", sha256(rawSecret))
            .apply()
    }

    fun getAuthType(): AuthType =
        AuthType.valueOf(prefs.getString("auth_type", AuthType.PIN.name)!!)

    fun verifySecret(input: String): Boolean {
        val saved = prefs.getString("secret_hash", null) ?: return false
        return saved == sha256(input)
    }

    var isFingerprintEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", true)
        set(value) = prefs.edit().putBoolean("biometric_enabled", value).apply()

    var isPatternHidden: Boolean
        get() = prefs.getBoolean("hide_pattern_path", false)
        set(value) = prefs.edit().putBoolean("hide_pattern_path", value).apply()

    var isLockOnExitEnabled: Boolean
        get() = prefs.getBoolean("lock_on_exit", true)
        set(value) = prefs.edit().putBoolean("lock_on_exit", value).apply()

    var isBackgroundBlurEnabled: Boolean
        get() = prefs.getBoolean("bg_blur_enabled", true)
        set(value) = prefs.edit().putBoolean("bg_blur_enabled", value).apply()

    var idleTimeoutSeconds: Int
        get() = prefs.getInt("idle_timeout_sec", 30)
        set(value) = prefs.edit().putInt("idle_timeout_sec", value).apply()

    // App-wise locked packages
    fun getLockedApps(): Set<String> =
        prefs.getStringSet("locked_packages", emptySet()) ?: emptySet()

    fun setLockedApps(packages: Set<String>) {
        prefs.edit().putStringSet("locked_packages", packages).apply()
    }

    // App-wise Fake Crash packages
    fun getFakeCrashApps(): Set<String> =
        prefs.getStringSet("fake_crash_packages", emptySet()) ?: emptySet()

    fun setFakeCrashApps(packages: Set<String>) {
        prefs.edit().putStringSet("fake_crash_packages", packages).apply()
    }

    // App-wise Internet / Wi-Fi Blocked packages
    fun getBlockedNetApps(): Set<String> =
        prefs.getStringSet("blocked_net_packages", emptySet()) ?: emptySet()

    fun setBlockedNetApps(packages: Set<String>) {
        prefs.edit().putStringSet("blocked_net_packages", packages).apply()
    }

    // Hand-sign gesture shortcuts: stores normalized stroke signature -> target packageName
    fun saveGestureShortcut(signature: String, targetPackage: String) {
        val map = getGestureShortcuts().toMutableMap()
        map[signature] = targetPackage
        val encoded = map.entries.joinToString("||") { "${it.key}::${it.value}" }
        prefs.edit().putString("gesture_shortcuts", encoded).apply()
    }

    fun getGestureShortcuts(): Map<String, String> {
        val raw = prefs.getString("gesture_shortcuts", "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split("||").mapNotNull { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
