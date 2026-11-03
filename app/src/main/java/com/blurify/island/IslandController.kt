package com.blurify.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Surface
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Owns the island: the overlay window over the camera hole, the media / mic / camera listeners, and the
 * rules for which [Mode] is showing. The UI only ever animates toward the mode chosen here.
 */
class IslandController(private val context: Context) {

    companion object {
        private const val PREFS = "island"
        const val KEY_UNLOCK = "unlock_anim"
        private const val COLLAPSE_AFTER_MS = 7000L
        private const val UNLOCK_MS = 2500L
        private const val TORCH_PEEK_MS = 4500L

        fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun unlockAnimEnabled(c: Context) = prefs(c).getBoolean(KEY_UNLOCK, true)
    }

    val model = IslandModel()

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val density = context.resources.displayMetrics.density

    private val uiMode = mutableStateOf(Mode.Hidden)
    private val geoState = mutableStateOf(computeGeo() ?: Geo(90f, 26f, 14f, context.resources.displayMetrics.widthPixels / density))
    private val geo: Geo get() = geoState.value
    private var landscape = false

    private var view: ComposeView? = null
    private var owner: OverlayLifecycle? = null
    private var removeRunnable: Runnable? = null
    private var collapseRunnable: Runnable? = null

    private var controllers: List<MediaController> = emptyList()
    private var active: MediaController? = null
    private var demoActive = false
    private val unavailableCameras = HashSet<String>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bindControllers(list ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
        override fun onSessionDestroyed() = refreshMedia()
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            rawMic = !configs.isNullOrEmpty()
            updateRecording()
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            unavailableCameras.add(cameraId)
            rawCam = unavailableCameras.isNotEmpty()
            updateRecording()
        }

