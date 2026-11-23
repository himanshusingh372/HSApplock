package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var lastForegroundPackage: String = ""

    companion object {
        var temporarilyUnlockedPackage: String? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = LockStorage(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == packageName || pkg == "com.android.systemui") return

        if (pkg != lastForegroundPackage) {
            if (pkg != temporarilyUnlockedPackage) {
                temporarilyUnlockedPackage = null
            }
            lastForegroundPackage = pkg
        }

        if (storage.getLockedApps().contains(pkg) && pkg != temporarilyUnlockedPackage) {
            val intent = Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("TARGET_PACKAGE", pkg)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}
}
