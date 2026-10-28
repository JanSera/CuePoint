# CuePoint – Wear OS App

Wear OS app for dance instructors: set a "from" time on the watch, then reset Spotify playback to it and play/pause with one tap while rehearsing a segment of a song.

## Prerequisites

1. **Android Studio** (or Rider with Android plugin) – [developer.android.com/studio](https://developer.android.com/studio)
2. **Spotify Premium account** – playback control endpoints require Premium
3. **Spotify Developer App** – create one at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
4. **Wear OS watch or emulator** – create an AVD (Android Virtual Device) for Wear OS in Android Studio

## Spotify Developer App Setup

1. Go to developer.spotify.com → Dashboard → Create App
2. Name: anything (e.g. "CuePoint")
3. Redirect URI: `cuepoint://callback`
4. Check **Web API**
5. Copy your **Client ID**
6. Under **User Management**, add the Spotify accounts that will use the app

Apps in development mode need an owner with Spotify Premium and allow at most 5 allowlisted users.

## Project Structure

```
app/
├── build.gradle.kts          ← dependencies, BuildConfig.SPOTIFY_CLIENT_ID
├── src/main/
│   ├── AndroidManifest.xml   ← permissions, activity, cuepoint://callback filter
│   └── java/com/cuepoint/
│       ├── MainActivity.kt       ← Activity + Compose UI (login, setup, control)
│       ├── MainViewModel.kt      ← UI state and actions
│       ├── SpotifyAuth.kt        ← OAuth 2.0 PKCE
│       └── SpotifyApi.kt         ← Spotify Web API calls, token storage and refresh
```

## How to Run

1. Open the project in Android Studio / Rider
2. Add your client ID to `local.properties` (not committed):
   ```
   spotify.clientId=<your client id>
   ```
3. Build → Run on a Wear OS emulator or watch
4. Tap **Login with Spotify**; the login page opens on the paired phone
5. After login: set the time, open Spotify on any device, then use Reset and Play/Pause on the watch

## Testing Tip

For a quick test without OAuth: generate a temporary token in the Spotify Developer Console
(Get Token → check `user-modify-playback-state` and `user-read-playback-state`).
Paste it into `TEST_TOKEN` in `SpotifyApi.kt`. Don't commit it.

## Testing on an emulator

An emulator has no paired phone, so the in-app login can't run. Log in on your PC instead and
inject the tokens (access + refresh) into the debug build:

1. Spotify dashboard: add `http://127.0.0.1:8888/callback` as a Redirect URI.
2. Install the debug build: `./gradlew installDebug`
3. Run (Windows PowerShell 5.1):
   ```
   powershell -ExecutionPolicy Bypass -File tools\Get-SpotifyToken.ps1
   ```

The script opens the Spotify login, sends the tokens over adb to a debug-only receiver and
restarts the app. Options: `-Serial <id>` (several devices), `-NoAdb` (print the adb command),
`-ShowTokens`. The receiver exists only in debug builds.
