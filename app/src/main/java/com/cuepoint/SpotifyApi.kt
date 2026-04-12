package com.cuepoint

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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

    private val httpClient = OkHttpClient()

    /** Seeks playback to [positionMs] milliseconds. Scope: user-modify-playback-state */
    suspend fun seek(positionMs: Long): Boolean =
        spotifyPut("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")

    /** Resumes playback on the active device. Scope: user-modify-playback-state */
    suspend fun play(): Boolean =
        spotifyPut("https://api.spotify.com/v1/me/player/play")

    /** Pauses playback on the active device. Scope: user-modify-playback-state */
    suspend fun pause(): Boolean =
        spotifyPut("https://api.spotify.com/v1/me/player/pause")

    /**
     * Returns true if Spotify is currently playing, false if paused, null if no active player.
     * Spotify API: GET /v1/me/player
     */
    suspend fun isPlaying(): Boolean? = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext null

            val response = httpClient.newCall(
                Request.Builder()
                    .url("https://api.spotify.com/v1/me/player")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            ).execute()

            when (response.code) {
                200  -> JSONObject(response.body?.string() ?: return@withContext false).getBoolean("is_playing")
                204  -> false  // No active player
                401  -> { clearTokens(); null }
                else -> { println("isPlaying failed: ${response.code}"); null }
            }
        } catch (e: Exception) {
            println("isPlaying error: ${e.message}")
            null
        }
    }

    // Shared PUT helper. On 401, attempts a token refresh and retries once before giving up.
    // Transparent to callers — they just get true/false.
    private suspend fun spotifyPut(url: String): Boolean = withContext(Dispatchers.IO) {
        val firstCode = executePut(url)
        if (firstCode == 200 || firstCode == 204) return@withContext true
        if (firstCode != 401) return@withContext false

        // 401 — try to refresh
        val newToken = refreshAccessToken() ?: run {
            clearTokens()   // refresh failed, force re-login
            return@withContext false
        }
        saveToken(newToken)

        // Retry once with the new token
        val retryCode = executePut(url)
        if (retryCode == 200 || retryCode == 204) return@withContext true

        clearTokens()   // still failing after refresh, force re-login
        false
    }

    // Executes a single PUT and returns the HTTP status code, or -1 on network error.
    private suspend fun executePut(url: String): Int {
        return try {
            val token = getToken() ?: return -1
            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .put(ByteArray(0).toRequestBody(null))
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            ).execute()
            if (!response.isSuccessful && response.code != 204) {
                println("Spotify API failed [${response.code}]: ${response.body?.string()}")
            }
            response.code
        } catch (e: Exception) {
            println("Spotify API error: ${e.message}")
            -1
        }
    }

    // Calls the Spotify token endpoint with the stored refresh token.
    // Returns the new access token, or null if refresh is not possible or fails.
    // Spotify may also rotate the refresh token — saves the new one if present.
    private suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken() ?: return@withContext null
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                .build()

            val response = httpClient.newCall(
                Request.Builder().url(TOKEN_URL).post(body).build()
            ).execute()

            val responseBody = response.body?.string() ?: return@withContext null

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                // Spotify may rotate the refresh token — save the new one if present
                json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
                    saveRefreshToken(it)
                }
                json.getString("access_token")
            } else {
                println("Token refresh failed [${response.code}]: $responseBody")
                null
            }
        } catch (e: Exception) {
            println("Token refresh error: ${e.message}")
            null
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
