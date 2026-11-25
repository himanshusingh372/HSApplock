package com.local.offlinelocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import kotlin.math.abs

class MainActivity : FragmentActivity() {

    private lateinit var storage: LockStorage
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var swipeDetector: GestureDetector

    private lateinit var topLockBtn: ImageButton
    private lateinit var tabSettingsText: TextView
    private lateinit var tabLockedText: TextView
    private lateinit var tabNetText: TextView
    private lateinit var tabSettingsIndicator: View
    private lateinit var tabLockedIndicator: View
    private lateinit var tabNetIndicator: View

    private lateinit var permissionBanner: LinearLayout
    private lateinit var permissionBannerText: TextView
    private lateinit var permissionFixBtn: Button

    private lateinit var pageSettingsView: ScrollView
    private lateinit var pageAppsContainer: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var listView: ListView
    private lateinit var emptyStateText: TextView
    private lateinit var fabAddBtn: ImageButton
    private lateinit var popupBanner: TextView

    private lateinit var selfLockSwitchRef: Switch
    private lateinit var instantLockSwitchRef: Switch
    private lateinit var settingsLockSwitchRef: Switch
    private lateinit var appInfoLockSwitchRef: Switch
    private lateinit var bioSwitchRef: Switch
    private lateinit var masterPauseSwitchRef: Switch
    private lateinit var antiUninstallSwitchRef: Switch

    private enum class Page { SETTINGS, LOCKED_APP, NET_GUARDED, ALL_APPS }
    private var currentPage = Page.LOCKED_APP
    private var searchQuery = ""
    private var isSelfUnlocked = false
    private var isPromptingSelfLock = false

    private var allLaunchableApps: List<AppEntry> = emptyList()
    private var displayedApps: List<AppEntry> = emptyList()
    private val bannerHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQ_VPN_PERMISSION = 101
        private const val REQ_DEVICE_ADMIN = 102
        private const val REQ_DISABLE_UNINSTALL_AUTH = 103
        private const val REQ_SELF_LOCK_AUTH = 104

