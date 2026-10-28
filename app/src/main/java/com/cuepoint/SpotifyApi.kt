package com.cuepoint

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Spotify Web API client.
 *
 * For .NET developers:
 * - OkHttpClient ≈ HttpClient
 * - DataStore ≈ SecureStorage / Preferences
 * - suspend fun ≈ async Task<T>
 * - withContext(Dispatchers.IO) ≈ Task.Run() on a background thread
 */

// Extension on Context to expose DataStore (singleton, similar to DI registration)
// internal: SpotifyAuth keeps its pending-login values in the same file. A second
// preferencesDataStore with the same name would crash.
internal val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "spotify_prefs")

private const val TAG = "SpotifyApi"

// One client for the whole app (SpotifyAuth uses it too). It owns the connection pool,
// so like HttpClient in .NET you want a single instance.
internal val httpClient = OkHttpClient()

// Top level (not per SpotifyApi instance): the Activity creates a new SpotifyApi on every
// onCreate, and refreshes must stay serialised across all of them.
private val refreshMutex = Mutex()

// sampledAtElapsedMs: midpoint of the request round trip (only filled in by send()).
private class RawResponse(val code: Int, val body: String, val sampledAtElapsedMs: Long = 0L)

// Only Rejected means the refresh token is dead and the user must log in again.
private sealed interface RefreshResult {
    data class Token(val value: String) : RefreshResult
    data object Rejected : RefreshResult      // 400/401 invalid_grant, or no refresh token stored
    data object NetworkError : RefreshResult  // keep tokens, report "Network error"
    data object Failed : RefreshResult        // 5xx etc. — keep tokens
}

/**
 * Outcome of a Spotify call. Each failure the UI words differently gets its own case.
 * C# analogue: a hand-rolled Result<T> / discriminated union; `when` over it must be exhaustive.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>      // refresh failed or retry still 401: tokens cleared, go to login
    data object NoActiveDevice : ApiResult<Nothing>    // 404
    data object PremiumRequired : ApiResult<Nothing>   // 403 with PREMIUM_REQUIRED only; "Restriction violated" is Ok for play/pause, Other elsewhere
    data object Network : ApiResult<Nothing>           // no connection
    data class Other(val code: Int) : ApiResult<Nothing>
}

/** What GET /v1/me/player says is happening right now. */
data class PlaybackState(
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    // Null track fields: the player is active but has no track (ad, some podcasts). is_playing is still valid.
    val trackId: String?,
    val trackName: String?,
    val deviceName: String,
    // SystemClock.elapsedRealtime() at the midpoint of the request's round trip. The position
    // now is progressMs + (elapsedRealtime() - sampledAtElapsedMs) while playing.
    val sampledAtElapsedMs: Long
)

/**
 * What the ViewModel needs from Spotify; SpotifyApi is the real implementation, tests can fake it.
 * C# analogue: ISpotifyClient. The token methods are included (rather than a second interface)
 * because the VM is the only consumer and splitting four methods out would be more ceremony than value.
 */
interface SpotifyClient {
    suspend fun play(): ApiResult<Unit>
    suspend fun pause(): ApiResult<Unit>
    suspend fun seek(positionMs: Long): ApiResult<Unit>
    /** Ok(null) means no player at all (204 / empty body). An item-less state (ad, podcast) is Ok with null track fields. */
    suspend fun getPlaybackState(): ApiResult<PlaybackState?>

    suspend fun isLoggedIn(): Boolean
    suspend fun clearTokens()
    suspend fun saveToken(token: String)
    suspend fun saveRefreshToken(token: String)
}

class SpotifyApi(private val context: Context) : SpotifyClient {

    companion object {
        private val TOKEN_KEY         = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")

        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        // =============================================================
        // FOR TESTING: Paste a temporary token from Spotify Developer Console here.
        // In production the token is obtained via OAuth (see SpotifyAuth.kt).
        //
        // How to get a test token:
        // 1. Go to https://developer.spotify.com/console/put-seek/
        // 2. Click "Get Token"
        // 3. Check scope: user-modify-playback-state
        // 4. Copy the token here (valid for ~1 hour)
        // =============================================================
        private const val TEST_TOKEN = ""  // Paste token here for testing
    }

