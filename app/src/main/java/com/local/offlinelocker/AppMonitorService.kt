package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeForegroundPkg: String? = null
    private var lastPromptedKey: String? = null
    private var lastPromptTimeMs: Long = 0L
    private var lastAppInfoExitMs: Long = 0L
    private var wasInAppInfo: Boolean = false
    private val exitCooldownByPkg = mutableMapOf<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_USER_PRESENT) {
                if (::storage.isInitialized) {
                    storage.clearAllUnlockedApps()
                }
                activeForegroundPkg = null
                wasInAppInfo = false
                lastPromptedKey = null
                exitCooldownByPkg.clear()
            }
        }
    }

    companion object {
        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.oplus.apprecover",
            "com.coloros.packageinstaller",
            "com.samsung.android.packageinstaller"
        )

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.coloros.securityguard",
            "com.vivo.permissionmanager",
            "com.samsung.android.settings",
            "com.oneplus.security"
        )

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

        private val APP_INFO_CLASS_HINTS = listOf(
            "AppInfo",
            "InstalledAppDetails",
            "ApplicationDetails",
            "ManageApplications",
            "AppDetail",
            "ApplicationsDetails"
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
            wasInAppInfo = false
            return
        }

        val rootPkg = rootInActiveWindow?.packageName?.toString()
        val eventPkg = event.packageName?.toString()

        // Prefer rootInActiveWindow so late background events from an app the user just exited are ignored
        val pkg = when {
            !rootPkg.isNullOrEmpty() && !TRANSIENT_OVERLAY_PACKAGES.contains(rootPkg) -> rootPkg
            !eventPkg.isNullOrEmpty() -> eventPkg
            else -> return
        }

        if (pkg == packageName || pkg == "com.local.offlinelocker" || TRANSIENT_OVERLAY_PACKAGES.contains(pkg)) {
            return
        }

        val className = event.className?.toString().orEmpty()

        // Recents / Overview clears all active unlock sessions
        val isRecentsScreen = pkg == "com.android.systemui" &&
            (className.contains("Recents", ignoreCase = true) ||
                className.contains("Overview", ignoreCase = true) ||
                className.contains("TaskStack", ignoreCase = true))

        if (isRecentsScreen) {
            storage.clearAllUnlockedApps()
            activeForegroundPkg = null
            wasInAppInfo = false
            return
        }

        if (pkg == "com.android.systemui") return

        // User switched away from `prev` to Home Launcher or another app
        val prev = activeForegroundPkg
        if (prev != null && prev != pkg) {
            if (storage.isInstantLockEnabled) {
                storage.clearAppUnlocked(prev)
                // Add a 1.2s exit cooldown so trailing window events from the closing app don't re-lock on exit
                exitCooldownByPkg[prev] = System.currentTimeMillis()

                // Only clear SYSTEM_ACTION_GUARD when leaving BOTH installer and settings completely
                if (!INSTALLER_PACKAGES.contains(pkg) && !SETTINGS_PACKAGES.contains(pkg)) {
                    storage.clearAppUnlocked("SYSTEM_ACTION_GUARD")
                }
                if (SETTINGS_PACKAGES.contains(prev) && !SETTINGS_PACKAGES.contains(pkg)) {
                    storage.clearAppUnlocked("APP_INFO_GUARD")
                    storage.clearAppUnlocked("SETTINGS_ALL")
                }
            }
        }
        activeForegroundPkg = pkg

        val isSystemOrInstaller = SETTINGS_PACKAGES.contains(pkg) || INSTALLER_PACKAGES.contains(pkg)
        val screenText = if (isSystemOrInstaller) {
            collectScreenText(rootInActiveWindow)
        } else {
            ""
        }

        // 1. Uninstall & Force Stop Blocker (stays unlocked across multi-step installer dialogs until exit)
        val isUninstallOrForceStopUi = INSTALLER_PACKAGES.contains(pkg) ||
            className.contains("Uninstall", ignoreCase = true) ||
            screenText.contains("do you want to uninstall", ignoreCase = true) ||
            screenText.contains("uninstall this app", ignoreCase = true) ||
            screenText.contains("if you force stop", ignoreCase = true) ||
            screenText.contains("force stop?", ignoreCase = true) ||
            (isSystemOrInstaller &&
                (screenText.contains("uninstall", ignoreCase = true) ||
                    screenText.contains("force stop", ignoreCase = true)) &&
                screenText.contains("cancel", ignoreCase = true) &&
                (screenText.contains("ok", ignoreCase = true) ||
                    screenText.contains("uninstall", ignoreCase = true)))

        if (storage.isAntiUninstallEnabled && isUninstallOrForceStopUi) {
            val guardKey = "SYSTEM_ACTION_GUARD"
            if (!storage.isAppUnlocked(guardKey)) {
                launchGuard(key = guardKey, targetPackage = guardKey, isFakeCrash = false)
            }
            return
        }

        // 2. Full-Phone App Info Lock
        val isAppInfoScreen = !isUninstallOrForceStopUi && SETTINGS_PACKAGES.contains(pkg) &&
            (APP_INFO_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
                screenText.contains("app info", ignoreCase = true) ||
                screenText.contains("application info", ignoreCase = true) ||
                (screenText.contains("force stop", ignoreCase = true) &&
                    (screenText.contains("storage", ignoreCase = true) ||
                        screenText.contains("permissions", ignoreCase = true) ||
                        screenText.contains("mobile data", ignoreCase = true))))

        if (wasInAppInfo && !isAppInfoScreen) {
            wasInAppInfo = false
            storage.clearAppUnlocked("APP_INFO_GUARD")
            lastAppInfoExitMs = System.currentTimeMillis()
        }

        if (storage.isAppInfoLockEnabled && isAppInfoScreen) {
            wasInAppInfo = true
            if (System.currentTimeMillis() - lastAppInfoExitMs < 700L) return
            val guardKey = "APP_INFO_GUARD"
            if (!storage.isAppUnlocked(guardKey)) {
                launchGuard(key = guardKey, targetPackage = guardKey, isFakeCrash = false)
            }
            return
        }

        // 3. System Settings Lock
        val lockedApps = storage.getLockedApps()
        val isSettingsLocked = storage.isSettingsLockEnabled ||
            SETTINGS_PACKAGES.any { lockedApps.contains(it) }

        if (isSettingsLocked && SETTINGS_PACKAGES.contains(pkg)) {
            val guardKey = "SETTINGS_ALL"
            if (!storage.isAppUnlocked(guardKey)) {
                launchGuard(key = guardKey, targetPackage = guardKey, isFakeCrash = false)
            }
            return
        }

        // 4. Locked Apps Check (skip if the app just exited within the last 1.2s)
        if (lockedApps.contains(pkg)) {
            val exitedAt = exitCooldownByPkg[pkg] ?: 0L
            if (System.currentTimeMillis() - exitedAt < 1200L) return

            val guardKey = "APP_$pkg"
            if (!storage.isAppUnlocked(guardKey)) {
                val isFakeCrash = storage.getFakeCrashApps().contains(pkg)
                launchGuard(key = guardKey, targetPackage = guardKey, isFakeCrash = isFakeCrash)
            }
        }
    }

    private fun collectScreenText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 150) {
            val node = stack.removeFirst()
            visited++
            node.text?.let {
                if (it.isNotBlank()) sb.append(it).append(' ')
            }
            node.contentDescription?.let {
                if (it.isNotBlank()) sb.append(it).append(' ')
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return sb.toString()
    }

    private fun launchGuard(key: String, targetPackage: String, isFakeCrash: Boolean) {
        val now = System.currentTimeMillis()
        if (key == lastPromptedKey && (now - lastPromptTimeMs) < 700L) return
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
