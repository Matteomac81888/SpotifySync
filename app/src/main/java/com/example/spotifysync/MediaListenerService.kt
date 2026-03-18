package com.example.spotifysync

import android.app.Notification
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private var lastPlayedTrack = ""

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Ignoriamo la VERA app di Spotify e noi stessi per evitare cortocircuiti
        if (packageName == this.packageName || packageName.contains("spotify")) return

        val extras = sbn.notification.extras

        // IL FILTRO UNIVERSALE: Se la notifica è una sessione multimediale, la leggiamo!
        if (!extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        var title: String? = null
        var artist: String? = null

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
                if (metadata != null) {
                    title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (title.isNullOrEmpty() || artist.isNullOrEmpty()) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }

        if (!title.isNullOrEmpty() && !artist.isNullOrEmpty()) {
            val cleanTitle = title.replace(
                Regex("(?i)\\(official.*?\\)|\\[official.*?]|\\(lyric.*?\\)|\\(video.*?\\)|\\(audio.*?\\)|\\[lyrics.*?]"),
                ""
            ).trim()

            val currentTrack = "$cleanTitle - $artist"

            if (currentTrack != lastPlayedTrack) {
                lastPlayedTrack = currentTrack

                val intent = Intent(this, SpotifyWorkaroundService::class.java).apply {
                    action = "SYNC_TRACK"
                    putExtra("TITLE", cleanTitle)
                    putExtra("ARTIST", artist)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
}