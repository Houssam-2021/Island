package com.blurify.island

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class IslandActions(
    val onTap: () -> Unit,
    val onPrev: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onInteract: () -> Unit,
    val onTorchOff: () -> Unit,
    val onSwap: () -> Unit
)

private val GreenFace = Color(0xFF7DF08E)

@Composable
fun IslandUI(model: IslandModel, geo: Geo, mode: Mode, actions: IslandActions) {
    val bouncy = spring<Dp>(dampingRatio = 0.56f, stiffness = 380f)

    val compactW = (if (model.hasMedia || model.torch) geo.compactMediaW else geo.compactBareW).dp
    // The open island shows the flashlight card when it is on and either asked for or alone
    val torchFocus = model.torch && (model.focusTorch || !model.hasMedia)
    val targetW: Dp
    val targetH: Dp
    val targetR: Dp
    when (mode) {
        Mode.Hidden -> { targetW = geo.holeW.dp; targetH = geo.holeH.dp; targetR = (geo.holeH / 2).dp }
        Mode.Compact -> { targetW = compactW; targetH = geo.pillH.dp; targetR = (geo.pillH / 2).dp }
        Mode.Expanded -> {
            targetW = geo.expandedW.dp
            targetH = (if (torchFocus) geo.torchH else geo.expandedH).dp
            targetR = if (torchFocus) 34.dp else 46.dp
        }
        Mode.Unlock -> { targetW = geo.unlockSize.dp; targetH = geo.unlockSize.dp; targetR = 44.dp }
    }
    val w by animateDpAsState(targetW, bouncy, label = "w")
    val h by animateDpAsState(targetH, bouncy, label = "h")
    val r by animateDpAsState(targetR, spring(0.7f, 420f), label = "r")
    val yOff by animateDpAsState(
        if (mode == Mode.Hidden) geo.pad.dp else 0.dp, spring(0.8f, 500f), label = "y"
    )

    // Expanded content blurs and fades in a moment after the shape starts to grow
    val expandAlpha by animateFloatAsState(
        if (mode == Mode.Expanded) 1f else 0f,
        if (mode == Mode.Expanded) tween(260, delayMillis = 120) else tween(110), label = "ea"
    )
    val compactAlpha by animateFloatAsState(
        if (mode == Mode.Compact) 1f else 0f,
        if (mode == Mode.Compact) tween(220, delayMillis = 90) else tween(90), label = "ca"
    )
    // Nothing is drawn while hidden, even though the window stays up
    val shown by animateFloatAsState(
        if (mode == Mode.Hidden) 0f else 1f,
        if (mode == Mode.Hidden) tween(100, delayMillis = 450) else tween(40), label = "shown"
    )
    val torchA by animateFloatAsState(if (torchFocus) 1f else 0f, tween(240), label = "ta")
    val unlockAlpha by animateFloatAsState(
        if (mode == Mode.Unlock) 1f else 0f,
        if (mode == Mode.Unlock) tween(300, delayMillis = 70) else tween(160), label = "ua"
    )

    // The side bubble: mic (orange) or camera (green) in use
    val recording = model.mic || model.cam
    val dotScale by animateFloatAsState(
        if (recording && (mode == Mode.Compact)) 1f else 0f,
        spring(0.5f, 420f), label = "dot"
    )
    val dotColor = if (model.cam) Color(0xFF30D158) else Color(0xFFFF9F0A)

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = mode == Mode.Expanded) { actions.onTap() } // a tap beside the open player closes it
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = geo.pillTop.dp)
                .offset(y = yOff)
        ) {
            // The main island
            Box(
                Modifier
                    .size(w, h.coerceAtLeast(1.dp))
                    .align(Alignment.TopCenter)
                    .alpha(shown)
                    .clip(RoundedCornerShape(r.coerceAtLeast(0.dp)))
                    .background(Color.Black)
                    .border(
                        0.8.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))),
                        RoundedCornerShape(r.coerceAtLeast(0.dp))
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (mode == Mode.Compact) actions.onTap() else if (mode == Mode.Expanded) actions.onSwap()
                    }
            ) {
                if (compactAlpha > 0.01f) CompactContent(model, compactAlpha)
                if (expandAlpha > 0.01f) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            // requiredSize would centre this inside a smaller island and push the content up
                            .wrapContentSize(Alignment.TopCenter, unbounded = true)
                            .size(geo.expandedW.dp, geo.expandedH.dp)
                            .alpha(expandAlpha)
                            .blur(((1f - expandAlpha) * 14f).dp)
                    ) {
                        if (torchA < 0.99f) {
                            Box(Modifier.fillMaxSize().alpha(1f - torchA).blur((torchA * 10f).dp)) {
                                ExpandedContent(model, actions)
                            }
                        }
                        if (torchA > 0.01f) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(geo.torchH.dp)
                                    .alpha(torchA)
                                    .blur(((1f - torchA) * 10f).dp)
                            ) { TorchCard(actions) }
                        }
                    }
                }
                if (unlockAlpha > 0.01f) {
                    Box(
                        Modifier
                            .requiredSize(geo.unlockSize.dp)
                            .align(Alignment.TopCenter)
                            .alpha(unlockAlpha)
                            .blur(((1f - unlockAlpha) * 18f).dp)
                    ) { UnlockContent(active = mode == Mode.Unlock) }
                }
            }

            // Bubble that pops out beside the island while the mic or camera is recording
            if (dotScale > 0.01f) {
                val gap = 6.dp
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = w / 2 + gap + (geo.pillH / 2).dp)
                        .size(geo.pillH.dp)
                        .scale(dotScale)
                        .clip(CircleShape)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(dotColor))
                }
            }
        }
    }
}

