package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var lastForegroundPackage: String = ""

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

        if (pkg == packageName || pkg == "com.android.systemui") return

        if (lastForegroundPackage.isNotEmpty() &&
            lastForegroundPackage != pkg &&
            unlockedTimestamps.containsKey(lastForegroundPackage)
        ) {
            unlockedTimestamps[lastForegroundPackage] = SystemClock.elapsedRealtime()
        }

        lastForegroundPackage = pkg

        if (storage.getLockedApps().contains(pkg)) {
            val timeout = storage.relockTimeoutSeconds
            if (isWithinGracePeriod(pkg, timeout)) {
                unlockedTimestamps[pkg] = SystemClock.elapsedRealtime()
                return
            }

            unlockedTimestamps.remove(pkg)
            val intent = Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("TARGET_PACKAGE", pkg)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}
}
