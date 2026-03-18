👻 GhostSync
GhostSync is an open-source Android background utility designed to bridge the gap between your favorite music player and social apps that exclusively support Spotify.
🎯 The Problem: Why I built this
I created this app for a very specific use case: Bump.
Bump is a social app that lets you share what you are currently listening to with your friends, but it only syncs with Spotify. I personally use YouTube Music (and sometimes other music apps) and I don't have Spotify Premium. I wanted my friends on Bump to see my live listening status without being forced to switch to Spotify or pay for Premium.
💡 The Solution: How it works
GhostSync acts as a "ghost" middleman. When you play a song on YouTube Music, Deezer, or any other music app, GhostSync does the following entirely in the background:
Detects the currently playing track and artist using Android's universal MediaSession.
Spins up a completely invisible, hardware-accelerated Spotify Web Player (tricked into thinking it's running on a macOS desktop to bypass Spotify Free mobile limitations).
Searches for the track and automatically clicks "Play" via JavaScript injection.
Forces the Spotify Web Player's volume to 0.01% (virtually muted) so it doesn't interrupt or overlap with your actual music.
The result? Spotify's servers register that you are listening to that specific song, which instantly updates your Bump status. You get to listen to your music on your preferred app, and Bump gets the data it needs from Spotify.
✨ Key Features
🎵 Universal Compatibility: Reads metadata from any music app broadcasting a media session (YT Music, Deezer, Apple Music, SoundCloud, etc.).
🔓 No Premium Required: Bypasses the mobile "Shuffle-only" restriction of Spotify Free by emulating a Desktop browser environment.
🔋 Battery Optimized (Zero-Drain): Uses an event-driven Watchdog. Once the track is confirmed as playing on the hidden Spotify instance, the CPU usage drops to 0% until the next track change.
👻 Completely Invisible: Uses a 0% opacity SYSTEM_ALERT_WINDOW to prevent Android's aggressive battery manager from freezing the background JavaScript execution.
🛡️ Auto-DRM Bypass: Automatically grants DRM and audio playback permissions to the hidden WebView.
🚀 Setup & Usage
Install the APK and grant the Notification Access and Display over other apps permissions.
Open the app and log into your Spotify Free account using the provided mini-browser.
Once logged in, you can close the app.
Play any song on your favorite music app.
Watch your Bump status update magically!
🛠️ Technologies Used
Kotlin (Native Android SDK)
NotificationListenerService & MediaController API
Headless WebView with injected JavaScript (evaluateJavascript)
DOM manipulation & Web Audio API overrides
Disclaimer: This project was built for educational purposes to understand Android background services, WebView manipulation, and IPC (Inter-Process Communication). It is not affiliated with Spotify or Bump.
