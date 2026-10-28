package com.cuepoint

import android.app.Application
import android.content.Context
import android.net.Uri
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

// Unknown until the stored-token check finishes, so the login screen does not flash at startup.
// C# analogue: a three-value enum instead of a nullable bool.
enum class AuthState { Unknown, LoggedIn, LoggedOut }

// C# analogue: record with INotifyPropertyChanged backing
data class CuePointUiState(
    val timeMs: Long = 73_000L,         // 1:13 default
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val authState: AuthState = AuthState.Unknown,
    // Shown on the login screen. Unlike errorMessage it does not auto-clear: it stays until the
    // next login tap, so a glance at the watch later still explains what happened.
    val loginError: String? = null
)

// AndroidViewModel (not plain ViewModel) because we need Application context for haptics.
// C# analogue: ViewModel : INotifyPropertyChanged with ObservableProperty<T> fields
class MainViewModel(
    app: Application,
    private val spotifyApi: SpotifyApi,
    private val spotifyAuth: SpotifyAuth   // must hold the Application context, not an Activity
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(CuePointUiState())
    val uiState: StateFlow<CuePointUiState> = _uiState.asStateFlow()

    // Haptics — VibratorManager is the modern API (API 31+), Vibrator is the legacy fallback
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var errorClearJob: Job? = null

    // True while a callback is being exchanged, so init does not resolve to LoggedOut meanwhile
    // (that would flash the login screen and invite a second tap). Main thread only.
    private var callbackInFlight = false

    init {
        // Check for a stored token on startup — determines whether to show login screen
        viewModelScope.launch {
            val loggedIn = spotifyApi.isLoggedIn()
            // Only resolve Unknown: a callback handled meanwhile may already have decided.
            if (callbackInFlight) return@launch   // handleAuthCallback will resolve the state
            _uiState.update {
                if (it.authState != AuthState.Unknown) it
                else it.copy(authState = if (loggedIn) AuthState.LoggedIn else AuthState.LoggedOut)
            }
        }
    }

    // --- Auth ---

    // Login tap: opens the Spotify login on the phone. The Activity is not involved, so nothing
    // here can leak it (SpotifyAuth uses the Application context).
    fun login() {
        _uiState.update { it.copy(loginError = null) }
        viewModelScope.launch {
            if (!spotifyAuth.openAuthInBrowser()) {
                _uiState.update { it.copy(loginError = "No paired phone found") }
            }
        }
    }

    // Called by MainActivity (cold start or onNewIntent) with the cuepoint://callback Uri.
    fun handleAuthCallback(uri: Uri) {
        callbackInFlight = true   // set synchronously, before init's token check can finish
        viewModelScope.launch {
            try {
                processCallback(uri)
            } finally {
                callbackInFlight = false   // only after state is resolved, tokens saved included
            }
        }
    }

    private suspend fun processCallback(uri: Uri) {
        val result = spotifyAuth.handleCallback(uri)
        when (result) {
            is SpotifyAuth.AuthResult.Success -> {
                spotifyApi.saveToken(result.tokens.accessToken)
                result.tokens.refreshToken?.let { spotifyApi.saveRefreshToken(it) }
                _uiState.update { it.copy(authState = AuthState.LoggedIn, loginError = null) }
            }
            SpotifyAuth.AuthResult.Cancelled -> failLogin("Login cancelled")
            SpotifyAuth.AuthResult.Failed -> failLogin("Login failed")
        }
    }

    // Decides from storage, not UI state: on a cold start authState is still Unknown (init skipped
    // while the callback was in flight), and a stray callback must not log out a user whose
    // tokens are still valid.
    private suspend fun failLogin(message: String) {
        if (spotifyApi.isLoggedIn()) {
            _uiState.update { it.copy(authState = AuthState.LoggedIn) }   // stray callback: keep session, no error
        } else {
            _uiState.update { it.copy(authState = AuthState.LoggedOut, loginError = message) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            spotifyApi.clearTokens()
            _uiState.update { it.copy(authState = AuthState.LoggedOut, loginError = null) }
        }
    }

    // --- Spotify actions ---

    fun togglePlayPause() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            vibrate(30)
            try {
                val wasPlaying = _uiState.value.isPlaying
                val result = if (wasPlaying) spotifyApi.pause() else spotifyApi.play()
                if (result == CallResult.OK) {
                    _uiState.update { it.copy(isPlaying = !wasPlaying) }
                } else {
                    handleApiFailure(result, "Playback failed")
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
                // Pause before seeking to avoid a race condition where Spotify
                // resumes mid-seek and ignores the position. Always pause: isPlaying isn't
                // synced with Spotify yet (Phase 3) so it can be stale, and pause is idempotent
                // (an already-paused 403 counts as OK). Skipping it can return once state is synced.
                val pauseResult = spotifyApi.pause()
                if (pauseResult != CallResult.OK) {
                    handleApiFailure(pauseResult, "Could not pause")
                    return@launch
                }

                val seekResult = spotifyApi.seek(_uiState.value.timeMs)
                if (seekResult == CallResult.OK) {
                    _uiState.update { it.copy(isPlaying = false) }
                } else {
                    handleApiFailure(seekResult, "Seek failed")
                }
            } catch (e: Exception) {
                showError("Network error")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // --- Time adjustment (used by InlineSlider in current UI; replaced by rotary in Step 5) ---

    fun setMinutes(m: Int) {
        val s = (_uiState.value.timeMs % 60_000L) / 1_000L
        _uiState.update { it.copy(timeMs = (m * 60_000L + s * 1_000L).coerceIn(0L, 600_000L)) }
    }

    fun setSeconds(s: Int) {
        val m = _uiState.value.timeMs / 60_000L
        _uiState.update { it.copy(timeMs = (m * 60_000L + s * 1_000L).coerceIn(0L, 600_000L)) }
    }

    // --- Error handling ---

    // Network errors get their own message; otherwise, if the tokens were cleared
    // (refresh + retry both got 401), go to login.
    private suspend fun handleApiFailure(result: CallResult, fallbackMessage: String) {
        if (result == CallResult.NETWORK) {
            showError("Network error")
        } else if (!spotifyApi.isLoggedIn()) {
            _uiState.update { it.copy(authState = AuthState.LoggedOut, loginError = "Session expired") }
        } else {
            showError(fallbackMessage)
        }
    }

    fun showError(message: String) {
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

    // Factory needed because the constructor takes a non-Application parameter (spotifyApi).
    // C# analogue: typed factory registered with an IoC container.
    companion object {
        fun factory(app: Application, spotifyApi: SpotifyApi, spotifyAuth: SpotifyAuth) =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(app, spotifyApi, spotifyAuth) as T
                }
            }
    }
}
