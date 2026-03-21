package com.example.spotifysync

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val name: String, val packageName: String, val icon: Drawable, var isSelected: Boolean)

class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { WebView.setDataDirectorySuffix("spotify_shared") } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }

        val prefs = getSharedPreferences("GhostSyncPrefs", Context.MODE_PRIVATE)

        // Switch avvio automatico
        val switchAutoStart = findViewById<Switch>(R.id.switchAutoStart)
        switchAutoStart.isChecked = prefs.getBoolean("PREF_AUTOSTART", true)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("PREF_AUTOSTART", isChecked).apply()

            // FIX: avvia o ferma il servizio in base allo switch
            if (isChecked) {
                val intent = Intent(this, SpotifyWorkaroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                stopService(Intent(this, SpotifyWorkaroundService::class.java))
            }
        }

        // FIX: avvia il servizio in background SOLO se l'autostart è attivo
        if (prefs.getBoolean("PREF_AUTOSTART", true)) {
            val intent = Intent(this, SpotifyWorkaroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }

        // Lista app
        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        rvApps.layoutManager = LinearLayoutManager(this)

        Thread {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedInfos = pm.queryIntentActivities(mainIntent, 0)
            val allowedAppsSet = prefs.getStringSet("ALLOWED_APPS", mutableSetOf()) ?: mutableSetOf()

            val appList = resolvedInfos.mapNotNull {
                val pkg = it.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                AppInfo(it.loadLabel(pm).toString(), pkg, it.loadIcon(pm), allowedAppsSet.contains(pkg))
            }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }

            runOnUiThread {
                rvApps.adapter = AppAdapter(appList) { pkg, isChecked ->
                    val current = prefs.getStringSet("ALLOWED_APPS", mutableSetOf())
                        ?.toMutableSet() ?: mutableSetOf()
                    if (isChecked) current.add(pkg) else current.remove(pkg)
                    prefs.edit().putStringSet("ALLOWED_APPS", current).apply()
                }
            }
        }.start()

        // Pulsante permessi notifiche
        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Mini player WebView
        val loginWebView = findViewById<WebView>(R.id.loginWebView)
        loginWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        loginWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true)
        loginWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
        loginWebView.webViewClient = WebViewClient()
        loginWebView.loadUrl("https://open.spotify.com/")
    }

    override fun onResume() {
        super.onResume()
        val isEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (isEnabled) {
            try {
                val pm = packageManager
                val cn = ComponentName(this, MediaListenerService::class.java)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } catch (_: Exception) {}
        }
    }

    inner class AppAdapter(
        private val apps: List<AppInfo>,
        private val onCheckChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val cb: CheckBox = view.findViewById(R.id.appCheckBox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.name
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = app.isSelected
            holder.cb.setOnCheckedChangeListener { _, isChecked ->
                app.isSelected = isChecked
                onCheckChanged(app.packageName, isChecked)
            }
        }

        override fun getItemCount() = apps.size
    }
}