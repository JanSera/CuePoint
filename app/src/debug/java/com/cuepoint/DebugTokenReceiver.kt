package com.cuepoint

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "DebugTokenReceiver"

/**
 * Saves Spotify tokens sent over `adb shell am broadcast`, so an emulator without a paired
 * phone can be logged in from the PC (see tools/Get-SpotifyToken.ps1).
 *
 * Lives in src/debug so release builds don't contain it. It is exported, so any app on a
 * debug device could send this broadcast and overwrite the tokens. That is acceptable for
 * debug builds and would never be for release.
 *
 * Extras: `access` (required), `refresh` (optional). Values are never logged.
 * The app reads auth state only when the ViewModel starts, so the caller restarts the app afterwards.
 */
class DebugTokenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val access = intent.getStringExtra("access")
        if (access.isNullOrEmpty()) {
            Log.w(TAG, "Ignored: missing 'access' extra")
            return
        }
        val refresh = intent.getStringExtra("refresh")

        // goAsync keeps the receiver alive past onReceive (≈ not disposing until the Task completes);
        // without it the process may be killed before the DataStore write finishes.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Same keys as the app, via SpotifyApi, so they can't drift apart.
                val api = SpotifyApi(context.applicationContext)
                api.saveToken(access)
                if (!refresh.isNullOrEmpty()) api.saveRefreshToken(refresh)
                Log.i(TAG, "tokens injected")
                // am broadcast prints this result code; the script requires it (a missing receiver gives 0).
                pending.setResultCode(Activity.RESULT_OK)
            } catch (e: Exception) {
                Log.w(TAG, "token injection failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
