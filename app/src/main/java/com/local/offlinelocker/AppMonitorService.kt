package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeForegroundPkg: String? = null
    private var lastPromptedKey: String? = null
    private var lastPromptTimeMs: Long = 0L

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

        private val APP_INFO_CLASS_HINTS = listOf(
            "AppInfo",
            "InstalledAppDetails",
            "ApplicationDetails",
            "ManageApplications",
            "AppDetail",
            "ApplicationsDetails"
        )

        private val UNINSTALL_CLASS_HINTS = listOf(
            "Uninstall",
            "Uninstaller",
            "DeleteApp"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storage = LockStorage(this)
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

        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty()) return

        // Ignore our own LockActivity overlay while authenticating so it doesn't wipe the session mid-unlock
        if (pkg == packageName || pkg == "com.local.offlinelocker") {
            return
        }

        val className = event.className?.toString().orEmpty()

        // Detect Recents / Overview screen ("com.android.systemui" with Recents class) or switching to any other app
        val isRecentsScreen = pkg == "com.android.systemui" &&
            (className.contains("Recents", ignoreCase = true) ||
                className.contains("Overview", ignoreCase = true))

        if (isRecentsScreen) {
            if (storage.isInstantLockEnabled) {
                storage.clearAllUnlockedApps()
            }
            activeForegroundPkg = null
            return
        }

        // Ignore transient systemui status/notification shade events unless it's Recents
        if (pkg == "com.android.systemui") return

        // User left the previously active foreground app (Home, Launcher, Another App, or Closed from Background)
        val prev = activeForegroundPkg
        if (prev != null && prev != pkg) {
            if (storage.isInstantLockEnabled) {
                storage.clearAppUnlocked(prev)
            }
        }
        activeForegroundPkg = pkg

        val isSystemOrInstaller = SETTINGS_PACKAGES.contains(pkg) || INSTALLER_PACKAGES.contains(pkg)
        val screenText = if (isSystemOrInstaller) {
            collectScreenText(rootInActiveWindow)
        } else {
            ""
        }

        // 1. Full-Phone Uninstall Blocker (works for ALL apps, locked or unlocked)
        val isUninstallUi = INSTALLER_PACKAGES.contains(pkg) ||
            UNINSTALL_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            screenText.contains("uninstall this app", ignoreCase = true) ||
            screenText.contains("do you want to uninstall", ignoreCase = true) ||
            (isSystemOrInstaller &&
                screenText.contains("uninstall", ignoreCase = true) &&
                (screenText.contains("cancel", ignoreCase = true) ||
                    screenText.contains("ok", ignoreCase = true) ||
                    screenText.contains("app info", ignoreCase = true)))

        if (storage.isAntiUninstallEnabled && isUninstallUi) {
            launchGuard(
                key = "UNINSTALL_ALL",
                targetPackage = "DISABLE_ANTI_UNINSTALL",
                isFakeCrash = false
            )
            return
        }

        // 2. Full-Phone App Info Lock (works for ALL apps, locked or unlocked)
        val isAppInfoScreen = !isUninstallUi && SETTINGS_PACKAGES.contains(pkg) &&
            (APP_INFO_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
                screenText.contains("app info", ignoreCase = true) ||
                screenText.contains("application info", ignoreCase = true) ||
                (screenText.contains("force stop", ignoreCase = true) &&
                    (screenText.contains("storage", ignoreCase = true) ||
                        screenText.contains("permissions", ignoreCase = true) ||
                        screenText.contains("mobile data", ignoreCase = true))))

        if (storage.isAppInfoLockEnabled && isAppInfoScreen) {
            val guardKey = "APP_INFO_ALL"
            if (!storage.isAppUnlocked(guardKey)) {
                launchGuard(
                    key = guardKey,
                    targetPackage = guardKey,
                    isFakeCrash = false
                )
            }
            return
        }

        // 3. System Settings Lock (reliable check across all Settings sub-screens)
        val isSettingsScreen = !isUninstallUi && !isAppInfoScreen && SETTINGS_PACKAGES.contains(pkg)
        if (storage.isSettingsLockEnabled && isSettingsScreen) {
            val guardKey = "SETTINGS_$pkg"
            if (!storage.isAppUnlocked(guardKey)) {
                launchGuard(
                    key = guardKey,
                    targetPackage = guardKey,
                    isFakeCrash = false
                )
            }
            return
        }

        // 4. Locked Apps Check
        if (storage.getLockedApps().contains(pkg)) {
            val guardKey = "APP_$pkg"
            if (!storage.isAppUnlocked(guardKey)) {
                val isFakeCrash = storage.getFakeCrashApps().contains(pkg)
                launchGuard(
                    key = guardKey,
                    targetPackage = guardKey,
                    isFakeCrash = isFakeCrash
                )
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
