package com.cuepoint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var spotifyApi: SpotifyApi
    private lateinit var spotifyAuth: SpotifyAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotifyApi = SpotifyApi(this)
        spotifyAuth = SpotifyAuth(this)

        setContent {
            val vm: MainViewModel = viewModel(
                factory = MainViewModel.factory(application, spotifyApi)
            )
            CuePointApp(vm)
        }
    }

    // Called when Spotify redirects back to cuepoint://callback after OAuth login.
    // singleTop (AndroidManifest) ensures this Activity instance receives the intent
    // rather than a new one being created.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        if (uri.scheme == "cuepoint" && uri.host == "callback") {
            lifecycleScope.launch {
                val token = spotifyAuth.handleCallback(uri)
                if (token != null) {
                    spotifyApi.saveToken(token)
                }
            }
        }
    }
}

@Composable
fun CuePointApp(vm: MainViewModel) {
    val uiState by vm.uiState.collectAsState()

    val minutes = (uiState.timeMs / 60_000L).toInt()
    val seconds = ((uiState.timeMs % 60_000L) / 1_000L).toInt()

    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // === TIME DISPLAY ===
                Text(
                    text = "%d:%02d".format(minutes, seconds),
                    fontSize = 32.sp,
                    color = Color(0xFF1DB954),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // === PICKER ROW ===
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InlineSlider(
                        value = minutes,
                        onValueChange = { vm.setMinutes(it) },
                        valueProgression = 0..9,
                        decreaseIcon = { Icon(Icons.Default.Remove, contentDescription = "Decrease") },
                        increaseIcon = { Icon(Icons.Default.Add, contentDescription = "Increase") },
                        modifier = Modifier.width(80.dp)
                    )

                    Text(":", fontSize = 24.sp, color = Color.White)

                    InlineSlider(
                        value = seconds,
                        onValueChange = { vm.setSeconds(it) },
                        valueProgression = 0..59,
                        decreaseIcon = { Icon(Icons.Default.Remove, contentDescription = "Decrease") },
                        increaseIcon = { Icon(Icons.Default.Add, contentDescription = "Increase") },
                        modifier = Modifier.width(80.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === BUTTONS ROW ===
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play / Pause
                    Button(
                        onClick = { vm.togglePlayPause() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.size(48.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1DB954))
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Reset (pause + seek)
                    Button(
                        onClick = { vm.resetToTime() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.size(48.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Reset",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Loading indicator
                if (uiState.isLoading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                // Error message — auto-clears after 3s
                uiState.errorMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = Color.Red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
