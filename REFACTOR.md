# CuePoint — Incremental Refactor Instructions

Refactoring from 3-file no-ViewModel to MVVM with HorizontalPager, rotary crown input, and full Wear OS UX polish. Each step is independently buildable and testable.

## Design Decisions

**Keep OkHttp** — 3 endpoints don't justify adding Retrofit + Gson. Add `play()`/`pause()` with a shared helper method.

**Keep DataStore** — EncryptedSharedPreferences `1.1.0-alpha06` is unstable and has Tink conflicts on API 32+. DataStore works fine for short-lived (1-hour) access tokens. Revisit if refresh tokens are added.

**Evolve existing files** — No parallel file replacements. `SpotifyAuth.kt` and `SpotifyApi.kt` are modified in place, not replaced by new `AuthManager.kt`/`SpotifyApiService.kt`.

**No "to" time / auto-stop** — Not needed for MVP. The instructor workflow is: Reset (pause + seek to start) → Play. Simple and sufficient.

---

## Step 1 — Fix manifest + clean unused dep

**Files:** `AndroidManifest.xml`, `app/build.gradle.kts`

### AndroidManifest.xml

Add `android:launchMode="singleTop"` to the `<activity>` element:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:taskAffinity="">
```

**Why:** Without `singleTop`, when Spotify redirects to `cuepoint://callback` after OAuth login, Android creates a *second* `MainActivity` instance instead of calling `onNewIntent` on the existing one — the token exchange never completes.

### app/build.gradle.kts

Remove the unused `wear-phone-interactions` dependency:

```kotlin
// DELETE this line:
implementation("androidx.wear:wear-phone-interactions:1.0.1")
```

**Why:** This dependency is never imported anywhere in the codebase.

### Verify

```bash
./gradlew assembleDebug
```

- [ ] Done

---

## Step 2 — Wire up `onNewIntent` for OAuth callback

**Files:** `MainActivity.kt`

### Changes

1. Move `SpotifyApi` and add `SpotifyAuth` as class-level `lateinit var` properties (so `onNewIntent` can access them).
2. Override `onNewIntent` to handle the OAuth callback.

```kotlin
class MainActivity : ComponentActivity() {

    private lateinit var spotifyApi: SpotifyApi
    private lateinit var spotifyAuth: SpotifyAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotifyApi = SpotifyApi(this)
        spotifyAuth = SpotifyAuth(this)

        setContent {
            CuePointApp(spotifyApi)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        if (uri.scheme == "cuepoint" && uri.host == "callback") {
            lifecycleScope.launch {
                val token = spotifyAuth.handleCallback(uri)
                if (token != null) {
                    spotifyApi.saveToken(token)
                }
            }
        }
    }
}
```

**New imports needed:**
```kotlin
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
```

**Why:** Completes the OAuth flow end-to-end for the first time. Without this, `handleCallback()` is never called after the Spotify redirect.

### Verify

```bash
./gradlew assembleDebug
```

Test OAuth round-trip or simulate with ADB:
```bash
adb shell am start -W -a android.intent.action.VIEW -d "cuepoint://callback?code=test123" com.cuepoint
```

- [ ] Done

---

## Step 3 — Add `play()` and `pause()` to SpotifyApi

**Files:** `SpotifyApi.kt`

### Changes

Extract shared helper and add two new methods:

```kotlin
// Add this private helper to reduce duplication across seek/play/pause
private suspend fun spotifyPut(url: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val token = getToken() ?: return@withContext false

        val request = Request.Builder()
            .url(url)
            .put(ByteArray(0).toRequestBody(null))
            .addHeader("Authorization", "Bearer $token")
            .build()

        val response = httpClient.newCall(request).execute()

        when (response.code) {
            200, 204 -> true
            401 -> { clearToken(); false }
            else -> {
                println("Spotify API failed: ${response.code} ${response.body?.string()}")
                false
            }
        }
    } catch (e: Exception) {
        println("Spotify API error: ${e.message}")
        false
    }
}

suspend fun seek(positionMs: Long): Boolean =
    spotifyPut("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")

suspend fun play(): Boolean =
    spotifyPut("https://api.spotify.com/v1/me/player/play")

suspend fun pause(): Boolean =
    spotifyPut("https://api.spotify.com/v1/me/player/pause")
```

**Note:** `play()` returns 200 (not 204) when resuming — the `when` covers both.

Refactor existing `seek()` to use the shared helper. The old `seek()` body is replaced entirely.

### Verify

```bash
./gradlew assembleDebug
```

Test with a pasted `TEST_TOKEN` — call `play()` and `pause()` and verify Spotify responds.

- [ ] Done

---

## Step 4 — Add ViewModel layer

