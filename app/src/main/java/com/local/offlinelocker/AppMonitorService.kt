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
            val currentPkg = activeUnlockedPackage ?: return
            val timeoutSec = storage.idleTimeoutSeconds
            if (timeoutSec <= 0) return

            val lastTouch = lastInteractionMap[currentPkg] ?: return
            val elapsedMs = SystemClock.elapsedRealtime() - lastTouch

            if (elapsedMs >= timeoutSec * 1000L) {
                // Inactivity timeout reached while app is open
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        // Reset inactivity countdown whenever the user clicks, scrolls, or types in the active unlocked app
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == activeUnlockedPackage) {
                lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
            }
            return
        }

        // Window changed: user switched apps, went Home, or opened Recents
        if (activeUnlockedPackage != null && activeUnlockedPackage != pkg) {
            val previousPkg = activeUnlockedPackage!!
            idleHandler.removeCallbacks(idleCheckRunnable)

            if (storage.isLockOnExitEnabled) {
                // Rule A: Lock immediately on exit
                lastInteractionMap.remove(previousPkg)
            } else {
                // Rule B: Keep unlocked until idleTimeoutSeconds expires
                lastInteractionMap[previousPkg] = SystemClock.elapsedRealtime()
            }
            activeUnlockedPackage = null
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
        // If Lock-on-Exit is OFF and Idle Timeout is disabled (0), stay unlocked until manually locked
        if (!storage.isLockOnExitEnabled && timeoutSec <= 0) return true
        // If Lock-on-Exit is ON and user just unlocked it within the last 2 seconds (window transition)
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
