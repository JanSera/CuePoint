package com.cuepoint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    // Application context everywhere: the ViewModel outlives the Activity (rotation), so it
    // must never hold a reference to it. The factory only runs when no VM exists yet.
    // C# analogue: resolving a scoped service from the container.
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(
            application,
            SpotifyApi(applicationContext),
            SpotifyAuth(applicationContext),
            DataStoreSegmentStore(applicationContext)
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
    // No foreground service in v1, so the loop cannot be trusted once the app is hidden: disarm loudly.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }

    // Foreground resync every ~10 s while RESUMED and logged in, so a song change on the phone is
    // noticed (it loads that song's segment) before MARK/Reset act on a stale track.
    // Paused while the auto-stop loop is armed: the loop makes its own GETs (every ~5 s, see
    // LoopController), and two pollers would overlap. Restarts (with a fresh 10 s wait) on disarm.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(uiState.authState, uiState.loopArmed, lifecycleOwner) {
        if (uiState.authState == AuthState.LoggedIn && !uiState.loopArmed) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(10_000)   // ON_RESUME already synced once, so wait first
                    vm.syncPlayback(silent = true)
                }
            }
        }
    }

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

    // Page 0 = Control (default, used almost all the time), page 1 = Segment.
    // Back on Segment returns to Control; on Control the handler is disabled, so Back exits.
    BackHandler(enabled = pagerState.currentPage == 1) {
        coroutineScope.launch { pagerState.animateScrollToPage(0) }
    }

    // Screen stays on while the loop is armed (a dimmed screen could suspend the app and stop it) or
    // while playing (Reset+Play without a "to" must not drop to the watch face mid-rehearsal), on any page.
    // Otherwise the normal timeout applies.
    val activity = LocalContext.current as Activity
    DisposableEffect(uiState.loopArmed || uiState.isPlaying) {
        if (uiState.loopArmed || uiState.isPlaying)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // rotaryScrollableBehavior = null: by default Wear's pager grabs the crown/bezel to flip pages,
        // which would fight the Segment page's own rotary handling (and steal focus). Pages are
        // changed by swiping only.
        HorizontalPager(state = pagerState, rotaryScrollableBehavior = null) { page ->
            when (page) {
                0 -> ControlScreen(
                    uiState = uiState,
                    onPlayPause = vm::togglePlayPause,
                    onReset = vm::resetToTime
                )
                1 -> SegmentScreen(
                    uiState = uiState,
                    vm = vm,
                    isCurrent = pagerState.currentPage == 1
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

private fun formatMs(ms: Long): String = "%d:%02d".format(ms / 60_000L, (ms % 60_000L) / 1_000L)

// Rotary pixels that count as one detent. Wear reports scroll in pixels, and a detent's size varies
// by watch, so this is a tuning constant: raise it if one click skips seconds, lower it if clicks are lost.
private const val ROTARY_PX_PER_STEP = 40f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SegmentScreen(uiState: CuePointUiState, vm: MainViewModel, isCurrent: Boolean) {
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    // Rotary events only reach the focused node, and the pager does not hand focus to the page that
    // scrolls in. So ask for it whenever this page becomes current (and after a selection change,
    // in case a tap on a chip moved focus away). runCatching: the node may not be attached yet.
    LaunchedEffect(isCurrent, uiState.selectedField) {
        if (isCurrent) runCatching { focusRequester.requestFocus() }
    }

    var rotaryAcc by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                // Clockwise = positive pixels = later in the song. One detent = +-1 s on the selected field.
                rotaryAcc += event.verticalScrollPixels
                while (abs(rotaryAcc) >= ROTARY_PX_PER_STEP) {
                    val dir = if (rotaryAcc > 0) 1 else -1
                    rotaryAcc -= dir * ROTARY_PX_PER_STEP
                    vm.nudge(dir * 1_000L)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            // No TimeText here: the round corners need the room. Bottom padding clears the page dots.
            // Three 48dp touch rows + a 24dp Log out line must fit 192dp. Side padding of 32dp keeps the
            // top row's MARK chip inside the circle's chord at that height.
            .padding(top = 16.dp, bottom = 2.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        SegmentRow(
            label = "FROM",
            value = formatMs(uiState.fromMs),
            selected = uiState.selectedField == SegmentField.From,
            onSelect = { vm.selectField(SegmentField.From) },
            onLongClick = null,
            onMark = { vm.mark(SegmentField.From) }
        )
        SegmentRow(
            label = "TO",
            value = uiState.toMs?.let(::formatMs) ?: "—",
            selected = uiState.selectedField == SegmentField.To,
            onSelect = { vm.selectField(SegmentField.To) },
            onLongClick = vm::clearTo,   // long-press clears "to"
            onMark = { vm.mark(SegmentField.To) }
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { vm.nudge(-1_000L) },
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),   // 48dp touch target
                colors = ButtonDefaults.secondaryButtonColors()
            ) { Icon(Icons.Default.Remove, "Back 1 second", Modifier.size(20.dp)) }
            Button(
                onClick = { vm.nudge(1_000L) },
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                colors = ButtonDefaults.secondaryButtonColors()
            ) { Icon(Icons.Default.Add, "Forward 1 second", Modifier.size(20.dp)) }
        }

        // The error line takes the Log out chip's place for its 3 s, so a MARK error is visible
        // without needing extra height (and Log out cannot be hit by accident meanwhile).
        Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            val error = uiState.errorMessage
            if (error != null) {
                Text(
                    text = error, fontSize = 10.sp, lineHeight = 11.sp, color = Color.Red,
                    textAlign = TextAlign.Center, maxLines = 2
                )
            } else {
                CompactChip(
                    onClick = vm::logout,
                    modifier = Modifier.height(24.dp),
                    label = { Text("Log out", fontSize = 10.sp) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

// One line: [FROM 1:13] [MARK]. Tapping the label/value area selects the field for crown and +/-.
// Label stacked over the value keeps the area narrow, so the MARK chip stays inside the round screen.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(
    label: String,
    value: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMark: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)   // touch target
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Color(0xFF1B3B2A) else Color.Transparent)
                .combinedClickable(onClick = onSelect, onLongClick = onLongClick)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = label, fontSize = 9.sp, color = Color.LightGray)
            Text(
                text = value,
                fontSize = 18.sp,
                color = if (selected) Color(0xFF1DB954) else Color.White,
                maxLines = 1
            )
        }
        CompactChip(
            onClick = onMark,
            label = { Text("MARK", fontSize = 10.sp) },
            colors = ChipDefaults.secondaryChipColors()
        )
    }
}


@Composable
fun ControlScreen(
    uiState: CuePointUiState,
    onPlayPause: () -> Unit,
    onReset: () -> Unit
) {
    val playback = uiState.playback

    // Live position: tick a local clock while playing and extrapolate from the last snapshot.
    // No network calls. The effect restarts whenever the snapshot changes (sync, play/pause, reset),
    // and the loop ends by itself when paused.
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // repeatOnLifecycle(RESUMED): no ticking (and no recomposition) while the activity is stopped.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playback, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            nowMs = SystemClock.elapsedRealtime()
            while (playback?.isPlaying == true) {
                delay(250)
                nowMs = SystemClock.elapsedRealtime()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Armed: a ring around the bezel filling from "from" to "to" as the song plays. Glanceable from
    // across the room; its presence alone says "will auto-stop". Drawn first, so the content sits on top.
    val to = uiState.toMs
    if (uiState.loopArmed && to != null && playback != null) {
        val span = (to - uiState.fromMs).coerceAtLeast(1L)
        val progress = ((playback.positionAt(nowMs) - uiState.fromMs).toFloat() / span).coerceIn(0f, 1f)
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize().padding(2.dp),
            indicatorColor = Color(0xFF1DB954),
            trackColor = Color(0xFF1B3B2A),
            strokeWidth = 4.dp
        )
    }
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 22.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // One ellipsized line ("Track · Device", or just the device when an ad/podcast has no
            // track). Narrow side padding because the round bezel clips corners. Empty line keeps its
            // height so the layout does not jump when playback appears.
            val line = playback?.let { p ->
                listOfNotNull(p.trackName, p.deviceName.ifEmpty { null }).joinToString(" · ")
            }.orEmpty()
            Text(
                text = line,
                fontSize = 11.sp,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .height(16.dp)
                    .padding(horizontal = 32.dp)
            )

            Text(
                text = playback?.let { formatMs(it.positionAt(nowMs)) } ?: "-:--",
                fontSize = 34.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = uiState.toMs?.let { "${if (uiState.loopArmed) "LOOP " else ""}${formatMs(uiState.fromMs)} → ${formatMs(it)}" }
                    ?: "from ${formatMs(uiState.fromMs)}",
                fontSize = 11.sp,
                color = Color(0xFF1DB954),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // The spinner shares a Box with the buttons, so it is centred on the row itself.
            Box(contentAlignment = Alignment.Center) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onReset,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.size(52.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333333))
                    ) {
                        Icon(Icons.Default.Replay, "Reset", Modifier.size(28.dp))
                    }

                    Button(
                        onClick = onPlayPause,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.size(52.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1DB954))
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(60.dp), strokeWidth = 3.dp)
                }
            }

            // Reserved height: an error appearing must not push the buttons around.
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                uiState.errorMessage?.let { msg ->
                    Text(text = msg, fontSize = 11.sp, color = Color.Red, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
    }
}