**Files:** `app/build.gradle.kts`, new `MainViewModel.kt`, `MainActivity.kt`

### app/build.gradle.kts

Add lifecycle dependencies:

```kotlin
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
```

### Create `app/src/main/java/com/cuepoint/MainViewModel.kt`

```kotlin
package com.cuepoint

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// C# analogue: ViewModel : INotifyPropertyChanged with ObservableProperty<T> fields
data class CuePointUiState(
    val timeMs: Long = 73_000L,        // 1:13 default
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel(
    app: Application,
    private val spotifyApi: SpotifyApi
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(CuePointUiState())
    val uiState: StateFlow<CuePointUiState> = _uiState.asStateFlow()

    // Haptics
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var errorClearJob: Job? = null

    // --- Spotify actions ---

    fun togglePlayPause() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            vibrate(30)
            try {
                val isPlaying = _uiState.value.isPlaying
                val success = if (isPlaying) spotifyApi.pause() else spotifyApi.play()
                if (success) {
                    _uiState.update { it.copy(isPlaying = !isPlaying) }
                } else {
                    showError("Playback failed")
                }
            } catch (e: Exception) {
                showError("Network error")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun resetToTime() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            vibrate(30)
            try {
                // Must pause before seeking to avoid race condition
                val pauseOk = spotifyApi.pause()
                if (!pauseOk) {
                    showError("Pause failed")
                    return@launch
                }
                val seekOk = spotifyApi.seek(_uiState.value.timeMs)
                if (seekOk) {
                    _uiState.update { it.copy(isPlaying = false) }
                } else {
                    showError("Seek failed")
                }
            } catch (e: Exception) {
                showError("Network error")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // --- Time adjustment (for InlineSlider, replaced by rotary in Step 5) ---

    fun setMinutes(m: Int) {
        _uiState.update { it.copy(timeMs = (m * 60_000L + it.timeMs % 60_000L).coerceIn(0L, 600_000L)) }
    }

    fun setSeconds(s: Int) {
        _uiState.update { it.copy(timeMs = (it.timeMs / 60_000L * 60_000L + s * 1_000L).coerceIn(0L, 600_000L)) }
    }

    // --- Error handling ---

    private fun showError(message: String) {
        errorClearJob?.cancel()
        _uiState.update { it.copy(errorMessage = message) }
        errorClearJob = viewModelScope.launch {
            delay(3_000)
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    // --- Haptics ---

    private fun vibrate(durationMs: Long) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // --- Factory ---

    companion object {
        fun factory(app: Application, spotifyApi: SpotifyApi) =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(app, spotifyApi) as T
                }
            }
    }
}
```

### Update `MainActivity.kt`

Replace `remember {}` state with ViewModel. Keep the existing InlineSlider UI — only change where state comes from:

```kotlin
class MainActivity : ComponentActivity() {

    private lateinit var spotifyApi: SpotifyApi
    private lateinit var spotifyAuth: SpotifyAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotifyApi = SpotifyApi(this)
        spotifyAuth = SpotifyAuth(this)

        setContent {
            val vm: MainViewModel = viewModel(
                factory = MainViewModel.factory(application, spotifyApi)
            )
            CuePointApp(vm)
        }
    }

    // onNewIntent stays the same as Step 2
}

@Composable
fun CuePointApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    val minutes = (uiState.timeMs / 60_000).toInt()
    val seconds = ((uiState.timeMs % 60_000) / 1_000).toInt()

    // Keep existing Scaffold + InlineSlider UI
    // Replace remember-based state reads with uiState fields
    // Replace scope.launch { spotifyApi.seek(...) } with viewModel.resetToTime()
    // Add play/pause button using viewModel.togglePlayPause()
    // Show uiState.errorMessage in red text (auto-clears after 3s)
    // Show CircularProgressIndicator when uiState.isLoading
}
```

**New imports for MainActivity.kt:**
```kotlin
import androidx.lifecycle.viewmodel.compose.viewModel
```

**Key:** This step keeps the same visual layout. The only change is where state lives and how API calls are triggered.

### Verify

```bash
./gradlew assembleDebug
```

- [ ] App works as before visually
- [ ] State survives screen rotation (if applicable)
- [ ] Error messages appear and auto-clear after 3s
- [ ] Haptic feedback on button press

- [ ] Done

---

## Step 5 — Replace InlineSlider with rotary crown input

**Files:** `MainViewModel.kt`, `MainActivity.kt`

### Add to `MainViewModel.kt`