// ---- compact: album art on the left, waveform on the right ------------------------------------------------

@Composable
private fun CompactContent(model: IslandModel, alpha: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha)
            .blur(((1f - alpha) * 8f).dp)
    ) {
        if (model.hasMedia) {
            Art(model, Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(24.dp), 7.dp)
            // With the flashlight on its icon takes the right-hand place of the waveform
            if (!model.torch) {
                Bars(
                    model.playing, 4, Modifier.align(Alignment.CenterEnd).padding(end = 13.dp).size(18.dp, 16.dp),
                    Color.White.copy(alpha = 0.85f), 2.2.dp
                )
            }
        }
        if (model.torch) {
            TorchIcon(
                Modifier
                    .align(if (model.hasMedia) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(start = 11.dp, end = 11.dp)
                    .size(22.dp)
            )
        }
    }
}

@Composable
private fun Art(model: IslandModel, modifier: Modifier, corner: Dp) {
    val art = model.art
    if (art != null) {
        Image(
            art.asImageBitmap(), null,
            modifier.clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(corner))
                .background(Brush.linearGradient(listOf(Color(0xFF5B6CFF), Color(0xFFB45BFF)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.6f)) }
    }
}

/** Equalizer bars that dance while music plays and rest low when paused. */
@Composable
private fun Bars(playing: Boolean, count: Int, modifier: Modifier, color: Color, barWidth: Dp) {
    val t by rememberInfiniteTransition(label = "bars").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "t"
    )
    val level by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "lvl")
    Canvas(modifier) {
        val bw = barWidth.toPx()
        val gap = (size.width - count * bw) / (count - 1).coerceAtLeast(1)
        for (i in 0 until count) {
            val wave = abs(sin(2.0 * PI * (t * (1 + i % 2) + i * 0.23))).toFloat()
            val frac = 0.22f + level * (0.18f + 0.6f * wave)
            val bh = size.height * frac
            drawRoundRect(
                color,
                Offset(i * (bw + gap), (size.height - bh) / 2),
                Size(bw, bh),
                androidx.compose.ui.geometry.CornerRadius(bw / 2)
            )
        }
    }
}

// ---- flashlight ----------------------------------------------------------------------------------------

/** A small flashlight with a soft glow around its lens. */
@Composable
private fun TorchIcon(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val ox = (size.width - 24f * s) / 2f
        val oy = (size.height - 24f * s) / 2f
        fun o(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Transparent), o(12f, 4f), 11f * s),
            11f * s, o(12f, 4f)
        )
        val head = androidx.compose.ui.graphics.Path().apply {
            moveTo(ox + 6.6f * s, oy + 2.4f * s)
            lineTo(ox + 17.4f * s, oy + 2.4f * s)
            lineTo(ox + 17.4f * s, oy + 6.2f * s)
            lineTo(ox + 14.6f * s, oy + 9.6f * s)
            lineTo(ox + 9.4f * s, oy + 9.6f * s)
            lineTo(ox + 6.6f * s, oy + 6.2f * s)
            close()
        }
        val white = Color.White
        drawPath(head, white)
        drawPath(head, white, style = Stroke(1.6f * s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawRoundRect(
            white, o(9.2f, 9f), Size(5.6f * s, 13f * s), androidx.compose.ui.geometry.CornerRadius(2.4f * s)
        )
        drawLine(Color.Black.copy(alpha = 0.8f), o(12f, 12.6f), o(12f, 16f), 1.8f * s, StrokeCap.Round)
    }
}