        private val COLOR_PRIMARY_BLUE = Color.parseColor("#0277BD")
        private val COLOR_DARK_BLUE = Color.parseColor("#01579B")
        private val COLOR_ACCENT_YELLOW = Color.parseColor("#FDD835")
        private val COLOR_BG_LIGHT = Color.parseColor("#F4F6F9")
        private val COLOR_TEXT_DARK = Color.parseColor("#0F172A")
        private val COLOR_TEXT_MUTED = Color.parseColor("#475569")
    }

    data class AppEntry(
        val name: String,
        val packageName: String,
        val icon: Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = COLOR_DARK_BLUE

        storage = LockStorage(this)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, VaultDeviceAdminReceiver::class.java)

        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || currentPage == Page.ALL_APPS) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > 120 && abs(velocityX) > 120) {
                    if (diffX < 0) {
                        when (currentPage) {
                            Page.SETTINGS -> switchToPage(Page.LOCKED_APP)
                            Page.LOCKED_APP -> switchToPage(Page.NET_GUARDED)
                            else -> {}
                        }
                    } else {
                        when (currentPage) {
                            Page.NET_GUARDED -> switchToPage(Page.LOCKED_APP)
                            Page.LOCKED_APP -> switchToPage(Page.SETTINGS)
                            else -> {}
                        }
                    }
                    return true
                }
                return false
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage == Page.ALL_APPS) {
                    switchToPage(Page.LOCKED_APP)
                } else {
                    isSelfUnlocked = false
                    finish()
                }
            }
        })

        allLaunchableApps = packageManager.getInstalledApplications(0)
            .filter {
                it.packageName != packageName &&
                    (packageManager.getLaunchIntentForPackage(it.packageName) != null ||
                        it.packageName == "com.android.settings")
            }
            .map {
                AppEntry(
                    name = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    icon = packageManager.getApplicationIcon(it)
                )
            }
            .sortedBy { it.name.lowercase() }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BG_LIGHT)
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else 72

        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_PRIMARY_BLUE)
            setPadding(40, statusBarHeight + 28, 36, 20)
        }

        val shieldIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_secure)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(68, 68).apply {
                rightMargin = 24
            }
        }

        val appTitle = TextView(this).apply {
            text = "HS AppLock v$appVersion"
            textSize = 20.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        topLockBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_lock_idle_lock)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#26FFFFFF"))
            }
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(96, 96)
            contentDescription = "Anti-Uninstall Restriction"
            setOnClickListener { toggleAntiUninstallRestriction() }
        }

        appBar.addView(shieldIcon)
        appBar.addView(appTitle)
        appBar.addView(topLockBtn)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_PRIMARY_BLUE)
            elevation = 8.0f
        }

        fun buildTab(label: String, targetPage: Page): Pair<TextView, View> {
            val tabWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                setOnClickListener { switchToPage(targetPage) }
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 12.0f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(6, 22, 6, 20)
                setTextColor(Color.WHITE)
            }
            val indicator = View(this).apply {
                setBackgroundColor(COLOR_ACCENT_YELLOW)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    9
                )
            }
            tabWrapper.addView(tv)
            tabWrapper.addView(indicator)
            tabBar.addView(tabWrapper)
            return Pair(tv, indicator)
        }

        val (tSet, iSet) = buildTab("SETTINGS", Page.SETTINGS)
        val (tLock, iLock) = buildTab("LOCKED (0)", Page.LOCKED_APP)
        val (tNet, iNet) = buildTab("NET GUARD (0)", Page.NET_GUARDED)

        tabSettingsText = tSet; tabSettingsIndicator = iSet
        tabLockedText = tLock; tabLockedIndicator = iLock
        tabNetText = tNet; tabNetIndicator = iNet

        permissionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#B91C1C"))
            visibility = View.GONE
        }

        permissionBannerText = TextView(this).apply {
            textSize = 12.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                rightMargin = 12
            }
        }

        permissionFixBtn = Button(this).apply {
            text = "FIX"
            textSize = 11.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B91C1C"))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 14.0f
            }
        }

        permissionBanner.addView(permissionBannerText)
        permissionBanner.addView(permissionFixBtn)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        pageAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        searchBox = EditText(this).apply {
            hint = "Search apps..."
            setSingleLine(true)
            setPadding(32, 22, 32, 22)
            setTextColor(COLOR_TEXT_DARK)
            setHintTextColor(COLOR_TEXT_MUTED)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24.0f
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(28, 20, 28, 12)
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                    refreshAppList(preserveScroll = false)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        emptyStateText = TextView(this).apply {
            textSize = 14.0f
            setTextColor(COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            visibility = View.GONE
        }

        listView = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#E2E8F0"))
            dividerHeight = 1
            setPadding(0, 4, 0, 180)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        pageAppsContainer.addView(searchBox)
        pageAppsContainer.addView(emptyStateText)
        pageAppsContainer.addView(listView)

        pageSettingsView = buildSettingsPage()

        contentFrame.addView(pageAppsContainer)
        contentFrame.addView(pageSettingsView)

        mainColumn.addView(appBar)
        mainColumn.addView(tabBar)
        mainColumn.addView(permissionBanner)
        mainColumn.addView(contentFrame)

        fabAddBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_PRIMARY_BLUE)
            }
            elevation = 16.0f
            setPadding(36, 36, 36, 36)
            contentDescription = "All Apps"
            layoutParams = FrameLayout.LayoutParams(148, 148, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, 48, 56)
            }
            setOnClickListener {
                if (currentPage == Page.ALL_APPS) {
                    switchToPage(Page.LOCKED_APP)
                } else {
                    switchToPage(Page.ALL_APPS)
                }
            }
        }

        popupBanner = TextView(this).apply {
            textSize = 13.0f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(36, 22, 36, 22)
            visibility = View.GONE
            elevation = 20.0f
            background = GradientDrawable().apply {
                setColor(COLOR_DARK_BLUE)
                cornerRadius = 28.0f
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(32, statusBarHeight + 24, 32, 0)
            }
        }

        rootFrame.addView(mainColumn)
        rootFrame.addView(fabAddBtn)
        rootFrame.addView(popupBanner)
        setContentView(rootFrame)

        syncTopLockIconUI()
        updateTabCounts()
        switchToPage(Page.LOCKED_APP)
        enforceMasterLockIfNeeded()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        enforceMasterLockIfNeeded()
        checkMandatoryPermissions()
        syncTopLockIconUI()
        updateTabCounts()
        if (::antiUninstallSwitchRef.isInitialized) {
            antiUninstallSwitchRef.isChecked = isAntiUninstallActive()
        }
        if (::bioSwitchRef.isInitialized) {
            bioSwitchRef.isChecked = storage.isFingerprintEnabled
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            isSelfUnlocked = false
        }
    }

    private fun enforceMasterLockIfNeeded() {
        val hasAnyCredential = storage.hasCredential(LockStorage.AuthType.PIN) ||
            storage.hasCredential(LockStorage.AuthType.PASSWORD) ||
            storage.hasCredential(LockStorage.AuthType.PATTERN)

        if (storage.isSelfLockEnabled && hasAnyCredential && !isSelfUnlocked && !isPromptingSelfLock) {
            isPromptingSelfLock = true
            val authIntent = Intent(this, LockActivity::class.java).apply {
                putExtra("TARGET_PACKAGE", "SELF_APP_LOCK")
            }
            startActivityForResult(authIntent, REQ_SELF_LOCK_AUTH)
        } else if (!storage.isSelfLockEnabled || !hasAnyCredential) {
            isSelfUnlocked = true
        }
    }

    private fun updateTabCounts() {
        val lockedCount = storage.getLockedApps().size
        val netAndCrashCount = (
            storage.getWifiBlockedApps() +
                storage.getMobileBlockedApps() +
                storage.getFakeCrashApps()
            ).size
        tabLockedText.text = "LOCKED ($lockedCount)"
        tabNetText.text = "NET GUARD ($netAndCrashCount)"
    }

    private fun isAntiUninstallActive(): Boolean {
        return storage.isAntiUninstallEnabled && dpm.isAdminActive(adminComponent)
    }

    private fun syncTopLockIconUI() {
        val active = isAntiUninstallActive()
        topLockBtn.setColorFilter(if (active) COLOR_ACCENT_YELLOW else Color.WHITE)
        topLockBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.parseColor("#40FDD835") else Color.parseColor("#26FFFFFF"))
            if (active) setStroke(3, COLOR_ACCENT_YELLOW)
        }
    }

    private fun toggleAntiUninstallRestriction() {
        if (!dpm.isAdminActive(adminComponent)) {
            storage.isAntiUninstallEnabled = true
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Protects HS AppLock and asks for your password before any app can be uninstalled."
                )
            }
            startActivityForResult(intent, REQ_DEVICE_ADMIN)
        } else if (!storage.isAntiUninstallEnabled) {
            storage.isAntiUninstallEnabled = true
            syncTopLockIconUI()
            if (::antiUninstallSwitchRef.isInitialized) antiUninstallSwitchRef.isChecked = true
            showBanner("Uninstall Blocker: ON", "#0277BD")
        } else {
            if (::antiUninstallSwitchRef.isInitialized) antiUninstallSwitchRef.isChecked = true
            val verifyIntent = Intent(this, LockActivity::class.java).apply {
                putExtra("TARGET_PACKAGE", "DISABLE_ANTI_UNINSTALL")
            }
            startActivityForResult(verifyIntent, REQ_DISABLE_UNINSTALL_AUTH)
        }
    }

    private fun switchToPage(page: Page) {
        currentPage = page
        searchQuery = ""
        searchBox.text.clear()
        updateTabCounts()

        tabSettingsIndicator.visibility = if (page == Page.SETTINGS) View.VISIBLE else View.INVISIBLE
        tabLockedIndicator.visibility = if (page == Page.LOCKED_APP) View.VISIBLE else View.INVISIBLE
        tabNetIndicator.visibility = if (page == Page.NET_GUARDED) View.VISIBLE else View.INVISIBLE

        tabSettingsText.alpha = if (page == Page.SETTINGS) 1.0f else 0.72f
        tabLockedText.alpha = if (page == Page.LOCKED_APP) 1.0f else 0.72f
        tabNetText.alpha = if (page == Page.NET_GUARDED) 1.0f else 0.72f

        if (page == Page.SETTINGS) {
            pageSettingsView.visibility = View.VISIBLE
            pageAppsContainer.visibility = View.GONE
            fabAddBtn.visibility = View.GONE
        } else {
            pageSettingsView.visibility = View.GONE
            pageAppsContainer.visibility = View.VISIBLE
            fabAddBtn.visibility = View.VISIBLE
            fabAddBtn.rotation = if (page == Page.ALL_APPS) 45.0f else 0.0f
            searchBox.hint = when (page) {
                Page.ALL_APPS -> "Search all ${allLaunchableApps.size} installed apps..."
                Page.NET_GUARDED -> "Search net-guarded & crash apps..."
                else -> "Search locked apps..."
            }
            refreshAppList(preserveScroll = false)
        }
    }

    private fun refreshAppList(preserveScroll: Boolean = true) {
        val savedIndex = listView.firstVisiblePosition
        val savedTop = listView.getChildAt(0)?.top ?: 0

        val lockedSet = storage.getLockedApps()
        val wifiSet = storage.getWifiBlockedApps()
        val cellSet = storage.getMobileBlockedApps()
        val crashSet = storage.getFakeCrashApps()
        updateTabCounts()

        displayedApps = allLaunchableApps.filter { app ->
            val matchesPage = when (currentPage) {
                Page.LOCKED_APP -> lockedSet.contains(app.packageName)
                Page.NET_GUARDED -> wifiSet.contains(app.packageName) ||
                    cellSet.contains(app.packageName) ||
                    crashSet.contains(app.packageName)
                Page.ALL_APPS -> true
                Page.SETTINGS -> false
            }
            val matchesSearch = searchQuery.isEmpty() || app.name.lowercase().contains(searchQuery)
            matchesPage && matchesSearch
        }

        if (displayedApps.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            emptyStateText.text = when (currentPage) {
                Page.LOCKED_APP -> "No apps locked yet.\nTap '+' below to add apps."
                Page.NET_GUARDED -> "No apps have Wi-Fi, Mobile Data, or Fake Crash active."
                else -> "No matching apps found."
            }
        } else {
            emptyStateText.visibility = View.GONE
        }

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedApps.size
            override fun getItem(position: Int): Any = displayedApps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = displayedApps[position]
                val isLocked = lockedSet.contains(app.packageName)
                val isWifiBlocked = wifiSet.contains(app.packageName)
                val isCellBlocked = cellSet.contains(app.packageName)
                val isFakeCrash = crashSet.contains(app.packageName)

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.WHITE)
                    setPadding(28, 22, 24, 22)
                    layoutParams = AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT
                    )
                }

                val iconView = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(82, 82).apply {
                        rightMargin = 22
                    }
                }

                val nameText = TextView(this@MainActivity).apply {
                    text = app.name
                    textSize = 14.0f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT_DARK)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                val crashBadge = makeActionPill(
                    label = "CRASH",
                    active = isFakeCrash,
                    activeColor = Color.parseColor("#E53935"),
                    inactiveStroke = Color.parseColor("#EF9A9A"),
                    inactiveText = Color.parseColor("#E53935")
                ) {
                    val updated = storage.getFakeCrashApps().toMutableSet()
                    if (updated.contains(app.packageName)) {
                        updated.remove(app.packageName)
                        storage.setFakeCrashApps(updated)
                        showBanner("${app.name}: Fake Crash OFF", "#64748B")
                    } else {
                        updated.add(app.packageName)
                        storage.setFakeCrashApps(updated)
                        val l = storage.getLockedApps().toMutableSet().apply { add(app.packageName) }
                        storage.setLockedApps(l)
                        showBanner("${app.name}: Fake Crash ON", "#E53935")
                    }
                    refreshAppList(preserveScroll = true)
                }

                val wifiBadge = makeActionPill(
                    label = if (isWifiBlocked) "WIFI✕" else "WIFI",
                    active = isWifiBlocked,
                    activeColor = Color.parseColor("#F57C00"),
                    inactiveStroke = Color.parseColor("#90CAF9"),
                    inactiveText = Color.parseColor("#0277BD")
                ) {
                    val updated = storage.getWifiBlockedApps().toMutableSet()
                    if (updated.contains(app.packageName)) {
                        updated.remove(app.packageName)
                        storage.setWifiBlockedApps(updated)
                        syncNetworkFirewall()
                        showBanner("${app.name}: Wi-Fi Allowed", "#0277BD")
                    } else {
                        updated.add(app.packageName)
                        storage.setWifiBlockedApps(updated)
                        syncNetworkFirewall()
                        showBanner("${app.name}: Wi-Fi Blocked", "#F57C00")
                    }
                    refreshAppList(preserveScroll = true)
                }

                val netBadge = makeActionPill(
                    label = if (isCellBlocked) "NET✕" else "NET",
                    active = isCellBlocked,
                    activeColor = Color.parseColor("#6D28D9"),
                    inactiveStroke = Color.parseColor("#C4B5FD"),
                    inactiveText = Color.parseColor("#6D28D9")
                ) {
                    val updated = storage.getMobileBlockedApps().toMutableSet()
                    if (updated.contains(app.packageName)) {
                        updated.remove(app.packageName)
                        storage.setMobileBlockedApps(updated)
                        syncNetworkFirewall()
                        showBanner("${app.name}: Mobile Net Allowed", "#0277BD")
                    } else {
                        updated.add(app.packageName)
                        storage.setMobileBlockedApps(updated)
                        syncNetworkFirewall()
                        showBanner("${app.name}: Mobile Net Blocked", "#6D28D9")
                    }
                    refreshAppList(preserveScroll = true)
                }

                val lockIcon = ImageView(this@MainActivity).apply {
                    setImageResource(
                        if (isLocked) android.R.drawable.ic_lock_idle_lock
                        else android.R.drawable.ic_partial_secure
                    )
                    setColorFilter(if (isLocked) COLOR_PRIMARY_BLUE else Color.parseColor("#94A3B8"))
                    setPadding(10, 10, 4, 10)
                    layoutParams = LinearLayout.LayoutParams(62, 62)
                }

                row.addView(iconView)
                row.addView(nameText)
                row.addView(crashBadge)
                row.addView(wifiBadge)
                row.addView(netBadge)
                row.addView(lockIcon)

                row.setOnClickListener {
                    if (!isAccessibilityEnabled()) {
                        checkMandatoryPermissions()
                        showBanner("Enable Accessibility Service first!", "#B91C1C")
                        return@setOnClickListener
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(if (isLocked) "Unlock App?" else "Lock App?")
                        .setMessage(
                            if (isLocked) "Remove lock from ${app.name}?"
                            else "Add lock protection to ${app.name}?"
                        )
                        .setPositiveButton("Confirm") { _, _ ->
                            val updated = storage.getLockedApps().toMutableSet()
                            if (isLocked) {
                                updated.remove(app.packageName)
                                storage.setLockedApps(updated)
                                storage.clearAppUnlocked(app.packageName)
                                showBanner("${app.name}: Unlocked", "#64748B")
                            } else {
                                updated.add(app.packageName)
                                storage.setLockedApps(updated)
                                showBanner("${app.name}: Locked", "#0277BD")
                            }
                            refreshAppList(preserveScroll = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                return row
            }
        }

        if (preserveScroll) {
            listView.setSelectionFromTop(savedIndex, savedTop)
        }
    }

    private fun makeActionPill(
        label: String,
        active: Boolean,
        activeColor: Int,
        inactiveStroke: Int,
        inactiveText: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(14, 12, 14, 12)
            setTextColor(if (active) Color.WHITE else inactiveText)
            background = GradientDrawable().apply {
                setColor(if (active) activeColor else Color.WHITE)
                cornerRadius = 30.0f
                setStroke(2, if (active) activeColor else inactiveStroke)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = 10
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildSettingsPage(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 120)
        }

        fun makeCard(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 28, 32, 28)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 24.0f
                    setStroke(2, Color.parseColor("#E2E8F0"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
            }
        }

        fun makeSectionTitle(title: String): TextView {
            return TextView(this).apply {
                text = title
                textSize = 14.0f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_PRIMARY_BLUE)
                setPadding(0, 0, 0, 14)
            }
        }

        val controlCard = makeCard()
        controlCard.addView(makeSectionTitle("PROTECTION & LOCK MODES"))

        selfLockSwitchRef = Switch(this).apply {
            text = "Master Lock for HS AppLock Itself"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isSelfLockEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isSelfLockEnabled = checked
                isSelfUnlocked = !checked
                showBanner(if (checked) "HS AppLock Master Lock: ON" else "HS AppLock Master Lock: OFF")
            }
        }

        instantLockSwitchRef = Switch(this).apply {
            text = "\nInstant Lock (On Recents, Exit, or Background Close)"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isInstantLockEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isInstantLockEnabled = checked
                if (checked) storage.clearAllUnlockedApps()
                showBanner(if (checked) "Instant Lock: ON" else "Instant Lock: OFF")
            }
        }

        settingsLockSwitchRef = Switch(this).apply {
            text = "\nLock System Settings"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isSettingsLockEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isSettingsLockEnabled = checked
                showBanner(if (checked) "Settings Lock: ON" else "Settings Lock: OFF")
            }
        }

        appInfoLockSwitchRef = Switch(this).apply {
            text = "\nLock App Info Screens (All Apps)"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isAppInfoLockEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isAppInfoLockEnabled = checked
                showBanner(if (checked) "App Info Lock: ON" else "App Info Lock: OFF")
            }
        }

        masterPauseSwitchRef = Switch(this).apply {
            text = "\nTemporarily Pause All App Locks"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isMasterPaused
            setOnCheckedChangeListener { _, checked ->
                storage.isMasterPaused = checked
                storage.clearAllUnlockedApps()
                showBanner(
                    if (checked) "All Locks Temporarily OFF" else "Vault Protection Active",
                    if (checked) "#F57C00" else "#0277BD"
                )
            }
        }

        antiUninstallSwitchRef = Switch(this).apply {
            text = "\nUninstall Blocker (Protect All Apps & AppLock)"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = isAntiUninstallActive()
            setOnClickListener { toggleAntiUninstallRestriction() }
        }

        bioSwitchRef = Switch(this).apply {
            text = "\nFingerprint / Face Unlock / System Lock"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isFingerprintEnabled
            setOnCheckedChangeListener { _, checked ->
                storage.isFingerprintEnabled = checked
                showBanner(if (checked) "Fingerprint / Face Unlock: ON" else "Fingerprint / Face Unlock: OFF")
            }
        }

        controlCard.addView(selfLockSwitchRef)
        controlCard.addView(instantLockSwitchRef)
        controlCard.addView(settingsLockSwitchRef)
        controlCard.addView(appInfoLockSwitchRef)
        controlCard.addView(masterPauseSwitchRef)
        controlCard.addView(antiUninstallSwitchRef)
        controlCard.addView(bioSwitchRef)

        val authCard = makeCard()
        authCard.addView(makeSectionTitle("AUTHENTICATION CREDENTIALS"))

        val pinInput = EditText(this).apply {
            hint = "Set 4+ Digit PIN (${if (storage.hasCredential(LockStorage.AuthType.PIN)) "ACTIVE" else "Not Set"})"
            setTextColor(COLOR_TEXT_DARK)
            setHintTextColor(COLOR_TEXT_MUTED)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val savePinBtn = Button(this).apply {
            text = "Save PIN"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(COLOR_PRIMARY_BLUE); cornerRadius = 18.0f }
            setOnClickListener {
                val v = pinInput.text.toString()
                if (v.length >= 4) {
                    storage.setCredentialForType(LockStorage.AuthType.PIN, v)
                    pinInput.text.clear()
                    pinInput.hint = "Set 4+ Digit PIN (ACTIVE)"
                    showBanner("PIN Saved!")
                } else showBanner("Enter at least 4 digits", "#B91C1C")
            }
        }

        val passInput = EditText(this).apply {
            hint = "Set Password (${if (storage.hasCredential(LockStorage.AuthType.PASSWORD)) "ACTIVE" else "Not Set"})"
            setTextColor(COLOR_TEXT_DARK)
            setHintTextColor(COLOR_TEXT_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }
        val savePassBtn = Button(this).apply {
            text = "Save Password"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(COLOR_PRIMARY_BLUE); cornerRadius = 18.0f }
            setOnClickListener {
                val v = passInput.text.toString()
                if (v.length >= 4) {
                    storage.setCredentialForType(LockStorage.AuthType.PASSWORD, v)
                    passInput.text.clear()
                    passInput.hint = "Set Password (ACTIVE)"
                    showBanner("Password Saved!")
                } else showBanner("Enter at least 4 characters", "#B91C1C")
            }
        }

        var firstPattern: String? = null
        val patternPrompt = TextView(this).apply {
            text = "\nDraw 3x3 Pattern (${if (storage.hasCredential(LockStorage.AuthType.PATTERN)) "ACTIVE" else "Not Set"}):"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_DARK)
            gravity = Gravity.CENTER
        }

        val patternView = PatternLockView(this).apply {
            compactMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 12
                bottomMargin = 12
            }
            onPatternComplete = { drawn ->
                if (drawn.length < 4) {
                    showBanner("Connect at least 4 dots", "#B91C1C")
                } else if (firstPattern == null) {
                    firstPattern = drawn
                    patternPrompt.text = "\nDraw the same pattern again to confirm:"
                } else if (firstPattern == drawn) {
                    storage.setCredentialForType(LockStorage.AuthType.PATTERN, drawn)
                    firstPattern = null
                    patternPrompt.text = "\nPattern ACTIVE! Draw again to update:"
                    showBanner("Pattern Saved!")
                } else {
                    firstPattern = null
                    patternPrompt.text = "\nPatterns didn't match. Draw again:"
                    showBanner("Patterns did not match", "#B91C1C")
                }
            }
        }

        val hidePatternSwitch = Switch(this).apply {
            text = "Hide Pattern Line While Drawing"
            textSize = 14.0f
            setTextColor(COLOR_TEXT_DARK)
            isChecked = storage.isPatternHidden
            setOnCheckedChangeListener { _, checked ->
                storage.isPatternHidden = checked
                showBanner(if (checked) "Pattern Path Hidden" else "Pattern Path Visible")
            }
        }

        authCard.addView(pinInput)
        authCard.addView(savePinBtn)
        authCard.addView(passInput)
        authCard.addView(savePassBtn)
        authCard.addView(patternPrompt)
        authCard.addView(patternView)
        authCard.addView(hidePatternSwitch)

        val sysCard = makeCard()
        sysCard.addView(makeSectionTitle("TIMEOUT & SYSTEM PERMISSIONS"))

        val idleOptions = listOf(
            "Idle Timeout: Disabled" to 0,
            "Idle Timeout: 15 seconds" to 15,
            "Idle Timeout: 30 seconds" to 30,
            "Idle Timeout: 1 minute" to 60,
            "Idle Timeout: 5 minutes" to 300
        )

        val idleAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            idleOptions.map { it.first }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#090D16"))
                tv.setBackgroundColor(Color.parseColor("#E2E8F0"))
                tv.typeface = Typeface.DEFAULT_BOLD
                tv.textSize = 14.0f
                tv.setPadding(24, 24, 24, 24)
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#090D16"))
                tv.setBackgroundColor(Color.parseColor("#F1F5F9"))
                tv.typeface = Typeface.DEFAULT_BOLD
                tv.textSize = 14.0f
                tv.setPadding(28, 28, 28, 28)
                return tv
            }
        }

        val idleSpinner = Spinner(this).apply {
            adapter = idleAdapter
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E2E8F0"))
                cornerRadius = 16.0f
                setStroke(2, Color.parseColor("#475569"))
            }
            val idx = idleOptions.indexOfFirst { it.second == storage.idleTimeoutSeconds }
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var init = true
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (init) { init = false; return }
                    storage.idleTimeoutSeconds = idleOptions[pos].second
                    showBanner(idleOptions[pos].first)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val accessBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(COLOR_PRIMARY_BLUE); cornerRadius = 18.0f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val miInfoBtn = Button(this).apply {
            text = "App Info (MIUI Restricted Settings / Autostart)"
            setTextColor(COLOR_PRIMARY_BLUE)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18.0f
                setStroke(2, COLOR_PRIMARY_BLUE)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        sysCard.addView(idleSpinner)
        sysCard.addView(accessBtn)
        sysCard.addView(miInfoBtn)

        container.addView(controlCard)
        container.addView(authCard)
        container.addView(sysCard)
        scroll.addView(container)
        return scroll
    }

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
            permissionBanner.visibility = View.VISIBLE
            permissionBannerText.text = "Accessibility Service is OFF. On MI phones: App Info → Allow restricted settings first."
            permissionFixBtn.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else if (!isIgnoringBatteryOptimizations()) {
            permissionBanner.visibility = View.VISIBLE
            permissionBannerText.text = "Set Battery to 'No restrictions' so HS AppLock stays active."
            permissionFixBtn.setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } else {
            permissionBanner.visibility = View.GONE
        }
    }

    private fun syncNetworkFirewall() {
        val hasAnyBlocks = storage.getWifiBlockedApps().isNotEmpty() || storage.getMobileBlockedApps().isNotEmpty()
        if (!hasAnyBlocks) {
            startService(Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_STOP
            })
            return
        }

        val vpnPrepare = VpnService.prepare(this)
        if (vpnPrepare != null) {
            startActivityForResult(vpnPrepare, REQ_VPN_PERMISSION)
        } else {
            startForegroundService(Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_UPDATE
            })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SELF_LOCK_AUTH) {
            isPromptingSelfLock = false
            if (resultCode == RESULT_OK) {
                isSelfUnlocked = true
            } else {
                isSelfUnlocked = false
                finish()
            }
        } else if (requestCode == REQ_VPN_PERMISSION && resultCode == RESULT_OK) {
            startForegroundService(Intent(this, AppFirewallVpnService::class.java).apply {
                action = AppFirewallVpnService.ACTION_UPDATE
            })
            showBanner("Net Guard Firewall Active")
        } else if (requestCode == REQ_DEVICE_ADMIN) {
            syncTopLockIconUI()
            if (::antiUninstallSwitchRef.isInitialized) {
                antiUninstallSwitchRef.isChecked = isAntiUninstallActive()
            }
            if (isAntiUninstallActive()) {
                showBanner("Uninstall Blocker Enabled!")
            }
        } else if (requestCode == REQ_DISABLE_UNINSTALL_AUTH && resultCode == RESULT_OK) {
            storage.isAntiUninstallEnabled = false
            dpm.removeActiveAdmin(adminComponent)
            syncTopLockIconUI()
            if (::antiUninstallSwitchRef.isInitialized) {
                antiUninstallSwitchRef.isChecked = false
            }
            showBanner("Uninstall Blocker Disabled", "#64748B")
        }
    }

    private fun showBanner(message: String, bgHex: String = "#01579B") {
        bannerHandler.removeCallbacksAndMessages(null)
        (popupBanner.background as? GradientDrawable)?.setColor(Color.parseColor(bgHex))
        popupBanner.text = message
        popupBanner.visibility = View.VISIBLE
        popupBanner.translationY = -140.0f
        popupBanner.alpha = 0.0f

        popupBanner.animate()
            .translationY(0.0f)
            .alpha(1.0f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        bannerHandler.postDelayed({
            popupBanner.animate()
                .translationY(-140.0f)
                .alpha(0.0f)
                .setDuration(220)
                .withEndAction { popupBanner.visibility = View.GONE }
                .start()
        }, 1900)
    }
}
