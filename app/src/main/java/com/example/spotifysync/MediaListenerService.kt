package com.example.spotifysync

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private var lastPlayedTrack = ""
    private val handler = Handler(Looper.getMainLooper())
    // FIX: Debounce per evitare sync multipli su cambio canzone rapido
    private var debounceRunnable: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val activeNotifs = activeNotifications
            if (activeNotifs != null) {
                for (sbn in activeNotifs) {
                    processNotification(sbn)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    // FIX: Resetta il lastPlayedTrack quando la notifica viene rimossa (app pausata/fermata)
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val prefs = getSharedPreferences("GhostSyncPrefs", Context.MODE_PRIVATE)
        val allowedApps = prefs.getStringSet("ALLOWED_APPS", setOf()) ?: setOf()

        if (allowedApps.contains(packageName)) {
            // L'utente ha fermato la musica nell'altra app - non serve più sincronizzare
            // Opzionale: potresti fermare il WebView qui
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        if (packageName == this.packageName || packageName.contains("spotify")) return

        val prefs = getSharedPreferences("GhostSyncPrefs", Context.MODE_PRIVATE)
        val isAutoStartEnabled = prefs.getBoolean("PREF_AUTOSTART", true)
        if (!isAutoStartEnabled) return

        val allowedApps = prefs.getStringSet("ALLOWED_APPS", setOf()) ?: setOf()
        if (!allowedApps.contains(packageName)) return

        val extras = sbn.notification.extras
        if (!extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        var title: String? = null
        var artist: String? = null
        var isPlaying = false

        try {
            val mediaSessionToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            }

            if (mediaSessionToken != null) {
                val controller = MediaController(this, mediaSessionToken)
                val metadata = controller.metadata
                val playbackState = controller.playbackState

                if (metadata != null) {
                    title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                }

                // FIX: Controlla che la musica stia davvero suonando (non in pausa)
                if (playbackState != null) {
                    isPlaying = playbackState.state == android.media.session.PlaybackState.STATE_PLAYING
                } else {
                    // Fallback: se non riusciamo a leggere lo stato, assumiamo che stia suonando
                    isPlaying = true
                }
            }
        } catch (_: Exception) {
            isPlaying = true // Fallback
        }

        // Leggi titolo/artista dalla notifica come fallback
        if (title.isNullOrEmpty() || artist.isNullOrEmpty()) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }

        // FIX: Non sincronizzare se in pausa
        if (!isPlaying) return

        if (!title.isNullOrEmpty() && !artist.isNullOrEmpty()) {
            val cleanTitle = title.replace(
                Regex("(?i)\\s*\\(official.*?\\)|\\s*\\[official.*?]|\\s*\\(lyric.*?\\)|\\s*\\(video.*?\\)|\\s*\\(audio.*?\\)|\\s*\\[lyrics.*?]|\\s*\\(feat.*?\\)|\\s*ft\\..*"),
                ""
            ).trim()

            val currentTrack = "$cleanTitle|$artist"

            if (currentTrack == lastPlayedTrack) return
            lastPlayedTrack = currentTrack

            // FIX: Debounce 1.5s - se la canzone cambia velocemente (es. skip rapidi) aspetta
            debounceRunnable?.let { handler.removeCallbacks(it) }
            val capturedTitle = cleanTitle
            val capturedArtist = artist

            debounceRunnable = Runnable {
                val intent = Intent(this, SpotifyWorkaroundService::class.java).apply {
                    action = "SYNC_TRACK"
                    putExtra("TITLE", capturedTitle)
                    putExtra("ARTIST", capturedArtist)
                }
                try {
                    startForegroundService(intent)
                } catch (_: Exception) {}
            }
            handler.postDelayed(debounceRunnable!!, 1500)
        }
    }
}