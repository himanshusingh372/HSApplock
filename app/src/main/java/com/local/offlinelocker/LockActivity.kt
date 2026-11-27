package com.local.offlinelocker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.math.abs

class LockActivity : FragmentActivity() {

    private lateinit var storage: LockStorage
    private lateinit var swipeDetector: GestureDetector
    private var targetPackage: String = ""

    private lateinit var fakeCrashContainer: LinearLayout
    private lateinit var authContainer: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var pinDotsRow: LinearLayout
    private lateinit var pinKeypadContainer: LinearLayout
    private lateinit var patternView: PatternLockView
    private lateinit var modeSwitchPill: TextView

    private val enteredPin = StringBuilder()
    private val dotViews = mutableListOf<View>()
    private val holdHandler = Handler(Looper.getMainLooper())
    private var isPatternMode = false

    companion object {
        private val COLOR_BG_FROSTED = Color.parseColor("#EFECE6")
        private val COLOR_KEY_CIRCLE = Color.parseColor("#FAF8F5")
        private val COLOR_KEY_ACCENT = Color.parseColor("#F2B885")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#1F242D")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#5C6470")
        private val COLOR_PILL_BG = Color.parseColor("#E3DFD7")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = COLOR_BG_FROSTED
        window.navigationBarColor = COLOR_BG_FROSTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        storage = LockStorage(this)
        targetPackage = intent.getStringExtra("TARGET_PACKAGE") ?: ""
        val isFakeCrash = intent.getBooleanExtra("FAKE_CRASH", false)

        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || authContainer.visibility != View.VISIBLE) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > 130 && abs(velocityX) > 130) {
                    if (isPatternMode) showPinMode() else showPatternMode()
                    return true
                }
                return false
            }
        })

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BG_FROSTED)
            setPadding(28, 36, 28, 28)
        }

        fakeCrashContainer = buildFakeCrashView()
        authContainer = buildAuthView()

        rootFrame.addView(fakeCrashContainer)
        rootFrame.addView(authContainer)
        setContentView(rootFrame)

        val defaultToPattern = storage.hasCredential(LockStorage.AuthType.PATTERN) &&
            !storage.hasCredential(LockStorage.AuthType.PIN)

        if (isFakeCrash) {
            window.statusBarColor = Color.parseColor("#090D16")
            window.navigationBarColor = Color.parseColor("#090D16")
            rootFrame.setBackgroundColor(Color.parseColor("#F5090D16"))
            fakeCrashContainer.visibility = View.VISIBLE
            authContainer.visibility = View.GONE
        } else {
            fakeCrashContainer.visibility = View.GONE
            authContainer.visibility = View.VISIBLE
            if (defaultToPattern) showPatternMode() else showPinMode()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun buildFakeCrashView(): LinearLayout {
        val rawPkg = targetPackage.removePrefix("APP_")
        val appLabel = try {
            val info = packageManager.getApplicationInfo(rawPkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "App"
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val dialogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E2430"))
                cornerRadius = 32.0f
                setStroke(1, Color.parseColor("#334155"))
            }
            setPadding(44, 44, 44, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = "$appLabel keeps stopping"
            textSize = 19.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 14)
        }

        val subtitle = TextView(this).apply {
            text = "Close the app or check app info for details."
            textSize = 14.0f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, 32)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val closeBtn = TextView(this).apply {
            text = "Close app"
            textSize = 14.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(16, 20, 24, 20)
            setOnClickListener { goHomeAndFinish() }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1.0f)
        }

        var secretTriggered = false
        val unlockRunnable = Runnable {
            secretTriggered = true
            window.statusBarColor = COLOR_BG_FROSTED
            window.navigationBarColor = COLOR_BG_FROSTED
            (fakeCrashContainer.parent as? View)?.setBackgroundColor(COLOR_BG_FROSTED)
            fakeCrashContainer.visibility = View.GONE
            authContainer.visibility = View.VISIBLE
            val defaultToPattern = storage.hasCredential(LockStorage.AuthType.PATTERN) &&
                !storage.hasCredential(LockStorage.AuthType.PIN)
            if (defaultToPattern) showPatternMode() else showPinMode()
        }

        val appInfoBtn = TextView(this).apply {
            text = "App info"
            textSize = 14.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#93C5FD"))
            gravity = Gravity.END
            setPadding(28, 20, 16, 20)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        secretTriggered = false
                        holdHandler.postDelayed(unlockRunnable, 1800L)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        holdHandler.removeCallbacks(unlockRunnable)
                        if (!secretTriggered) {
                            goHomeAndFinish()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        holdHandler.removeCallbacks(unlockRunnable)
                        true
                    }
                    else -> false
                }
            }
        }

        buttonRow.addView(closeBtn)
        buttonRow.addView(spacer)
        buttonRow.addView(appInfoBtn)

        dialogCard.addView(title)
        dialogCard.addView(subtitle)
        dialogCard.addView(buttonRow)
        wrapper.addView(dialogCard)
        return wrapper
    }

    private fun buildAuthView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Larger top spacer pushes the keypad/pattern lower for comfortable single-hand reach
        val topSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1.35f)
        }

        titleView = TextView(this).apply {
            text = "Enter PIN"
            textSize = 21.0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }

        subtitleView = TextView(this).apply {
            text = "Swipe left/right for Pattern"
            textSize = 12.0f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }

        pinDotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 22 }
        }
        rebuildPinDots(storage.getPinLength())

        // Right-hand friendly ergonomic keypad (70dp keys, shifted slightly right for right-thumb reach)
        pinKeypadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 0, (resources.displayMetrics.density * 14).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "→")
        )

        val btnSizePx = (resources.displayMetrics.density * 70).toInt()
        val btnMarginPx = (resources.displayMetrics.density * 9).toInt()

        for (rowDigits in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (btnMarginPx * 1.4).toInt() }
            }

            for (digit in rowDigits) {
                val isActionKey = digit == "⌫" || digit == "→"
                val keyBtn = TextView(this).apply {
                    text = digit
                    textSize = if (isActionKey) 20.0f else 25.0f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setTextColor(COLOR_TEXT_PRIMARY)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (isActionKey) COLOR_KEY_ACCENT else COLOR_KEY_CIRCLE)
                    }
                    layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                        leftMargin = btnMarginPx
                        rightMargin = btnMarginPx
                    }
                    setOnClickListener { onKeypadPress(digit) }
                }
                rowLayout.addView(keyBtn)
            }
            pinKeypadContainer.addView(rowLayout)
        }

        // Right-hand reachable Pattern Grid
        val screenWidth = resources.displayMetrics.widthPixels
        val patternSizePx = minOf((resources.displayMetrics.density * 295).toInt(), (screenWidth * 0.80).toInt())
        patternView = PatternLockView(this).apply {
            compactMode = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(patternSizePx, patternSizePx).apply {
                gravity = Gravity.END
                rightMargin = (resources.displayMetrics.density * 10).toInt()
                topMargin = 8
                bottomMargin = 12
            }
            onPatternComplete = { drawn ->
                if (storage.verifyCredential(LockStorage.AuthType.PATTERN, drawn)) {
                    onAuthSuccess()
                } else {
                    subtitleView.text = "Wrong pattern. Try again"
                    subtitleView.setTextColor(Color.parseColor("#DC2626"))
                }
            }
        }

        val bottomSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.35f)
        }

        // Bottom Bar: "Use Fingerprint" on bottom-left (just like Pixel "Use PIN"), mode switch on bottom-right
        val bottomPillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val bioPill = TextView(this).apply {
            text = "Use Fingerprint"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(34, 16, 34, 16)
            background = GradientDrawable().apply {
                setColor(COLOR_PILL_BG)
                cornerRadius = 50.0f
            }
            visibility = if (storage.isFingerprintEnabled &&
                targetPackage != "DISABLE_ANTI_UNINSTALL"
            ) View.VISIBLE else View.GONE
            setOnClickListener { launchBiometricPrompt() }
        }

        val pillSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1.0f)
        }

        modeSwitchPill = TextView(this).apply {
            text = "Pattern"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(34, 16, 34, 16)
            background = GradientDrawable().apply {
                setColor(COLOR_PILL_BG)
                cornerRadius = 50.0f
            }
            setOnClickListener {
                if (isPatternMode) showPinMode() else showPatternMode()
            }
        }

        bottomPillRow.addView(bioPill)
        bottomPillRow.addView(pillSpacer)
        bottomPillRow.addView(modeSwitchPill)

        container.addView(topSpacer)
        container.addView(titleView)
        container.addView(subtitleView)
        container.addView(pinDotsRow)
        container.addView(pinKeypadContainer)
        container.addView(patternView)
        container.addView(bottomSpacer)
        container.addView(bottomPillRow)
        return container
    }

    private fun rebuildPinDots(totalDots: Int) {
        pinDotsRow.removeAllViews()
        dotViews.clear()
        val dotSize = (resources.displayMetrics.density * 10).toInt()
        val dotMargin = (resources.displayMetrics.density * 8).toInt()
        for (i in 0 until totalDots) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(3, COLOR_TEXT_SECONDARY)
                }
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    leftMargin = dotMargin
                    rightMargin = dotMargin
                }
            }
            dotViews.add(dot)
            pinDotsRow.addView(dot)
        }
    }

    private fun updatePinDots() {
        val targetLen = storage.getPinLength()
        if (dotViews.size != targetLen) {
            rebuildPinDots(targetLen)
        }
        val count = enteredPin.length
        for (i in dotViews.indices) {
            val filled = i < count
            dotViews[i].background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (filled) {
                    setColor(COLOR_TEXT_PRIMARY)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(3, COLOR_TEXT_SECONDARY)
                }
            }
        }
    }

    private fun onKeypadPress(key: String) {
        subtitleView.setTextColor(COLOR_TEXT_SECONDARY)
        val maxLen = storage.getPinLength()
        when (key) {
            "⌫" -> {
                if (enteredPin.isNotEmpty()) {
                    enteredPin.deleteCharAt(enteredPin.length - 1)
                }
            }
            "→" -> {
                val candidate = enteredPin.toString()
                if (storage.verifyCredential(LockStorage.AuthType.PIN, candidate)) {
                    onAuthSuccess()
                    return
                } else {
                    enteredPin.clear()
                    subtitleView.text = "Wrong PIN. Try again"
                    subtitleView.setTextColor(Color.parseColor("#DC2626"))
                }
            }
            else -> {
                if (enteredPin.length < maxLen) {
                    enteredPin.append(key)
                    val candidate = enteredPin.toString()
                    if (candidate.length == maxLen) {
                        if (storage.verifyCredential(LockStorage.AuthType.PIN, candidate)) {
                            updatePinDots()
                            onAuthSuccess()
                            return
                        } else {
                            enteredPin.clear()
                            subtitleView.text = "Wrong PIN. Try again"
                            subtitleView.setTextColor(Color.parseColor("#DC2626"))
                        }
                    }
                }
            }
        }
        updatePinDots()
    }

    private fun showPinMode() {
        isPatternMode = false
        enteredPin.clear()
        updatePinDots()
        titleView.text = "Enter PIN"
        subtitleView.text = "Swipe left/right for Pattern"
        subtitleView.setTextColor(COLOR_TEXT_SECONDARY)
        pinDotsRow.visibility = View.VISIBLE
        pinKeypadContainer.visibility = View.VISIBLE
        patternView.visibility = View.GONE
        modeSwitchPill.text = "Pattern"
    }

    private fun showPatternMode() {
        isPatternMode = true
        titleView.text = "Draw Pattern"
        subtitleView.text = "Swipe left/right for PIN"
        subtitleView.setTextColor(COLOR_TEXT_SECONDARY)
        pinDotsRow.visibility = View.GONE
        pinKeypadContainer.visibility = View.GONE
        patternView.visibility = View.VISIBLE
        patternView.invalidate()
        modeSwitchPill.text = "PIN"
    }

    private fun goHomeAndFinish() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun launchBiometricPrompt() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthSuccess()
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("HS AppLock")
            .setSubtitle("Touch the fingerprint sensor")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(info)
    }

    private fun onAuthSuccess() {
        if (targetPackage == "SELF_APP_LOCK" || targetPackage == "DISABLE_ANTI_UNINSTALL") {
            setResult(RESULT_OK)
            finish()
            return
        }

        if (targetPackage.isNotEmpty()) {
            storage.markAppUnlocked(targetPackage)
        }
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        holdHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
