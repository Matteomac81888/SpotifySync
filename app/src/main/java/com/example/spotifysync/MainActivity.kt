package com.example.spotifysync

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try { WebView.setDataDirectorySuffix("spotify_shared") } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        val btnPermission = findViewById<Button>(R.id.btnPermission)
        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val loginWebView = findViewById<WebView>(R.id.loginWebView)
        loginWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        loginWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true)

        loginWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        loginWebView.webViewClient = WebViewClient()
        loginWebView.loadUrl("https://accounts.spotify.com/login")

        val serviceIntent = Intent(this, SpotifyWorkaroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        val isEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (isEnabled) {
            try {
                val pm = packageManager
                val componentName = ComponentName(this, MediaListenerService::class.java)
                pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}