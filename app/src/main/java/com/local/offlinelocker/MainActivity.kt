package com.local.offlinelocker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.*

class MainActivity : Activity() {

    private lateinit var storage: LockStorage
    private lateinit var listView: ListView
    private lateinit var footerText: TextView
    private lateinit var popupBanner: TextView
    private lateinit var tabLockedBtn: Button
    private lateinit var tabAllBtn: Button

    private var showingLockedOnly = false
    private var searchQuery = ""
    private var allUserApps: List<AppEntry> = emptyList()
    private var displayedApps: List<AppEntry> = emptyList()
    private val bannerHandler = Handler(Looper.getMainLooper())

    data class AppEntry(
        val name: String,
        val packageName: String,
        val icon: Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = LockStorage(this)

        allUserApps = packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .map {
                AppEntry(
                    name = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    icon = packageManager.getApplicationIcon(it)
                )
            }
            .sortedBy { it.name.lowercase() }

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B1120")) // Deep Obsidian Slate
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ================= 1. MIDNIGHT SLATE & EMERALD HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 52, 32, 36)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#1E293B"))
            )
        }

        val lockHeaderIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_idle_lock)
            setColorFilter(Color.parseColor("#10B981"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2610B981"))
            }
            setPadding(18, 18, 18, 18)
            layoutParams = LinearLayout.LayoutParams(84, 84).apply {
                rightMargin = 24
            }
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val headerTitle = TextView(this).apply {
            text = "Offline AppLock"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val headerSub = TextView(this).apply {
            text = "Encrypted Local Security Vault"
            textSize = 12f
            setTextColor(Color.parseColor("#10B981"))
        }

        titleBox.addView(headerTitle)
        titleBox.addView(headerSub)

        val settingsBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.parseColor("#F8FAFC"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#334155"))
            }
            setPadding(22, 22, 22, 22)
            contentDescription = "Settings"
            setOnClickListener { openSettingsDialog() }
        }

        header.addView(lockHeaderIcon)
        header.addView(titleBox)
        header.addView(settingsBtn)

        // ================= 2. TWO SEPARATE TABS (ALL APPS / LOCKED APPS) =================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 20, 32, 12)
        }

        tabAllBtn = Button(this).apply {
            text = "All Apps"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 10
            }
            setOnClickListener {
                showingLockedOnly = false
                updateTabStyles()
                refreshAppList()
            }
        }

        tabLockedBtn = Button(this).apply {
            text = "Locked Apps"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 10
            }
            setOnClickListener {
                showingLockedOnly = true
                updateTabStyles()
                refreshAppList()
            }
        }

        tabBar.addView(tabAllBtn)
        tabBar.addView(tabLockedBtn)

        // ================= 3. SEARCH BAR =================
        val searchBox = EditText(this).apply {
            hint = "Search installed apps..."
            setSingleLine(true)
            setPadding(36, 24, 36, 24)
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(32, 8, 32, 14)
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                    refreshAppList()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        // ================= 4. APP LIST =================
        listView = ListView(this).apply {
            dividerHeight = 0
            setPadding(24, 0, 24, 16)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // ================= 5. FOOTER =================
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(28, 24, 28, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
            }
        }

        footerText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        footer.addView(footerText)

        mainColumn.addView(header)
        mainColumn.addView(tabBar)
        mainColumn.addView(searchBox)
        mainColumn.addView(listView)
        mainColumn.addView(footer)

        // ================= 6. ANIMATED POPUP NOTIFICATION =================
        popupBanner = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#06130E"))
            gravity = Gravity.CENTER
            setPadding(36, 24, 36, 24)
            visibility = View.GONE
            elevation = 16f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 32f
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(36, 56, 36, 0)
            }
        }

        rootFrame.addView(mainColumn)
        rootFrame.addView(popupBanner)
        setContentView(rootFrame)

        updateTabStyles()
        refreshAppList()
        updateFooter()
    }

    private fun showAnimatedNotification(message: String, bgHex: String = "#10B981", textHex: String = "#06130E") {
        bannerHandler.removeCallbacksAndMessages(null)
        (popupBanner.background as? GradientDrawable)?.setColor(Color.parseColor(bgHex))
        popupBanner.setTextColor(Color.parseColor(textHex))
        popupBanner.text = "✓  $message"
        popupBanner.visibility = View.VISIBLE
        popupBanner.translationY = -160f
        popupBanner.alpha = 0f

        popupBanner.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        bannerHandler.postDelayed({
            popupBanner.animate()
                .translationY(-160f)
                .alpha(0f)
                .setDuration(240)
                .withEndAction { popupBanner.visibility = View.GONE }
                .start()
        }, 2000)
    }

    private fun updateTabStyles() {
        val activeBg = GradientDrawable().apply {
            setColor(Color.parseColor("#10B981"))
            cornerRadius = 24f
        }
        val inactiveBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#334155"))
        }

        if (showingLockedOnly) {
            tabLockedBtn.background = activeBg
            tabLockedBtn.setTextColor(Color.parseColor("#06130E"))
            tabAllBtn.background = inactiveBg
            tabAllBtn.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            tabAllBtn.background = activeBg
            tabAllBtn.setTextColor(Color.parseColor("#06130E"))
            tabLockedBtn.background = inactiveBg
            tabLockedBtn.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun updateFooter() {
        val count = storage.getLockedApps().size
        val timeout = storage.relockTimeoutSeconds
        val timeLabel = if (timeout == 0) "Instant" else "${timeout}s idle"
        val mode = storage.getAuthType().name
        footerText.text = "$count Locked  •  $mode  •  Auto-Relock: $timeLabel"
    }

    private fun refreshAppList() {
        val lockedSet = storage.getLockedApps()
        displayedApps = allUserApps.filter { app ->
            val matchesTab = if (showingLockedOnly) lockedSet.contains(app.packageName) else true
            val matchesSearch = if (searchQuery.isEmpty()) true else app.name.lowercase().contains(searchQuery)
            matchesTab && matchesSearch
        }

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedApps.size
            override fun getItem(position: Int): Any = displayedApps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = displayedApps[position]
                val isLocked = storage.getLockedApps().contains(app.packageName)

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 20, 24, 20)
                    background = GradientDrawable().apply {
                        setColor(if (isLocked) Color.parseColor("#132A2E") else Color.parseColor("#1E293B"))
                        cornerRadius = 24f
                        setStroke(
                            2,
                            if (isLocked) Color.parseColor("#10B981") else Color.parseColor("#293548")
                        )
                    }
                    layoutParams = AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT
                    )
                }

                val wrapper = FrameLayout(this@MainActivity).apply {
                    setPadding(0, 7, 0, 7)
                }

                val iconView = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(88, 88).apply {
                        rightMargin = 24
                    }
                }

                val nameText = TextView(this@MainActivity).apply {
                    text = app.name
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#F8FAFC"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val statusLockIcon = ImageView(this@MainActivity).apply {
                    setImageResource(
                        if (isLocked) android.R.drawable.ic_lock_idle_lock
                        else android.R.drawable.ic_partial_secure
                    )
                    setColorFilter(
                        if (isLocked) Color.parseColor("#10B981") else Color.parseColor("#64748B")
                    )
                    layoutParams = LinearLayout.LayoutParams(54, 54)
                }

                row.addView(iconView)
                row.addView(nameText)
                row.addView(statusLockIcon)

                row.setOnClickListener {
                    val updated = storage.getLockedApps().toMutableSet()
                    if (updated.contains(app.packageName)) {
                        updated.remove(app.packageName)
                        storage.setLockedApps(updated)
                        showAnimatedNotification("${app.name} Unlocked", "#F59E0B", "#0B1120")
                    } else {
                        updated.add(app.packageName)
                        storage.setLockedApps(updated)
                        showAnimatedNotification("${app.name} Locked", "#10B981", "#06130E")
                    }
                    updateFooter()
                    refreshAppList()
                }

                wrapper.addView(row)
                return wrapper
            }
        }
    }

    // ================= SETTINGS DIALOG (WITH INTERACTIVE 3X3 PATTERN SETUP) =================
    private fun openSettingsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(44, 32, 44, 32)
        }

        val accessBtn = Button(this).apply {
            text = "1. Enable Accessibility Service"
            setTextColor(Color.parseColor("#0B1120"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = 22f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val authLabel = TextView(this).apply {
            text = "\n2. Select Lock Method:"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val authSpinner = Spinner(this).apply {
            val modes = LockStorage.AuthType.entries.map { it.name }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(storage.getAuthType().ordinal)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Text box container (for PIN / Password)
        val textCredentialBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val secretInput = EditText(this).apply {
            hint = "Enter new PIN or Password (min 4)"
        }

        val saveSecretBtn = Button(this).apply {
            text = "Save PIN / Password"
            setTextColor(Color.parseColor("#06130E"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 22f
            }
            setOnClickListener {
                val secret = secretInput.text.toString()
                if (secret.length >= 4) {
                    val type = LockStorage.AuthType.entries[authSpinner.selectedItemPosition]
                    storage.setCredentials(type, secret)
                    secretInput.text.clear()
                    updateFooter()
                    showAnimatedNotification("${type.name} Saved Successfully!")
                } else {
                    Toast.makeText(this@MainActivity, "Minimum 4 characters/digits", Toast.LENGTH_SHORT).show()
                }
            }
        }

        textCredentialBox.addView(secretInput)
        textCredentialBox.addView(saveSecretBtn)

        // Interactive 3x3 Pattern Grid container (for PATTERN mode)
        val patternSetupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var firstPatternAttempt: String? = null
        val patternPromptText = TextView(this).apply {
            text = "Draw your new pattern (connect at least 4 dots):"
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

        val patternSetupView = PatternLockView(this).apply {
            onPatternComplete = { drawn ->
                if (drawn.length < 4) {
                    Toast.makeText(this@MainActivity, "Connect at least 4 dots", Toast.LENGTH_SHORT).show()
                } else if (firstPatternAttempt == null) {
                    firstPatternAttempt = drawn
                    patternPromptText.text = "Draw the same pattern again to confirm:"
                } else if (firstPatternAttempt == drawn) {
                    storage.setCredentials(LockStorage.AuthType.PATTERN, drawn)
                    firstPatternAttempt = null
                    patternPromptText.text = "Pattern saved! Draw again to change:"
                    updateFooter()
                    showAnimatedNotification("New 3x3 Pattern Saved!")
                } else {
                    firstPatternAttempt = null
                    patternPromptText.text = "Patterns didn't match. Draw new pattern:"
                    Toast.makeText(this@MainActivity, "Patterns did not match", Toast.LENGTH_SHORT).show()
                }
            }
        }

        patternSetupBox.addView(patternPromptText)
        patternSetupBox.addView(patternSetupView)

        authSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedType = LockStorage.AuthType.entries[pos]
                if (selectedType == LockStorage.AuthType.PATTERN) {
                    textCredentialBox.visibility = View.GONE
                    patternSetupBox.visibility = View.VISIBLE
                    firstPatternAttempt = null
                    patternPromptText.text = "Draw your new pattern (connect at least 4 dots):"
                } else {
                    patternSetupBox.visibility = View.GONE
                    textCredentialBox.visibility = View.VISIBLE
                    secretInput.inputType = if (selectedType == LockStorage.AuthType.PIN) {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Auto-Relock Timer
        val timerOptions = listOf(
            "Immediately (0 sec)" to 0,
            "After 15 seconds" to 15,
            "After 30 seconds" to 30,
            "After 1 minute" to 60,
            "After 5 minutes" to 300,
            "After 15 minutes" to 900
        )

        val timerLabel = TextView(this).apply {
            text = "\n3. Auto-Relock After Inactivity:"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val timerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                timerOptions.map { it.first }
            )
            val currentIdx = timerOptions.indexOfFirst { it.second == storage.relockTimeoutSeconds }
            if (currentIdx >= 0) setSelection(currentIdx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var initial = true
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (initial) {
                        initial = false
                        return
                    }
                    storage.relockTimeoutSeconds = timerOptions[pos].second
                    updateFooter()
                    showAnimatedNotification("Auto-Relock: ${timerOptions[pos].first}", "#38BDF8", "#0B1120")
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        // Native Lightweight Switches
        val bioSwitch = Switch(this).apply {
            text = "\nFingerprint Biometric Unlock"
            isChecked = storage.isFingerprintEnabled
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked ->
                storage.isFingerprintEnabled = checked
                showAnimatedNotification(
                    if (checked) "Fingerprint Enabled" else "Fingerprint Disabled"
                )
            }
        }

        val fakeCrashSwitch = Switch(this).apply {
            text = "Fake App Crash Mode\n(Long-press 'App info' to unlock)"
            isChecked = storage.isFakeCrashEnabled
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked ->
                storage.isFakeCrashEnabled = checked
                showAnimatedNotification(
                    if (checked) "Fake Crash Enabled" else "Fake Crash Disabled",
                    "#F59E0B",
                    "#0B1120"
                )
            }
        }

        container.addView(accessBtn)
        container.addView(authLabel)
        container.addView(authSpinner)
        container.addView(textCredentialBox)
        container.addView(patternSetupBox)
        container.addView(timerLabel)
        container.addView(timerSpinner)
        container.addView(bioSwitch)
        container.addView(fakeCrashSwitch)
        scroll.addView(container)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("AppLock Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
