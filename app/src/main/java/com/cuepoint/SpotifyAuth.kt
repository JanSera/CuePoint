package com.cuepoint

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 *   → Redirect to cuepoint://callback?code=XYZ&state=ABC → MainActivity forwards the Uri
 *   → MainViewModel.handleAuthCallback() → handleCallback() → Token saved
 *
 * The verifier and the OAuth `state` are persisted in DataStore (not kept in a field) because
 * the app process can be killed while the user is logging in on the phone.
 *
 * [context] must be the Application context: this object lives as long as the ViewModel,
 * so holding an Activity here would leak it.
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

        // Pending-login values, stored between opening the browser and the callback.
        private val VERIFIER_KEY = stringPreferencesKey("pending_code_verifier")
        private val STATE_KEY    = stringPreferencesKey("pending_oauth_state")
    }

    /** Outcome of the redirect. Cancelled = the user denied access on the consent page. */
    sealed interface AuthResult {
        data class Success(val tokens: TokenPair) : AuthResult
        data object Cancelled : AuthResult
        data object Failed : AuthResult
    }

    /**
     * Step 1: Open the Spotify login in a browser on the paired phone.
     *
     * On Wear OS this is done via an intent – the system routes it
     * to the paired phone where a browser opens.
     */
    suspend fun openAuthInBrowser(): Boolean {
        // Generate the PKCE pair plus a random `state`. Spotify echoes `state` back on the
        // redirect; if it doesn't match, the callback isn't from the login we started (CSRF defence).
        val verifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(verifier)
        val state = generateCodeVerifier()
        context.dataStore.edit {
            it[VERIFIER_KEY] = verifier
            it[STATE_KEY] = state
        }

        // Build the authorize URL
        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("state", state)
            .build()

        // Open in browser on the paired phone.
        // CATEGORY_BROWSABLE + FLAG_ACTIVITY_NEW_TASK are both required for Wear OS to
        // route the intent through UriRedirectActivity to the phone's browser.
        // (NEW_TASK is also what allows starting an activity from the Application context.)
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
            clearPending()
            false
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Auth redirect failed — nothing can open the URL: ${e.message}")
            clearPending()
            false
        }
    }

    /**
     * Step 2: Handle the callback after login.
     *
     * Called (via the ViewModel) when the redirect
     * cuepoint://callback?code=...&state=... (or ?error=...) arrives.
     */
    suspend fun handleCallback(callbackUri: Uri): AuthResult {
        val prefs = context.dataStore.data.first()
        val verifier = prefs[VERIFIER_KEY]
        val expectedState = prefs[STATE_KEY]
        // A callback whose state does not match is foreign (another app, a stale link): reject it
        // but keep the pending values, or it would wipe the verifier of a login still in progress.
        val callbackState = callbackUri.getQueryParameter("state")
        if (callbackState != null && callbackState != expectedState) {
            Log.w(TAG, "Callback rejected: state mismatch")
            return AuthResult.Failed
        }
        // Single use for every other outcome: clear before the exchange so every outcome (success, failure, cancel)
        // leaves nothing behind. The values are already in memory, and an authorization code
        // can only be redeemed once anyway, so there is nothing to retry.
        clearPending()

        callbackUri.getQueryParameter("error")?.let { error ->
            Log.w(TAG, "Authorization error: $error")
            return if (error == "access_denied") AuthResult.Cancelled else AuthResult.Failed
        }

        if (verifier == null || expectedState == null ||
            callbackState != expectedState
        ) {
            Log.w(TAG, "Callback rejected: missing verifier or state")
            return AuthResult.Failed
        }
        val code = callbackUri.getQueryParameter("code") ?: return AuthResult.Failed

        return exchangeCode(code, verifier)?.let { AuthResult.Success(it) } ?: AuthResult.Failed
    }

    private suspend fun clearPending() {
        context.dataStore.edit {
            it.remove(VERIFIER_KEY)
            it.remove(STATE_KEY)
        }
    }

    // POSTs the authorization code + verifier to the token endpoint. Null on any failure.
    private suspend fun exchangeCode(code: String, verifier: String): TokenPair? =
        withContext(Dispatchers.IO) {
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
     * Generates a random URL-safe string (43 chars). Used for the code_verifier (43-128 allowed)
     * and, reused, for the OAuth `state`.
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
