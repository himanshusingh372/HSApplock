package com.local.offlinelocker

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class LockActivity : FragmentActivity() {

    private lateinit var storage: LockStorage
    private var targetPackage: String = ""

    private lateinit var pinInput: EditText
    private lateinit var passInput: EditText
    private lateinit var patternView: PatternLockView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        storage = LockStorage(this)
        targetPackage = intent.getStringExtra("TARGET_PACKAGE") ?: ""
        val isFakeCrash = intent.getBooleanExtra("FAKE_CRASH", false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(48, 96, 48, 64)
        }

        val titleView = TextView(this).apply {
            text = when (targetPackage) {
                "SELF_APP_LOCK" -> "Unlock HS AppLock"
                "DISABLE_ANTI_UNINSTALL" -> "Confirm Password to Disable Uninstall Blocker"
                else -> "Authentication Required"
            }
            textSize = 20.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        statusText = TextView(this).apply {
            text = "Enter PIN, Password, or Pattern"
            textSize = 13.0f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }

        pinInput = EditText(this).apply {
            hint = "Enter PIN"
            textSize = 18.0f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 20.0f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            setPadding(28, 24, 28, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnEditorActionListener { _, _, _ ->
                verifyPin(text.toString())
                true
            }
        }

        val unlockPinBtn = Button(this).apply {
            text = "Unlock with PIN"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0277BD"))
                cornerRadius = 18.0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            setOnClickListener { verifyPin(pinInput.text.toString()) }
        }

        passInput = EditText(this).apply {
            hint = "Enter Password"
            textSize = 16.0f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 20.0f
                setStroke(2, Color.parseColor("#475569"))
            }
            setPadding(28, 22, 28, 22)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }

        val unlockPassBtn = Button(this).apply {
            text = "Unlock with Password"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0277BD"))
                cornerRadius = 18.0f
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            setOnClickListener { verifyPassword(passInput.text.toString()) }
        }

        patternView = PatternLockView(this).apply {
            compactMode = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 24
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

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28 }
        }

        fun makeModeBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12.0f
                setTextColor(Color.parseColor("#E2E8F0"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 14.0f
                    setStroke(1, Color.parseColor("#475569"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    rightMargin = 8
                }
                setOnClickListener { onClick() }
            }
        }

        modeRow.addView(makeModeBtn("PIN") { showPinMode() })
        modeRow.addView(makeModeBtn("Password") { showPasswordMode() })
        modeRow.addView(makeModeBtn("Pattern") { showPatternMode() })

        val bioBtn = Button(this).apply {
            text = "Use Fingerprint / Biometrics"
            setTextColor(Color.parseColor("#FDD835"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 16.0f
                setStroke(1, Color.parseColor("#FDD835"))
            }
            visibility = if (storage.isFingerprintEnabled) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { launchBiometricPrompt() }
        }

        root.addView(titleView)
        root.addView(statusText)
        root.addView(pinInput)
        root.addView(unlockPinBtn)
        root.addView(passInput)
        root.addView(unlockPassBtn)
        root.addView(patternView)
        root.addView(modeRow)
        root.addView(bioBtn)
        setContentView(root)

        if (isFakeCrash) {
            showFakeCrashDialog()
        } else {
            showPinMode()
            if (storage.isFingerprintEnabled && targetPackage != "DISABLE_ANTI_UNINSTALL") {
                launchBiometricPrompt()
            }
        }
    }

    private fun showFakeCrashDialog() {
        val appLabel = try {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "App"
        }

        AlertDialog.Builder(this)
            .setTitle("$appLabel keeps stopping")
            .setMessage("App info or close app")
            .setCancelable(false)
            .setPositiveButton("Close app") { _, _ ->
                performGlobalActionHome()
            }
            .setNeutralButton("Unlock") { _, _ ->
                showPinMode()
                if (storage.isFingerprintEnabled) launchBiometricPrompt()
            }
            .show()
    }

    private fun performGlobalActionHome() {
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun showPinMode() {
        pinInput.visibility = View.VISIBLE
        passInput.visibility = View.GONE
        patternView.visibility = View.GONE
        pinInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showPasswordMode() {
        pinInput.visibility = View.GONE
        passInput.visibility = View.VISIBLE
        patternView.visibility = View.GONE
        passInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(passInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showPatternMode() {
        pinInput.visibility = View.GONE
        passInput.visibility = View.GONE
        patternView.visibility = View.VISIBLE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(patternView.windowToken, 0)
    }

    private fun verifyPin(pin: String) {
        if (storage.verifyCredential(LockStorage.AuthType.PIN, pin)) {
            onAuthSuccess()
        } else {
            pinInput.text.clear()
            statusText.text = "Incorrect PIN. Try again."
            statusText.setTextColor(Color.parseColor("#F87171"))
        }
    }

    private fun verifyPassword(password: String) {
        if (storage.verifyCredential(LockStorage.AuthType.PASSWORD, password)) {
            onAuthSuccess()
        } else {
            passInput.text.clear()
            statusText.text = "Incorrect Password. Try again."
            statusText.setTextColor(Color.parseColor("#F87171"))
        }
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }

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
}
