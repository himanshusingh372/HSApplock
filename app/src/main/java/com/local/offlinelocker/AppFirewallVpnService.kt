package com.local.offlinelocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor

class AppFirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val ACTION_UPDATE = "com.local.offlinelocker.UPDATE_FIREWALL"
        const val ACTION_STOP = "com.local.offlinelocker.STOP_FIREWALL"
        private const val CHANNEL_ID = "hs_applock_guard_channel"
    }

    override fun onCreate() {
        super.onCreate()
        startPersistentNotification()
        registerNetworkListener()
    }

    private fun startPersistentNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HS AppLock Background Guard",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HS AppLock Active")
            .setContentText("Wi-Fi & Mobile Data Firewall Guard running")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun registerNetworkListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                evaluateActiveNetworkAndApplyRules()
            }

            override fun onLost(network: Network) {
                evaluateActiveNetworkAndApplyRules()
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFirewall()
            return START_NOT_STICKY
        }
        evaluateActiveNetworkAndApplyRules()
        return START_STICKY
    }

    private fun evaluateActiveNetworkAndApplyRules() {
        val storage = LockStorage(this)
        val wifiBlocked = storage.getWifiBlockedApps()
        val mobileBlocked = storage.getMobileBlockedApps()

        if (wifiBlocked.isEmpty() && mobileBlocked.isEmpty()) {
            stopFirewall()
            return
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNet)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMobile = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val packagesToBlockNow = when {
            isWifi -> wifiBlocked
            isMobile -> mobileBlocked
            else -> wifiBlocked + mobileBlocked
        }

        applyVpnTunnel(packagesToBlockNow)
    }

    private fun applyVpnTunnel(blockedPackages: Set<String>) {
        try {
            vpnInterface?.close()
            vpnInterface = null

            if (blockedPackages.isEmpty()) return

            val builder = Builder()
                .setSession("HS AppLock NetGuard")
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("::", 0)

            for (pkg in blockedPackages) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: Exception) {
                }
            }

            vpnInterface = builder.establish()
        } catch (_: Exception) {
        }
    }

    private fun stopFirewall() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        stopFirewall()
        super.onDestroy()
    }
}
