package com.example.spotifysync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("GhostSyncPrefs", Context.MODE_PRIVATE)

        // Se l'utente ha disattivato l'avvio automatico, non fare nulla
        if (!prefs.getBoolean("PREF_AUTOSTART", true)) return

        val serviceIntent = Intent(context, SpotifyWorkaroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }
}