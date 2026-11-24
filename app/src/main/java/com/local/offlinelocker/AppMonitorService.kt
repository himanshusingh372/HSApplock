package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeUnlockedPackage: String? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private var blurOverlayView: View? = null

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (storage.isMasterPaused) return
        val eventType = event?.eventType ?: return

        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val activePkg = activeUnlockedPackage
            if (activePkg != null && storage.isBackgroundBlurEnabled && storage.getLockedApps().contains(activePkg)) {
                val foregroundNow = rootInActiveWindow?.packageName?.toString()
                if (foregroundNow != null && foregroundNow != activePkg && foregroundNow != packageName) {
                    showBackgroundBlurShield()
                }
            }
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        // Trigger Recents blur immediately if Pixel / MIUI Launcher enters foreground while a locked app was open
        if ((pkg == "com.google.android.apps.nexuslauncher" || pkg == "com.miui.home") && activeUnlockedPackage != null) {
            if (storage.isBackgroundBlurEnabled && storage.getLockedApps().contains(activeUnlockedPackage)) {
                showBackgroundBlurShield()
            }
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == activeUnlockedPackage) {
                lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
            }
            return
        }

        if (activeUnlockedPackage != null && activeUnlockedPackage != pkg) {
            val previousPkg = activeUnlockedPackage!!
            idleHandler.removeCallbacks(idleCheckRunnable)

            if (storage.isBackgroundBlurEnabled && storage.getLockedApps().contains(previousPkg)) {
                showBackgroundBlurShield()
            }

            if (storage.isLockOnExitEnabled) {
                lastInteractionMap.remove(previousPkg)
            } else {
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

    private fun showBackgroundBlurShield() {
        if (!Settings.canDrawOverlays(this) || blurOverlayView != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = 110
                }
            }

            val shield = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#E60B1120"))
                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val icon = ImageView(context).apply {
                        setImageResource(android.R.drawable.ic_lock_idle_lock)
                        setColorFilter(Color.parseColor("#10B981"))
                        layoutParams = LinearLayout.LayoutParams(120, 120)
                    }
                    val label = TextView(context).apply {
                        text = "HS AppLock Privacy Blur"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(0, 18, 0, 0)
                    }
                    addView(icon)
                    addView(label)
                }
                addView(box, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
            }

            blurOverlayView = shield
            wm.addView(shield, params)

            idleHandler.postDelayed({
                try {
                    blurOverlayView?.let { wm.removeView(it) }
                } catch (_: Exception) {
                }
                blurOverlayView = null
            }, 1300L)
        } catch (_: Exception) {
            blurOverlayView = null
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