    /** Seeks playback to [positionMs] milliseconds. Scope: user-modify-playback-state */
    override suspend fun seek(positionMs: Long): ApiResult<Unit> =
        put("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")

    /** Resumes playback on the active device. Scope: user-modify-playback-state */
    override suspend fun play(): ApiResult<Unit> {
        val response = execute("https://api.spotify.com/v1/me/player/play", put = true)
        // Playing an already-playing player is a 403 "Restriction violated"; the goal state ("playing") holds.
        if (response.code == 403 && response.body.contains("Restriction violated")) return ApiResult.Ok(Unit)
        return response.toUnitResult()
    }

    /**
     * Pauses playback on the active device. Scope: user-modify-playback-state
     *
     * Pausing an already-paused player returns 403 "Restriction violated" — the player is
     * already in the state we wanted, so that counts as success.
     */
    override suspend fun pause(): ApiResult<Unit> {
        val response = execute("https://api.spotify.com/v1/me/player/pause", put = true)
        // The message can mean other restrictions too, but the goal state ("not playing") holds either way.
        if (response.code == 403 && response.body.contains("Restriction violated")) return ApiResult.Ok(Unit)
        return response.toUnitResult()
    }

    /**
     * Spotify API: GET /v1/me/player. Scope: user-read-playback-state
     * 204 (no active device) → Ok(null). A null item (ads, some podcasts) → Ok with null track fields.
     */
    override suspend fun getPlaybackState(): ApiResult<PlaybackState?> {
        val response = execute("https://api.spotify.com/v1/me/player", put = false)
        if (response.code !in 200..299) return response.errorResult()
        if (response.body.isBlank()) return ApiResult.Ok(null)   // 204, or a 202 with no body
        return try {
            val json = JSONObject(response.body)
            val item = json.optJSONObject("item")   // null for ads / some podcasts, but is_playing is still valid
            // optString returns the literal "null" for a JSON null, so check isNull() first.
            val id = if (item == null || item.isNull("id")) null else item.optString("id").ifEmpty { null }
            val name = if (item == null || item.isNull("name")) null else item.optString("name")
            val device = json.optJSONObject("device")
            ApiResult.Ok(
                PlaybackState(
                    isPlaying = json.optBoolean("is_playing", false),
                    progressMs = json.optLong("progress_ms", 0L),
                    durationMs = item?.optLong("duration_ms", 0L) ?: 0L,
                    trackId = id,
                    trackName = if (id == null) null else name,
                    deviceName = if (device == null || device.isNull("name")) "" else device.optString("name"),
                    sampledAtElapsedMs = response.sampledAtElapsedMs
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "getPlaybackState parse error: ${e.message}")
            ApiResult.Other(response.code)
        }
    }

    private suspend fun put(url: String): ApiResult<Unit> = execute(url, put = true).toUnitResult()

    // Any 2xx is success — Spotify's player endpoints can answer 200, 202 or 204.
    private fun RawResponse.toUnitResult(): ApiResult<Unit> =
        if (code in 200..299) ApiResult.Ok(Unit) else errorResult()

    // By the time execute() returns a 401 the refresh path has already run (and cleared tokens),
    // so 401 here always means "go to login". The synthetic 503 for a failed refresh falls into Other.
    private fun RawResponse.errorResult(): ApiResult<Nothing> = when (code) {
        -1   -> ApiResult.Network
        401  -> ApiResult.Unauthorized
        // Only PREMIUM_REQUIRED means non-Premium; other 403s ("Restriction violated" on seek during an ad, ...) are Other.
        403  -> if (body.contains("PREMIUM_REQUIRED")) ApiResult.PremiumRequired else ApiResult.Other(403)
        404  -> ApiResult.NoActiveDevice
        else -> ApiResult.Other(code)
    }

    // Shared request helper. On 401, refreshes the token and retries once. Tokens are cleared
    // (forcing re-login) only if the retry is also 401; 404/403/network errors just fail.
    private suspend fun execute(url: String, put: Boolean): RawResponse = withContext(Dispatchers.IO) {
        val usedToken = getToken()
        val first = send(url, put, usedToken)
        if (first.code != 401) return@withContext first

        val newToken = when (val refreshed = refreshIfStale(usedToken)) {
            is RefreshResult.Token -> refreshed.value
            RefreshResult.Rejected -> {
                clearTokens()   // refresh token is dead, force re-login
                return@withContext first
            }
            RefreshResult.NetworkError -> return@withContext RawResponse(-1, "")
            RefreshResult.Failed -> return@withContext RawResponse(503, "")
        }

        val retry = send(url, put, newToken)
        if (retry.code == 401) clearTokens()   // still unauthorized after a fresh token
        retry
    }

    // Sends one request. Returns the status code and body, or code -1 on network error.
    // A missing token is reported as 401 so it flows into the refresh/re-login path.
    // response.use {} ≈ C# using — always releases the connection.
    private fun send(url: String, put: Boolean, token: String?): RawResponse {
        if (token == null) return RawResponse(401, "")
        val sentAt = SystemClock.elapsedRealtime()
        return try {
            val builder = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
            if (put) builder.put(ByteArray(0).toRequestBody(null)) else builder.get()
            httpClient.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) Log.w(TAG, "Spotify API failed [${response.code}]: $body")
                val receivedAt = SystemClock.elapsedRealtime()
                RawResponse(response.code, body, sentAt + (receivedAt - sentAt) / 2)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify API error: ${e.message}")
            RawResponse(-1, "")
        }
    }

