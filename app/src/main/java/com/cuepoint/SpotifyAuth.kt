package com.cuepoint

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

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
        // =============================================================
        // PASTE YOUR CLIENT ID from the Spotify Developer Dashboard HERE
        // =============================================================
        private const val CLIENT_ID = "YOUR_CLIENT_ID_HERE"

        private const val REDIRECT_URI = "cuepoint://callback"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        // Scope = permissions. We only need playback control.
        private const val SCOPE = "user-modify-playback-state user-read-playback-state"
    }

    private val httpClient = OkHttpClient()

    // The code verifier must be kept between the authorize call and the callback.
    // In production you'd store it in DataStore; kept in memory here for simplicity.
    private var codeVerifier: String? = null

    /**
     * Step 1: Open the Spotify login in a browser on the paired phone.
     *
     * On Wear OS this is done via an intent – the system routes it
     * to the paired phone where a browser opens.
     */
    fun openAuthInBrowser() {
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

        // Open in browser (on phone)
        val intent = Intent(Intent.ACTION_VIEW, authUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Step 2: Handle the callback after login.
     *
     * Called from MainActivity.onNewIntent() when the redirect
     * cuepoint://callback?code=... arrives.
     *
     * Returns the access token, or null on error.
     */
    suspend fun handleCallback(callbackUri: Uri): String? = withContext(Dispatchers.IO) {
        val code = callbackUri.getQueryParameter("code") ?: return@withContext null
        val verifier = codeVerifier ?: return@withContext null

        try {
            // Exchange code for token (≈ POST to token endpoint)
            val requestBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (response.isSuccessful) {
                val json = JSONObject(body)
                // access_token is valid for ~1 hour
                // refresh_token can be used to renew it (implement later)
                json.getString("access_token")
            } else {
                println("Token exchange failed: $body")
                null
            }
        } catch (e: Exception) {
            println("Auth error: ${e.message}")
            null
        }
    }

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
