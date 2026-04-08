# CuePoint – Wear OS App

Minimal Wear OS app for .NET developers. One button that seeks Spotify to a chosen timestamp.

## Prerequisites

1. **Android Studio** (or Rider with Android plugin) – [developer.android.com/studio](https://developer.android.com/studio)
2. **Spotify Premium account** – the seek endpoint requires Premium
3. **Spotify Developer App** – create one at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
4. **Wear OS watch or emulator** – create an AVD (Android Virtual Device) for Wear OS in Android Studio

## Spotify Developer App Setup

1. Go to developer.spotify.com → Dashboard → Create App
2. Name: anything (e.g. "CuePoint")
3. Redirect URI: `cuepoint://callback`
4. Check **Web API**
5. Copy your **Client ID**

## Project Structure

```
app/
├── build.gradle.kts          ← dependencies
├── src/main/
│   ├── AndroidManifest.xml   ← permissions, activity
│   └── java/com/cuepoint/
│       ├── MainActivity.kt       ← UI (Compose for Wear OS)
│       ├── SpotifyAuth.kt        ← OAuth 2.0 PKCE
│       └── SpotifyApi.kt         ← seek HTTP calls
```

## How to Run

1. Open the project in Android Studio / Rider
2. Fill in your `CLIENT_ID` in `SpotifyAuth.kt`
3. Build → Run on a Wear OS emulator or watch
4. On first launch, a browser login opens on the paired phone
5. After login: set the time, press the button → Spotify seeks to that position

## Testing Tip

For a quick test without OAuth: generate a temporary token in the Spotify Developer Dashboard
(Console → Get Token → check `user-modify-playback-state`).
Paste that token into `SpotifyApi.kt` as a hardcoded value and handle OAuth later.
