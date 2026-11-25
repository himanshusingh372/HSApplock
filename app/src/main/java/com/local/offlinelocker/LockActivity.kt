package com.local.offlinelocker

import android.app.AlertDialog
import android.content.Intent
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
    private lateinit var unlockPinBtn: Button
    private lateinit var passInput: EditText
    private lateinit var unlockPassBtn: Button
    private lateinit var patternView: PatternLockView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        storage = LockStorage(this)
        targetPackage = intent.getStringExtra("TARGET_PACKAGE") ?: ""
        val isFakeCrash = intent.getBooleanExtra("FAKE_CRASH", false)

        val outerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(36, 36, 36, 36)
        }

        val centerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 28.0f
                setStroke(2, Color.parseColor("#334155"))
            }
            setPadding(36, 40, 36, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = when (targetPackage) {
                "SELF_APP_LOCK" -> "Unlock HS AppLock"
                "DISABLE_ANTI_UNINSTALL", "UNINSTALL_ALL" -> "Uninstall Protection"
                "APP_INFO_ALL" -> "App Info Locked"
                else -> "Authentication Required"
            }
            textSize = 20.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        statusText = TextView(this).apply {
            text = "Enter PIN, Password, or Pattern"
            textSize = 13.0f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        pinInput = EditText(this).apply {
            hint = "Enter PIN"
            textSize = 18.0f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 18.0f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnEditorActionListener { _, _, _ ->
                verifyPin(text.toString())
                true
            }
        }

        unlockPinBtn = Button(this).apply {
            text = "Unlock with PIN"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0277BD"))
                cornerRadius = 16.0f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener { verifyPin(pinInput.text.toString()) }
        }

        passInput = EditText(this).apply {
            hint = "Enter Password"
            textSize = 16.0f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 18.0f
                setStroke(2, Color.parseColor("#475569"))
            }
            setPadding(24, 18, 24, 18)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        unlockPassBtn = Button(this).apply {
            text = "Unlock with Password"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0277BD"))
                cornerRadius = 16.0f
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener { verifyPassword(passInput.text.toString()) }
        }

        // Large 300dp pattern grid centered in the lock card
        val screenWidth = resources.displayMetrics.widthPixels
        val patternSizePx = minOf((resources.displayMetrics.density * 300).toInt(), (screenWidth * 0.78).toInt())
        patternView = PatternLockView(this).apply {
            compactMode = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(patternSizePx, patternSizePx).apply {
                gravity = Gravity.CENTER
                topMargin = 10
                bottomMargin = 10
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
            ).apply { topMargin = 20 }
        }

        fun makeModeBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12.0f
                setTextColor(Color.parseColor("#E2E8F0"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F172A"))
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
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 14.0f
                setStroke(1, Color.parseColor("#FDD835"))
            }
            visibility = if (storage.isFingerprintEnabled) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            setOnClickListener { launchBiometricPrompt() }
        }

        centerCard.addView(titleView)
        centerCard.addView(statusText)
        centerCard.addView(pinInput)
        centerCard.addView(unlockPinBtn)
        centerCard.addView(passInput)
        centerCard.addView(unlockPassBtn)
        centerCard.addView(patternView)
        centerCard.addView(modeRow)
        centerCard.addView(bioBtn)

        outerContainer.addView(centerCard)
        setContentView(outerContainer)

        if (isFakeCrash) {
            showFakeCrashDialog()
        } else {
            showPinMode()
            if (storage.isFingerprintEnabled &&
                targetPackage != "DISABLE_ANTI_UNINSTALL" &&
                targetPackage != "UNINSTALL_ALL"
            ) {
                launchBiometricPrompt()
            }
        }
    }

    private fun showFakeCrashDialog() {
        val rawPkg = targetPackage.removePrefix("APP_")
        val appLabel = try {
            val info = packageManager.getApplicationInfo(rawPkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "App"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("$appLabel keeps stopping")
            .setMessage("App info or close app")
            .setCancelable(false)
            .setPositiveButton("Close app") { _, _ ->
                goHomeAndFinish()
            }
            .setNeutralButton("App info") { _, _ ->
                // Normal tap behaves like a standard crash dialog and closes the app
                goHomeAndFinish()
            }
            .create()

        dialog.show()

        // Secret trigger: Hold (long-press) "App info" button to reveal the unlock screen
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnLongClickListener {
            dialog.dismiss()
            showPinMode()
            if (storage.isFingerprintEnabled) launchBiometricPrompt()
            true
        }
    }

    private fun goHomeAndFinish() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun showPinMode() {
        pinInput.visibility = View.VISIBLE
        unlockPinBtn.visibility = View.VISIBLE
        passInput.visibility = View.GONE
        unlockPassBtn.visibility = View.GONE
        patternView.visibility = View.GONE
        pinInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showPasswordMode() {
        pinInput.visibility = View.GONE
        unlockPinBtn.visibility = View.GONE
        passInput.visibility = View.VISIBLE
        unlockPassBtn.visibility = View.VISIBLE
        patternView.visibility = View.GONE
        passInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(passInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showPatternMode() {
        pinInput.visibility = View.GONE
        unlockPinBtn.visibility = View.GONE
        passInput.visibility = View.GONE
        unlockPassBtn.visibility = View.GONE
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

        // Marks UNINSTALL_ALL / APP_INFO_ALL / SETTINGS_ALL / APP_* unlocked for this active foreground session
        if (targetPackage.isNotEmpty()) {
            storage.markAppUnlocked(targetPackage)
        }
        setResult(RESULT_OK)
        finish()
    }
}
