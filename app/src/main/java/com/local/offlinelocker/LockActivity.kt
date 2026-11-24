package com.local.offlinelocker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class LockActivity : FragmentActivity() {

    private lateinit var storage: LockStorage
    private var targetPackage: String = ""
    private var crashDialog: AlertDialog? = null
    private lateinit var activeAuthMode: LockStorage.AuthType

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes.blurBehindRadius = 95
        }

        storage = LockStorage(this)
        targetPackage = intent.getStringExtra("TARGET_PACKAGE") ?: ""
        activeAuthMode = storage.getDefaultAuthType()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        if (storage.getFakeCrashApps().contains(targetPackage)) {
            showFakeCrashDialog()
        } else {
            showRealLockScreen()
        }
    }

    private fun getTargetAppLabel(): String {
        return try {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            "Protected App"
        }
    }

    private fun showFakeCrashDialog() {
        val appLabel = getTargetAppLabel()

        crashDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$appLabel keeps stopping")
            .setMessage("Close the app and try again.")
            .setCancelable(false)
            .setPositiveButton("App info") { _, _ -> goHome() }
            .setNegativeButton("Close app") { _, _ -> goHome() }
            .create()

        crashDialog?.show()

        crashDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnLongClickListener {
            crashDialog?.dismiss()
            showRealLockScreen()
            true
        }
    }

    private fun showRealLockScreen() {
        val rootFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val blurBackdrop = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#EB060B14"),
                    Color.parseColor("#F00F172A"),
                    Color.parseColor("#F5091E2C")
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(
                    RenderEffect.createBlurEffect(55f, 55f, Shader.TileMode.CLAMP)
                )
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 48, 40, 48)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9162032"))
                cornerRadius = 44f
                setStroke(2, Color.parseColor("#10B981"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(32, 40, 32, 40)
            }
        }

        val lockIconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_idle_lock)
            setColorFilter(Color.parseColor("#10B981"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2610B981"))
            }
            setPadding(26, 26, 26, 26)
            layoutParams = LinearLayout.LayoutParams(124, 124).apply {
                bottomMargin = 16
            }
        }

        val appTitle = TextView(this).apply {
            text = getTargetAppLabel()
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = if (activeAuthMode == LockStorage.AuthType.PATTERN) "Draw unlock pattern"
            else "Enter ${activeAuthMode.name} to unlock"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 20)
        }

        card.addView(lockIconView)
        card.addView(appTitle)
        card.addView(subtitle)

        // Mode switcher bar if user configured multiple credentials (PIN + Pattern + Password)
        val configuredModes = storage.getConfiguredAuthTypes()
        if (configuredModes.size > 1) {
            val modeSwitchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            }
            for (mode in configuredModes) {
                val chip = TextView(this).apply {
                    text = mode.name
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(24, 12, 24, 12)
                    setTextColor(if (mode == activeAuthMode) Color.parseColor("#06130E") else Color.parseColor("#94A3B8"))
                    background = GradientDrawable().apply {
                        setColor(if (mode == activeAuthMode) Color.parseColor("#10B981") else Color.parseColor("#0B1120"))
                        cornerRadius = 20f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 8, 0) }
                    setOnClickListener {
                        activeAuthMode = mode
                        showRealLockScreen()
                    }
                }
                modeSwitchRow.addView(chip)
            }
            card.addView(modeSwitchRow)
        }

        if (activeAuthMode == LockStorage.AuthType.PATTERN) {
            val patternContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
            }
            val patternView = PatternLockView(this).apply {
                hidePattern = storage.isPatternHidden
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                onPatternComplete = { drawnCode ->
                    if (storage.verifyCredential(LockStorage.AuthType.PATTERN, drawnCode)) {
                        onUnlocked()
                    } else {
                        shakeCard(card)
                        Toast.makeText(this@LockActivity, "Incorrect Pattern", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            patternContainer.addView(patternView)
            card.addView(patternContainer)
        } else {
            val input = EditText(this).apply {
                hint = if (activeAuthMode == LockStorage.AuthType.PIN) "••••" else "Enter Password"
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(32, 26, 32, 26)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0B1120"))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#334155"))
                }
                inputType = if (activeAuthMode == LockStorage.AuthType.PIN) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24
                }
            }

            val unlockBtn = Button(this).apply {
                text = "Unlock"
                setTextColor(Color.parseColor("#06130E"))
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#10B981"))
                    cornerRadius = 24f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 18
                }
                setOnClickListener {
                    if (storage.verifyCredential(activeAuthMode, input.text.toString())) {
                        onUnlocked()
                    } else {
                        input.text.clear()
                        shakeCard(card)
                        Toast.makeText(this@LockActivity, "Wrong ${activeAuthMode.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            card.addView(input)
            card.addView(unlockBtn)
        }

        val bioBtn = Button(this).apply {
            text = "Touch Fingerprint"
            setTextColor(Color.parseColor("#38BDF8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1120"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            visibility = if (storage.isFingerprintEnabled) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { triggerFingerprintPrompt() }
        }

        card.addView(bioBtn)
        rootFrame.addView(blurBackdrop)
        rootFrame.addView(card)
        setContentView(rootFrame)

        if (storage.isFingerprintEnabled) {
            triggerFingerprintPrompt()
        }
    }

    private fun shakeCard(card: View) {
        card.animate().translationX(22f).setDuration(45).withEndAction {
            card.animate().translationX(-22f).setDuration(45).withEndAction {
                card.animate().translationX(0f).setDuration(45).start()
            }.start()
        }.start()
    }

    private fun triggerFingerprintPrompt() {
       // In LockActivity.kt -> replace triggerFingerprintPrompt() body with:
val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
    BiometricManager.Authenticators.DEVICE_CREDENTIAL

val promptInfo = BiometricPrompt.PromptInfo.Builder()
    .setTitle("Unlock ${getTargetAppLabel()}")
    .setSubtitle("Use Face Unlock, Fingerprint, or Device PIN/Pattern")
    .setAllowedAuthenticators(authenticators)
    .build()

prompt.authenticate(promptInfo)
        ) return

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onUnlocked()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ${getTargetAppLabel()}")
            .setSubtitle("Touch the Pixel fingerprint sensor")
            .setNegativeButtonText("Use ${activeAuthMode.name}")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun onUnlocked() {
        AppMonitorService.markAppUnlocked(targetPackage)
        finish()
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}
