package com.example.spotifysync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import java.net.URLEncoder

class SpotifyWorkaroundService : Service() {

    private var webView: WebView? = null
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val channelId = "spotify_sync_channel"
    private val notificationId = 1

    // Stato brano corrente
    private var targetTitle = ""
    private var targetArtist = ""

    // Stato WebView / Spotify
    private var isSpotifyReady = false
    private var isWebViewAlive = false

    // Lock navigazione: evitiamo di caricare più URL contemporaneamente
    private var isNavigating = false
    private var navigationStartTime = 0L
    private val navigationTimeoutMs = 12_000L   // reset lock se la pagina non risponde

    // Contatore click per il brano corrente
    private var clickAttempts = 0
    private val maxClickAttempts = 6

    // Ultimo momento in cui sappiamo per certo che Spotify sta suonando il brano giusto
    private var lastConfirmedPlayingTime = 0L

    // ──────────────────────────────────────────────
    // Silenziatore audio
    // ──────────────────────────────────────────────
    private val jsSilencer = """
        (function() {
            if (window.__gs) return;
            window.__gs = true;
            var vd = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
            var md = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
            Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                configurable: true, get: function(){ return 0; },
                set: function(){ if(vd&&vd.set) vd.set.call(this,0); }
            });
            Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                configurable: true, get: function(){ return true; },
                set: function(){ if(md&&md.set) md.set.call(this,true); }
            });
            var op = HTMLMediaElement.prototype.play;
            HTMLMediaElement.prototype.play = function(){
                this.muted = true; try{this.volume=0;}catch(e){}
                return op.apply(this,arguments);
            };
            setInterval(function(){
                document.querySelectorAll('audio,video').forEach(function(e){
                    if(!e.muted) e.muted=true;
                    try{if(e.volume>0)e.volume=0;}catch(ex){}
                });
            }, 300);
        })();
    """.trimIndent()

    // ──────────────────────────────────────────────
    // Click sul primo risultato + verifica playback
    // ──────────────────────────────────────────────
    private val jsClickFirstTrack = """
        (function() {
            var attempts = 0;
            var maxAttempts = 60;
            var t = setInterval(function() {
                attempts++;
                if (attempts > maxAttempts) {
                    clearInterval(t);
                    AndroidBridge.onPlayResult('timeout');
                    return;
                }
                var rows = document.querySelectorAll('[data-testid="tracklist-row"]');
                if (rows.length === 0) return;
                clearInterval(t);

                var row = rows[0];
                ['mouseenter','mouseover'].forEach(function(ev){
                    row.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true}));
                });

                var btn = row.querySelector('[data-testid="play-button"]')
                       || row.querySelector('[data-testid*="play"]')
                       || row.querySelector('button[aria-label*="Play"]')
                       || row.querySelector('button[aria-label*="Riproduci"]')
                       || row.querySelector('button[aria-label*="play"]');

                if (btn) {
                    btn.click();
                } else {
                    row.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,view:window}));
                }

                // Verifica dopo 1.2s se sta suonando (era 2s)
                setTimeout(function() {
                    var pb = document.querySelector('[data-testid="control-button-playpause"]');
                    if (!pb) { AndroidBridge.onPlayResult('no_playbar'); return; }
                    var lbl = (pb.getAttribute('aria-label') || '').toLowerCase();
                    var playing = lbl.includes('pause') || lbl.includes('pausa');
                    if (playing) {
                        AndroidBridge.onPlayResult('verified_playing');
                    } else {
                        pb.click();
                        setTimeout(function() {
                            var lbl2 = (pb.getAttribute('aria-label') || '').toLowerCase();
                            var ok = lbl2.includes('pause') || lbl2.includes('pausa');
                            AndroidBridge.onPlayResult(ok ? 'fallback_ok' : 'fallback_fail');
                        }, 800);
                    }
                }, 1200);
            }, 200);
        })();
    """.trimIndent()