        override fun onCameraAvailable(cameraId: String) {
            unavailableCameras.remove(cameraId)
            rawCam = unavailableCameras.isNotEmpty()
            updateRecording()
        }
    }

    // The flashlight: the card pops open when it is switched on, then settles into a torch icon in the pill
    private val torchIds = HashSet<String>()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (enabled) torchIds.add(cameraId) else torchIds.remove(cameraId)
            val on = torchIds.isNotEmpty()
            if (on == model.torch) return
            model.torch = on
            if (on) {
                model.focusTorch = true
                model.expanded = true
            } else {
                if (model.focusTorch) model.expanded = false
                model.focusTorch = false
            }
            recompute()
            scheduleCollapse()
        }
    }

    private fun flashCameraId(): String? = try {
        camera.cameraIdList.firstOrNull { id ->
            val c = camera.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: Exception) { null }

    private fun turnTorchOff() {
        torchIds.toList().ifEmpty { listOfNotNull(flashCameraId()) }.forEach {
            try { camera.setTorchMode(it, false) } catch (_: Exception) {}
        }
    }

    /** The "Try the flashlight island" button: really switches the torch on, the island reacts like it would to any app. */
    fun demoTorch() {
        val id = flashCameraId() ?: return
        try { camera.setTorchMode(id, true) } catch (_: Exception) {}
        handler.postDelayed({ if (model.torch) turnTorchOff() }, 15_000)
    }

    private var rawMic = false
    private var rawCam = false
    private var recRunnable: Runnable? = null

    private fun updateRecording() {
        recRunnable?.let { handler.removeCallbacks(it) }
        recRunnable = null
        val want = rawMic || rawCam
        val shown = model.mic || model.cam
        if (want == shown) {
            model.mic = rawMic
            model.cam = rawCam
            recompute()
        } else {
            recRunnable = Runnable {
                model.mic = rawMic
                model.cam = rawCam
                recompute()
            }.also { handler.postDelayed(it, if (want) 350L else 900L) }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> if (unlockAnimEnabled(context)) startUnlock()
                Intent.ACTION_SCREEN_OFF -> collapse()
            }
        }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        attachMedia()
        try {
            audio.registerAudioRecordingCallback(recordingCallback, handler)
        } catch (_: Exception) {}
        try {
            camera.registerAvailabilityCallback(cameraCallback, handler)
        } catch (_: Exception) {}
        try {
            camera.registerTorchCallback(torchCallback, handler)
        } catch (_: Exception) {}
        context.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        updateHeadphones()
        recompute()
    }

    private var mediaAttached = false

    /** Starts listening to media sessions; safe to call again once notification access has been granted. */
    fun attachMedia() {
        if (mediaAttached) return
        try {
            val listener = ComponentName(context, IslandService::class.java)
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener)
            mediaAttached = true
            bindControllers(sessions.getActiveSessions(listener))
        } catch (_: SecurityException) {
            // Notification access not granted yet; the listener service calls this again when it is
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mediaAttached = false
        handler.removeCallbacksAndMessages(null)
        try { sessions.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        try { audio.unregisterAudioRecordingCallback(recordingCallback) } catch (_: Exception) {}
        try { camera.unregisterAvailabilityCallback(cameraCallback) } catch (_: Exception) {}
        try { camera.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
        torchIds.clear()
        model.torch = false
        model.focusTorch = false
        try { context.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
        removeWindow()
    }

    fun onConfigurationChanged() {
        // A reading without a cutout (screen off, transition) must not move the island
        computeGeo()?.let { fresh ->
            val old = geoState.value
            if (abs(fresh.top - old.top) > 0.5f || abs(fresh.holeW - old.holeW) > 0.5f ||
                abs(fresh.screenW - old.screenW) > 0.5f) geoState.value = fresh
        }
        recompute()
    }

    // ---- media ---------------------------------------------------------------------------------------------

    private fun bindControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = list
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        refreshMedia()
    }

    private fun refreshMedia() {
        if (demoActive) return
        // A playing session wins. Otherwise stay on the one already shown, so the island never swaps
        // between two apps; only if that one is gone do we take another.
        val current = active?.takeIf { it in controllers && isLive(it) }
        val pick = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: current
            ?: controllers.firstOrNull { isLive(it) }
        if (pick == null) {
            // Apps drop their session for a moment between tracks; hide only if it stays gone
            if (hideRunnable == null && model.hasMedia) {
                hideRunnable = Runnable {
                    hideRunnable = null
                    if (!demoActive && controllers.none { isLive(it) }) {
                        active = null
                        model.hasMedia = false
                        model.playing = false
                        model.expanded = false
                        recompute()
                    }
                }.also { handler.postDelayed(it, 1600L) }
            }
            return
        }
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        active = pick

        val md = pick.metadata
        val st = pick.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        // Metadata is briefly empty while a track changes: keep what is on screen until the new one arrives
        if (title.isNotBlank() || artist.isNotBlank()) {
            if (model.title != title) model.title = title
            if (model.artist != artist) model.artist = artist
            val art = md?.let { loadArt(it) }
            if (art != null || model.title != title) model.art = art
            model.durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        }
        when (st?.state) {
            PlaybackState.STATE_PLAYING -> model.playing = true
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> model.playing = false
            else -> {} // buffering / skipping: keep showing what it was doing, no play-icon flash
        }
        if (st != null) model.setPosition(st.position, st.lastPositionUpdateTime, st.playbackSpeed)
        model.hasMedia = model.title.isNotBlank() || model.artist.isNotBlank()
        updateHeadphones()
        recompute()
    }

    private var hideRunnable: Runnable? = null

    private fun isLive(c: MediaController): Boolean = when (c.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    private fun loadArt(md: MediaMetadata): Bitmap? {
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (uri != null && !uri.startsWith("http")) {
            try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    return android.graphics.BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun updateHeadphones() {
        model.headphones = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> false
            }
        }
    }

    private val actions = IslandActions(
        onTap = { toggleExpanded() },
        onPrev = { active?.transportControls?.skipToPrevious() },
        onPlayPause = {
            if (demoActive) {
                model.playing = !model.playing
                model.setPosition(model.positionAt(android.os.SystemClock.elapsedRealtime()), 0, 1f)
            } else {
                val c = active
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c?.transportControls?.play()
            }
        },
        onNext = { active?.transportControls?.skipToNext() },
        onSeek = { f ->
            if (model.durationMs > 0) {
                val ms = (f * model.durationMs).toLong()
                if (demoActive) model.setPosition(ms, 0, 1f) else active?.transportControls?.seekTo(ms)
                model.setPosition(ms, 0, 1f)
            }
        },
        onInteract = { scheduleCollapse() },
        onTorchOff = { turnTorchOff() },
        onSwap = { swapFocus() }
    )

    // ---- mode ----------------------------------------------------------------------------------------------

    private fun toggleExpanded() {
        if (!(model.hasMedia || model.torch) || model.unlock) return
        model.expanded = !model.expanded
        // Opening with the flashlight on shows its card first
        if (model.expanded) model.focusTorch = model.torch
        scheduleCollapse()
        recompute()
    }

    /** With music and the flashlight both active, a tap on the open island flips between their cards. */
    private fun swapFocus() {
        if (!(model.torch && model.hasMedia)) return
        model.focusTorch = !model.focusTorch
        scheduleCollapse()
    }

    /** The unlock-animation switch in the app changed: keep or drop the standby window. */
    fun refresh() {
        if (!started) return
        if (!unlockAnimEnabled(context) && uiMode.value == Mode.Hidden) removeWindow()
        recompute()
    }

    fun collapse() {
        if (!model.expanded) return
        model.expanded = false
        recompute()
    }

    private fun scheduleCollapse() {
        collapseRunnable?.let { handler.removeCallbacks(it) }
        if (!model.expanded) return
        val wait = if (model.torch && model.focusTorch) TORCH_PEEK_MS else COLLAPSE_AFTER_MS
        collapseRunnable = Runnable { collapse() }.also { handler.postDelayed(it, wait) }
    }

    fun startUnlock() {
        handler.postDelayed({
            model.unlock = true
            recompute()
            handler.postDelayed({
                model.unlock = false
                recompute()
            }, UNLOCK_MS)
        }, 200)
    }

    private fun desiredMode(): Mode = when {
        landscape -> Mode.Hidden
        model.unlock -> Mode.Unlock
        model.expanded && (model.hasMedia || model.torch) -> Mode.Expanded
        model.hasMedia || model.torch || model.mic || model.cam -> Mode.Compact
        else -> Mode.Hidden
    }

    private fun recompute() {
        if (!started) return
        val d = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        landscape = d.getDisplay(Display.DEFAULT_DISPLAY).rotation.let { it == Surface.ROTATION_90 || it == Surface.ROTATION_270 }
        val next = desiredMode()
        if (next == uiMode.value && view != null) return
        // With the unlock animation on, the window stays up (invisible) so it is already running when the phone
        // unlocks; creating it in the middle of the unlock transition left the animation frozen.
        val keep = unlockAnimEnabled(context)
        if (next == Mode.Hidden && view == null && !keep) return

        removeRunnable?.let { handler.removeCallbacks(it) }
        removeRunnable = null

        if (next == Mode.Hidden && keep) {
            ensureWindow()
            uiMode.value = Mode.Hidden
            updateTouchability(Mode.Hidden)
            return
        }

        if (next == Mode.Hidden) {
            uiMode.value = Mode.Hidden
            removeRunnable = Runnable { if (uiMode.value == Mode.Hidden) removeWindow() }.also {
                handler.postDelayed(it, 650)
            }
            return
        }

        ensureWindow()
        uiMode.value = next
        updateTouchability(next)
        if (next == Mode.Expanded) scheduleCollapse()
    }

    // ---- window --------------------------------------------------------------------------------------------

    private fun computeGeo(): Geo? {
        val d = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = d.getDisplay(Display.DEFAULT_DISPLAY)
        val screenW = context.resources.displayMetrics.widthPixels / density
        val rect = display.cutout?.boundingRectTop
        return if (rect != null && !rect.isEmpty) {
            Geo(rect.width() / density, rect.height() / density, rect.top / density, screenW)
        } else {
            null
        }
    }

    private fun px(dp: Float) = (dp * density).toInt()

    /*
     * The island window is never resized: a window that changes size is moved by the system for a moment, which
     * made the whole island jump sideways. Instead the visible window stays one fixed size, does not take touches
     * while the island is small, and a tiny invisible window sitting on the pill catches the tap that opens it.
     */

    private fun fixedHeightDp() = geo.pillTop + maxOf(geo.expandedH, geo.unlockSize) + 24f

    private fun ensureWindow() {
        if (view != null) return
        val lifecycle = OverlayLifecycle().also { it.onCreate() }
        owner = lifecycle
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    collapse()
                    true
                } else false
            }
            setContent { IslandUI(model, geoState.value, uiMode.value, actions) }
        }
        try {
            wm.addView(v, visibleParams(touchable = false))
            view = v
            lifecycle.onResume()
        } catch (e: Exception) {
            e.printStackTrace()
            owner = null
        }
    }

    private fun visibleParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            context.resources.displayMetrics.widthPixels, px(fixedHeightDp()),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Island"
        }
    }

    private var touchable = false
    private var touchCatcher: View? = null
    private var untouchRunnable: Runnable? = null

    /** The visible window takes touches only while the player is open; a small window handles the compact tap. */
    private fun updateTouchability(m: Mode) {
        val v = view ?: return
        val wantTouch = m == Mode.Expanded
        untouchRunnable?.let { handler.removeCallbacks(it) }
        untouchRunnable = null
        if (wantTouch != touchable) {
            val apply = Runnable {
                touchable = wantTouch
                try { wm.updateViewLayout(v, visibleParams(wantTouch)) } catch (_: Exception) {}
            }
            // Wait for the closing spring before the window stops taking touches
            if (wantTouch) apply.run() else handler.postDelayed(apply.also { untouchRunnable = it }, 450)
        }
        if (m == Mode.Compact) showTouchCatcher() else removeTouchCatcher()
    }

    private fun showTouchCatcher() {
        val g = geo
        val w = if (model.hasMedia || model.torch) g.compactMediaW else g.compactBareW
        val lp = WindowManager.LayoutParams(
            px(w), px(g.pillH),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - px(w)) / 2
            y = px(g.pillTop)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Island tap"
        }
        val existing = touchCatcher
        if (existing != null) {
            try { wm.updateViewLayout(existing, lp) } catch (_: Exception) {}
            return
        }
        val t = View(context).apply { setOnClickListener { toggleExpanded() } }
        try {
            wm.addView(t, lp)
            touchCatcher = t
        } catch (_: Exception) {}
    }

    private fun removeTouchCatcher() {
        touchCatcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        touchCatcher = null
    }

    private fun removeWindow() {
        removeTouchCatcher()
        val v = view ?: return
        try {
            owner?.onPause()
            wm.removeView(v)
            owner?.onDestroy()
        } catch (_: Exception) {}
        view = null
        owner = null
        touchable = false
        uiMode.value = Mode.Hidden
    }

    // ---- demo (the Test button in the app) -----------------------------------------------------------------

    fun demo(on: Boolean) {
        demoActive = on
        if (on) {
            model.title = "still love you - Slowed"
            model.artist = "GLM-RST"
            model.durationMs = 3 * 60_000L + 10_000L
            model.playing = true
            model.setPosition(26_000L, 0, 1f)
            model.hasMedia = true
            handler.postDelayed({ if (demoActive) { model.mic = true; recompute() } }, 1500)
            handler.postDelayed({ if (demoActive) toggleExpanded() }, 3200)
            handler.postDelayed({ demo(false) }, 22_000)
            recompute()
        } else {
            model.mic = false
            model.expanded = false
            refreshMedia()
        }
    }
}
