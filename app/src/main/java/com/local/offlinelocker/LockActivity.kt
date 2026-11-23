package com.local.offlinelocker

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class LockActivity : AppCompatActivity() {

    private lateinit var storage: LockStorage
    private var targetPackage: String = ""
    private var crashDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = LockStorage(this)
        targetPackage = intent.getStringExtra("TARGET_PACKAGE") ?: ""

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        if (storage.isFakeCrashEnabled) {
            showFakeCrashDialog()
        } else {
            showRealLockScreen()
        }
    }

    private fun showFakeCrashDialog() {
        val appLabel = try {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            "App"
        }

        crashDialog = AlertDialog.Builder(this)
            .setTitle("$appLabel keeps stopping")
            .setMessage("Close the app and try again.")
            .setCancelable(false)
            .setPositiveButton("App info") { _, _ ->
                goHome()
            }
            .setNegativeButton("Close app") { _, _ ->
                goHome()
            }
            .create()

        crashDialog?.show()

        // Secret trigger: Long-press "App info" to reveal the real unlock screen
        crashDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnLongClickListener {
            crashDialog?.dismiss()
            showRealLockScreen()
            true
        }
    }

    private fun showRealLockScreen() {
        val authType = storage.getAuthType()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "Unlock (${authType.name})"
            textSize = 20f
        }

        val input = EditText(this).apply {
            hint = when (authType) {
                LockStorage.AuthType.PIN -> "Enter PIN"
                LockStorage.AuthType.PASSWORD -> "Enter Password"
                LockStorage.AuthType.PATTERN -> "Enter Pattern (e.g. 12589)"
            }
            inputType = when (authType) {
                LockStorage.AuthType.PIN, LockStorage.AuthType.PATTERN ->
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                LockStorage.AuthType.PASSWORD ->
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        val unlockBtn = Button(this).apply {
            text = "Unlock"
            setOnClickListener {
                if (storage.verifySecret(input.text.toString())) {
                    onUnlocked()
                } else {
                    Toast.makeText(this@LockActivity, "Incorrect credentials", Toast.LENGTH_SHORT).show()
                    input.text.clear()
                }
            }
        }

        val bioBtn = Button(this).apply {
            text = "Use Fingerprint"
            visibility = if (storage.isFingerprintEnabled) View.VISIBLE else View.GONE
            setOnClickListener { triggerFingerprintPrompt() }
        }

        container.addView(title)
        container.addView(input)
        container.addView(unlockBtn)
        container.addView(bioBtn)
        setContentView(container)

        if (storage.isFingerprintEnabled) {
            triggerFingerprintPrompt()
        }
    }

    private fun triggerFingerprintPrompt() {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) return

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onUnlocked()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("App Locked")
            .setSubtitle("Touch the Pixel fingerprint sensor")
            .setNegativeButtonText("Use ${storage.getAuthType().name}")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun onUnlocked() {
        AppMonitorService.temporarilyUnlockedPackage = targetPackage
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
