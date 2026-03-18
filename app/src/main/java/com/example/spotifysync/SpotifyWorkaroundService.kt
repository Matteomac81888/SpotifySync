package com.example.spotifysync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import java.net.URLEncoder

class SpotifyWorkaroundService : Service() {

    private lateinit var webView: WebView
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private val CHANNEL_ID = "spotify_sync_channel"
    private val NOTIFICATION_ID = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isNavigating = false
    @Volatile private var isSuccessfullySynced = false // SALVA-BATTERIA: Flag di spegnimento
    private var currentTargetTitle = ""
    private var currentTargetArtist = ""

    // Il Ponte JavaScript per spegnere l'uso della CPU quando la musica parte
    inner class WebAppInterface {
        @JavascriptInterface
        fun onPlaybackStarted() {
            isNavigating = false
            isSuccessfullySynced = true // La musica è partita! Possiamo dormire.
        }
    }

    // Il Watchdog ora consuma lo 0% se la canzone è già partita
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            // SE È SINCRONIZZATO E IN PLAY, NON FARE NULLA. RISPARMIA BATTERIA AL 100%!
            if (isSuccessfullySynced) {
                return
            }

            if (!isNavigating && currentTargetTitle.isNotEmpty()) {
                val jsCheck = """
                    (function() {
                        var titleNode = document.querySelector('[data-testid="context-item-info-title"]');
                        var playingTitle = titleNode ? titleNode.innerText.trim() : "";
                        var btn = document.querySelector('[data-testid="play-button"],[data-testid="control-button-playpause"]');
                        var isPlaying = false;
                        if (btn) {
                            var label = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '').toLowerCase();
                            isPlaying = label.includes('pause') || label.includes('stop');
                        }
                        return playingTitle + "|||" + isPlaying;
                    })();
                """.trimIndent()

                webView.evaluateJavascript(jsCheck) { result ->
                    val res = result?.replace("\"", "") ?: ""
                    val parts = res.split("|||")
                    val playingTitle = parts.getOrNull(0) ?: ""
                    val isPlaying = parts.getOrNull(1) == "true"

                    val expectedClean = currentTargetTitle.lowercase()
                    val playingClean = playingTitle.lowercase()
                    val titleMatches = playingClean.isEmpty() || playingClean.contains(expectedClean) || expectedClean.contains(playingClean)

                    if (isPlaying && titleMatches) {
                        // Successo confermato dalla pagina: spegniamo il Watchdog
                        isSuccessfullySynced = true
                    } else if (!isPlaying || !titleMatches) {
                        // Ritenta se qualcosa è andato storto
                        forceSync(currentTargetTitle, currentTargetArtist)
                    }
                }
            }

            // Ritenta tra 15 secondi invece che 5, per dare respiro al telefono
            if (!isSuccessfullySynced) {
                mainHandler.postDelayed(this, 15000)
            }
        }
    }

    private val JS_SILENT_OVERRIDE = """
        (function() {
            if (window._myPlayOverridden) return;
            window._myPlayOverridden = true;
            var _play = HTMLMediaElement.prototype.play;
            HTMLMediaElement.prototype.play = function() {
                this.volume = 0;
                this.muted = true;
                try {
                    var result = _play.call(this);
                    if (result && result.then) { result.catch(function(){}); }
                } catch(e) {}
                return Promise.resolve();
            };
            var _volumeSetter = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume').set;
            Object.defineProperty(HTMLMediaElement.prototype, 'volume', { configurable: true, get: function() { return 0; }, set: function(v) { _volumeSetter.call(this, 0); } });
            var _mutedSetter = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted').set;
            Object.defineProperty(HTMLMediaElement.prototype, 'muted', { configurable: true, get: function() { return true; }, set: function(v) { _mutedSetter.call(this, true); } });
        })();
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ghost Sync", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildNotification("In attesa di musica..."))

        mainHandler.post {
            try { WebView.setDataDirectorySuffix("spotify_shared") } catch (e: Exception) {}

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            webView = WebView(this)
            webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(500, 500, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.alpha = 0.0f

            windowManager.addView(webView, params)
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webView.settings.apply {
                userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
            }

            webView.resumeTimers()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(JS_SILENT_OVERRIDE, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    view?.evaluateJavascript(JS_SILENT_OVERRIDE, null)

                    if (url.contains("/search/")) {
                        val jsSearch = """
                            (function() {
                                var attempts = 0;
                                var t = setInterval(function() {
                                    attempts++;
                                    var link = document.querySelector('a[href*="/track/"]') || document.querySelector('[data-testid="tracklist-row"] a');
                                    if (link) {
                                        clearInterval(t); // Ferma il loop
                                        window.location.href = link.href;
                                    } else if (attempts > 20) { clearInterval(t); }
                                }, 800);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsSearch, null)

                    } else if (url.contains("/track/")) {
                        val jsPlay = """
                            (function() {
                                var attempts = 0;
                                var t = setInterval(function() {
                                    attempts++;
                                    var btn = document.querySelector('[data-testid="play-button"], [data-testid="control-button-playpause"]');
                                    if (btn) {
                                        var label = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '').toLowerCase();
                                        if (label.includes('pause') || label.includes('stop')) {
                                            clearInterval(t); // Ferma il loop! CPU salvata.
                                            if(window.AndroidBridge) window.AndroidBridge.onPlaybackStarted();
                                            return;
                                        }
                                        btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                    }
                                    if (attempts > 30) clearInterval(t);
                                }, 1000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsPlay, null)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SYNC_TRACK") {
            val title = intent.getStringExtra("TITLE") ?: "Sconosciuto"
            val artist = intent.getStringExtra("ARTIST") ?: "Sconosciuto"

            currentTargetTitle = title
            currentTargetArtist = artist
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Ascoltando: $title - $artist"))

            // SVEGLIA IL WATCHDOG: Nuova canzone, dobbiamo lavorare!
            isSuccessfullySynced = false
            mainHandler.removeCallbacks(watchdogRunnable)

            forceSync(title, artist)

            // Attiva il watchdog di controllo dopo 20 secondi per vedere se la prima operazione è andata a buon fine
            mainHandler.postDelayed(watchdogRunnable, 20000)
        }
        return START_STICKY
    }

    private fun forceSync(title: String, artist: String) {
        isNavigating = true
        mainHandler.post {
            try {
                CookieManager.getInstance().flush()
                val query = URLEncoder.encode("$title $artist", "UTF-8")
                val searchUrl = "https://open.spotify.com/search/$query/tracks"
                webView.evaluateJavascript("window.location.replace('$searchUrl');", null)
                webView.loadUrl(searchUrl)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdogRunnable)
        if (::windowManager.isInitialized && ::webView.isInitialized) {
            windowManager.removeView(webView)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ghost Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}