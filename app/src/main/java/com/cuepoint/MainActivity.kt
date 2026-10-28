package com.cuepoint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Application context everywhere: the ViewModel outlives the Activity (rotation), so it
    // must never hold a reference to it. The factory only runs when no VM exists yet.
    // C# analogue: resolving a scoped service from the container.
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(
            application,
            SpotifyApi(applicationContext),
            SpotifyAuth(applicationContext)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cold start: if the process was killed during login, Android relaunches us with the
        // callback Uri in the launch intent and onNewIntent is never called. Only on a fresh
        // launch: after a rotation/restore the same intent is still attached and the callback
        // was already handled.
        if (savedInstanceState == null) handleAuthIntent(intent)

        setContent {
            CuePointApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    // The Activity only forwards the Uri; the ViewModel does the work.
    private fun handleAuthIntent(intent: Intent) {
        val uri = intent.data ?: return
        // Recents relaunch re-delivers the old callback Uri; it was already handled.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (uri.scheme == "cuepoint" && uri.host == "callback") {
            viewModel.handleAuthCallback(uri)
        }
    }
}

@Composable
fun CuePointApp(vm: MainViewModel) {
    val uiState by vm.uiState.collectAsState()

    // Re-read the real player state whenever the app comes back to the foreground. Lives here
    // (not in MainPager) so it is registered before login finishes; the VM ignores it unless logged in.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    MaterialTheme {
        when (uiState.authState) {
            // Blank spinner instead of the login screen until the stored-token check finishes.
            AuthState.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            AuthState.LoggedOut -> LoginScreen(onLogin = vm::login, errorMessage = uiState.loginError)
            AuthState.LoggedIn -> MainPager(vm, uiState)
        }
    }
}

@Composable
fun LoginScreen
(onLogin: () -> Unit, errorMessage: String?) {
    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "CuePoint",
                    fontSize = 20.sp,
                    color = Color(0xFF1DB954)
                )
                Chip(
                    onClick = onLogin,
                    label = { Text("Login with Spotify") },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1DB954))
                )
                errorMessage?.let {
                    Text(
                        text = it,
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

@Composable
fun MainPager(vm: MainViewModel, uiState: CuePointUiState) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

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

            Spacer(modifier = Modifier.height(4.dp))

            // Bottom of the Setup page (the reorder to page 1 is a later phase).
            CompactChip(
                onClick = vm::logout,
                label = { Text("Log out") },
                colors = ChipDefaults.secondaryChipColors()
            )
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
    BackHandler(enabled = true) { onBack() }

    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            uiState.playback?.let { p ->
                // One ellipsized line just above the buttons ("Track · Device", or just the device
                // when an ad/podcast has no track). Narrow side padding because the round bezel clips corners.
                val line = listOfNotNull(p.trackName, p.deviceName.ifEmpty { null }).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                Button(
                    onClick = onReset,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.size(64.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.Replay, "Reset", Modifier.size(32.dp))
                }
            }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(80.dp), strokeWidth = 3.dp)
            }

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
