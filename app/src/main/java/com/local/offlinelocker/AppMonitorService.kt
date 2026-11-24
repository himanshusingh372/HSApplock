package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeUnlockedPackage: String? = null
    private val idleHandler = Handler(Looper.getMainLooper())

    companion object {
        private val lastInteractionMap = mutableMapOf<String, Long>()

        fun markAppUnlocked(pkg: String) {
            lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
        }
    }

    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            if (storage.isMasterPaused) return
            val currentPkg = activeUnlockedPackage ?: return
            val timeoutSec = storage.idleTimeoutSeconds
            if (timeoutSec <= 0) return

            val lastTouch = lastInteractionMap[currentPkg] ?: return
            val elapsedMs = SystemClock.elapsedRealtime() - lastTouch

            if (elapsedMs >= timeoutSec * 1000L) {
                lastInteractionMap.remove(currentPkg)
                activeUnlockedPackage = null
                launchLockScreen(currentPkg)
            } else {
                idleHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = LockStorage(this)
    }

    private fun isUninstallOrAdminPackage(pkg: String): Boolean {
        return pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.miui.packageinstaller"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (storage.isMasterPaused) return
        val eventType = event?.eventType ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == activeUnlockedPackage) {
                lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
            }
            return
        }

        if (activeUnlockedPackage != null && activeUnlockedPackage != pkg) {
            val previousPkg = activeUnlockedPackage!!
            idleHandler.removeCallbacks(idleCheckRunnable)

            if (storage.isLockOnExitEnabled && !isUninstallOrAdminPackage(previousPkg)) {
                lastInteractionMap.remove(previousPkg)
            } else {
                lastInteractionMap[previousPkg] = SystemClock.elapsedRealtime()
            }
            activeUnlockedPackage = null
        }

        // Ask for PIN / Pattern / Password before uninstalling any app
        if (storage.isAntiUninstallEnabled && isUninstallOrAdminPackage(pkg)) {
            val lastUnlock = lastInteractionMap[pkg] ?: 0L
            val withinGraceWindow = (SystemClock.elapsedRealtime() - lastUnlock) < 15_000L
            if (!withinGraceWindow) {
                lastInteractionMap.remove(pkg)
                activeUnlockedPackage = null
                idleHandler.removeCallbacks(idleCheckRunnable)
                launchLockScreen(pkg)
                return
            } else {
                activeUnlockedPackage = pkg
                lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
                return
            }
        }

        if (storage.getLockedApps().contains(pkg)) {
            if (isSessionStillValid(pkg)) {
                activeUnlockedPackage = pkg
                lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
                startIdleTimer()
                return
            }

            lastInteractionMap.remove(pkg)
            activeUnlockedPackage = null
            idleHandler.removeCallbacks(idleCheckRunnable)
            launchLockScreen(pkg)
        }
    }

    private fun isSessionStillValid(pkg: String): Boolean {
        val lastTouch = lastInteractionMap[pkg] ?: return false
        val timeoutSec = storage.idleTimeoutSeconds
        if (!storage.isLockOnExitEnabled && timeoutSec <= 0) return true
        val elapsedMs = SystemClock.elapsedRealtime() - lastTouch
        if (storage.isLockOnExitEnabled && timeoutSec <= 0) {
            return elapsedMs < 2000L
        }
        return elapsedMs < (timeoutSec * 1000L)
    }

    private fun startIdleTimer() {
        idleHandler.removeCallbacks(idleCheckRunnable)
        if (storage.idleTimeoutSeconds > 0) {
            idleHandler.postDelayed(idleCheckRunnable, 1000L)
        }
    }

    private fun launchLockScreen(pkg: String) {
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("TARGET_PACKAGE", pkg)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        idleHandler.removeCallbacks(idleCheckRunnable)
    }
}
