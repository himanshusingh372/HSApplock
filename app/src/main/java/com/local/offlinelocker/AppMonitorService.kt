package com.local.offlinelocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityGestureEvent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlin.math.abs

class AppMonitorService : AccessibilityService() {

    private lateinit var storage: LockStorage
    private var activeUnlockedPackage: String? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private var blurOverlayView: View? = null
    private var floatingBubbleView: View? = null
    private var gesturePadOverlayView: View? = null

    companion object {
        private val lastInteractionMap = mutableMapOf<String, Long>()
        var instance: AppMonitorService? = null
        private var torchIsOn = false

        fun markAppUnlocked(pkg: String) {
            lastInteractionMap[pkg] = SystemClock.elapsedRealtime()
        }

        fun triggerSystemScreenshot(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
            } else false
        }

        fun toggleTorch(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cm.cameraIdList.firstOrNull() ?: return false
                torchIsOn = !torchIsOn
                cm.setTorchMode(cameraId, torchIsOn)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun refreshFloatingBubble() {
            instance?.syncFloatingBubbleState()
        }

        override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
    when (gestureEvent.gestureId) {
        GESTURE_3_FINGER_SWIPE_DOWN -> {
            triggerSystemScreenshot()
            return true
        }
        GESTURE_3_FINGER_SWIPE_UP -> {
            toggleTorch(this)
            return true
        }
        GESTURE_3_FINGER_DOUBLE_TAP -> {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch != null) startActivity(launch)
            return true
        }
    }
    return super.onGesture(gestureEvent)
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
        instance = this
        storage = LockStorage(this)
        syncFloatingBubbleState()
    }

    // ================= FLOATING EDGE GESTURE BUBBLE =================
    fun syncFloatingBubbleState() {
        if (!storage.isFloatingGestureEnabled || !Settings.canDrawOverlays(this)) {
            removeFloatingBubble()
            return
        }
        if (floatingBubbleView != null) return

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                116,
                116,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = 12
                y = 0
            }

            val bubble = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_input_add)
                setColorFilter(Color.parseColor("#38BDF8"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E60F172A"))
                    setStroke(3, Color.parseColor("#38BDF8"))
                }
                setPadding(28, 28, 28, 28)
                elevation = 14f
            }

            var initialY = 0
            var initialTouchY = 0f
            var moved = false

            bubble.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = params.y
                        initialTouchY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dy) > 12) moved = true
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(bubble, params)
                        } catch (_: Exception) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            openFloatingGesturePad()
                        }
                        true
                    }
                    else -> false
                }
            }

            floatingBubbleView = bubble
            wm.addView(bubble, params)
        } catch (_: Exception) {
            floatingBubbleView = null
        }
    }

    private fun removeFloatingBubble() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            floatingBubbleView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        floatingBubbleView = null
    }

    // ================= GLOBAL OVERLAY GESTURE PAD =================
    private fun openFloatingGesturePad() {
        if (gesturePadOverlayView != null || !Settings.canDrawOverlays(this)) return

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = 70
                }
            }

            val backdrop = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#CC060B14"))
                setOnClickListener { closeFloatingGesturePad() }
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(40, 40, 40, 40)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2162032"))
                    cornerRadius = 40f
                    setStroke(2, Color.parseColor("#38BDF8"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setOnClickListener { /* prevent backdrop dismiss */ }
            }

            val title = TextView(this).apply {
                text = "Draw Saved Hand Gesture"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F8FAFC"))
                setPadding(0, 0, 0, 8)
            }

            val status = TextView(this).apply {
                text = "Draw sign for Torch, Screenshot, or App"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, 20)
            }

            val gestureCanvas = HandGestureView(this).apply {
                onGestureDrawn = { signature ->
                    val matched = storage.getAllGestureBindings().firstOrNull { it.signature == signature }
                    if (matched != null) {
                        closeFloatingGesturePad()
                        executeGlobalGestureAction(matched.target, matched.label)
                    } else {
                        status.text = "No shortcut matched ($signature). Try again:"
                        status.setTextColor(Color.parseColor("#F59E0B"))
                        clearCanvas()
                    }
                }
            }

            val cancelBtn = Button(this).apply {
                text = "Close"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#334155"))
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 20 }
                setOnClickListener { closeFloatingGesturePad() }
            }

            card.addView(title)
            card.addView(status)
            card.addView(gestureCanvas)
            card.addView(cancelBtn)
            backdrop.addView(card)

            gesturePadOverlayView = backdrop
            wm.addView(backdrop, params)
        } catch (_: Exception) {
            gesturePadOverlayView = null
        }
    }

    private fun closeFloatingGesturePad() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            gesturePadOverlayView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        gesturePadOverlayView = null
    }

    private fun executeGlobalGestureAction(target: String, label: String) {
        when (target) {
            "ACTION_TORCH" -> {
                val ok = toggleTorch(this)
                Toast.makeText(this, if (ok) "Torch Toggled" else "Torch Unavailable", Toast.LENGTH_SHORT).show()
            }
            "ACTION_SCREENSHOT" -> {
                // Brief delay so the gesture overlay finishes closing before screenshot is snapped
                idleHandler.postDelayed({ triggerSystemScreenshot() }, 250L)
            }
            else -> {
                val launch = packageManager.getLaunchIntentForPackage(target)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null) {
                    Toast.makeText(this, "Opening $label", Toast.LENGTH_SHORT).show()
                    startActivity(launch)
                }
            }
        }
    }

    // ================= ACCESSIBILITY APP MONITORING =================
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
       if (pkg == "com.google.android.apps.nexuslauncher" && activeUnlockedPackage != null) {
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

    override fun onDestroy() {
        removeFloatingBubble()
        closeFloatingGesturePad()
        instance = null
        super.onDestroy()
    }
}
