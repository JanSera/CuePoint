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

// C# analogue: record with INotifyPropertyChanged backing
data class CuePointUiState(
    val timeMs: Long = 73_000L,         // 1:13 default
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false      // false until token check completes on init
)

// AndroidViewModel (not plain ViewModel) because we need Application context for haptics.
// C# analogue: ViewModel : INotifyPropertyChanged with ObservableProperty<T> fields
class MainViewModel(
    app: Application,
    private val spotifyApi: SpotifyApi
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

    init {
        // Check for a stored token on startup — determines whether to show login screen
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggedIn = spotifyApi.isLoggedIn()) }
        }
    }

    // Called from MainActivity.onNewIntent() after a successful OAuth token exchange
    fun onLoginComplete() {
        _uiState.update { it.copy(isLoggedIn = true) }
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
            _uiState.update { it.copy(isLoggedIn = false, errorMessage = "Session expired") }
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
        fun factory(app: Application, spotifyApi: SpotifyApi) =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(app, spotifyApi) as T
                }
            }
    }
}