```kotlin
// Rotary accumulator state (add as class fields)
private var rotaryAccumulator = 0f
private val SMALL_THRESHOLD = 20f   // px → ±1 second
private val LARGE_THRESHOLD = 80f   // px → ±10 seconds

fun onRotaryScroll(delta: Float) {
    // Reset accumulator on direction reversal
    if (rotaryAccumulator != 0f && (delta > 0) != (rotaryAccumulator > 0)) {
        rotaryAccumulator = 0f
    }
    rotaryAccumulator += delta
    val absAcc = kotlin.math.abs(rotaryAccumulator)

    val stepMs: Long = when {
        absAcc >= LARGE_THRESHOLD -> 10_000L.also { rotaryAccumulator = 0f }
        absAcc >= SMALL_THRESHOLD ->  1_000L.also { rotaryAccumulator = 0f }
        else -> return
    }
    val sign = if (delta > 0) 1L else -1L
    _uiState.update { it.copy(timeMs = (it.timeMs + sign * stepMs).coerceIn(0L, 600_000L)) }
    vibrate(15)  // short tick haptic
}
```

Remove the `setMinutes()` and `setSeconds()` methods (no longer needed).

### Update `MainActivity.kt` — replace InlineSlider with rotary display

Replace the InlineSlider row and picker with:

```kotlin
@Composable
fun CuePointApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onRotaryScrollEvent { event ->
                        viewModel.onRotaryScroll(event.verticalScrollPixels)
                        true
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Large time display
                    Text(
                        text = "%d:%02d".format(
                            uiState.timeMs / 60_000,
                            (uiState.timeMs % 60_000) / 1_000
                        ),
                        fontSize = 48.sp,
                        color = Color(0xFF1DB954),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Use bezel/crown to adjust",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))

                    // Keep seek button + play/pause button below
                    // (These move to page 2 in Step 6)
                }
            }
        }
    }
}
```

**New imports:**
```kotlin
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
```

### Verify

```bash
./gradlew assembleDebug
```

- [ ] Crown/bezel rotation adjusts time by 1s (small) and 10s (fast)
- [ ] Direction reversal is instant (no dead zone)
- [ ] Time clamps at 0:00 and 10:00
- [ ] Haptic tick on each step

- [ ] Done

---

## Step 6 — Add HorizontalPager (2 pages)

**Files:** `MainActivity.kt`

### Changes

Split the single screen into a `HorizontalPager` with two pages:
- **Page 0 (Setup):** rotary time picker from Step 5
- **Page 1 (Control):** Play/Pause + Reset buttons

Use the Wear OS-specific pager (already in `wear.compose.foundation:1.3.0`):

```kotlin
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.PageIndicatorState
```

**Do NOT use** `androidx.compose.foundation.pager` — it doesn't handle round displays correctly.

### Structure

```kotlin
@Composable
fun CuePointApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    // Keep screen on while on Control page
    val activity = LocalContext.current as Activity
    DisposableEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> SetupScreen(uiState, viewModel::onRotaryScroll)
                    1 -> ControlScreen(
                        uiState = uiState,
                        onPlayPause = viewModel::togglePlayPause,
                        onReset = viewModel::resetToTime,
                        onBack = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }
                    )
                }
            }

            // Page indicator dots
            HorizontalPageIndicator(
                pageIndicatorState = object : PageIndicatorState {
                    override val pageCount: Int get() = 2
                    override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
                    override val selectedPage: Int get() = pagerState.currentPage
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            )
        }
    }
}
```

### SetupScreen (extract from existing)

The time display + rotary input from Step 5 becomes its own composable. The `focusRequester` must be re-requested when navigating back to this page:

```kotlin
@Composable
fun SetupScreen(uiState: CuePointUiState, onRotaryScroll: (Float) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    onRotaryScroll(event.verticalScrollPixels)
                    true
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%d:%02d".format(uiState.timeMs / 60_000, (uiState.timeMs % 60_000) / 1_000),
                    fontSize = 48.sp,
                    color = Color(0xFF1DB954),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text("Use bezel/crown to adjust", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}
```

### ControlScreen (new)

```kotlin
@Composable
fun ControlScreen(
    uiState: CuePointUiState,
    onPlayPause: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }

    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Pause
                Button(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1DB954))
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Reset (pause + seek to set time)
                Button(
                    onClick = onReset,
                    modifier = Modifier.size(64.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.Replay, "Reset", Modifier.size(32.dp))
                }
            }

            // Loading overlay
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(80.dp), strokeWidth = 3.dp)
            }

            // Error message
            uiState.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
                )
            }
        }
    }
}
```

**New imports:**
```kotlin
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.PageIndicatorState
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
```

### Verify

```bash
./gradlew assembleDebug
```

- [ ] Swipe left → Control screen, swipe right → Setup screen
- [ ] Page indicator dots visible at bottom
- [ ] Back gesture on Control → goes to Setup (not exit)
- [ ] Screen stays on while on Control screen
- [ ] Play/Pause and Reset buttons function correctly
- [ ] Loading spinner shows during API calls
- [ ] Error message appears in red, disappears after ~3s

