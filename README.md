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

**GitHub (no PC needed):** push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds on every push. Open the repo's **Actions** tab → latest
run → download **MazeIsland-debug-apk** → unzip → install the `.apk` on your phone.

## First run on the phone

1. Install the APK (allow "Install unknown apps" for your browser/Files app when asked).
2. Open **Maze Island** and turn on:
   - **Notification access** – music info, controls, texts
   - **Accessibility service** – lets the island draw over the status bar
3. If either switch is grayed out ("Restricted setting"): App info → ⋮ → **Allow restricted
   settings**, then go back and turn it on. Android does this for any app not from the Play Store.
4. App info → Battery → **Unrestricted** (stops Samsung/Xiaomi/etc. from killing it).
5. Use the sliders to line the pill up exactly with your camera hole.
6. Paste a playlist link (Spotify → playlist → ⋯ → Share → Copy link) and tap **Add widget**.

## How it works

| Piece | File | Notes |
|---|---|---|
| Media + notifications | `media/MediaListenerService.kt` | `NotificationListenerService` → `MediaSessionManager.getActiveSessions()` |
| Shared state | `media/MediaRepository.kt` | StateFlows read by the island, widget and settings |
| Island overlay | `island/IslandService.kt` | `TYPE_ACCESSIBILITY_OVERLAY` window, anchored to `DisplayCutout` |
| Island UI | `island/IslandUi.kt` | Compose `AnimatedContent` morphing between idle / mini / media / menu / notification |
| Widget | `widget/PlaylistWidget.kt` | Glance; cover blurred in `util/BitmapUtils.kt` |
| Playlist info | `widget/PlaylistLoader.kt` | Spotify's public oEmbed endpoint – no login/API key |

## Known prototype limits

- "Play playlist" starts it directly only if Spotify is already running and accepts it;
  otherwise it opens the playlist in Spotify and you tap play there.
- The island hides in landscape and doesn't yet hide for fullscreen video/games.
- Google Play is strict about apps using the Accessibility API – fine for sideloading.
