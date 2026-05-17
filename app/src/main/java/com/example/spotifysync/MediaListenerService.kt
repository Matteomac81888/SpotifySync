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
    private var debounceRunnable: Runnable? = null
    // Debounce minimo: notifica il servizio quasi subito
    private val debounceDelayMs = 200L

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { processNotification(it) }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Nessuna azione: la WebView continua fino al prossimo sync
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        if (packageName == this.packageName || packageName.contains("spotify")) return

        val prefs = getSharedPreferences("GhostSyncPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("PREF_AUTOSTART", true)) return

        val allowedApps = prefs.getStringSet("ALLOWED_APPS", setOf()) ?: setOf()
        if (!allowedApps.contains(packageName)) return

        val extras = sbn.notification.extras
        if (!extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        var title: String? = null
        var artist: String? = null
        var isPlaying = false

        try {
            val mediaSessionToken: MediaSession.Token? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable(
                        Notification.EXTRA_MEDIA_SESSION,
                        MediaSession.Token::class.java
                    )
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

                isPlaying = if (playbackState != null) {
                    playbackState.state == android.media.session.PlaybackState.STATE_PLAYING
                } else {
                    true // Fallback conservativo
                }
            }
        } catch (_: Exception) {
            isPlaying = true // Fallback
        }

        // Fallback da notifica se MediaSession non ha metadati
        if (title.isNullOrEmpty() || artist.isNullOrEmpty()) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }

        if (!isPlaying) return
        if (title.isNullOrEmpty() || artist.isNullOrEmpty()) return

        val cleanTitle = title.replace(
            Regex(
                "(?i)\\s*\\(official.*?\\)|\\s*\\[official.*?]|\\s*\\(lyric.*?\\)|" +
                        "\\s*\\(video.*?\\)|\\s*\\(audio.*?\\)|\\s*\\[lyrics.*?]|" +
                        "\\s*\\(feat.*?\\)|\\s*ft\\..*"
            ), ""
        ).trim()

        val currentTrack = "$cleanTitle|$artist"
        if (currentTrack == lastPlayedTrack) return
        lastPlayedTrack = currentTrack

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }
        handler.postDelayed(debounceRunnable!!, debounceDelayMs)
    }
}