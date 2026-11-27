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
    private lateinit var pinKeypadContainer: LinearLayout
    private lateinit var patternView: PatternLockView
    private lateinit var pinDotsView: TextView
    private lateinit var statusText: TextView

    private val enteredPin = StringBuilder()
    private val holdHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#090D16")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = 48
            }
        }

        storage = LockStorage(this)
        targetPackage = intent.getStringExtra("TARGET_PACKAGE") ?: ""
        val isFakeCrash = intent.getBooleanExtra("FAKE_CRASH", false)

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5090D16"))
            setPadding(36, 48, 36, 36)
        }

        // 1. Blank/Blurred Fake Crash Screen with Right-Aligned Long-Hold "App info"
        fakeCrashContainer = buildFakeCrashView()

        // 2. Full-Screen Native-Style PIN Keypad + Large Pattern Screen
        authContainer = buildAuthView()

        rootFrame.addView(fakeCrashContainer)
        rootFrame.addView(authContainer)
        setContentView(rootFrame)

        if (isFakeCrash) {
            fakeCrashContainer.visibility = View.VISIBLE
            authContainer.visibility = View.GONE
        } else {
            fakeCrashContainer.visibility = View.GONE
            authContainer.visibility = View.VISIBLE
            if (storage.hasCredential(LockStorage.AuthType.PATTERN) && !storage.hasCredential(LockStorage.AuthType.PIN)) {
    showPatternMode()
} else {
    showPinMode()
}
            if (storage.isFingerprintEnabled &&
                targetPackage != "DISABLE_ANTI_UNINSTALL" &&
                targetPackage != "SYSTEM_ACTION_GUARD"
            ) {
                launchBiometricPrompt()
            }
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

        // Bottom button row: "Close app" on left, "App info" shifted to the right side
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
            fakeCrashContainer.visibility = View.GONE
            authContainer.visibility = View.VISIBLE
           if (storage.hasCredential(LockStorage.AuthType.PATTERN) && !storage.hasCredential(LockStorage.AuthType.PIN)) {
    showPatternMode()
} else {
    showPinMode()
}
            if (storage.isFingerprintEnabled) launchBiometricPrompt()
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
                        // Requires holding "App info" for 1.8 seconds to unlock
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
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = when (targetPackage) {
                "SELF_APP_LOCK" -> "HS AppLock"
                "DISABLE_ANTI_UNINSTALL", "SYSTEM_ACTION_GUARD" -> "Protected System Action"
                "APP_INFO_GUARD" -> "App Info Locked"
                else -> "Enter AppLock PIN or Pattern"
            }
            textSize = 21.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        statusText = TextView(this).apply {
            text = "Enter PIN or draw pattern"
            textSize = 13.0f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        pinDotsView = TextView(this).apply {
            text = "○  ○  ○  ○"
            textSize = 24.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        // Full-Screen System-Style Numeric Keypad
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
            listOf("CLEAR", "0", "⌫")
        )

        val btnSizePx = (resources.displayMetrics.density * 74).toInt()
        for (rowDigits in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
            }

            for (digit in rowDigits) {
                val keyBtn = TextView(this).apply {
                    text = digit
                    textSize = if (digit == "CLEAR") 12.0f else 24.0f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#1E293B"))
                        setStroke(2, Color.parseColor("#334155"))
                    }
                    layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                        leftMargin = 18
                        rightMargin = 18
                    }
                    setOnClickListener { onKeypadPress(digit) }
                }
                rowLayout.addView(keyBtn)
            }
            pinKeypadContainer.addView(rowLayout)
        }

        // Large 310dp Pattern Grid
        val screenWidth = resources.displayMetrics.widthPixels
        val patternSizePx = minOf((resources.displayMetrics.density * 310).toInt(), (screenWidth * 0.82).toInt())
        patternView = PatternLockView(this).apply {
            compactMode = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(patternSizePx, patternSizePx).apply {
                gravity = Gravity.CENTER
                topMargin = 12
                bottomMargin = 12
            }
            onPatternComplete = { drawn ->
                if (storage.verifyCredential(LockStorage.AuthType.PATTERN, drawn)) {
                    onAuthSuccess()
                } else {
                    statusText.text = "Wrong pattern. Try again."
                    statusText.setTextColor(Color.parseColor("#F87171"))
                }
            }
        }

        // Mode switcher: PIN and Pattern only (Password option removed)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }

        fun makeModeBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 13.0f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E2E8F0"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 18.0f
                    setStroke(1, Color.parseColor("#475569"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    leftMargin = 12
                    rightMargin = 12
                }
                setOnClickListener { onClick() }
            }
        }

        modeRow.addView(makeModeBtn("PIN Keypad") { showPinMode() })
        modeRow.addView(makeModeBtn("Pattern") { showPatternMode() })

        val bioBtn = Button(this).apply {
            text = "Fingerprint / Face Unlock"
            setTextColor(Color.parseColor("#FDD835"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 18.0f
                setStroke(1, Color.parseColor("#FDD835"))
            }
            visibility = if (storage.isFingerprintEnabled) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
                leftMargin = 12
                rightMargin = 12
            }
            setOnClickListener { launchBiometricPrompt() }
        }

        container.addView(titleView)
        container.addView(statusText)
        container.addView(pinDotsView)
        container.addView(pinKeypadContainer)
        container.addView(patternView)
        container.addView(modeRow)
        container.addView(bioBtn)
        return container
    }

    private fun onKeypadPress(key: String) {
        when (key) {
            "CLEAR" -> enteredPin.clear()
            "⌫" -> if (enteredPin.isNotEmpty()) enteredPin.deleteCharAt(enteredPin.length - 1)
            else -> if (enteredPin.length < 12) enteredPin.append(key)
        }
        updatePinDots()

        val candidate = enteredPin.toString()
        if (candidate.length >= 4 && storage.verifyCredential(LockStorage.AuthType.PIN, candidate)) {
            onAuthSuccess()
        }
    }

    private fun updatePinDots() {
        val len = enteredPin.length
        pinDotsView.text = if (len == 0) {
            "○  ○  ○  ○"
        } else {
            "●  ".repeat(len).trim()
        }
    }

    private fun showPinMode() {
        enteredPin.clear()
        updatePinDots()
        pinDotsView.visibility = View.VISIBLE
        pinKeypadContainer.visibility = View.VISIBLE
        patternView.visibility = View.GONE
    }

    private fun showPatternMode() {
        pinDotsView.visibility = View.GONE
        pinKeypadContainer.visibility = View.GONE
        patternView.visibility = View.VISIBLE
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
