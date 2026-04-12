package com.cuepoint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CuePointApp(vm: MainViewModel) {
    val uiState by vm.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    // Keep screen on while on the Control page — important during a dance session
    val activity = LocalContext.current as Activity
    DisposableEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> SetupScreen(uiState, vm)
                    1 -> ControlScreen(
                        uiState = uiState,
                        onPlayPause = vm::togglePlayPause,
                        onReset = vm::resetToTime,
                        onBack = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }
                    )
                }
            }

            // Page indicator dots at the bottom
            HorizontalPageIndicator(
                pageIndicatorState = object : PageIndicatorState {
                    override val pageCount get() = 2
                    override val pageOffset get() = pagerState.currentPageOffsetFraction
                    override val selectedPage get() = pagerState.currentPage
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            )
        }
    }
}

@Composable
fun SetupScreen(uiState: CuePointUiState, vm: MainViewModel) {
    val minutes = (uiState.timeMs / 60_000L).toInt()
    val seconds = ((uiState.timeMs % 60_000L) / 1_000L).toInt()

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "%d:%02d".format(minutes, seconds),
                fontSize = 32.sp,
                color = Color(0xFF1DB954),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

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
        }
    }
}

@Composable
fun ControlScreen(
    uiState: CuePointUiState,
    onPlayPause: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    // Intercept back gesture — go to Setup instead of exiting the app
    BackHandler(enabled = true) { onBack() }

    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Pause
                Button(
                    onClick = onPlayPause,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.size(64.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1DB954))
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Reset (pause + seek to set time)
                Button(
                    onClick = onReset,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.size(64.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.Replay, "Reset", Modifier.size(32.dp))
                }
            }

            // Loading overlay
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(80.dp), strokeWidth = 3.dp)
            }

            // Error message — auto-clears after 3s
            uiState.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
                )
            }
        }
    }
}
