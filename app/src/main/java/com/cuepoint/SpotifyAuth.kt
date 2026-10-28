package com.cuepoint

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val TAG = "SpotifyAuth"

/**
 * Spotify OAuth 2.0 with PKCE (Proof Key for Code Exchange).
 *
 * PKCE is the secure flow for "public clients" (apps without a backend)
 * where you can't safely store a client_secret.
 *
 * For .NET developers – PKCE flow is the same as in MSAL/IdentityModel:
 * 1. Generate a code_verifier (random string)
 * 2. Derive code_challenge from it (SHA256 hash)
 * 3. Send the user to the authorize URL with code_challenge
 * 4. After the callback, exchange authorization_code + code_verifier for access_token
 *
 * FLOW:
 *   Watch → openAuthInBrowser() → Phone browser → Spotify login
 *   → Redirect to cuepoint://callback?code=XYZ → handleCallback() → Token saved
 */
class SpotifyAuth(private val context: Context) {

    companion object {
        // Injected at build time from local.properties (never committed).
        // Throws at startup if missing — add spotify.clientId to local.properties.
        private val CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID.also {
            check(it.isNotEmpty()) {
                "SPOTIFY_CLIENT_ID is not set. Add spotify.clientId=<your_id> to local.properties."
            }
        }

        private const val REDIRECT_URI = "cuepoint://callback"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        // Scope = permissions. We only need playback control.
        private const val SCOPE = "user-modify-playback-state user-read-playback-state"
    }

    // The code verifier must be kept between the authorize call and the callback.
    // In production you'd store it in DataStore; kept in memory here for simplicity.
    private var codeVerifier: String? = null

    /**
     * Step 1: Open the Spotify login in a browser on the paired phone.
     *
     * On Wear OS this is done via an intent – the system routes it
     * to the paired phone where a browser opens.
     */
    fun openAuthInBrowser(): Boolean {
        // Generate PKCE pair
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier!!)

        // Build the authorize URL
        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .build()

        // Open in browser on the paired phone.
        // CATEGORY_BROWSABLE + FLAG_ACTIVITY_NEW_TASK are both required for Wear OS to
        // route the intent through UriRedirectActivity to the phone's browser.
        // Returns false if no paired phone is available (e.g. emulator, unpaired watch).
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Auth redirect failed — no paired phone? ${e.message}")
            false
        }
    }

    /**
     * Step 2: Handle the callback after login.
     *
     * Called from MainActivity.onNewIntent() when the redirect
     * cuepoint://callback?code=... arrives.
     *
     * Returns a TokenPair (accessToken + refreshToken), or null on error.
     */
    suspend fun handleCallback(callbackUri: Uri): TokenPair? = withContext(Dispatchers.IO) {
        val code = callbackUri.getQueryParameter("code") ?: return@withContext null
        val verifier = codeVerifier ?: return@withContext null

        try {
            val requestBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            httpClient.newCall(
                Request.Builder().url(TOKEN_URL).post(requestBody).build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@withContext null

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    TokenPair(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() }
                    )
                } else {
                    Log.w(TAG, "Token exchange failed: $body")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auth error: ${e.message}")
            null
        }
    }

    data class TokenPair(val accessToken: String, val refreshToken: String?)

    // === PKCE helper methods ===

    /**
     * Generates a random code_verifier (43-128 chars, URL-safe).
     * ≈ RandomNumberGenerator.GetBytes() in C#
     */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Computes code_challenge = BASE64URL(SHA256(code_verifier))
     * ≈ SHA256.HashData() + Convert.ToBase64String() in C#
     */
    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }
}
