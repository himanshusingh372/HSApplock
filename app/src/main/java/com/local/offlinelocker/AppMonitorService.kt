package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeUnlockedPackage: String? = null

    companion object {
        private val unlockedTimestamps = mutableMapOf<String, Long>()

        fun markAppUnlocked(pkg: String) {
            unlockedTimestamps[pkg] = SystemClock.elapsedRealtime()
        }

        fun isWithinGracePeriod(pkg: String, timeoutSeconds: Int): Boolean {
            if (timeoutSeconds <= 0) return false
            val lastUsed = unlockedTimestamps[pkg] ?: return false
            val elapsedMs = SystemClock.elapsedRealtime() - lastUsed
            return elapsedMs < (timeoutSeconds * 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = LockStorage(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own lock screen overlay and system UI notifications
        if (pkg == packageName || pkg == "com.android.systemui") return

        // User left the currently unlocked app (went to Home, Recents, or another app)
        if (activeUnlockedPackage != null && activeUnlockedPackage != pkg) {
            val previousPkg = activeUnlockedPackage!!
            if (storage.relockTimeoutSeconds <= 0) {
                // Immediate relock: clear session as soon as app goes to background
                unlockedTimestamps.remove(previousPkg)
            } else {
                // Record exact timestamp when user exited the app for inactivity timer
                unlockedTimestamps[previousPkg] = SystemClock.elapsedRealtime()
            }
            activeUnlockedPackage = null
        }

        if (storage.getLockedApps().contains(pkg)) {
            val timeout = storage.relockTimeoutSeconds
            if (isWithinGracePeriod(pkg, timeout)) {
                activeUnlockedPackage = pkg
                return
            }

            unlockedTimestamps.remove(pkg)
            activeUnlockedPackage = null

            val intent = Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("TARGET_PACKAGE", pkg)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}
}
