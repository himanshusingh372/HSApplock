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
import android.os.PowerManager
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
    private lateinit var masterPauseBtn: Button
    private lateinit var bioQuickBtn: Button
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
        val safeTopPadding = statusBarHeight + 52

        // ================= 1. HEADER =================
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
            text = "Security Vault & Firewall"
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
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(100, 100)
            contentDescription = "Settings"
            setOnClickListener { openSettingsDialog() }
        }

        header.addView(lockHeaderIcon)
        header.addView(titleBox)
        header.addView(settingsBtn)

        // ================= 2. MASTER TEMPORARY OFF BUTTON =================
        masterPauseBtn = Button(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(28, 14, 28, 4)
            }
            setOnClickListener {
                storage.isMasterPaused = !storage.isMasterPaused
                updateMasterButtonUI()
                showAnimatedNotification(
                    if (storage.isMasterPaused) "All App Locks Temporarily OFF"
                    else "Vault Protection Resumed!",
                    if (storage.isMasterPaused) "#F59E0B" else "#10B981",
                    "#0B1120"
                )
            }
        }

        // ================= 3. QUICK FINGERPRINT / BIOMETRIC BUTTON =================
        bioQuickBtn = Button(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(28, 6, 28, 4)
            }
            setOnClickListener {
                storage.isFingerprintEnabled = !storage.isFingerprintEnabled
                updateBioQuickButtonUI()
                showAnimatedNotification(
                    if (storage.isFingerprintEnabled) "Fingerprint / Face Unlock: ON"
                    else "Fingerprint / Face Unlock: OFF",
                    "#38BDF8",
                    "#0B1120"
                )
            }
        }

        // ================= 4. MANDATORY PERMISSIONS WARNING BANNER =================
        permissionWarningBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7F1D1D"))
                cornerRadius = 22f
                setStroke(2, Color.parseColor("#EF4444"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(28, 10, 28, 4)
            }
        }

        permissionWarningText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FECACA"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 14
            }
        }

        fixPermissionBtn = Button(this).apply {
            text = "Enable Now"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = 16f
            }
        }

        permissionWarningBox.addView(permissionWarningText)
        permissionWarningBox.addView(fixPermissionBtn)

        // ================= 5. TWO SEPARATE TABS =================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 12, 28, 8)
        }

        tabAllBtn = Button(this).apply {
            text = "All Apps (${allLaunchableApps.size})"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8
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
                leftMargin = 8
            }
            setOnClickListener {
                showingLockedOnly = true
                updateTabStyles()
                refreshAppList()
            }
        }

        tabBar.addView(tabAllBtn)
        tabBar.addView(tabLockedBtn)

        // ================= 6. SEARCH BAR =================
        val searchBox = EditText(this).apply {
            hint = "Search app (WIFI • CELL • CRASH • LOCK)..."
            setSingleLine(true)
            setPadding(32, 20, 32, 20)
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(28, 6, 28, 10)
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

        // ================= 7. APP LIST =================
        listView = ListView(this).apply {
            dividerHeight = 0
            setPadding(20, 0, 20, 14)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // ================= 8. FOOTER =================
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
            }
        }

        footerText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        footer.addView(footerText)

        mainColumn.addView(header)
        mainColumn.addView(masterPauseBtn)
        mainColumn.addView(bioQuickBtn)
        mainColumn.addView(permissionWarningBox)
        mainColumn.addView(tabBar)
        mainColumn.addView(searchBox)
        mainColumn.addView(listView)
        mainColumn.addView(footer)

        // ================= 9. ANIMATED POPUP NOTIFICATION =================
        popupBanner = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#06130E"))
            gravity = Gravity.CENTER
            setPadding(36, 22, 36, 22)
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
                setMargins(32, safeTopPadding + 14, 32, 0)
            }
        }

        rootFrame.addView(mainColumn)
        rootFrame.addView(popupBanner)
        setContentView(rootFrame)

        updateMasterButtonUI()
        updateBioQuickButtonUI()
        updateTabStyles()
        refreshAppList()
        updateFooter()
    }

    override fun onResume() {
        super.onResume()
        checkMandatoryPermissions()
        updateBioQuickButtonUI()
    }

    private fun updateMasterButtonUI() {
        if (storage.isMasterPaused) {
            masterPauseBtn.text = "⏸ ALL LOCKS TEMPORARILY OFF — TAP TO RESUME"
            masterPauseBtn.setTextColor(Color.parseColor("#0B1120"))
            masterPauseBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#F59E0B"))
                cornerRadius = 22f
            }
        } else {
            masterPauseBtn.text = "🛡 VAULT ACTIVE — TAP TO TEMPORARILY TURN OFF ALL LOCKS"
            masterPauseBtn.setTextColor(Color.parseColor("#10B981"))
            masterPauseBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F2922"))
                cornerRadius = 22f
                setStroke(2, Color.parseColor("#10B981"))
            }
        }
    }

    private fun updateBioQuickButtonUI() {
        if (storage.isFingerprintEnabled) {
            bioQuickBtn.text = "👆 FINGERPRINT / FACE UNLOCK: ON — TAP TO TURN OFF"
            bioQuickBtn.setTextColor(Color.parseColor("#0B1120"))
            bioQuickBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = 22f
            }
        } else {
            bioQuickBtn.text = "👆 FINGERPRINT / FACE UNLOCK: OFF — TAP TO TURN ON"
            bioQuickBtn.setTextColor(Color.parseColor("#94A3B8"))
            bioQuickBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 22f
                setStroke(2, Color.parseColor("#334155"))
            }
        }
    }

    // ================= MANDATORY PERMISSIONS CHECK =================
    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedComponent = ComponentName(this, AppMonitorService::class.java)
        return enabledServices.any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == expectedComponent
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkMandatoryPermissions() {
        if (!isAccessibilityEnabled()) {
            permissionWarningBox.visibility = View.VISIBLE
            permissionWarningText.text = "⚠ Enable Accessibility Service (On MI phones: App Info → Allow restricted settings & Autostart first)."
            fixPermissionBtn.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else if (!isIgnoringBatteryOptimizations()) {
            permissionWarningBox.visibility = View.VISIBLE
            permissionWarningText.text = "⚠ Set Battery to 'No restrictions' so MI/Android never stops HS AppLock."
            fixPermissionBtn.setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } else {
            permissionWarningBox.visibility = View.GONE
        }
    }

    // ================= FIREWALL VPN SYNC =================
    private fun syncNetworkFirewall() {
        val hasAnyBlocks = storage.getWifiBlockedApps().isNotEmpty() || storage.getMobileBlockedApps().isNotEmpty()
        if (!hasAnyBlocks) {
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
            startForegroundService(startIntent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PERMISSION && resultCode == RESULT_OK) {
            val startIntent = Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_UPDATE
            }
            startForegroundService(startIntent)
            showAnimatedNotification("Wi-Fi / Mobile Firewall Active")
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
            cornerRadius = 22f
        }
        val inactiveBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 22f
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
        val wifiBlocked = storage.getWifiBlockedApps().size
        val cellBlocked = storage.getMobileBlockedApps().size
        val activeModes = storage.getConfiguredAuthTypes().joinToString("+") { it.name }.ifEmpty { "NONE" }
        footerText.text = "🔒 $lockedCount  •  📶 Wi-Fi Off: $wifiBlocked  •  📡 Cell Off: $cellBlocked  •  🔑 $activeModes"
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
                val isWifiBlocked = storage.getWifiBlockedApps().contains(app.packageName)
                val isMobileBlocked = storage.getMobileBlockedApps().contains(app.packageName)
                val isFakeCrash = storage.getFakeCrashApps().contains(app.packageName)

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 16, 16, 16)
                    background = GradientDrawable().apply {
                        setColor(if (isLocked) Color.parseColor("#132A2E") else Color.parseColor("#1E293B"))
                        cornerRadius = 22f
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
                    setPadding(0, 5, 0, 5)
                }

                val iconView = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(72, 72).apply {
                        rightMargin = 14
                    }
                }

                val nameText = TextView(this@MainActivity).apply {
                    text = app.name
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#F8FAFC"))
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                // 1. Per-App Fake Crash Badge
                val crashBtn = TextView(this@MainActivity).apply {
                    text = "CRASH"
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(10, 10, 10, 10)
                    setTextColor(if (isFakeCrash) Color.WHITE else Color.parseColor("#64748B"))
                    background = GradientDrawable().apply {
                        setColor(if (isFakeCrash) Color.parseColor("#DC2626") else Color.parseColor("#0F172A"))
                        cornerRadius = 12f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 8 }
                    setOnClickListener {
                        val updated = storage.getFakeCrashApps().toMutableSet()
                        if (updated.contains(app.packageName)) {
                            updated.remove(app.packageName)
                            storage.setFakeCrashApps(updated)
                            showAnimatedNotification("${app.name}: Fake Crash OFF", "#64748B", "#F8FAFC")
                        } else {
                            updated.add(app.packageName)
                            storage.setFakeCrashApps(updated)
                            val locked = storage.getLockedApps().toMutableSet().apply { add(app.packageName) }
                            storage.setLockedApps(locked)
                            showAnimatedNotification("${app.name}: Fake Crash ON", "#EF4444", "#FFFFFF")
                        }
                        updateFooter()
                        refreshAppList()
                    }
                }

                // 2. Per-App WI-FI On/Off Badge
                val wifiBtn = TextView(this@MainActivity).apply {
                    text = if (isWifiBlocked) "WIFI✕" else "WIFI"
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(10, 10, 10, 10)
                    setTextColor(if (isWifiBlocked) Color.WHITE else Color.parseColor("#38BDF8"))
                    background = GradientDrawable().apply {
                        setColor(if (isWifiBlocked) Color.parseColor("#D97706") else Color.parseColor("#0F172A"))
                        cornerRadius = 12f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 8 }
                    setOnClickListener {
                        val updated = storage.getWifiBlockedApps().toMutableSet()
                        if (updated.contains(app.packageName)) {
                            updated.remove(app.packageName)
                            storage.setWifiBlockedApps(updated)
                            syncNetworkFirewall()
                            showAnimatedNotification("${app.name}: Wi-Fi Enabled", "#38BDF8", "#0B1120")
                        } else {
                            updated.add(app.packageName)
                            storage.setWifiBlockedApps(updated)
                            syncNetworkFirewall()
                            showAnimatedNotification("${app.name}: Wi-Fi Blocked", "#F59E0B", "#0B1120")
                        }
                        updateFooter()
                        refreshAppList()
                    }
                }

                // 3. Per-App MOBILE DATA On/Off Badge
                val cellBtn = TextView(this@MainActivity).apply {
                    text = if (isMobileBlocked) "CELL✕" else "CELL"
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(10, 10, 10, 10)
                    setTextColor(if (isMobileBlocked) Color.WHITE else Color.parseColor("#34D399"))
                    background = GradientDrawable().apply {
                        setColor(if (isMobileBlocked) Color.parseColor("#B45309") else Color.parseColor("#0F172A"))
                        cornerRadius = 12f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 10 }
                    setOnClickListener {
                        val updated = storage.getMobileBlockedApps().toMutableSet()
                        if (updated.contains(app.packageName)) {
                            updated.remove(app.packageName)
                            storage.setMobileBlockedApps(updated)
                            syncNetworkFirewall()
                            showAnimatedNotification("${app.name}: Mobile Data Enabled", "#34D399", "#0B1120")
                        } else {
                            updated.add(app.packageName)
                            storage.setMobileBlockedApps(updated)
                            syncNetworkFirewall()
                            showAnimatedNotification("${app.name}: Mobile Data Blocked", "#F59E0B", "#0B1120")
                        }
                        updateFooter()
                        refreshAppList()
                    }
                }

                // 4. Per-App Lock Icon
                val statusLockIcon = ImageView(this@MainActivity).apply {
                    setImageResource(
                        if (isLocked) android.R.drawable.ic_lock_idle_lock
                        else android.R.drawable.ic_partial_secure
                    )
                    setColorFilter(
                        if (isLocked) Color.parseColor("#10B981") else Color.parseColor("#64748B")
                    )
                    layoutParams = LinearLayout.LayoutParams(46, 46)
                }

                row.addView(iconView)
                row.addView(nameText)
                row.addView(crashBtn)
                row.addView(wifiBtn)
                row.addView(cellBtn)
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

    // ================= SETTINGS DIALOG =================
    private fun openSettingsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 28, 40, 28)
        }

        val accessBtn = Button(this).apply {
            text = if (isAccessibilityEnabled()) "✓ Accessibility Service Active"
            else "⚠ Enable Mandatory Accessibility Service"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(if (isAccessibilityEnabled()) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
                cornerRadius = 20f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val miAppInfoBtn = Button(this).apply {
            text = "Open App Info (MIUI Restricted Settings / Autostart)"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 18f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        // Section 1: PIN Lock
        val pinHeader = TextView(this).apply {
            text = "\n1. PIN Lock (${if (storage.hasCredential(LockStorage.AuthType.PIN)) "ACTIVE" else "Not Set"}):"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val pinInput = EditText(this).apply {
            hint = "Enter 4+ digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val savePinBtn = Button(this).apply {
            text = "Save PIN"
            setTextColor(Color.parseColor("#06130E"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 18f }
            setOnClickListener {
                val v = pinInput.text.toString()
                if (v.length >= 4) {
                    storage.setCredentialForType(LockStorage.AuthType.PIN, v)
                    pinInput.text.clear()
                    pinHeader.text = "\n1. PIN Lock (ACTIVE):"
                    updateFooter()
                    showAnimatedNotification("PIN Saved!")
                } else Toast.makeText(this@MainActivity, "Min 4 digits", Toast.LENGTH_SHORT).show()
            }
        }

        // Section 2: Password Lock
        val passHeader = TextView(this).apply {
            text = "\n2. Password Lock (${if (storage.hasCredential(LockStorage.AuthType.PASSWORD)) "ACTIVE" else "Not Set"}):"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val passInput = EditText(this).apply {
            hint = "Enter 4+ character Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val savePassBtn = Button(this).apply {
            text = "Save Password"
            setTextColor(Color.parseColor("#06130E"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#10B981")); cornerRadius = 18f }
            setOnClickListener {
                val v = passInput.text.toString()
                if (v.length >= 4) {
                    storage.setCredentialForType(LockStorage.AuthType.PASSWORD, v)
                    passInput.text.clear()
                    passHeader.text = "\n2. Password Lock (ACTIVE):"
                    updateFooter()
                    showAnimatedNotification("Password Saved!")
                } else Toast.makeText(this@MainActivity, "Min 4 characters", Toast.LENGTH_SHORT).show()
            }
        }

        // Section 3: Compact 3x3 Pattern Lock
        val patternHeader = TextView(this).apply {
            text = "\n3. Pattern Lock (${if (storage.hasCredential(LockStorage.AuthType.PATTERN)) "ACTIVE" else "Not Set"}):"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        var firstPatternAttempt: String? = null
        val patternPromptText = TextView(this).apply {
            text = "Draw pattern (connect at least 4 dots):"
            gravity = Gravity.CENTER
        }

        val patternSetupView = PatternLockView(this).apply {
            compactMode = true
            onPatternComplete = { drawn ->
                if (drawn.length < 4) {
                    Toast.makeText(this@MainActivity, "Connect at least 4 dots", Toast.LENGTH_SHORT).show()
                } else if (firstPatternAttempt == null) {
                    firstPatternAttempt = drawn
                    patternPromptText.text = "Draw same pattern again to confirm:"
                } else if (firstPatternAttempt == drawn) {
                    storage.setCredentialForType(LockStorage.AuthType.PATTERN, drawn)
                    firstPatternAttempt = null
                    patternHeader.text = "\n3. Pattern Lock (ACTIVE):"
                    patternPromptText.text = "Pattern saved! Draw again to update:"
                    updateFooter()
                    showAnimatedNotification("Pattern Saved!")
                } else {
                    firstPatternAttempt = null
                    patternPromptText.text = "Didn't match. Draw pattern again:"
                    Toast.makeText(this@MainActivity, "Patterns did not match", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val hidePatternSwitch = Switch(this).apply {
            text = "Hide Pattern Path"
            isChecked = storage.isPatternHidden
            setOnCheckedChangeListener { _, checked ->
                storage.isPatternHidden = checked
                showAnimatedNotification(if (checked) "Pattern Path Hidden" else "Pattern Path Visible")
            }
        }

        val lockOnExitSwitch = Switch(this).apply {
            text = "\nLock Immediately on App Exit"
            isChecked = storage.isLockOnExitEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isLockOnExitEnabled = checked
                showAnimatedNotification(if (checked) "Lock on Exit: ON" else "Lock on Exit: OFF")
            }
        }

        val idleOptions = listOf(
            "Disabled (No idle timeout)" to 0,
            "After 15 seconds" to 15,
            "After 30 seconds" to 30,
            "After 1 minute" to 60,
            "After 5 minutes" to 300
        )

        val idleLabel = TextView(this).apply {
            text = "\nInactivity / Idle Lock Timeout:"
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val idleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, idleOptions.map { it.first })
            val idx = idleOptions.indexOfFirst { it.second == storage.idleTimeoutSeconds }
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var init = true
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (init) { init = false; return }
                    storage.idleTimeoutSeconds = idleOptions[pos].second
                    showAnimatedNotification("Idle Timeout: ${idleOptions[pos].first}", "#38BDF8", "#0B1120")
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val bioSwitch = Switch(this).apply {
            text = "\nFace Unlock / Fingerprint / System Lock"
            isChecked = storage.isFingerprintEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isFingerprintEnabled = checked
                updateBioQuickButtonUI()
                showAnimatedNotification(if (checked) "Biometric & Device Lock Enabled" else "Biometric Disabled")
            }
        }

        container.addView(accessBtn)
        container.addView(miAppInfoBtn)
        container.addView(pinHeader)
        container.addView(pinInput)
        container.addView(savePinBtn)
        container.addView(passHeader)
        container.addView(passInput)
        container.addView(savePassBtn)
        container.addView(patternHeader)
        container.addView(patternPromptText)
        container.addView(patternSetupView)
        container.addView(hidePatternSwitch)
        container.addView(lockOnExitSwitch)
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
