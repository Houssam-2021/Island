package com.blurify.island

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the island is currently showing. */
enum class Mode { Hidden, Compact, Expanded, Unlock }

/** Everything the island UI draws, observed by Compose. */
class IslandModel {
    var hasMedia by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)
    var headphones by mutableStateOf(false)

    var durationMs by mutableLongStateOf(0L)
    private var anchorMs by mutableLongStateOf(0L)
    private var anchorTime by mutableLongStateOf(0L)
    private var speed by mutableFloatStateOf(1f)

    var mic by mutableStateOf(false)
    var cam by mutableStateOf(false)

    /** The flashlight is on; [focusTorch] says the open island shows its card rather than the music player. */
    var torch by mutableStateOf(false)
    var focusTorch by mutableStateOf(false)

    var expanded by mutableStateOf(false)
    var unlock by mutableStateOf(false)

    fun setPosition(positionMs: Long, updateTime: Long, playbackSpeed: Float) {
        anchorMs = positionMs
        anchorTime = if (updateTime > 0) updateTime else SystemClock.elapsedRealtime()
        speed = playbackSpeed
    }

    fun positionAt(now: Long): Long {
        val p = if (playing) anchorMs + ((now - anchorTime) * speed).toLong() else anchorMs
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }
}

/** Where the camera hole is, in dp. The island grows out of this spot. */
class Geo(
    val holeW: Float,
    val holeH: Float,
    val top: Float,
    val screenW: Float
) {
    val pad = 5f
    val pillH get() = holeH + 2 * pad
    val pillTop get() = (top - pad).coerceAtLeast(2f)

    val compactMediaW get() = holeW + 2 * 46f
    val compactBareW get() = holeW + 16f
    val expandedW get() = minOf(screenW - 24f, 368f)
    val expandedH = 176f
    val torchH = 80f
    val unlockSize = 132f

    /** Size of the overlay window needed to show [mode], in dp (width, height). */
    fun windowFor(mode: Mode): Pair<Float, Float> = when (mode) {
        Mode.Hidden, Mode.Compact -> (compactMediaW + 2 * (pillH + 12f)) to (pillTop + pillH + 4f)
        Mode.Expanded -> (screenW - 8f) to (pillTop + expandedH + 20f)
        Mode.Unlock -> (unlockSize + 40f) to (pillTop + unlockSize + 24f)
    }
}
