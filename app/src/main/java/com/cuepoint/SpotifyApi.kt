package com.cuepoint

import android.content.Context
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
private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "spotify_prefs")

private const val TAG = "SpotifyApi"

// One client for the whole app (SpotifyAuth uses it too). It owns the connection pool,
// so like HttpClient in .NET you want a single instance.
internal val httpClient = OkHttpClient()

// Top level (not per SpotifyApi instance): the Activity creates a new SpotifyApi on every
// onCreate, and refreshes must stay serialised across all of them.
private val refreshMutex = Mutex()

private class RawResponse(val code: Int, val body: String)

// Only Rejected means the refresh token is dead and the user must log in again.
private sealed interface RefreshResult {
    data class Token(val value: String) : RefreshResult
    data object Rejected : RefreshResult      // 400/401 invalid_grant, or no refresh token stored
    data object NetworkError : RefreshResult  // keep tokens, report "Network error"
    data object Failed : RefreshResult        // 5xx etc. — keep tokens
}

/** Outcome of a playback call. NETWORK is split out so the UI can say "Network error". */
enum class CallResult { OK, FAILED, NETWORK }

class SpotifyApi(private val context: Context) {

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
    suspend fun seek(positionMs: Long): CallResult =
        put("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")

    /** Resumes playback on the active device. Scope: user-modify-playback-state */
    suspend fun play(): CallResult =
        put("https://api.spotify.com/v1/me/player/play")

    /**
     * Pauses playback on the active device. Scope: user-modify-playback-state
     *
     * Pausing an already-paused player returns 403 "Restriction violated" — the player is
     * already in the state we wanted, so that counts as success.
     */
    suspend fun pause(): CallResult {
        val response = execute("https://api.spotify.com/v1/me/player/pause", put = true)
        // The message can mean other restrictions too, but the goal state ("not playing") holds either way.
        if (response.code == 403 && response.body.contains("Restriction violated")) return CallResult.OK
        return response.toCallResult()
    }

    /**
     * Returns true if playing, false if paused, null if there is no active device or the call failed.
     * Spotify API: GET /v1/me/player (204 = no active device)
     */
    suspend fun isPlaying(): Boolean? {
        val response = execute("https://api.spotify.com/v1/me/player", put = false)
        if (response.code != 200) return null
        return try {
            JSONObject(response.body).getBoolean("is_playing")
        } catch (e: Exception) {
            Log.w(TAG, "isPlaying parse error: ${e.message}")
            null
        }
    }

    private suspend fun put(url: String): CallResult = execute(url, put = true).toCallResult()

    // Any 2xx is success — Spotify's player endpoints can answer 200, 202 or 204.
    private fun RawResponse.toCallResult(): CallResult = when {
        code in 200..299 -> CallResult.OK
        code == -1       -> CallResult.NETWORK
        else             -> CallResult.FAILED
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
        return try {
            val builder = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
            if (put) builder.put(ByteArray(0).toRequestBody(null)) else builder.get()
            httpClient.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) Log.w(TAG, "Spotify API failed [${response.code}]: $body")
                RawResponse(response.code, body)
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
        if (TEST_TOKEN.isNotEmpty()) return TEST_TOKEN
        return null
    }

    private suspend fun getRefreshToken(): String? {
        return context.dataStore.data.first()[REFRESH_TOKEN_KEY]?.takeIf { it.isNotEmpty() }
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { it[TOKEN_KEY] = token }
    }

    suspend fun saveRefreshToken(token: String) {
        context.dataStore.edit { it[REFRESH_TOKEN_KEY] = token }
    }

    /** Clears both access and refresh tokens — call on logout or unrecoverable auth failure. */
    suspend fun clearTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
        }
    }

    /** Checks whether the user is logged in. */
    suspend fun isLoggedIn(): Boolean = getToken() != null
}
