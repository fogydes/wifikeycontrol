package com.wifikeycontrol.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NetworkDiscoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                Log.d("NetworkDiscoveryReceiver", "Network connectivity changed")
            }
            "android.net.wifi.WIFI_STATE_CHANGED" -> {
                Log.d("NetworkDiscoveryReceiver", "WiFi state changed")
            }
        }
    }
}