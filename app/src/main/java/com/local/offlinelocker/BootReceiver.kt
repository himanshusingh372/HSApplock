package com.local.offlinelocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val storage = LockStorage(context)
            val hasNetBlocks = storage.getWifiBlockedApps().isNotEmpty() || storage.getMobileBlockedApps().isNotEmpty()
            if (hasNetBlocks && VpnService.prepare(context) == null) {
                val vpnIntent = Intent(context, AppFirewallVpnService::class.java).apply {
                    this.action = AppFirewallVpnService.ACTION_UPDATE
                }
                context.startForegroundService(vpnIntent)
            }
        }
    }
}
