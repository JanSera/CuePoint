package com.cuepoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The auto-stop timer: while armed it waits for the playback position to reach "to", keeps its
 * estimate honest with a few GETs, then pauses and seeks back to "from".
 *
 * Plain Kotlin on purpose (no Android types): the clock and the scope come in as parameters, so
 * Phase 6 tests can run it on virtual time. C# analogue: a service taking TimeProvider and an
 * ISpotifyClient, with Task.Delay as the only way it waits.
 *
 * It decides WHEN to fire. WHETHER the loop should be armed (right song, still playing, position
 * in range) is the ViewModel's call: it arms and disarms this controller on every state change.
 *
 * Not thread-safe: call from the scope's thread (the main thread in the app).
 */
class LoopController(
    private val client: SpotifyClient,
    private val clock: () -> Long,   // SystemClock.elapsedRealtime in the app; same unit as PlaybackState.sampledAtElapsedMs
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        /** A GET made by the loop. Feed it into the normal state path (track change, segment load, errors). May call arm()/disarm(). */
        suspend fun onSynced(result: ApiResult<PlaybackState?>)

        /** pause + seek(from) both succeeded. Playback is paused at [fromMs]; [atElapsedMs] is when the seek returned. */
        fun onFired(fromMs: Long, atElapsedMs: Long)

        /** pause or seek failed ([message] is the action-specific text). The loop is disarmed. */
        fun onFireFailed(result: ApiResult<*>, message: String)
    }

    // Round trip of the PUTs (watch -> phone -> internet -> Spotify and back). The pause lands about
    // half of it after we send, so we send that much early. Moving average so one slow call does not
    // swing the timing; the first guess is typical for Bluetooth-tethered watches.
    var rttEstimateMs: Long = INITIAL_RTT_MS
        private set

    /** Call with the duration of every successful play/pause/seek PUT, including the VM's own. */
    fun recordRoundTrip(ms: Long) {
        if (ms < 0) return
        rttEstimateMs = (rttEstimateMs * (RTT_WEIGHT_DEN - RTT_WEIGHT_NEW) + ms * RTT_WEIGHT_NEW) / RTT_WEIGHT_DEN
    }

    private var job: Job? = null
    private var firing = false
    private var anchor: PlaybackState? = null
    private var fromMs = 0L
    private var toMs = 0L

    val isArmed: Boolean get() = job?.isActive == true

    /** True between the loop's pause and its seek (and while the listener reports the outcome). */
    val isFiring: Boolean get() = firing

    /**
     * Arms the loop, or refreshes a running one. [anchor] is the freshest snapshot (callers pass
     * it on every state change). Same segment = the running timer just reads the new anchor on
     * its next step; a changed segment restarts it.
     */
    // Same segment: a fresher anchor is NOT acted on mid-delay (the running delay was computed from the
    // old one). Fine: the pre-target resync re-anchors before the final delay.
    fun arm(fromMs: Long, toMs: Long, anchor: PlaybackState) {
        if (firing) return   // the shielded pause+seek is already underway
        val segmentChanged = this.fromMs != fromMs || this.toMs != toMs
        this.anchor = anchor
        this.fromMs = fromMs
        this.toMs = toMs
        if (isArmed && !segmentChanged) return
        job?.cancel()
        job = scope.launch { run() }
    }

    fun disarm() {
        job?.cancel()   // a pause+seek already in flight is shielded and finishes
        job = null
    }

    private suspend fun run() {
        while (true) {
            val position = anchor?.positionAt(clock()) ?: return
            val remaining = toMs - position
            when {
                remaining > FAR_MS -> {
                    delay(RESYNC_INTERVAL_MS)
                    resync()
                }
                remaining > NEAR_MS -> {
                    // One last correction shortly before the shot, so the final delay starts from a fresh anchor.
                    delay(remaining - PRE_TARGET_MS)
                    resync()
                }
                else -> {
                    // Never negative: if we are already late, fire now. Aim to land ON the target, not before.
                    delay((remaining - rttEstimateMs / 2).coerceAtLeast(0L))
                    fire()
                    return
                }
            }
        }
    }

    private suspend fun resync() {
        val result = call { client.getPlaybackState() }
        listener.onSynced(result)
        // The listener may have disarmed us (song changed, external pause...). Without this the
        // loop would run one more step on a stale anchor.
        currentCoroutineContext().ensureActive()
    }

    // Shielded from cancellation: a disarm between pause and seek must not leave the song paused
    // somewhere in the middle, or seek back while it is still playing.
    private suspend fun fire() = withContext(NonCancellable) {
        firing = true
        try {
            val pause = timedCall { client.pause() }
            if (pause !is ApiResult.Ok) {
                listener.onFireFailed(pause, "Could not pause")
                return@withContext
            }
            val seek = timedCall { client.seek(fromMs) }
            if (seek !is ApiResult.Ok) {
                listener.onFireFailed(seek, "Seek failed")
                return@withContext
            }
            listener.onFired(fromMs, clock())
        } finally {
            firing = false
        }
    }

    private suspend fun <T> timedCall(block: suspend () -> ApiResult<T>): ApiResult<T> {
        val start = clock()
        val result = call(block)
        if (result is ApiResult.Ok) recordRoundTrip(clock() - start)
        return result
    }

    // The client reports failures as ApiResult, but a stray exception must not kill the loop silently.
    private suspend fun <T> call(block: suspend () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Network
        }

    companion object {
        const val INITIAL_RTT_MS = 400L
        private const val RTT_WEIGHT_NEW = 3L   // new sample counts 30%
        private const val RTT_WEIGHT_DEN = 10L

        private const val FAR_MS = 7_000L
        private const val NEAR_MS = 3_000L
        private const val RESYNC_INTERVAL_MS = 5_000L
        private const val PRE_TARGET_MS = 2_500L   // the pre-target GET happens this long before "to"
    }
}
