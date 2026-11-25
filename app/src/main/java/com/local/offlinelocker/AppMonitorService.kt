package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var lastPromptedPkg: String? = null
    private var lastPromptTimeMs: Long = 0L

    companion object {
        private val SYSTEM_SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.coloros.securityguard",
            "com.vivo.permissionmanager",
            "com.samsung.android.settings"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = LockStorage(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        if (!::storage.isInitialized) {
            storage = LockStorage(this)
        }

        if (storage.isMasterPaused) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.local.offlinelocker") return

        val now = System.currentTimeMillis()
        if (pkg == lastPromptedPkg && (now - lastPromptTimeMs) < 1200L) return

        val isLockedApp = storage.getLockedApps().contains(pkg)
        val isAppInfoScreen = storage.isAppInfoLockEnabled && SYSTEM_SETTINGS_PACKAGES.contains(pkg)

        if (!isLockedApp && !isAppInfoScreen) return
        if (storage.isAppUnlocked(pkg)) return

        lastPromptedPkg = pkg
        lastPromptTimeMs = now

        val isFakeCrash = isLockedApp && storage.getFakeCrashApps().contains(pkg)
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("TARGET_PACKAGE", pkg)
            putExtra("FAKE_CRASH", isFakeCrash)
        }
        startActivity(lockIntent)
    }

    override fun onInterrupt() {
        // No-op
    }
}
