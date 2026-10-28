package com.cuepoint

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The practice segment of one track: where to start and, optionally, where to stop.
 * Instructors keep one segment per song (named multi-segments are a later idea).
 */
data class Segment(val trackId: String, val fromMs: Long, val toMs: Long?)

/** What the ViewModel needs from segment storage; tests (Phase 6) can fake it. C# analogue: ISegmentStore. */
interface SegmentStore {
    suspend fun load(trackId: String): Segment?
    suspend fun save(segment: Segment)
}

/**
 * One DataStore entry per track: key `segment_<trackId>`, value `"fromMs,toMs"` (empty toMs = no "to").
 * Uses the app's single shared [dataStore] (declared in SpotifyApi.kt); a second preferencesDataStore
 * for the same file would crash.
 */
class DataStoreSegmentStore(private val context: Context) : SegmentStore {

    override suspend fun load(trackId: String): Segment? {
        val raw = context.dataStore.data.first()[key(trackId)] ?: return null
        return decode(trackId, raw)
    }

    override suspend fun save(segment: Segment) {
        context.dataStore.edit { it[key(segment.trackId)] = encode(segment) }
    }

    private fun key(trackId: String) = stringPreferencesKey("segment_$trackId")

    // Pure and internal so a unit test can reach them without a Context.
    internal companion object {
        fun encode(segment: Segment): String = "${segment.fromMs},${segment.toMs ?: ""}"

        // A malformed value counts as "no segment" rather than crashing the sync.
        fun decode(trackId: String, raw: String): Segment? {
            val parts = raw.split(",")
            if (parts.size != 2) return null
            val from = parts[0].toLongOrNull() ?: return null
            val to = if (parts[1].isEmpty()) null else (parts[1].toLongOrNull() ?: return null)
            return Segment(trackId, from, to)
        }
    }
}

// --- Validation rules ---
//
// Invariant: 0 <= from, from + MIN_SEGMENT_MS <= to (when "to" is set), both within [0, duration]
// when the duration is known (> 0).
//
// Rule for conflicts: "from" always wins. Moving "from" so that "to" would be closer than
// MIN_SEGMENT_MS CLEARS "to" (marking a new start after the old end is the natural way to begin a
// new segment, and silently dragging "to" along would invent a value the user never chose).
// "to" never moves "from": a "to" that is too close to "from" is REJECTED and the old value kept.

const val MIN_SEGMENT_MS = 2_000L

fun clampToTrack(ms: Long, durationMs: Long): Long =
    if (durationMs > 0) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)

/** Returns the new (from, to) after moving "from" to [newFromMs]; "to" is cleared if it no longer fits. */
fun moveFrom(fromMs: Long, toMs: Long?, newFromMs: Long, durationMs: Long): Pair<Long, Long?> {
    val from = clampToTrack(newFromMs, durationMs)
    val to = toMs?.let { clampToTrack(it, durationMs) }?.takeIf { it >= from + MIN_SEGMENT_MS }
    return from to to
}

/** Returns the new "to", or null when [newToMs] is less than MIN_SEGMENT_MS after [fromMs] (rejected). */
fun moveTo(fromMs: Long, newToMs: Long, durationMs: Long): Long? =
    clampToTrack(newToMs, durationMs).takeIf { it >= fromMs + MIN_SEGMENT_MS }
