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

    private var targetTitle = ""
    private var targetArtist = ""
    private var isSpotifyReady = false
    private var isSyncing = false
    private var lastSyncTime = 0L


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

    private val jsClickFirstTrack = """
        (function() {
            var attempts = 0;
            var t = setInterval(function() {
                attempts++;
                var rows = document.querySelectorAll('[data-testid="tracklist-row"]');
                if (rows.length === 0 && attempts < 30) return;
                clearInterval(t);
                if (rows.length === 0) { AndroidBridge.onPlayResult('no_rows'); return; }
                var row = rows[0];
                ['mouseenter','mouseover'].forEach(function(ev){
                    row.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true}));
                });
                var btn = row.querySelector('[data-testid="play-button"]')
                       || row.querySelector('[data-testid*="play"]')
                       || row.querySelector('button[aria-label*="Play"]')
                       || row.querySelector('button[aria-label*="Riproduci"]')
                       || row.querySelector('button[aria-label*="play"]');
                if (btn) { btn.click(); AndroidBridge.onPlayResult('btn_click'); return; }
                row.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true,view:window}));
                AndroidBridge.onPlayResult('dblclick');
            }, 500);
        })();
    """.trimIndent()

    private val jsCheckPlaying = """
        (function() {
            var btn = document.querySelector('[data-testid="control-button-playpause"]');
            if (!btn) { AndroidBridge.onPlayResult('no_btn'); return; }
            var lbl = (btn.getAttribute('aria-label') || '').toLowerCase();
            var playing = lbl.includes('pause') || lbl.includes('metti in pausa');
            AndroidBridge.onPlayResult(playing ? 'playing' : 'paused');
        })();
    """.trimIndent()

    inner class JsBridge {
        @JavascriptInterface
        fun onPlayResult(result: String) {
            mainHandler.post {
                isSyncing = false
                if ((result == "no_rows" || result == "no_btn" || result == "paused") && targetTitle.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - lastSyncTime
                    if (elapsed > 15000) {
                        mainHandler.postDelayed({ triggerSearch(targetTitle, targetArtist) }, 20000)
                    }
                }
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(applicationContext, MediaListenerService::class.java)
                )
            } catch (_: Exception) {}
            if (targetTitle.isNotEmpty() && isSpotifyReady && !isSyncing) {
                webView?.evaluateJavascript(jsCheckPlaying, null)
            }
            mainHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        NotificationChannel(channelId, "Ghost Sync", NotificationManager.IMPORTANCE_LOW).also {
            notificationManager.createNotificationChannel(it)
        }
        startForeground(notificationId, buildNotification("In attesa di musica..."))
        initWebView()
        mainHandler.postDelayed(watchdog, 30000)
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

            webView = WebView(applicationContext)
            webView!!.addJavascriptInterface(JsBridge(), "AndroidBridge")

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                1, 1, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0.01f
            }
            windowManager.addView(webView, params)
            webView!!.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    isSpotifyReady = false
                    isSyncing = false
                    mainHandler.postDelayed({ initWebView() }, 2000)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(jsSilencer, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    view?.evaluateJavascript(jsSilencer, null)

                    when {
                        isSpotifyHome(url) -> {
                            isSpotifyReady = true
                            isSyncing = false
                            if (targetTitle.isNotEmpty()) {
                                mainHandler.postDelayed({ triggerSearch(targetTitle, targetArtist) }, 1500)
                            }
                        }
                        url.contains("/search/") -> {
                            // Pagina ricerca caricata: clicca il primo risultato
                            view?.evaluateJavascript(jsClickFirstTrack, null)
                        }
                        url.contains("/login") || url.contains("/signup") -> {
                            isSpotifyReady = false
                            isSyncing = false
                        }
                    }
                }
            }

            webView!!.resumeTimers()
            isSpotifyReady = false
            webView!!.loadUrl("https://open.spotify.com/")
        }
    }

    private fun isSpotifyHome(url: String) =
        url.contains("open.spotify.com") &&
                !url.contains("/search") && !url.contains("/track") &&
                !url.contains("/album") && !url.contains("/artist") &&
                !url.contains("/login") && !url.contains("/signup")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        when (intent.action) {
            "STOP_SERVICE" -> {
                return START_NOT_STICKY
            }
            "SYNC_TRACK" -> {
                val title = intent.getStringExtra("TITLE") ?: return START_STICKY
                val artist = intent.getStringExtra("ARTIST") ?: return START_STICKY
                targetTitle = title
                targetArtist = artist
                notificationManager.notify(notificationId, buildNotification("🎵 $title — $artist"))
                forceSync(title, artist)
            }
        }
        return START_STICKY
    }

    private fun forceSync(title: String, artist: String) {
        if (isSyncing) return
        if (System.currentTimeMillis() - lastSyncTime < 4000) return
        if (!isSpotifyReady) {
            mainHandler.post { webView?.loadUrl("https://open.spotify.com/") }
            return
        }
        triggerSearch(title, artist)
    }

    // La navigazione parte SEMPRE da Kotlin con loadUrl, mai da JS con window.location
    private fun triggerSearch(title: String, artist: String) {
        if (isSyncing) return
        isSyncing = true
        lastSyncTime = System.currentTimeMillis()
        mainHandler.post {
            try {
                val query = URLEncoder.encode("$title $artist", "UTF-8")
                webView?.loadUrl("https://open.spotify.com/search/$query/tracks")
            } catch (_: Exception) { isSyncing = false }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdog)
        webView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            it.destroy()
        }
        webView = null
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, SpotifyWorkaroundService::class.java).apply { action = "STOP_SERVICE" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ghost Sync").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}