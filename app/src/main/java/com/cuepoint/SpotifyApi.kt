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
        private val TOKEN_KEY = stringPreferencesKey("access_token")

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

    /**
     * Seeks playback to the given position in milliseconds.
     *
     * Spotify API: PUT /v1/me/player/seek?position_ms={ms}
     * Scope: user-modify-playback-state
     */
    suspend fun seek(positionMs: Long): Boolean =
        putPlayerCommand("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")

    /**
     * Resumes playback.
     * Spotify API: PUT /v1/me/player/play
     */
    suspend fun play(): Boolean =
        putPlayerCommand("https://api.spotify.com/v1/me/player/play")

    /**
     * Pauses playback.
     * Spotify API: PUT /v1/me/player/pause
     */
    suspend fun pause(): Boolean =
        putPlayerCommand("https://api.spotify.com/v1/me/player/pause")

    /**
     * Returns true if Spotify is currently playing, false if paused, null if no active player.
     * Spotify API: GET /v1/me/player
     *
     * C# equivalent: await _httpClient.GetFromJsonAsync<PlayerState>(".../player");
     */
    suspend fun isPlaying(): Boolean? = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext null

            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()

            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@withContext false
                    JSONObject(body).getBoolean("is_playing")
                }
                204 -> false  // No active player
                401 -> { clearToken(); null }
                else -> {
                    println("isPlaying failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            println("isPlaying error: ${e.message}")
            null
        }
    }

    // Shared helper for PUT commands that expect 204 No Content.
    // ≈ private async Task<bool> PutPlayerCommand(string url)
    private suspend fun putPlayerCommand(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext false

            val request = Request.Builder()
                .url(url)
                .put(ByteArray(0).toRequestBody(null))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()

            // 204 = success (No Content)
            // 401 = token expired
            // 403 = not Premium
            // 404 = no active device
            when (response.code) {
                204 -> true
                401 -> { clearToken(); false }
                else -> {
                    println("Player command failed: ${response.code} ${response.body?.string()}")
                    false
                }
            }
        } catch (e: Exception) {
            println("Player command error: ${e.message}")
            false
        }
    }

    // === Token management ===

    /**
     * Retrieves the stored access token.
     * Tries DataStore first, then falls back to TEST_TOKEN.
     */
    private suspend fun getToken(): String? {
        // Try stored OAuth token
        val prefs = context.dataStore.data.first()
        val savedToken = prefs[TOKEN_KEY]
        if (!savedToken.isNullOrEmpty()) return savedToken

        // Fallback to test token
        if (TEST_TOKEN.isNotEmpty()) return TEST_TOKEN

        return null
    }

    /**
     * Saves the access token after a successful OAuth login.
     */
    suspend fun saveToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
        }
    }

    /**
     * Deletes the token (on expiry or logout).
     */
    suspend fun clearToken() {
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
        }
    }

    /**
     * Checks whether the user is logged in.
     */
    suspend fun isLoggedIn(): Boolean {
        return getToken() != null
    }
}
