# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product Purpose

CuePoint is a Wear OS app for **dance instructors** who rehearse choreography to specific segments of a song. The core workflow:

1. User sets a **"from" time** (start position) using a time picker
2. User optionally sets a **"to" time** (end position)
3. **Reset button** seeks Spotify playback back to the "from" time instantly
4. When a "to" time is set, playback **auto-stops and resets** to "from" when the track reaches it
5. User can also **start/resume playback** directly from the watch

The watch is strapped to an instructor's wrist during a dance session — UI must be glanceable, one-tap operable, and work well on a round display.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected Wear OS device/emulator
./gradlew installDebug

# Build and run
./gradlew :app:installDebug

# Lint
./gradlew lint
```

There are no unit tests yet.

To test Spotify API calls without going through OAuth, paste a temporary token from `https://developer.spotify.com/console/put-seek/` into `TEST_TOKEN` in `SpotifyApi.kt`.

## Architecture

Single Activity, MVVM (one ViewModel exposing a `StateFlow<CuePointUiState>`), Compose for Wear OS:

| File | Role |
|------|------|
| `MainActivity.kt` | Activity, OAuth callback forwarding, and all Compose UI: `LoginScreen`, `MainPager` (Wear `HorizontalPager`) with `SetupScreen` and `ControlScreen` |
| `MainViewModel.kt` | UI state, play/pause/reset actions, time adjustment, error display, haptics |
| `SpotifyApi.kt` | Spotify Web API calls (seek, play, pause, playback state); token + refresh-token storage via DataStore; transparent refresh-and-retry on 401 |
| `SpotifyAuth.kt` | OAuth 2.0 PKCE flow; opens browser on paired phone via `Intent.ACTION_VIEW` |

The Spotify client ID is injected at build time from `local.properties` (`spotify.clientId=...`) into `BuildConfig.SPOTIFY_CLIENT_ID`.

**Auth flow:** `SpotifyAuth.openAuthInBrowser()` → user logs in on phone → Spotify redirects to `cuepoint://callback` → `MainActivity.onNewIntent()` → `SpotifyAuth.handleCallback()` → access + refresh tokens saved to DataStore → `MainViewModel.onLoginComplete()`.

**Token resolution** (`SpotifyApi.getToken()`): DataStore token → fallback to hardcoded `TEST_TOKEN` → null (shows login screen).

**Roadmap:** the remaining work is planned in `.wip/ROADMAP.md` (gitignored, local only).

## Key Constraints

- **Wear OS only** — `minSdk 30` (Wear OS 3+), round display assumed
- **Spotify Premium required** — seek and playback control APIs require a Premium account; `403` means non-Premium user (except `403 "Restriction violated"` on pause, which means already paused and is treated as success)
- **Active device required** — Spotify must be playing (or paused) on some device; `404` means no active player
- **Token expiry** — access tokens last ~1 hour; on `401` the app refreshes the token (mutex-guarded, so concurrent callers share one refresh) and retries once; tokens are cleared and the login screen shown only when the refresh is rejected (400/401 or no refresh token) or the retry is also `401`. Network errors, 5xx on the token endpoint, 404 and other 403s never log out
- `codeVerifier` in `SpotifyAuth` is kept in memory — app process kill between auth steps loses it (issue #6)
- **Development mode** — a Spotify app in development mode requires the app owner to have Premium and allows at most 5 allowlisted users

## Spotify API Scopes

`user-modify-playback-state` — seek, play, pause  
`user-read-playback-state` — read current position (needed for auto-stop on "to" time)
