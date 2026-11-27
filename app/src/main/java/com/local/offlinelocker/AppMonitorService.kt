package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeForegroundPkg: String? = null
    private var lastPromptedKey: String? = null
    private var lastPromptTimeMs: Long = 0L
    private val exitCooldownByPkg = mutableMapOf<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_USER_PRESENT) {
                if (::storage.isInitialized) {
                    storage.clearAllUnlockedApps()
                }
                activeForegroundPkg = null
                lastPromptedKey = null
                exitCooldownByPkg.clear()
            }
        }
    }

    companion object {
        private val TRANSIENT_OVERLAY_PACKAGES = setOf(
            "android",
            "com.android.intentresolver",
            "com.google.android.gms",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.miui.inputmethod",
            "com.swiftkey.swiftkeyconfigurator",
            "com.touchtype.swiftkey"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = LockStorage(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (!::storage.isInitialized) {
            storage = LockStorage(this)
        }

        if (storage.isMasterPaused) {
            storage.clearAllUnlockedApps()
            activeForegroundPkg = null
            return
        }

        val rootPkg = rootInActiveWindow?.packageName?.toString()
        val eventPkg = event.packageName?.toString()

        val pkg = when {
            !rootPkg.isNullOrEmpty() && !TRANSIENT_OVERLAY_PACKAGES.contains(rootPkg) -> rootPkg
            !eventPkg.isNullOrEmpty() -> eventPkg
            else -> return
        }

        if (pkg == packageName || pkg == "com.local.offlinelocker" || TRANSIENT_OVERLAY_PACKAGES.contains(pkg)) {
            return
        }

        val className = event.className?.toString().orEmpty()

        // Clear all active unlock sessions immediately when Recents / Overview opens
        val isRecentsScreen = pkg == "com.android.systemui" &&
            (className.contains("Recents", ignoreCase = true) ||
                className.contains("Overview", ignoreCase = true) ||
                className.contains("TaskStack", ignoreCase = true))

        if (isRecentsScreen) {
            storage.clearAllUnlockedApps()
            activeForegroundPkg = null
            return
        }

        if (pkg == "com.android.systemui") return

        // Pressing Home (Launcher) or switching to ANY other app immediately clears the previous app's unlock
        val prev = activeForegroundPkg
        if (prev != null && prev != pkg) {
            if (storage.isInstantLockEnabled) {
                storage.clearAppUnlocked(prev)
                exitCooldownByPkg[prev] = System.currentTimeMillis()
            }
        }
        activeForegroundPkg = pkg

        // Unified Locked Apps check (handles normal apps, System Settings, and Package Installer once per session)
        val lockedApps = storage.getLockedApps()
        if (lockedApps.contains(pkg)) {
            val exitedAt = exitCooldownByPkg[pkg] ?: 0L
            // Only suppress duplicate prompt for 600ms right after leaving the app so reopening immediately locks
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                System.currentTimeMillis() - exitedAt < 600L
            ) {
                return
            }

            val guardKey = "APP_$pkg"
            if (!storage.isAppUnlocked(guardKey)) {
                val isFakeCrash = storage.getFakeCrashApps().contains(pkg)
                launchGuard(key = guardKey, targetPackage = guardKey, isFakeCrash = isFakeCrash)
            }
        }
    }

    private fun launchGuard(key: String, targetPackage: String, isFakeCrash: Boolean) {
        val now = System.currentTimeMillis()
        if (key == lastPromptedKey && (now - lastPromptTimeMs) < 900L) return
        lastPromptedKey = key
        lastPromptTimeMs = now

        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("TARGET_PACKAGE", targetPackage)
            putExtra("FAKE_CRASH", isFakeCrash)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // No-op
    }
}
