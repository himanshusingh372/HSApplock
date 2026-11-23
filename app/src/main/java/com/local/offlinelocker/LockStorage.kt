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

    var isFakeCrashEnabled: Boolean
        get() = prefs.getBoolean("fake_crash_enabled", false)
        set(value) = prefs.edit().putBoolean("fake_crash_enabled", value).apply()

    var relockTimeoutSeconds: Int
        get() = prefs.getInt("relock_timeout_sec", 30)
        set(value) = prefs.edit().putInt("relock_timeout_sec", value).apply()

    fun getLockedApps(): Set<String> =
        prefs.getStringSet("locked_packages", emptySet()) ?: emptySet()

    fun setLockedApps(packages: Set<String>) {
        prefs.edit().putStringSet("locked_packages", packages).apply()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