/** The open island while the flashlight is on: icon, what it is, and a Turn off button. */
@Composable
private fun TorchCard(a: IslandActions) {
    Row(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TorchIcon(Modifier.size(34.dp))
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Flashlight", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "Flashlight is on", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    a.onTorchOff()
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) { Text("Turn off", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

// ---- expanded music player -----------------------------------------------------------------------------

@Composable
private fun ExpandedContent(model: IslandModel, a: IslandActions) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(250)
        }
    }
    val pos = model.positionAt(now)
    val dur = model.durationMs
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(model, Modifier.size(56.dp), 13.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    model.title.ifBlank { "Not playing" }, color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    model.artist, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Bars(model.playing, 5, Modifier.size(24.dp, 20.dp), Color(0xFFBFD0FF), 2.6.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(pos), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, modifier = Modifier.width(30.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(22.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { o -> a.onSeek((o.x / size.width).coerceIn(0f, 1f)); a.onInteract() }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            a.onSeek((change.position.x / size.width).coerceIn(0f, 1f)); a.onInteract()
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
                Box(
                    Modifier
                        .fillMaxWidth(frac.coerceAtLeast(0.01f))
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.75f))
                )
            }
            Text(
                if (dur > 0) "-" + fmt(dur - pos) else "", color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlIcon(Icons.Rounded.FastRewind, 38.dp, Color.White.copy(alpha = 0.5f)) { a.onPrev(); a.onInteract() }
            ControlIcon(
                if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 44.dp, Color.White
            ) { a.onPlayPause(); a.onInteract() }
            ControlIcon(Icons.Rounded.FastForward, 38.dp, Color.White.copy(alpha = 0.5f)) { a.onNext(); a.onInteract() }
            Icon(
                Icons.Rounded.Headphones, null,
                tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, size: Dp, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size + 14.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size)) }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---- unlock: a blurred green swirl that sharpens into the face --------------------------------------------

@Composable
private fun UnlockContent(active: Boolean) {
    val spin = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }   // 0 = swirl, 1 = face
    val ring = remember { Animatable(0f) }
    val pop = remember { Animatable(0.84f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        spin.snapTo(0f)
        reveal.snapTo(0f)
        ring.snapTo(0f)
        pop.snapTo(0.84f)
        coroutineScope {
            launch { spin.animateTo(1f, tween(1500, easing = LinearEasing)) }
            launch {
                delay(650)
                reveal.animateTo(1f, tween(420))
            }
            launch {
                delay(760)
                ring.animateTo(1f, tween(520))
                pop.animateTo(1f, spring(0.45f, 500f))
            }
        }
    }
    val swirlAlpha = 1f - reveal.value
    Box(Modifier.fillMaxSize()) {
        if (swirlAlpha > 0.01f) {
            // The swirl is soft to begin with and melts further as the face comes in
            Canvas(
                Modifier
                    .fillMaxSize()
                    .alpha(swirlAlpha)
                    .blur((5f + 14f * reveal.value).dp)
            ) {
                val c = Offset(size.width / 2, size.height / 2)
                val r0 = size.minDimension * 0.27f
                val sw = size.minDimension * 0.07f
                val base = spin.value * 900f
                for (k in 0 until 3) {
                    val rr = r0 * (0.5f + 0.22f * k)
                    drawArc(
                        GreenFace.copy(alpha = 0.9f), base * (1 + k * 0.45f) + k * 120f, 120f, false,
                        Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2),
                        style = Stroke(sw, cap = StrokeCap.Round)
                    )
                }
            }
        }
        if (reveal.value > 0.01f) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .alpha(reveal.value)
                    .blur(((1f - reveal.value) * 16f).dp)
            ) {
                val c = Offset(size.width / 2, size.height / 2)
                val rr = size.minDimension * 0.27f * pop.value
                val sw = size.minDimension * 0.045f
                val a = ring.value
                drawArc(
                    GreenFace, -90f, 360f * a, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2),
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
                val eyeA = ((a - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val col = GreenFace.copy(alpha = eyeA)
                val u = rr / 36f
                drawLine(col, Offset(c.x - 12 * u, c.y - 13 * u), Offset(c.x - 12 * u, c.y - 6 * u), sw, StrokeCap.Round)
                drawLine(col, Offset(c.x + 12 * u, c.y - 13 * u), Offset(c.x + 12 * u, c.y - 6 * u), sw, StrokeCap.Round)
                val nose = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x + 2 * u, c.y - 10 * u)
                    lineTo(c.x + 2 * u, c.y + 3 * u)
                    quadraticTo(c.x + 2 * u, c.y + 6 * u, c.x - 2 * u, c.y + 6 * u)
                }
                drawPath(nose, col, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(
                    col, 25f, 130f * eyeA, false,
                    Offset(c.x - 14 * u, c.y + 3 * u), Size(28 * u, 18 * u),
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
            }
        }
    }
}
