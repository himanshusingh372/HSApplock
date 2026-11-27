package com.local.offlinelocker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class LockActivity : FragmentActivity() {

    private lateinit var storage: LockStorage
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
        // Material You / Pixel Light Lock Palette
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

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BG_FROSTED)
            setPadding(36, 48, 36, 36)
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

        val topSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.7f)
        }

        titleView = TextView(this).apply {
            text = "Enter PIN"
            textSize = 22.0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 6)
        }

        subtitleView = TextView(this).apply {
            text = when (targetPackage) {
                "SELF_APP_LOCK" -> "HS AppLock"
                "DISABLE_ANTI_UNINSTALL", "SYSTEM_ACTION_GUARD" -> "Protected System Action"
                "APP_INFO_GUARD" -> "App Info"
                else -> "HS AppLock"
            }
            textSize = 13.0f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }

        // Crisp Hollow/Filled PIN Indicator Dots (matching Pixel lock screen)
        pinDotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 36 }
        }
        rebuildPinDots(6)

        // Clean Pixel-style Numeric Keypad (1-9, Backspace, 0, Enter)
        pinKeypadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
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

        val btnSizePx = (resources.displayMetrics.density * 78).toInt()
        val btnMarginPx = (resources.displayMetrics.density * 11).toInt()

        for (rowDigits in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (btnMarginPx * 1.5).toInt() }
            }

            for (digit in rowDigits) {
                val isActionKey = digit == "⌫" || digit == "→"
                val keyBtn = TextView(this).apply {
                    text = digit
                    textSize = if (isActionKey) 22.0f else 28.0f
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

        // Large 310dp Pattern Grid matching the same clean light aesthetic
        val screenWidth = resources.displayMetrics.widthPixels
        val patternSizePx = minOf((resources.displayMetrics.density * 310).toInt(), (screenWidth * 0.82).toInt())
        patternView = PatternLockView(this).apply {
            compactMode = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(patternSizePx, patternSizePx).apply {
                gravity = Gravity.CENTER
                topMargin = 8
                bottomMargin = 16
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
            layoutParams = LinearLayout.LayoutParams(1, 0, 1.0f)
        }

        // Bottom Pill Row: Switch PIN / Pattern + Fingerprint pill (styled like "Emergency" pill)
        val bottomPillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        }

        modeSwitchPill = TextView(this).apply {
            text = "Use Pattern"
            textSize = 14.0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(44, 20, 44, 20)
            background = GradientDrawable().apply {
                setColor(COLOR_PILL_BG)
                cornerRadius = 60.0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 10
                rightMargin = 10
            }
            setOnClickListener {
                if (isPatternMode) showPinMode() else showPatternMode()
            }
        }

        val bioPill = TextView(this).apply {
            text = "Fingerprint"
            textSize = 14.0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(44, 20, 44, 20)
            background = GradientDrawable().apply {
                setColor(COLOR_PILL_BG)
                cornerRadius = 60.0f
            }
            visibility = if (storage.isFingerprintEnabled &&
                targetPackage != "DISABLE_ANTI_UNINSTALL" &&
                targetPackage != "SYSTEM_ACTION_GUARD"
            ) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 10
                rightMargin = 10
            }
            setOnClickListener { launchBiometricPrompt() }
        }

        bottomPillRow.addView(modeSwitchPill)
        bottomPillRow.addView(bioPill)

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
        val dotSize = (resources.displayMetrics.density * 11).toInt()
        val dotMargin = (resources.displayMetrics.density * 9).toInt()
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
        val count = enteredPin.length
        if (count > dotViews.size) {
            rebuildPinDots(count)
        } else if (count <= 6 && dotViews.size != 6) {
            rebuildPinDots(6)
        }
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
                } else {
                    enteredPin.clear()
                    subtitleView.text = "Wrong PIN. Try again"
                    subtitleView.setTextColor(Color.parseColor("#DC2626"))
                }
            }
            else -> {
                if (enteredPin.length < 12) {
                    enteredPin.append(key)
                    val candidate = enteredPin.toString()
                    if (candidate.length >= 4 && storage.verifyCredential(LockStorage.AuthType.PIN, candidate)) {
                        updatePinDots()
                        onAuthSuccess()
                        return
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
        subtitleView.text = "HS AppLock"
        subtitleView.setTextColor(COLOR_TEXT_SECONDARY)
        pinDotsRow.visibility = View.VISIBLE
        pinKeypadContainer.visibility = View.VISIBLE
        patternView.visibility = View.GONE
        modeSwitchPill.text = "Use Pattern"
    }

    private fun showPatternMode() {
        isPatternMode = true
        titleView.text = "Draw Pattern"
        subtitleView.text = "HS AppLock"
        subtitleView.setTextColor(COLOR_TEXT_SECONDARY)
        pinDotsRow.visibility = View.GONE
        pinKeypadContainer.visibility = View.GONE
        patternView.visibility = View.VISIBLE
        modeSwitchPill.text = "Use PIN"
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
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthSuccess()
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("HS AppLock")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
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