    // Controlla solo se sta suonando (senza toccare nulla)
    private val jsCheckPlaying = """
        (function() {
            var btn = document.querySelector('[data-testid="control-button-playpause"]');
            if (!btn) { AndroidBridge.onPlayResult('no_btn'); return; }
            var lbl = (btn.getAttribute('aria-label') || '').toLowerCase();
            var playing = lbl.includes('pause') || lbl.includes('pausa');
            AndroidBridge.onPlayResult(playing ? 'playing' : 'paused');
        })();
    """.trimIndent()

    // ──────────────────────────────────────────────
    // Bridge JS → Kotlin
    // ──────────────────────────────────────────────
    inner class JsBridge {
        @JavascriptInterface
        fun onPlayResult(result: String) {
            mainHandler.post {
                when (result) {
                    "verified_playing", "fallback_ok", "playing" -> {
                        // Sta suonando: aggiorna timestamp e azzera tentativi
                        lastConfirmedPlayingTime = System.currentTimeMillis()
                        clickAttempts = 0
                        isNavigating = false
                    }
                    else -> {
                        // Non sta suonando: rilascia il lock di navigazione
                        // Il polling aggressor ci riproverà a breve
                        isNavigating = false
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // POLLING AGGRESSIVO — ogni 3 secondi
    // Cuore del fix: se c'è un brano target e Spotify
    // non sta confermando il playback, ri-naviga subito.
    // ──────────────────────────────────────────────
    private val aggressivePoller = object : Runnable {
        override fun run() {
            tickPoll()
            mainHandler.postDelayed(this, 3_000)
        }
    }

    private fun tickPoll() {
        if (targetTitle.isEmpty()) return

        // Reset del lock navigazione se scaduto
        if (isNavigating && System.currentTimeMillis() - navigationStartTime > navigationTimeoutMs) {
            isNavigating = false
        }

        if (isNavigating) return

        // WebView non pronta → ricrea
        if (!isWebViewAlive) {
            initWebView()
            return
        }

        // Spotify non pronta → ricarica home
        if (!isSpotifyReady) {
            navigate("https://open.spotify.com/")
            return
        }

        // Spotify pronta: controlla se sta suonando il brano giusto.
        // Se non abbiamo conferma entro 3s dall'ultimo check, ri-cerca subito.
        val timeSinceConfirmed = System.currentTimeMillis() - lastConfirmedPlayingTime
        if (timeSinceConfirmed > 3_000) {
            if (clickAttempts < maxClickAttempts) {
                clickAttempts++
                triggerSearch(targetTitle, targetArtist)
            } else {
                webView?.evaluateJavascript(jsCheckPlaying, null)
            }
        } else {
            webView?.evaluateJavascript(jsCheckPlaying, null)
        }
    }

    // ──────────────────────────────────────────────
    // Keep-alive timer JS Android
    // ──────────────────────────────────────────────
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            webView?.let {
                it.resumeTimers()
                it.onResume()
            }
            mainHandler.postDelayed(this, 4_000)
        }
    }

    // ──────────────────────────────────────────────
    // Watchdog — rebind listener ogni 20s
    // ──────────────────────────────────────────────
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(applicationContext, MediaListenerService::class.java)
                )
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 20_000)
        }
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        NotificationChannel(channelId, "Ghost Sync", NotificationManager.IMPORTANCE_LOW).also {
            notificationManager.createNotificationChannel(it)
        }
        startForeground(notificationId, buildNotification("In attesa di musica..."))
        initWebView()
        mainHandler.post(keepAliveRunnable)
        mainHandler.postDelayed(watchdog, 20_000)
        mainHandler.postDelayed(aggressivePoller, 3_000)  // avvia il polling dopo 3s
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { WebView.setDataDirectorySuffix("spotify_shared") } catch (_: Exception) {}
            }
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            webView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                it.destroy()
            }
            isWebViewAlive = false
            isSpotifyReady = false
            isNavigating = false

            webView = WebView(applicationContext)
            webView!!.addJavascriptInterface(JsBridge(), "AndroidBridge")

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                2, 2, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0.02f
            }
            windowManager.addView(webView, params)
            webView!!.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isWebViewAlive = true

            webView!!.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            webView!!.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }

            webView!!.webViewClient = object : WebViewClient() {

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    isSpotifyReady = false
                    isNavigating = false
                    isWebViewAlive = false
                    mainHandler.postDelayed({ initWebView() }, 2_000)
                    return true
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(jsSilencer, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    view?.evaluateJavascript(jsSilencer, null)
                    isNavigating = false   // pagina caricata, rilascia lock

                    when {
                        isSpotifyHome(url) -> {
                            isSpotifyReady = true
                            // Home caricata: se c'è un brano target, cerca subito (no delay)
                            if (targetTitle.isNotEmpty()) {
                                triggerSearch(targetTitle, targetArtist)
                            }
                        }
                        url.contains("/search/") -> {
                            // Pagina ricerca caricata: click sul primo risultato quasi subito
                            mainHandler.postDelayed({
                                view?.evaluateJavascript(jsClickFirstTrack, null)
                            }, 100)
                        }
                        url.contains("/login") || url.contains("/signup") -> {
                            isSpotifyReady = false
                            isNavigating = false
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    isNavigating = false   // errore caricamento, rilascia lock
                }
            }

            webView!!.resumeTimers()
            webView!!.onResume()
            navigate("https://open.spotify.com/")
        }
    }

    private fun isSpotifyHome(url: String) =
        url.contains("open.spotify.com") &&
                !url.contains("/search") && !url.contains("/track") &&
                !url.contains("/album") && !url.contains("/artist") &&
                !url.contains("/login") && !url.contains("/signup")

    // ──────────────────────────────────────────────
    // Navigazione con lock
    // ──────────────────────────────────────────────
    private fun navigate(url: String) {
        isNavigating = true
        navigationStartTime = System.currentTimeMillis()
        mainHandler.post {
            try {
                webView?.loadUrl(url)
            } catch (_: Exception) {
                isNavigating = false
            }
        }
    }

    // ──────────────────────────────────────────────
    // Comandi
    // ──────────────────────────────────────────────
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        when (intent.action) {
            "STOP_SERVICE" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "SYNC_TRACK" -> {
                val title = intent.getStringExtra("TITLE") ?: return START_STICKY
                val artist = intent.getStringExtra("ARTIST") ?: return START_STICKY

                val newTrack = "$title|$artist"
                val trackChanged = newTrack != "$targetTitle|$targetArtist"

                targetTitle = title
                targetArtist = artist

                if (trackChanged) {
                    // Brano cambiato: azzera tutto e cerca subito
                    clickAttempts = 0
                    lastConfirmedPlayingTime = 0L
                    isNavigating = false
                    notificationManager.notify(notificationId, buildNotification("🎵 $title — $artist"))
                    forceSync(title, artist)
                }
                // Se stesso brano: il polling aggressivo si occupa di risincronizzare
            }
        }
        return START_STICKY
    }

    private fun forceSync(title: String, artist: String) {
        if (!isWebViewAlive) {
            initWebView()
            return
        }
        if (!isSpotifyReady) {
            // Solo se non è pronta carichiamo la home, altrimenti andiamo dritti alla ricerca
            navigate("https://open.spotify.com/")
            return
        }
        // Spotify già pronta: vai diretto alla ricerca, senza passare dalla home
        triggerSearch(title, artist)
    }

    private fun triggerSearch(title: String, artist: String) {
        if (isNavigating) return
        navigate("https://open.spotify.com/search/${URLEncoder.encode("$title $artist", "UTF-8")}/tracks")
    }

    // ──────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(keepAliveRunnable)
        mainHandler.removeCallbacks(aggressivePoller)
        webView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            it.destroy()
        }
        webView = null
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, SpotifyWorkaroundService::class.java).apply { action = "STOP_SERVICE" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ghost Sync").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}