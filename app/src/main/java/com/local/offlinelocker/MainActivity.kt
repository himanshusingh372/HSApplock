package com.local.offlinelocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
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
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.OvershootInterpolator
import android.widget.*

class MainActivity : Activity() {

    private lateinit var storage: LockStorage
    private lateinit var listView: ListView
    private lateinit var footerText: TextView
    private lateinit var popupBanner: TextView
    private lateinit var permissionWarningBox: LinearLayout
    private lateinit var permissionWarningText: TextView
    private lateinit var fixPermissionBtn: Button
    private lateinit var tabLockedBtn: Button
    private lateinit var tabAllBtn: Button

    private var showingLockedOnly = false
    private var searchQuery = ""
    private var allLaunchableApps: List<AppEntry> = emptyList()
    private var displayedApps: List<AppEntry> = emptyList()
    private val bannerHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQ_VPN_PERMISSION = 101
    }

    data class AppEntry(
        val name: String,
        val packageName: String,
        val icon: Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blur / obscure HS AppLock preview in Recents when backgrounded
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        storage = LockStorage(this)

        allLaunchableApps = packageManager.getInstalledApplications(0)
            .filter {
                it.packageName != packageName &&
                    packageManager.getLaunchIntentForPackage(it.packageName) != null
            }
            .map {
                AppEntry(
                    name = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    icon = packageManager.getApplicationIcon(it)
                )
            }
            .sortedBy { it.name.lowercase() }

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else 80
        val safeTopPadding = statusBarHeight + 56

        // ================= 1. HEADER WITH (+) GESTURE & SETTINGS ICONS =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, safeTopPadding, 32, 32)
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
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                rightMargin = 20
            }
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val headerTitle = TextView(this).apply {
            text = "HS AppLock"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val headerSub = TextView(this).apply {
            text = "Security Vault & NetGuard"
            textSize = 12f
            setTextColor(Color.parseColor("#10B981"))
        }

        titleBox.addView(headerTitle)
        titleBox.addView(headerSub)

        // Plus (+) Icon Button for Hand-Sign Gesture Shortcuts
        val gesturePlusBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.parseColor("#38BDF8"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E3A5F"))
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(102, 102).apply {
                rightMargin = 16
            }
            contentDescription = "Hand Sign Gestures"
            setOnClickListener { openGestureStudioDialog() }
        }

        // Settings Icon Button
        val settingsBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.parseColor("#F8FAFC"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#334155"))
            }
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(102, 102)
            contentDescription = "Settings"
            setOnClickListener { openSettingsDialog() }
        }

        header.addView(lockHeaderIcon)
        header.addView(titleBox)
        header.addView(gesturePlusBtn)
        header.addView(settingsBtn)

        // ================= 2. MANDATORY PERMISSIONS WARNING BANNER =================
        permissionWarningBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 20, 28, 20)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7F1D1D"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#EF4444"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(28, 16, 28, 4)
            }
        }

        permissionWarningText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FECACA"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 16
            }
        }

        fixPermissionBtn = Button(this).apply {
            text = "Enable Now"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = 18f
            }
        }

        permissionWarningBox.addView(permissionWarningText)
        permissionWarningBox.addView(fixPermissionBtn)

        // ================= 3. TWO SEPARATE TABS =================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 16, 32, 10)
        }

        tabAllBtn = Button(this).apply {
            text = "All Apps (${allLaunchableApps.size})"
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

        // ================= 4. SEARCH BAR =================
        val searchBox = EditText(this).apply {
            hint = "Search apps (Lock • Wi-Fi • Crash per app)..."
            setSingleLine(true)
            setPadding(36, 22, 36, 22)
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
                setMargins(32, 6, 32, 12)
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

        // ================= 5. APP LIST =================
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

        // ================= 6. FOOTER =================
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 22, 24, 34)
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
        mainColumn.addView(permissionWarningBox)
        mainColumn.addView(tabBar)
        mainColumn.addView(searchBox)
        mainColumn.addView(listView)
        mainColumn.addView(footer)

        // ================= 7. ANIMATED POPUP NOTIFICATION =================
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
                setMargins(36, safeTopPadding + 16, 36, 0)
            }
        }

        rootFrame.addView(mainColumn)
        rootFrame.addView(popupBanner)
        setContentView(rootFrame)

        updateTabStyles()
        refreshAppList()
        updateFooter()
    }

    override fun onResume() {
        super.onResume()
        checkMandatoryPermissions()
    }

    // ================= MANDATORY PERMISSIONS CHECK & REDIRECT =================
    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedComponent = ComponentName(this, AppMonitorService::class.java)
        return enabledServices.any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == expectedComponent
        }
    }

    private fun checkMandatoryPermissions() {
        if (!isAccessibilityEnabled()) {
            permissionWarningBox.visibility = View.VISIBLE
            permissionWarningText.text = "⚠ ERROR: Mandatory Accessibility Service is OFF. App lock will not work."
            fixPermissionBtn.setOnClickListener {
                Toast.makeText(this, "Enable HS AppLock in Accessibility", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else if (storage.isBackgroundBlurEnabled && !Settings.canDrawOverlays(this)) {
            permissionWarningBox.visibility = View.VISIBLE
            permissionWarningText.text = "⚠ ERROR: 'Display over other apps' permission required for Background Blur."
            fixPermissionBtn.setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } else {
            permissionWarningBox.visibility = View.GONE
        }
    }

    // ================= FIREWALL VPN SYNC =================
    private fun syncNetworkFirewall() {
        val blocked = storage.getBlockedNetApps()
        if (blocked.isEmpty()) {
            val stopIntent = Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_STOP
            }
            startService(stopIntent)
            return
        }

        val vpnPrepare = VpnService.prepare(this)
        if (vpnPrepare != null) {
            startActivityForResult(vpnPrepare, REQ_VPN_PERMISSION)
        } else {
            val startIntent = Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_UPDATE
            }
            startService(startIntent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PERMISSION && resultCode == RESULT_OK) {
            val startIntent = Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_UPDATE
            }
            startService(startIntent)
            showAnimatedNotification("App Internet Firewall Activated")
        }
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
        val lockedCount = storage.getLockedApps().size
        val netBlockedCount = storage.getBlockedNetApps().size
        val crashCount = storage.getFakeCrashApps().size
        footerText.text = "🔒 $lockedCount Locked  •  🌐 $netBlockedCount Net-Blocked  •  ⚠ $crashCount Crash"
    }

    private fun refreshAppList() {
        val lockedSet = storage.getLockedApps()
        displayedApps = allLaunchableApps.filter { app ->
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
                val isNetBlocked = storage.getBlockedNetApps().contains(app.packageName)
                val isFakeCrash = storage.getFakeCrashApps().contains(app.packageName)

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(20, 18, 20, 18)
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
                    setPadding(0, 6, 0, 6)
                }

                val iconView = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                        rightMargin = 18
                    }
                }

                val nameText = TextView(this@MainActivity).apply {
                    text = app.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#F8FAFC"))
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                // 1. Per-App Fake Crash Toggle Button
                val crashBtn = TextView(this@MainActivity).apply {
                    text = "CRASH"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(14, 12, 14, 12)
                    setTextColor(if (isFakeCrash) Color.WHITE else Color.parseColor("#64748B"))
                    background = GradientDrawable().apply {
                        setColor(if (isFakeCrash) Color.parseColor("#DC2626") else Color.parseColor("#0F172A"))
                        cornerRadius = 14f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 10 }
                    setOnClickListener {
                        val updated = storage.getFakeCrashApps().toMutableSet()
                        if (updated.contains(app.packageName)) {
                            updated.remove(app.packageName)
                            storage.setFakeCrashApps(updated)
                            showAnimatedNotification("${app.name}: Fake Crash OFF", "#64748B", "#F8FAFC")
                        } else {
                            updated.add(app.packageName)
                            storage.setFakeCrashApps(updated)
                            // Ensure app is also locked so Fake Crash triggers
                            val locked = storage.getLockedApps().toMutableSet().apply { add(app.packageName) }
                            storage.setLockedApps(locked)
                            showAnimatedNotification("${app.name}: Fake Crash ON", "#EF4444", "#FFFFFF")
                        }
                        updateFooter()
                        refreshAppList()
                    }
                }

                // 2. Per-App Internet / Wi-Fi On/Off Toggle Button
                val netBtn = TextView(this@MainActivity).apply {
                    text = if (isNetBlocked) "NET OFF" else "NET ON"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(14, 12, 14, 12)
                    setTextColor(if (isNetBlocked) Color.WHITE else Color.parseColor("#38BDF8"))
                    background = GradientDrawable().apply {
                        setColor(if (isNetBlocked) Color.parseColor("#D97706") else Color.parseColor("#0F172A"))
                        cornerRadius = 14f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 12 }
                    setOnClickListener {
                        val updated = storage.getBlockedNetApps().toMutableSet()
                        if (updated.contains(app.packageName)) {
                            updated.remove(app.packageName)
                            storage.setBlockedNetApps(updated)
                            syncNetworkFirewall()
                            showAnimatedNotification("${app.name}: Internet Enabled", "#38BDF8", "#0B1120")
                        } else {
                            updated.add(app.packageName)
                            storage.setBlockedNetApps(updated)
                            syncNetworkFirewall()
                            showAnimatedNotification("${app.name}: Internet Blocked", "#F59E0B", "#0B1120")
                        }
                        updateFooter()
                        refreshAppList()
                    }
                }

                // 3. Per-App Lock Status Icon
                val statusLockIcon = ImageView(this@MainActivity).apply {
                    setImageResource(
                        if (isLocked) android.R.drawable.ic_lock_idle_lock
                        else android.R.drawable.ic_partial_secure
                    )
                    setColorFilter(
                        if (isLocked) Color.parseColor("#10B981") else Color.parseColor("#64748B")
                    )
                    layoutParams = LinearLayout.LayoutParams(52, 52)
                }

                row.addView(iconView)
                row.addView(nameText)
                row.addView(crashBtn)
                row.addView(netBtn)
                row.addView(statusLockIcon)

                row.setOnClickListener {
                    if (!isAccessibilityEnabled()) {
                        checkMandatoryPermissions()
                        showAnimatedNotification("Enable Accessibility Permission First!", "#EF4444", "#FFFFFF")
                        return@setOnClickListener
                    }
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

    // ================= PLUS (+) HAND-SIGN GESTURE SHORTCUT STUDIO =================
    private fun openGestureStudioDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 28, 40, 28)
        }

        val infoText = TextView(this).apply {
            text = "Draw a hand sign / symbol below to launch a shortcut app or bind a new gesture:"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val appSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                allLaunchableApps.map { it.name }
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        var lastDrawnSignature: String? = null
        val statusLabel = TextView(this).apply {
            text = "Draw your gesture on the pad:"
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
        }

        val gestureView = HandGestureView(this).apply {
            onGestureDrawn = { signature ->
                lastDrawnSignature = signature
                val existingPkg = storage.getGestureShortcuts()[signature]
                if (existingPkg != null) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(existingPkg)
                    if (launchIntent != null) {
                        showAnimatedNotification("Launching Gesture Shortcut!")
                        startActivity(launchIntent)
                    }
                } else {
                    statusLabel.text = "Gesture captured ($signature). Tap 'Save Shortcut' below!"
                }
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val clearBtn = Button(this).apply {
            text = "Clear"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 10
            }
            setOnClickListener {
                lastDrawnSignature = null
                gestureView.clearCanvas()
                statusLabel.text = "Draw your gesture on the pad:"
            }
        }

        val saveGestureBtn = Button(this).apply {
            text = "Save Shortcut"
            setTextColor(Color.parseColor("#06130E"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = 20f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 10
            }
            setOnClickListener {
                val sig = lastDrawnSignature
                if (sig.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "Draw a hand sign first!", Toast.LENGTH_SHORT).show()
                } else {
                    val chosenApp = allLaunchableApps[appSpinner.selectedItemPosition]
                    storage.saveGestureShortcut(sig, chosenApp.packageName)
                    gestureView.clearCanvas()
                    lastDrawnSignature = null
                    showAnimatedNotification("Gesture Linked to ${chosenApp.name}!", "#38BDF8", "#0B1120")
                }
            }
        }

        btnRow.addView(clearBtn)
        btnRow.addView(saveGestureBtn)

        container.addView(infoText)
        container.addView(appSpinner)
        container.addView(statusLabel)
        container.addView(gestureView)
        container.addView(btnRow)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("+ Hand-Sign Gesture Shortcuts")
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    // ================= SETTINGS DIALOG =================
    private fun openSettingsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(44, 32, 44, 32)
        }

        val accessBtn = Button(this).apply {
            text = if (isAccessibilityEnabled()) "✓ Accessibility Service Active"
            else "⚠ Enable Mandatory Accessibility Service"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(if (isAccessibilityEnabled()) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
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
            text = "\n2. Lock Method (PIN / Password / Pattern):"
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

        val hidePatternSwitch = Switch(this).apply {
            text = "Hide Pattern Path (Invisible lines while drawing)"
            isChecked = storage.isPatternHidden
            setPadding(0, 16, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked ->
                storage.isPatternHidden = checked
                showAnimatedNotification(
                    if (checked) "Pattern Path Hidden" else "Pattern Path Visible"
                )
            }
        }

        patternSetupBox.addView(patternPromptText)
        patternSetupBox.addView(patternSetupView)
        patternSetupBox.addView(hidePatternSwitch)

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

        val lockOnExitSwitch = Switch(this).apply {
            text = "\n3. Lock Immediately on App Exit"
            isChecked = storage.isLockOnExitEnabled
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked ->
                storage.isLockOnExitEnabled = checked
                showAnimatedNotification(
                    if (checked) "Lock on Exit: ON" else "Lock on Exit: OFF"
                )
            }
        }

        val bgBlurSwitch = Switch(this).apply {
            text = "\n4. Blur App Preview in Background / Recents"
            isChecked = storage.isBackgroundBlurEnabled
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked ->
                storage.isBackgroundBlurEnabled = checked
                checkMandatoryPermissions()
                showAnimatedNotification(
                    if (checked) "Background Blur Shield: ON" else "Background Blur Shield: OFF",
                    "#38BDF8",
                    "#0B1120"
                )
            }
        }

        val idleOptions = listOf(
            "Disabled (No idle timeout)" to 0,
            "After 15 seconds of inactivity" to 15,
            "After 30 seconds of inactivity" to 30,
            "After 1 minute of inactivity" to 60,
            "After 3 minutes of inactivity" to 180,
            "After 5 minutes of inactivity" to 300,
            "After 15 minutes of inactivity" to 900
        )

        val idleLabel = TextView(this).apply {
            text = "\n5. Inactivity / Idle Lock Timeout:"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val idleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                idleOptions.map { it.first }
            )
            val currentIdx = idleOptions.indexOfFirst { it.second == storage.idleTimeoutSeconds }
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
                    storage.idleTimeoutSeconds = idleOptions[pos].second
                    showAnimatedNotification("Idle Timer: ${idleOptions[pos].first}", "#38BDF8", "#0B1120")
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val bioSwitch = Switch(this).apply {
            text = "\n6. Fingerprint Biometric Unlock"
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

        container.addView(accessBtn)
        container.addView(authLabel)
        container.addView(authSpinner)
        container.addView(textCredentialBox)
        container.addView(patternSetupBox)
        container.addView(lockOnExitSwitch)
        container.addView(bgBlurSwitch)
        container.addView(idleLabel)
        container.addView(idleSpinner)
        container.addView(bioSwitch)
        scroll.addView(container)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("HS AppLock Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