    // Serialises refreshes: Spotify may rotate the refresh token, so two concurrent refreshes
    // would make the second one fail with invalid_grant. ≈ SemaphoreSlim(1) in C#.
    private suspend fun refreshIfStale(staleToken: String?): RefreshResult = refreshMutex.withLock {
        // If the stored token changed while we waited, another caller already refreshed — reuse it.
        val current = getToken()
        if (current != null && current != staleToken) return@withLock RefreshResult.Token(current)
        val result = refreshAccessToken()
        if (result is RefreshResult.Token) saveToken(result.value)
        result
    }

    // Calls the Spotify token endpoint with the stored refresh token.
    // Spotify may also rotate the refresh token — saves the new one if present.
    private suspend fun refreshAccessToken(): RefreshResult = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken() ?: return@withContext RefreshResult.Rejected
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                .build()

            httpClient.newCall(
                Request.Builder().url(TOKEN_URL).post(body).build()
            ).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                when {
                    response.isSuccessful -> {
                        val json = JSONObject(responseBody)
                        // Spotify may rotate the refresh token — save the new one if present
                        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
                            saveRefreshToken(it)
                        }
                        RefreshResult.Token(json.getString("access_token"))
                    }
                    // 400 invalid_grant / 401: the refresh token is genuinely dead
                    response.code == 400 || response.code == 401 -> {
                        Log.w(TAG, "Token refresh rejected [${response.code}]: $responseBody")
                        RefreshResult.Rejected
                    }
                    else -> {
                        Log.w(TAG, "Token refresh failed [${response.code}]: $responseBody")
                        RefreshResult.Failed
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Token refresh network error: ${e.message}")
            RefreshResult.NetworkError
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh error: ${e.message}")
            RefreshResult.Failed
        }
    }

    // === Token management ===

    private suspend fun getToken(): String? {
        val prefs = context.dataStore.data.first()
        val savedToken = prefs[TOKEN_KEY]
        if (!savedToken.isNullOrEmpty()) return savedToken
        // Debug builds only. Note: logging out with a TEST_TOKEN set just logs you back in on
        // the next start, because this fallback still finds the token. Acceptable for testing.
        if (BuildConfig.DEBUG && TEST_TOKEN.isNotEmpty()) return TEST_TOKEN
        return null
    }

    private suspend fun getRefreshToken(): String? {
        return context.dataStore.data.first()[REFRESH_TOKEN_KEY]?.takeIf { it.isNotEmpty() }
    }

    override suspend fun saveToken(token: String) {
        context.dataStore.edit { it[TOKEN_KEY] = token }
    }

    override suspend fun saveRefreshToken(token: String) {
        context.dataStore.edit { it[REFRESH_TOKEN_KEY] = token }
    }

    /** Clears both access and refresh tokens — call on logout or unrecoverable auth failure. */
    override suspend fun clearTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
        }
    }

    /** Checks whether the user is logged in. */
    override suspend fun isLoggedIn(): Boolean = getToken() != null
}
