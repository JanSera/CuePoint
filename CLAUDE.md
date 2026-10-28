# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product Purpose

CuePoint is a Wear OS app for **dance instructors** who rehearse choreography to specific segments of a song. The core workflow:

1. User sets a **"from" time** (start position) by pressing **MARK** while the song plays (nudge with crown/bezel or ± by 1 s)
2. User optionally sets a **"to" time** (end position) the same way; segments are remembered per track
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
| `MainActivity.kt` | Activity (forwards the OAuth callback Uri to the ViewModel, in `onCreate` on cold start and in `onNewIntent`) and all Compose UI: spinner while auth is `Unknown`, `LoginScreen`, `MainPager` (Wear `HorizontalPager`, rotary handling switched off so the Segment page owns the crown/bezel) with `ControlScreen` (page 0, default: "Track · Device", live position ticked locally every 250 ms, "from → to" summary, Reset and Play/Pause) and `SegmentScreen` (page 1: FROM/TO rows with MARK, tap to select the field, long-press TO clears it, crown/bezel and ± change the selected field by 1 s, Log out chip at the bottom); Back on Segment returns to Control; syncs playback state on `ON_RESUME` via `LifecycleEventEffect` |
| `MainViewModel.kt` | UI state incl. `AuthState` (`Unknown`/`LoggedIn`/`LoggedOut`), the current segment (`fromMs`, `toMs?`, `selectedField`), login/logout and OAuth callback handling, `syncPlayback()` (resume, cold start, after login; loads the track's segment when the track id changes), play/pause/reset actions (re-anchor the local playback snapshot after success, no extra GET), `mark()`/`nudge()`/`clearTo()` with auto-save, mapping each non-Ok `ApiResult` to one error line, error display, haptics |
| `SegmentStore.kt` | `Segment(trackId, fromMs, toMs?)`, the `SegmentStore` interface (fakeable) and `DataStoreSegmentStore` (one entry per track in the shared DataStore: `segment_<trackId>` = `"fromMs,toMs"`), plus the validation rules: `to >= from + 2 s`, both clamped to `[0, duration]`; moving "from" so "to" no longer fits clears "to", a too-close "to" is rejected |
| `SpotifyApi.kt` | `ApiResult<T>` (Ok/Unauthorized/NoActiveDevice/PremiumRequired/Network/Other), `PlaybackState`, the `SpotifyClient` interface (what the ViewModel depends on; fakeable) and its implementation `SpotifyApi`: seek, play, pause, `getPlaybackState()` (GET /v1/me/player; 204 gives `Ok(null)`; an ad or podcast with no track gives a state with null track fields), `PlaybackState.positionAt(now)` (local position extrapolation, used by the live position and MARK); token + refresh-token storage via DataStore; transparent refresh-and-retry on 401 |
| `SpotifyAuth.kt` | OAuth 2.0 PKCE flow; opens browser on paired phone via `Intent.ACTION_VIEW`; persists the verifier and OAuth `state` in DataStore until the callback |

The Spotify client ID is injected at build time from `local.properties` (`spotify.clientId=...`) into `BuildConfig.SPOTIFY_CLIENT_ID`.

**Auth flow:** `MainViewModel.login()` → `SpotifyAuth.openAuthInBrowser()` (saves PKCE verifier + random `state` to DataStore, opens the browser on the phone) → user logs in → Spotify redirects to `cuepoint://callback` → `MainActivity` (`onCreate` on cold start, or `onNewIntent`) → `MainViewModel.handleAuthCallback(uri)` → `SpotifyAuth.handleCallback()` (checks `error` and `state`, clears the pending values, exchanges the code) → tokens saved → `AuthState.LoggedIn`. Failures show "Login cancelled" (`error=access_denied`) or "Login failed" on the login screen until the next tap. Log out (chip at the bottom of the Segment page) clears the tokens.

**Token resolution** (`SpotifyApi.getToken()`): DataStore token → fallback to hardcoded `TEST_TOKEN` (debug builds only; so logging out with one set logs you straight back in) → null (shows login screen).

**Roadmap:** the remaining work is planned in `.wip/ROADMAP.md` (gitignored, local only).

## Key Constraints

- **Wear OS only** — `minSdk 30` (Wear OS 3+), round display assumed
- **Spotify Premium required** — seek and playback control APIs require a Premium account; only a `403` whose body says `PREMIUM_REQUIRED` means non-Premium (`403 "Restriction violated"` on play/pause means already in that state and is treated as success; elsewhere it is a generic failure)
- **Active device required** — Spotify must be playing (or paused) on some device; `404` means no active player
- **Token expiry** — access tokens last ~1 hour; on `401` the app refreshes the token (mutex-guarded, so concurrent callers share one refresh) and retries once; tokens are cleared and the login screen shown only when the refresh is rejected (400/401 or no refresh token) or the retry is also `401`. Network errors, 5xx on the token endpoint, 404 and other 403s never log out
- **Application context only** — `SpotifyApi` and `SpotifyAuth` are held by the ViewModel, so they get `applicationContext`, never the Activity. The PKCE verifier and `state` live in DataStore (not memory), so the login survives process death; `Context.dataStore` in `SpotifyApi.kt` is shared, do not declare a second DataStore with the same name (`SegmentStore` uses it too)
- **Segments are per track** — keyed by Spotify track id. With no track id (ad, podcast, local file) the values stay in memory and are not saved; a track without a stored segment starts at from = 0:00, to unset. MARK uses the locally extrapolated position minus `REACTION_OFFSET_MS` (300 ms), so it needs a synced playback state ("Open Spotify on a device" otherwise)
- **Development mode** — a Spotify app in development mode requires the app owner to have Premium and allows at most 5 allowlisted users

## Spotify API Scopes

`user-modify-playback-state` — seek, play, pause  
`user-read-playback-state` — read current position (needed for auto-stop on "to" time)
