package com.cuepoint

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
import androidx.wear.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import kotlinx.coroutines.launch

/**
 * Main (and only) Activity of the app.
 *
 * For .NET developers:
 * - Activity ≈ Window/Page in WPF/MAUI
 * - Compose ≈ declarative UI like XAML, but directly in code
 * - remember {} ≈ @State property (holds UI state)
 * - LaunchedEffect ≈ OnAppearing + async
 * - coroutineScope.launch ≈ Task.Run()
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val spotifyApi = SpotifyApi(this)

        setContent {
            CuePointApp(spotifyApi)
        }
    }
}

@Composable
fun CuePointApp(spotifyApi: SpotifyApi) {
    // UI state – minutes and seconds
    // C# analogue: private int _minutes = 1; with INotifyPropertyChanged
    var minutes by remember { mutableIntStateOf(1) }
    var seconds by remember { mutableIntStateOf(13) }

    // Playback state – null means unknown (still fetching)
    // Boolean? ≈ Nullable<bool> in C#
    var isPlaying by remember { mutableStateOf<Boolean?>(null) }

    // Button states
    var seekStatus by remember { mutableStateOf("") }
    var isSeekLoading by remember { mutableStateOf(false) }
    var isPlayPauseLoading by remember { mutableStateOf(false) }

    // Coroutine scope for async operations (≈ async/await context)
    val scope = rememberCoroutineScope()

    // Fetch playback state when the screen opens (≈ OnAppearing in MAUI)
    LaunchedEffect(Unit) {
        isPlaying = spotifyApi.isPlaying()
    }

    MaterialTheme {
        // Scaffold = base Wear OS layout (supports round display)
        Scaffold(
            timeText = { TimeText() }  // Clock at the top of the display
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // === SELECTED TIME DISPLAY ===
                Text(
                    text = "%d:%02d".format(minutes, seconds),
                    fontSize = 32.sp,
                    color = Color(0xFF1DB954),  // Spotify green
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // === PICKER ROW ===
                // Picker on Wear OS = rotary dial (also works with touch)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Minutes: 0-9
                    InlineSlider(
                        value = minutes,
                        onValueChange = { minutes = it },
                        valueProgression = 0..9,
                        decreaseIcon = { Icon(Icons.Default.Remove, contentDescription = "Decrease") },
                        increaseIcon = { Icon(Icons.Default.Add, contentDescription = "Increase") },
                        modifier = Modifier.width(80.dp)
                    )

                    Text(":", fontSize = 24.sp, color = Color.White)

                    // Seconds: 0-59
                    InlineSlider(
                        value = seconds,
                        onValueChange = { seconds = it },
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
                    // --- Play / Pause ---
                    Button(
                        onClick = {
                            if (!isPlayPauseLoading) {
                                isPlayPauseLoading = true
                                scope.launch {
                                    val success = if (isPlaying == true) spotifyApi.pause() else spotifyApi.play()
                                    if (success) isPlaying = !(isPlaying ?: false)
                                    isPlayPauseLoading = false
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1DB954))
                    ) {
                        if (isPlayPauseLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = if (isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying == true) "Pause" else "Play",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // --- Seek / Reset ---
                    Button(
                        onClick = {
                            if (!isSeekLoading) {
                                isSeekLoading = true
                                seekStatus = ""
                                val positionMs = (minutes * 60 + seconds) * 1000L
                                scope.launch {
                                    val result = spotifyApi.seek(positionMs)
                                    seekStatus = if (result) "✓" else "✗"
                                    isSeekLoading = false
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1DB954))
                    ) {
                        if (isSeekLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Replay,
                                contentDescription = "Seek",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Status text for seek result
                if (seekStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = seekStatus,
                        fontSize = 18.sp,
                        color = if (seekStatus == "✓") Color(0xFF1DB954) else Color.Red
                    )
                }
            }
        }
    }
}
