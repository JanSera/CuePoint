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
     * Returns: true = success, false = error
     *
     * C# equivalent:
     *   await _httpClient.PutAsync($"...?position_ms={positionMs}", null);
     */
    suspend fun seek(positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext false

            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")
                .put(okhttp3.RequestBody.create(null, ByteArray(0)))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()

            // 204 = success (No Content – Spotify returns an empty body)
            // 401 = token expired → needs refresh
            // 403 = not Premium
            // 404 = no active device is playing
            when (response.code) {
                204 -> true
                401 -> {
                    // Token expired – delete it, user must log in again
                    clearToken()
                    false
                }
                else -> {
                    // Log for debugging
                    println("Spotify seek failed: ${response.code} ${response.body?.string()}")
                    false
                }
            }
        } catch (e: Exception) {
            println("Seek error: ${e.message}")
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
