# Maze Island

An Android prototype with two features in one app:

1. **Dynamic Island** – a black pill around your front camera that
   - shows the cover + a little equalizer while music plays (Spotify or any player)
   - expands to full controls when tapped
   - pops up incoming messages (tap to open the chat)
   - opens an app menu (your chosen apps) when tapped with nothing playing
2. **Spotify playlist widget** – pick a playlist by pasting its link; the widget shows the
   current song cover as a blurred background, plus play/pause/skip and a "play playlist" button.

Kotlin + Jetpack Compose (app + island), Jetpack Glance (widget). Min Android 8.0, tested target Android 14.

## Build

**Android Studio (PC):** File → Open → this folder. Let Gradle sync, plug in your phone with
USB debugging on, press ▶ Run. Or Build → Build App Bundle(s)/APK(s) → Build APK(s).

**GitHub (no PC needed):** every push to `main` builds the app and publishes it as the latest release.
Direct download (open in Chrome, not the GitHub app):
https://github.com/mazijoni/MazeIsland/releases/latest/download/MazeIsland.apk
