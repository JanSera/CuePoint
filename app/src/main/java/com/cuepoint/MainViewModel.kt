package com.cuepoint

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
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

// Which end of the segment the crown / bezel and the +/- buttons act on.
enum class SegmentField { From, To }

// MARK subtracts this from the extrapolated position: the finger lags the music by roughly this much.
const val REACTION_OFFSET_MS = 300L

// C# analogue: record with INotifyPropertyChanged backing
data class CuePointUiState(
    // The current track's segment (see SegmentStore.kt for the rules). No segment yet = 0:00 → no "to".
    val fromMs: Long = 0L,
    val toMs: Long? = null,
    val selectedField: SegmentField = SegmentField.From,
    val isPlaying: Boolean = false,
    val playback: PlaybackState? = null,   // last synced snapshot; null = nothing loaded / not synced yet
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
    private val spotifyApi: SpotifyClient,
    private val spotifyAuth: SpotifyAuth,  // must hold the Application context, not an Activity
    private val segmentStore: SegmentStore
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

    // Track whose segment is currently shown, and the key segment edits are saved under.
    // Null until the first synced track. It is NOT reset by an ad/podcast (trackId null): the values
    // stay in memory, unsaved, and the same song coming back needs no reload. Main thread only.
    private var loadedTrackId: String? = null

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
            // The first ON_RESUME fired while Unknown (skipped), so sync here for the cold start.
            if (_uiState.value.authState == AuthState.LoggedIn) syncPlayback()
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
                syncPlayback()   // ON_RESUME fired on the redirect, before the exchange finished (still LoggedOut), so sync explicitly
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
            syncPlayback()
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

    // Pulls the real player state (button icon, track, device). Called on resume and after login,
    // so the UI is right even if someone used Spotify on the phone in the meantime.
    // silent = the background 10 s loop (see CuePointApp): state effects apply, error text is rate-limited.
    fun syncPlayback(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                syncPlaybackOnce(silent)
            } catch (e: Exception) {
                if (!silent || !lastSyncFailed) showError("No connection")
                lastSyncFailed = true
            }
        }
    }

    // Set by any failed sync, cleared by the next success. The silent 10 s loop shows an error only
    // on the success-to-failure edge, so a dead connection does not flash a message every 10 s.
    private var lastSyncFailed = false

    private enum class SyncOutcome { Failed, Unchanged, TrackChanged }

    // One GET + state update; shared by syncPlayback() and resetToTime(). Shows its own error on failure.
    private suspend fun syncPlaybackOnce(silent: Boolean = false): SyncOutcome {
        when (val result = spotifyApi.getPlaybackState()) {
            is ApiResult.Ok -> {
                val state = result.value
                // A different song: swap in its stored segment (or the empty default). A null
                // trackId (ad, podcast, local file) or no player leaves the values alone.
                val trackId = state?.trackId
                val changed = state != null && trackId != null && trackId != loadedTrackId
                // A failing store must not look like a network error: log it and fall back to the
                // default segment. loadedTrackId still moves on, so edits are never saved under the old track.
                val stored = if (changed) {
                    try {
                        segmentStore.load(trackId!!)
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Loading segment failed: ${e.message}")
                        null
                    }
                } else null
                if (changed) loadedTrackId = trackId
                _uiState.update {
                    val synced = it.copy(playback = state, isPlaying = state?.isPlaying ?: false)
                    if (!changed) synced else {
                        // moveFrom re-validates the stored values against this track's duration.
                        val from = stored?.fromMs ?: 0L
                        val (f, t) = moveFrom(from, stored?.toMs, from, state!!.durationMs)
                        synced.copy(fromMs = f, toMs = t)
                    }
                }
                lastSyncFailed = false
                return if (changed) SyncOutcome.TrackChanged else SyncOutcome.Unchanged
            }
            else -> {
                // Only "no device" proves the snapshot is stale. On Network/Other keep the last
                // known state and just show the error.
                if (result == ApiResult.NoActiveDevice) {
                    _uiState.update { it.copy(playback = null, isPlaying = false) }
                }
                // Silent: state effects above/below still apply (Unauthorized logs out), only the text is skipped.
                if (!silent || result == ApiResult.Unauthorized || !lastSyncFailed) {
                    handleApiFailure(result, "Could not read playback")
                }
                lastSyncFailed = true
                return SyncOutcome.Failed
            }
        }
    }

    // ON_RESUME hook. LoggedIn only: before that there is no token, and init/login sync themselves.
    fun onResume() {
        if (_uiState.value.authState == AuthState.LoggedIn) syncPlayback()
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            vibrate(30)
            try {
                // isPlaying is kept in sync with Spotify (see syncPlayback), so it is a reliable toggle basis.
                val wasPlaying = _uiState.value.isPlaying
                val result = if (wasPlaying) spotifyApi.pause() else spotifyApi.play()
                if (result is ApiResult.Ok) {
                    // Re-anchor the local snapshot so the position stays right without another GET:
                    // freeze/resume from where it is extrapolated to right now.
                    // `now` is taken after the PUT returned, so the position overshoots by about
                    // RTT/2; Phase 5's RTT estimate can correct it.
                    val now = SystemClock.elapsedRealtime()
                    _uiState.update {
                        it.copy(
                            isPlaying = !wasPlaying,
                            playback = it.playback?.let { p ->
                                p.copy(isPlaying = !wasPlaying, progressMs = p.positionAt(now), sampledAtElapsedMs = now)
                            }
                        )
                    }
                } else {
                    handleApiFailure(result, "Playback failed")
                }
            } catch (e: Exception) {
                showError("No connection")
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
                // The song may have changed on the phone since the last sync. Check first: seeking a
                // new song to the old song's "from" would be wrong. On a change, load its segment
                // and stop; the user taps Reset again with the right values showing.
                when (syncPlaybackOnce()) {
                    SyncOutcome.Failed -> return@launch
                    SyncOutcome.TrackChanged -> {
                        showError("New song — segment loaded")
                        return@launch
                    }
                    SyncOutcome.Unchanged -> Unit
                }

                // Pause before seeking to avoid a race condition where Spotify
                // resumes mid-seek and ignores the position. We still always pause even though
                // isPlaying is synced now: pause is idempotent (an already-paused 403 counts as OK)
                // and cheap, and it covers state that changed since the last sync.
                val pauseResult = spotifyApi.pause()
                if (pauseResult !is ApiResult.Ok) {
                    handleApiFailure(pauseResult, "Could not pause")
                    return@launch
                }

                val fromMs = _uiState.value.fromMs
                val seekResult = spotifyApi.seek(fromMs)
                if (seekResult is ApiResult.Ok) {
                    // We paused and seeked, so the position is known exactly: no GET needed.
                    val now = SystemClock.elapsedRealtime()
                    _uiState.update {
                        it.copy(
                            isPlaying = false,
                            playback = it.playback?.copy(isPlaying = false, progressMs = fromMs, sampledAtElapsedMs = now)
                        )
                    }
                } else {
                    handleApiFailure(seekResult, "Seek failed")
                }
            } catch (e: Exception) {
                showError("No connection")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // --- Segment editing ---
    // Every change ends in setField/clearTo so validation (SegmentStore.kt) and saving happen in one place.

    fun selectField(field: SegmentField) {
        _uiState.update { it.copy(selectedField = field) }
    }

    // MARK: take the live position (extrapolated locally, no network round trip) minus the reaction offset.
    fun mark(field: SegmentField) {
        selectField(field)
        val playback = _uiState.value.playback
        if (playback == null) {
            showError("Open Spotify on a device")
            return
        }
        vibrate(30)
        setField(field, playback.positionAt(SystemClock.elapsedRealtime()) - REACTION_OFFSET_MS)
    }

    // +/- and the crown: move the selected field by deltaMs.
    fun nudge(deltaMs: Long) {
        val s = _uiState.value
        when (s.selectedField) {
            SegmentField.From -> setField(SegmentField.From, s.fromMs + deltaMs)
            SegmentField.To -> when {
                s.toMs != null -> setField(SegmentField.To, s.toMs + deltaMs)
                // "to" is unset: the first + creates the shortest valid segment, - has nothing to do.
                deltaMs > 0 -> setField(SegmentField.To, s.fromMs + MIN_SEGMENT_MS)
            }
        }
    }

    fun clearTo() {
        _uiState.update { it.copy(toMs = null) }
        persistSegment()
    }

    private fun setField(field: SegmentField, ms: Long) {
        val s = _uiState.value
        val duration = s.playback?.durationMs ?: 0L
        when (field) {
            SegmentField.From -> {
                val (from, to) = moveFrom(s.fromMs, s.toMs, ms, duration)
                _uiState.update { it.copy(fromMs = from, toMs = to) }
                if (s.toMs != null && to == null) showError("To cleared")   // the rule in SegmentStore.kt
            }
            SegmentField.To -> {
                val to = moveTo(s.fromMs, ms, duration)
                if (to == null) {
                    showError("To must be after From")
                    return
                }
                _uiState.update { it.copy(toMs = to) }
            }
        }
        persistSegment()
    }

    // Saves after every change. No track id (ad, nothing synced yet) = nothing to key it under.
    // Launches start in order and DataStore applies edits in call order, so the last change wins.
    private fun persistSegment() {
        val trackId = loadedTrackId ?: return
        val s = _uiState.value
        val segment = Segment(trackId, s.fromMs, s.toMs)
        viewModelScope.launch {
            try {
                segmentStore.save(segment)
            } catch (e: Exception) {
                Log.w("MainViewModel", "Saving segment failed: ${e.message}")
            }
        }
    }

    // --- Error handling ---

    // One line per failure kind. Unauthorized means the client already cleared the tokens,
    // so go to login; Other gets the caller's action-specific text.
    private fun handleApiFailure(result: ApiResult<*>, fallbackMessage: String) {
        when (result) {
            is ApiResult.Ok -> Unit   // not a failure
            ApiResult.Unauthorized ->
                _uiState.update { it.copy(authState = AuthState.LoggedOut, loginError = "Session expired") }
            ApiResult.NoActiveDevice -> showError("Open Spotify on a device")
            ApiResult.PremiumRequired -> showError("Spotify Premium required")
            ApiResult.Network -> showError("No connection")
            is ApiResult.Other -> showError(fallbackMessage)
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
        fun factory(app: Application, spotifyApi: SpotifyClient, spotifyAuth: SpotifyAuth, segmentStore: SegmentStore) =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(app, spotifyApi, spotifyAuth, segmentStore) as T
                }
            }
    }
}
