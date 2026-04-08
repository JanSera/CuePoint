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

    // Button state
    var status by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // Coroutine scope for async operations (≈ async/await context)
    val scope = rememberCoroutineScope()

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
                        value = minutes.toFloat(),
                        onValueChange = { minutes = it.toInt() },
                        valueRange = 0f..9f,
                        steps = 8,
                        modifier = Modifier.width(60.dp)
                    )

                    Text(":", fontSize = 24.sp, color = Color.White)

                    // Seconds: 0-59
                    InlineSlider(
                        value = seconds.toFloat(),
                        onValueChange = { seconds = it.toInt() },
                        valueRange = 0f..59f,
                        steps = 58,
                        modifier = Modifier.width(60.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === SEEK BUTTON ===
                Button(
                    onClick = {
                        if (!isLoading) {
                            isLoading = true
                            status = ""
                            // Convert to milliseconds for Spotify API
                            val positionMs = (minutes * 60 + seconds) * 1000L

                            // Launch async operation (≈ await SpotifyApi.Seek())
                            scope.launch {
                                val result = spotifyApi.seek(positionMs)
                                status = if (result) "✓" else "✗"
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF1DB954)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Seek",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Status text
                if (status.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = status,
                        fontSize = 18.sp,
                        color = if (status == "✓") Color(0xFF1DB954) else Color.Red
                    )
                }
            }
        }
    }
}