- [ ] Done

---

## Step 7 — Add login screen + 401 redirect

**Files:** `MainViewModel.kt`, `MainActivity.kt`

### Add to `CuePointUiState`

```kotlin
data class CuePointUiState(
    val timeMs: Long = 73_000L,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false     // NEW
)
```

### Add to `MainViewModel`

```kotlin
// Check login on init
init {
    viewModelScope.launch {
        _uiState.update { it.copy(isLoggedIn = spotifyApi.isLoggedIn()) }
    }
}

// Call after successful token exchange in onNewIntent
fun onLoginComplete() {
    _uiState.update { it.copy(isLoggedIn = true) }
}
```

Update the error handling in `togglePlayPause()` / `resetToTime()` — when `spotifyApi` returns false and the token was cleared (401), set `isLoggedIn = false`:

```kotlin
// In spotifyApi, make seek/play/pause return an Int status code (or a sealed Result)
// so the ViewModel can distinguish 401 from other failures.
// Alternative: check spotifyApi.isLoggedIn() after a failure.
```

**Design choice:** The simplest approach is to check `spotifyApi.isLoggedIn()` after any API failure:

```kotlin
if (!success) {
    if (!spotifyApi.isLoggedIn()) {
        _uiState.update { it.copy(isLoggedIn = false, errorMessage = "Session expired") }
    } else {
        showError("Playback failed")
    }
}
```

### Add to `MainActivity.kt`

```kotlin
@Composable
fun CuePointApp(viewModel: MainViewModel, onLogin: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme {
        if (!uiState.isLoggedIn) {
            LoginScreen(onLogin)
        } else {
            // Existing pager code from Step 6
        }
    }
}

@Composable
fun LoginScreen(onLogin: () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CuePoint", fontSize = 20.sp, color = Color(0xFF1DB954))
                Spacer(Modifier.height(16.dp))
                Chip(
                    onClick = onLogin,
                    label = { Text("Login with Spotify") },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFF1DB954)
                    )
                )
            }
        }
    }
}
```

Wire up in `MainActivity`:

```kotlin
setContent {
    val vm: MainViewModel = viewModel(
        factory = MainViewModel.factory(application, spotifyApi)
    )
    CuePointApp(vm, onLogin = { spotifyAuth.openAuthInBrowser() })
}
```

Update `onNewIntent` to notify ViewModel:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data ?: return
    if (uri.scheme == "cuepoint" && uri.host == "callback") {
        lifecycleScope.launch {
            val token = spotifyAuth.handleCallback(uri)
            if (token != null) {
                spotifyApi.saveToken(token)
                // Need a reference to the ViewModel here — see note below
            }
        }
    }
}
```

**Note on ViewModel access from `onNewIntent`:** The ViewModel is created inside `setContent`. To access it from `onNewIntent`, either:
1. Store a reference to it as a class-level property, or
2. Use a shared `MutableStateFlow<Boolean>` that both `onNewIntent` and the ViewModel observe.

Option 1 (simplest):
```kotlin
private var viewModel: MainViewModel? = null

override fun onCreate(savedInstanceState: Bundle?) {
    // ...
    setContent {
        val vm: MainViewModel = viewModel(
            factory = MainViewModel.factory(application, spotifyApi)
        )
        viewModel = vm  // capture reference
        CuePointApp(vm, onLogin = { spotifyAuth.openAuthInBrowser() })
    }
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // ...
    lifecycleScope.launch {
        val token = spotifyAuth.handleCallback(uri)
        if (token != null) {
            spotifyApi.saveToken(token)
            viewModel?.onLoginComplete()
        }
    }
}
```

### Verify

```bash
./gradlew assembleDebug
```

- [ ] App starts with Login screen when no token is saved
- [ ] "Login with Spotify" opens browser on phone
- [ ] After OAuth completes, app transitions to Setup/Control pager
- [ ] Paste a test token into `TEST_TOKEN` → app skips login screen
- [ ] Force a 401 (use expired token) → app navigates back to Login screen with "Session expired"

- [ ] Done

---

## Summary — File Map

| File | Steps Modified |
|------|---------------|
| `app/src/main/AndroidManifest.xml` | 1 |
| `app/build.gradle.kts` | 1, 4 |
| `app/src/main/java/com/cuepoint/MainActivity.kt` | 2, 4, 5, 6, 7 |
| `app/src/main/java/com/cuepoint/SpotifyApi.kt` | 3 |
| `app/src/main/java/com/cuepoint/MainViewModel.kt` (new) | 4, 5, 7 |
| `app/src/main/java/com/cuepoint/SpotifyAuth.kt` | unchanged |
