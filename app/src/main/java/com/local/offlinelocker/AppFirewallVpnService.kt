package com.local.offlinelocker

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class AppFirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_UPDATE = "com.local.offlinelocker.UPDATE_FIREWALL"
        const val ACTION_STOP = "com.local.offlinelocker.STOP_FIREWALL"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFirewall()
            return START_NOT_STICKY
        }

        val storage = LockStorage(this)
        val blockedApps = storage.getBlockedNetApps()

        if (blockedApps.isEmpty()) {
            stopFirewall()
            return START_NOT_STICKY
        }

        applyFirewallRules(blockedApps)
        return START_STICKY
    }

    private fun applyFirewallRules(blockedPackages: Set<String>) {
        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("HS AppLock NetGuard")
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("::", 0)

            // Route ONLY the selected blocked apps into this local sinkhole interface
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
        stopSelf()
    }

    override fun onDestroy() {
        stopFirewall()
        super.onDestroy()
    }
}
