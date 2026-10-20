# Maze Island

An Android prototype with two features in one app:

1. **Dynamic Island** – lives around your front camera (always-on bar, or hidden until needed)
   - shows the cover + a little equalizer while music plays
   - tap: opens the music tab (if something played in the last minute), otherwise your app menu;
     swipe between music tabs (Spotify first, one tab per player) and the app menu
   - blurred cover background that fades to black around the edges and the camera
   - pops up messages; tap a message to reply right in the island
   - can replace Android's own pop-up banners (one-time `adb` grant, see Settings)
2. **Spotify playlist widget** – pick a playlist by pasting its link; the widget shows the
   current song cover as a blurred background, plus play/pause/skip and a "play playlist" button.

Kotlin + Jetpack Compose (app + island), Jetpack Glance (widget). Min Android 8.0, tested target Android 14.

## Build

**Android Studio (PC):** File → Open → this folder. Let Gradle sync, plug in your phone with
USB debugging on, press ▶ Run. Or Build → Build App Bundle(s)/APK(s) → Build APK(s).

**GitHub (no PC needed):** every push to `main` builds the app and publishes it as the latest release.
Direct download (open in Chrome, not the GitHub app):
https://github.com/mazijoni/MazeIsland/releases/latest/download/MazeIsland.apk
