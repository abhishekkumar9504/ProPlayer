package com.example.localproplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.app.PictureInPictureParams
import android.util.Rational
import java.util.Locale
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

@UnstableApi
class PlayerActivity : Activity() {
    private var player: Player? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var queueUris: List<Uri> = emptyList()
    private var resumePosition = 0L
    private lateinit var playerView: PlayerView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var errorPanel: LinearLayout
    private lateinit var gestureHint: TextView
    private lateinit var zoomValueLabel: TextView
    private lateinit var zoomControls: LinearLayout
    private lateinit var audioTrackButton: TextView
    private lateinit var subtitleTrackButton: TextView
    private lateinit var loadSubtitleButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var lockButton: TextView
    private lateinit var orientationButton: TextView
    private lateinit var pipButton: TextView
    private lateinit var abRepeatButton: TextView
    private lateinit var sleepTimerButton: TextView
    private lateinit var unlockButton: TextView
    private lateinit var decoderButton: TextView
    private lateinit var playerActions: LinearLayout
    private lateinit var tapGestureDetector: GestureDetector
    private var controlsVisible = true
    private var audioTracks: List<AudioTrackOption> = emptyList()
    private var subtitleTracks: List<SubtitleTrackOption> = emptyList()
    private var externalSubtitleUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private var doubleTapInProgress = false
    private var isScreenLocked = false
    private var playbackSpeed = 1.0f
    private var videoMode = VideoMode.FIT
    private var videoZoom = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var repeatStartMs: Long? = null
    private var repeatEndMs: Long? = null
    private var sleepDeadlineMs: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val abRepeatTicker = object : Runnable {
        override fun run() {
            val start = repeatStartMs
            val end = repeatEndMs
            val current = player
            if (start != null && end != null && current?.isPlaying == true && current.currentPosition >= end) {
                current.seekTo(start)
            }
            if (repeatStartMs != null) mainHandler.postDelayed(this, AB_REPEAT_CHECK_MS)
        }
    }
    private val sleepTimerTicker = object : Runnable {
        override fun run() {
            val deadline = sleepDeadlineMs ?: return
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                sleepDeadlineMs = null
                player?.pause()
                sleepTimerButton.text = null
                showGestureHint("Sleep timer paused playback")
            } else {
                sleepTimerButton.text = formatTime(remaining)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mainHandler.removeCallbacks(hideZoomControls)
            mainHandler.removeCallbacks(saveProgress)
            if (isPlaying) mainHandler.postDelayed(saveProgress, PROGRESS_SAVE_INTERVAL_MS) else persistPlaybackProgress()
            zoomControls.visibility = if (isPlaying) View.GONE else View.VISIBLE
            audioTrackButton.visibility = if (controlsVisible && audioTracks.size > 1) View.VISIBLE else View.GONE
            if (isPlaying) mainHandler.postDelayed(hideZoomControls, CONTROLLER_TIMEOUT_MS + 300L)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaUri = mediaItem?.mediaId?.let(Uri::parse) ?: return
            resumePosition = prefs.getLong(progressKey(mediaUri), 0L)
            externalSubtitleUri = prefs.getString(externalSubtitleKey(mediaUri), null)?.let(Uri::parse)
            loadSubtitleButton.text = if (externalSubtitleUri == null) "Load subs" else "Subs ✓"
        }
        override fun onTracksChanged(tracks: Tracks) {
            audioTracks = buildAudioTrackOptions(tracks)
            audioTrackButton.text = if (audioTracks.size > 1) "Audio" else null
            audioTrackButton.visibility = if (controlsVisible && audioTracks.size > 1) View.VISIBLE else View.GONE
            subtitleTracks = buildSubtitleTrackOptions(tracks)
            subtitleTrackButton.text = if (subtitleTracks.isNotEmpty()) "Subs" else null
            subtitleTrackButton.visibility = if (controlsVisible && subtitleTracks.isNotEmpty()) View.VISIBLE else View.GONE
            loadSubtitleButton.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Media session playback failed; service will attempt decoder fallback", error)
            mainHandler.removeCallbacks(showPlaybackError)
            mainHandler.postDelayed(showPlaybackError, DECODER_RETRY_NOTICE_DELAY_MS)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                mainHandler.removeCallbacks(showPlaybackError)
                errorPanel.visibility = View.GONE
            }
        }
    }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartPosition = 0L
    private var gestureStartBrightness = 0.5f
    private var gestureStartVolume = 0
    private var activeGesture = Gesture.NONE
    private var gestureConsumed = false
    private var pendingSeekPosition: Long? = null
    private val hideGestureHint = Runnable { if (::gestureHint.isInitialized) gestureHint.visibility = View.GONE }
    private val showPlaybackError = Runnable {
        if (player?.playerError != null) errorPanel.visibility = View.VISIBLE
    }
    private val hideZoomControls = Runnable { if (player?.isPlaying == true) { if (::zoomControls.isInitialized) zoomControls.visibility = View.GONE; if (::playerActions.isInitialized) playerActions.visibility = View.GONE } }
    private val saveProgress = object : Runnable {
        override fun run() {
            persistPlaybackProgress()
            if (player?.isPlaying == true) mainHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
        }
    }

    private var isSwitchingDecoder = false
    private var decoderSwitchTimeoutRunnable: Runnable? = null
    
    private val fallbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.localproplayer.RETRY_SOFTWARE") {
                Log.e(TAG, "Decoder error: auto-fallback to Software requested by service")
                performDecoderSwitch(2, 0, isRollback = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumePosition = savedInstanceState?.getLong(STATE_POSITION) ?: intent.data?.let { prefs.getLong(progressKey(it), 0L) } ?: 0L
        externalSubtitleUri = intent.data?.let { videoUri -> prefs.getString(externalSubtitleKey(videoUri), null)?.let(Uri::parse) }
        queueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS).orEmpty().mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(fallbackReceiver, IntentFilter("com.example.localproplayer.RETRY_SOFTWARE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fallbackReceiver, IntentFilter("com.example.localproplayer.RETRY_SOFTWARE"))
        }

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS.toInt())
        }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.rgb(16, 19, 25))
            visibility = View.GONE
        }
        errorPanel.addView(TextView(this).apply {
            text = "This video could not be decoded on this device."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        errorPanel.addView(TextView(this).apply {
            text = "Try another file. If the problem continues, the device may not support this video codec."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(16))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        errorPanel.addView(Button(this).apply {
            text = "Back to library"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        gestureHint = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(Color.argb(190, 16, 19, 25))
            visibility = View.GONE
        }

        zoomValueLabel = TextView(this).apply {
            text = "Fit · 1.0×"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Current video view mode and zoom"
        }
        zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.argb(210, 16, 19, 25))
        }
        fun addControl(label: String, description: String, width: Int, action: () -> Unit) {
            zoomControls.addView(Button(this).apply {
                text = label
                contentDescription = description
                isAllCaps = false
                textSize = 13f
                minHeight = dp(48)
                minimumHeight = dp(48)
                setPadding(dp(5), 0, dp(5), 0)
                setTextColor(Color.WHITE)
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(8f).toFloat()
                }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
                    shape,
                    null
                )
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(width), dp(48)))
        }
        addControl("−", "Zoom out", 42) { changeVideoZoom(-0.1f) }
        zoomControls.addView(zoomValueLabel, LinearLayout.LayoutParams(dp(86), dp(42)))
        addControl("+", "Zoom in", 42) { changeVideoZoom(0.1f) }
        addControl("Fit", "Fit video inside screen", 48) { setVideoMode(VideoMode.FIT) }
        addControl("Stretch", "Stretch video to fill screen", 62) { setVideoMode(VideoMode.STRETCH) }
        addControl("Crop", "Crop video to fill screen", 50) { setVideoMode(VideoMode.CROP) }

        // Playback group
        speedButton = iconButton(R.drawable.ic_speed, "1.0×", "Playback speed") { showSpeedDialog() }
        orientationButton = iconButton(R.drawable.ic_rotate, null, "Choose screen orientation") { showOrientationDialog() }
        pipButton = iconButton(R.drawable.ic_pip, null, "Enter picture in picture") { enterPictureInPicture() }
        val playbackGroup = controlGroup(speedButton, orientationButton, pipButton)

        // Tools group
        abRepeatButton = iconButton(R.drawable.ic_ab_repeat, "A–B", "Set or clear A–B repeat points") { advanceAbRepeat() }
        sleepTimerButton = iconButton(R.drawable.ic_sleep, null, "Set a sleep timer") { showSleepTimerDialog() }
        lockButton = iconButton(R.drawable.ic_lock, null, "Lock player controls") { setScreenLocked(true) }
        val toolsGroup = controlGroup(abRepeatButton, sleepTimerButton, lockButton)

        // Media group
        audioTrackButton = iconButton(R.drawable.ic_audio, null, "Select audio language or track") { showAudioTrackDialog() }.apply { visibility = View.GONE }
        subtitleTrackButton = iconButton(R.drawable.ic_subtitle, null, "Select subtitle track") { showSubtitleTrackDialog() }.apply { visibility = View.GONE }
        loadSubtitleButton = iconButton(R.drawable.ic_load_sub, if (externalSubtitleUri == null) "Load" else "Subs ✓", "Load an external SRT or VTT subtitle file") { pickExternalSubtitle() }
        decoderButton = iconButton(R.drawable.ic_decoder, decoderLabelShort(prefs.getInt("decoder_mode", 0)), "Choose decoder mode") { showDecoderDialog() }
        val mediaGroup = controlGroup(audioTrackButton, subtitleTrackButton, loadSubtitleButton, decoderButton)
        
        playerActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.BOTTOM
            
            addView(mediaGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(toolsGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(playbackGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        unlockButton = iconButton(R.drawable.ic_unlock, "Tap to unlock", "Unlock player controls") { setScreenLocked(false) }.apply {
            visibility = View.GONE
            val bgShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(220, 16, 19, 25))
                cornerRadius = dp(28f).toFloat()
            }
            background = bgShape
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
            addView(gestureHint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(unlockButton, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(zoomControls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(18)
            })
            addView(playerActions, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = dp(12)
            })
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controlsVisible = visibility == View.VISIBLE
            playerActions.visibility = visibility
            audioTrackButton.visibility = if (audioTracks.size > 1) View.VISIBLE else View.GONE
            subtitleTrackButton.visibility = if (subtitleTracks.isNotEmpty()) View.VISIBLE else View.GONE
            loadSubtitleButton.visibility = View.VISIBLE
            zoomControls.visibility = visibility
            mainHandler.removeCallbacks(hideZoomControls)
            if (visibility == View.VISIBLE && player?.isPlaying == true) {
                mainHandler.postDelayed(hideZoomControls, CONTROLLER_TIMEOUT_MS + 300L)
            }
        })
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                videoZoom = (videoZoom * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                applyVideoZoom()
                showGestureHint("${"%.1f".format(videoZoom)}×")
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                mainHandler.removeCallbacks(hideGestureHint)
                mainHandler.postDelayed(hideGestureHint, GESTURE_HINT_DURATION_MS)
            }
        })
        tapGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                doubleTapInProgress = true
                val currentPlayer = player ?: return true
                when {
                    event.x < playerView.width / 3f -> {
                        currentPlayer.seekTo((currentPlayer.currentPosition - DOUBLE_TAP_SEEK_MS).coerceAtLeast(0L))
                        showGestureHint("−10 sec")
                    }
                    event.x > playerView.width * 2f / 3f -> {
                        val duration = currentPlayer.duration
                        val target = currentPlayer.currentPosition + DOUBLE_TAP_SEEK_MS
                        currentPlayer.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                        showGestureHint("+10 sec")
                    }
                    else -> {
                        currentPlayer.playWhenReady = !currentPlayer.playWhenReady
                        showGestureHint(if (currentPlayer.playWhenReady) "Playing" else "Paused")
                    }
                }
                return true
            }
        })
        playerView.setOnTouchListener { view, event -> handlePlayerTouch(view, event) }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val uri = intent.data ?: run {
            finish()
            return
        }
        connectToPlaybackService(uri)
    }

    private fun connectToPlaybackService(uri: Uri) {
        if (controllerFuture != null) return
        errorPanel.visibility = View.GONE
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        val mainExecutor = Executor { command -> mainHandler.post(command) }
        future.addListener({
            if (isFinishing || isDestroyed || controllerFuture !== future) return@addListener
            val controller = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "Could not connect to playback service", it)
                errorPanel.visibility = View.VISIBLE
                return@addListener
            }
            player = controller
            controller.addListener(playerListener)
            playerView.player = controller
            playerView.resizeMode = videoMode.resizeMode
            applyVideoZoom()
            val sameItem = controller.currentMediaItem?.mediaId == uri.toString()
            if (!sameItem) {
                val queue = queueUris.takeIf { it.isNotEmpty() } ?: listOf(uri)
                val startIndex = queue.indexOf(uri).coerceAtLeast(0)
                controller.setMediaItems(queue.map(::buildMediaItem), startIndex, resumePosition)
                controller.setPlaybackParameters(PlaybackParameters(playbackSpeed))
                controller.prepare()
                controller.playWhenReady = true
            }
            mainHandler.removeCallbacks(hideZoomControls)
            mainHandler.postDelayed(hideZoomControls, CONTROLLER_TIMEOUT_MS + 300L)
        }, mainExecutor)
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPictureInPicture()
        }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || player?.isPlaying != true) return
        val videoSize = player?.videoSize
        val aspect = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
            (videoSize.width.toFloat() / videoSize.height).coerceIn(0.42f, 2.39f)
        } else {
            16f / 9f
        }
        val ratio = Rational((aspect * 1_000).toInt(), 1_000)
        val params = PictureInPictureParams.Builder().setAspectRatio(ratio).build()
        setPictureInPictureParams(params)
        enterPictureInPictureMode(params)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        zoomControls.visibility = visibility
        playerActions.visibility = visibility
        unlockButton.visibility = if (isInPictureInPictureMode) View.GONE else unlockButton.visibility
        if (isInPictureInPictureMode) playerView.hideController()
    }
    private fun handlePlayerTouch(view: View, event: MotionEvent): Boolean {
        if (isScreenLocked) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) unlockButton.visibility = View.VISIBLE
            return true
        }
        if (errorPanel.visibility == View.VISIBLE) return false
        scaleGestureDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
            activeGesture = Gesture.NONE
            pendingSeekPosition = null
            gestureConsumed = true
            return true
        }
        tapGestureDetector.onTouchEvent(event)
        if (doubleTapInProgress) {
            gestureConsumed = true
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                doubleTapInProgress = false
                mainHandler.removeCallbacks(hideGestureHint)
                mainHandler.postDelayed(hideGestureHint, GESTURE_HINT_DURATION_MS)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.x
                gestureStartY = event.y
                gestureStartPosition = player?.currentPosition ?: 0L
                gestureStartBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                activeGesture = Gesture.NONE
                gestureConsumed = false
                pendingSeekPosition = null
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - gestureStartX
                val dy = event.y - gestureStartY
                
                if (videoZoom > 1.0f && activeGesture == Gesture.NONE) {
                    panX += dx
                    panY += dy
                    gestureStartX = event.x
                    gestureStartY = event.y
                    applyVideoZoom()
                    gestureConsumed = true
                    return true
                }
                
                if (activeGesture == Gesture.NONE && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    activeGesture = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        Gesture.SEEK
                    } else if (gestureStartX < view.width / 2f) {
                        Gesture.BRIGHTNESS
                    } else {
                        Gesture.VOLUME
                    }
                }
                when (activeGesture) {
                    Gesture.SEEK -> {
                        val duration = player?.duration?.takeIf { it > 0L } ?: return true
                        val delta = (dx / view.width.coerceAtLeast(1)) * duration
                        pendingSeekPosition = (gestureStartPosition + delta.toLong()).coerceIn(0L, duration)
                        showGestureHint(formatSeekHint(pendingSeekPosition!! - gestureStartPosition))
                        gestureConsumed = true
                        return true
                    }
                    Gesture.BRIGHTNESS -> {
                        val fraction = (gestureStartBrightness - dy / view.height.coerceAtLeast(1)).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = fraction }
                        showGestureHint("Brightness ${(fraction * 100).toInt()}%")
                        gestureConsumed = true
                        return true
                    }
                    Gesture.VOLUME -> {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val steps = ((-dy / view.height.coerceAtLeast(1)) * maxVolume).toInt()
                        val volume = (gestureStartVolume + steps).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                        showGestureHint("Volume ${volume * 100 / maxVolume}%")
                        gestureConsumed = true
                        return true
                    }
                    Gesture.NONE -> return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pendingSeekPosition?.let { position ->
                    player?.seekTo(position)
                    showGestureHint("Seeked to ${formatTime(position)}")
                }
                val consumed = gestureConsumed
                activeGesture = Gesture.NONE
                if (!consumed) return false
                mainHandler.removeCallbacks(hideGestureHint)
                mainHandler.postDelayed(hideGestureHint, GESTURE_HINT_DURATION_MS)
                return true
            }
        }
        return gestureConsumed
    }

    private fun buildAudioTrackOptions(tracks: Tracks): List<AudioTrackOption> {
        val options = mutableListOf<AudioTrackOption>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                options += AudioTrackOption(
                    label = describeAudioTrack(format, options.size),
                    group = group,
                    trackIndex = index,
                    selected = group.isTrackSelected(index)
                )
            }
        }
        return options
    }

    private fun pickExternalSubtitle() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, REQUEST_EXTERNAL_SUBTITLE)
    }

    @Deprecated("Kept compatible with the initial Activity-only project.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXTERNAL_SUBTITLE || resultCode != RESULT_OK) return
        val subtitleUri = data?.data ?: return
        val displayName = queryDisplayName(subtitleUri)
        if (subtitleMimeType(displayName) == null) {
            AlertDialog.Builder(this).setTitle("Unsupported subtitle")
                .setMessage("Choose an .srt, .vtt, .ass, .ssa, or .ttml subtitle file.")
                .setPositiveButton("OK", null).show()
            return
        }
        runCatching { contentResolver.takePersistableUriPermission(subtitleUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        externalSubtitleUri = subtitleUri
        currentVideoUri()?.let { videoUri -> prefs.edit().putString(externalSubtitleKey(videoUri), subtitleUri.toString()).apply() }
        loadSubtitleButton.text = "Subs ✓"
        reloadVideoWithExternalSubtitle()
    }

    private fun reloadVideoWithExternalSubtitle() {
        val currentPlayer = player ?: return
        val videoUri = currentVideoUri() ?: return
        val position = currentPlayer.currentPosition
        val shouldPlay = currentPlayer.playWhenReady
        val queue = queueUris.takeIf { it.isNotEmpty() } ?: listOf(videoUri)
        val currentIndex = queue.indexOf(videoUri).takeIf { it >= 0 } ?: currentPlayer.currentMediaItemIndex.coerceAtLeast(0)
        currentPlayer.setMediaItems(queue.map(::buildMediaItem), currentIndex, position)
        currentPlayer.prepare()
        currentPlayer.playWhenReady = shouldPlay
        
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }
    private fun buildMediaItem(videoUri: Uri): MediaItem {
        val builder = MediaItem.Builder().setMediaId(videoUri.toString()).setUri(videoUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(queryDisplayName(videoUri)).build())
        val subtitleUriForVideo = prefs.getString(externalSubtitleKey(videoUri), null)?.let(Uri::parse) ?: if (videoUri == intent.data) externalSubtitleUri else null
        subtitleUriForVideo?.let { subtitleUri ->
            val label = queryDisplayName(subtitleUri)
            val mimeType = subtitleMimeType(label) ?: return@let
            val language = Regex("\\.([a-z]{2,3})(?:[-_][A-Za-z]{2,4})?\\.(?:srt|vtt|ass|ssa|ttml)$", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT) ?: Locale.getDefault().language
            val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(mimeType).setLanguage(language).setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            builder.setSubtitleConfigurations(listOf(subtitle))
        }
        return builder.build()
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "Subtitle"
    }

    private fun subtitleMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
    private fun buildSubtitleTrackOptions(tracks: Tracks): List<SubtitleTrackOption> {
        val options = mutableListOf<SubtitleTrackOption>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                options += SubtitleTrackOption(
                    label = describeAudioTrack(format, options.size),
                    group = group,
                    trackIndex = index,
                    selected = group.isTrackSelected(index)
                )
            }
        }
        return options
    }

    private fun showSubtitleTrackDialog() {
        val currentPlayer = player ?: return
        val options = subtitleTracks
        val rows = listOf("Off") + options.map { it.label }
        val activeTrack = options.indexOfFirst { it.selected }
        val checkedRow = if (activeTrack >= 0) activeTrack + 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Subtitle track")
            .setSingleChoiceItems(rows.toTypedArray(), checkedRow) { dialog, which ->
                val builder = currentPlayer.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if (which == 0) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val option = options[which - 1]
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
                }
                currentPlayer.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun describeAudioTrack(format: Format, fallbackIndex: Int): String {
        val language = format.language?.takeUnless { it == "und" || it == "zxx" }
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
            ?.takeIf { it.isNotBlank() }
        val title = format.label?.takeIf { it.isNotBlank() } ?: language ?: "Audio ${fallbackIndex + 1}"
        val codec = when (format.sampleMimeType) {
            "audio/mp4a-latm" -> "AAC"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            "audio/ac3" -> "AC-3"
            "audio/eac3" -> "E-AC-3"
            "audio/vnd.dts" -> "DTS"
            "audio/true-hd" -> "TrueHD"
            else -> null
        }
        val channels = when (format.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            in 3..Int.MAX_VALUE -> "${format.channelCount} ch"
            else -> null
        }
        return listOf(title, codec, channels).filterNotNull().distinct().joinToString(" · ")
    }

    private fun showAudioTrackDialog() {
        val currentPlayer = player ?: return
        val options = audioTracks
        if (options.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Audio track")
                .setMessage("No supported audio tracks are available for this video.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val selectedIndex = options.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("Audio language / track")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), selectedIndex) { dialog, which ->
                val option = options[which]
                currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
                    .build()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun showOrientationDialog() {
        val labels = arrayOf("Auto", "Portrait", "Landscape", "Reverse landscape")
        val values = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        )
        val selected = values.indexOf(requestedOrientation).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle("Screen orientation")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                requestedOrientation = values[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setScreenLocked(locked: Boolean) {
        isScreenLocked = locked
        unlockButton.visibility = View.GONE
        if (locked) {
            playerActions.visibility = View.GONE
            zoomControls.visibility = View.GONE
            playerView.hideController()
        } else {
            playerView.showController()
        }
    }

    private fun advanceAbRepeat() {
        val current = player ?: return
        when {
            repeatStartMs == null -> {
                repeatStartMs = current.currentPosition
                repeatEndMs = null
                abRepeatButton.text = "A ${formatTime(current.currentPosition)}"
                showGestureHint("A point set")
            }
            repeatEndMs == null -> {
                val start = repeatStartMs ?: return
                val end = current.currentPosition
                if (end <= start + AB_REPEAT_MIN_MS) {
                    showGestureHint("Set B at least 1 second after A")
                    return
                }
                repeatEndMs = end
                abRepeatButton.text = "A–B ON"
                mainHandler.removeCallbacks(abRepeatTicker)
                mainHandler.post(abRepeatTicker)
                showGestureHint("A–B repeat on")
            }
            else -> {
                repeatStartMs = null
                repeatEndMs = null
                mainHandler.removeCallbacks(abRepeatTicker)
                abRepeatButton.text = "A–B"
                showGestureHint("A–B repeat cleared")
            }
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of this video", "Cancel timer")
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    sleepDeadlineMs = null
                    mainHandler.removeCallbacks(sleepTimerTicker)
                    sleepTimerButton.text = "Sleep"
                    return@setItems
                }
                if (which == options.size - 2) {
                    val current = player
                    if (current != null && current.duration > current.currentPosition) {
                        sleepDeadlineMs = android.os.SystemClock.elapsedRealtime() + (current.duration - current.currentPosition)
                    }
                } else {
                    val minutes = intArrayOf(15, 30, 45, 60)[which]
                    sleepDeadlineMs = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                }
                mainHandler.removeCallbacks(sleepTimerTicker)
                mainHandler.post(sleepTimerTicker)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
        val labels = speeds.map { "${it}×" }.toTypedArray()
        val selected = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - playbackSpeed) } ?: 2
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                playbackSpeed = speeds[which]
                player?.setPlaybackParameters(PlaybackParameters(playbackSpeed))
                speedButton.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun decoderLabelShort(mode: Int): String = when (mode) {
        1 -> "HW"
        2 -> "SW"
        else -> "Auto"
    }

    private fun showDecoderDialog() {
        val options = arrayOf("Automatic (HW with SW fallback)", "Hardware preferred", "Software preferred")
        val currentMode = prefs.getInt("decoder_mode", 0)
        AlertDialog.Builder(this)
            .setTitle("Decoder mode")
            .setSingleChoiceItems(options, currentMode) { dialog, which ->
                if (which != currentMode) {
                    performDecoderSwitch(which, currentMode, isRollback = false)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performDecoderSwitch(newMode: Int, oldMode: Int, isRollback: Boolean) {
        val currentPlayer = player ?: return
        if (isSwitchingDecoder) {
            Log.w(TAG, "decoder request ignored: switch already in progress")
            return
        }
        
        if (newMode == 2 && !isRollback) {
            var videoTrackFound = false
            var hasSoftwareDecoder = false
            try {
                for (group in currentPlayer.currentTracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until group.length) {
                            videoTrackFound = true
                            val mimeType = group.getTrackFormat(i).sampleMimeType
                            if (mimeType != null) {
                                val infos = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mimeType, false, false)
                                if (infos.any { !it.hardwareAccelerated }) {
                                    hasSoftwareDecoder = true
                                    break
                                }
                            }
                        }
                    }
                    if (hasSoftwareDecoder) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query decoders", e)
            }
            if (videoTrackFound && !hasSoftwareDecoder) {
                Log.w(TAG, "decoder request rejected: no software decoder available for this video")
                android.widget.Toast.makeText(this, "Software decoder unavailable for this video", android.widget.Toast.LENGTH_LONG).show()
                prefs.edit().putInt("decoder_mode", oldMode).apply()
                decoderButton.text = decoderLabelShort(oldMode)
                return
            }
        }

        isSwitchingDecoder = true
        Log.i(TAG, "decoder request: newMode=$newMode, rollback=$isRollback")
        
        val items = (0 until currentPlayer.mediaItemCount).map { currentPlayer.getMediaItemAt(it) }
        val index = currentPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val position = currentPlayer.currentPosition
        val isPlaying = currentPlayer.isPlaying || currentPlayer.playWhenReady
        val speed = currentPlayer.playbackParameters.speed
        val volume = currentPlayer.volume
        val repeatMode = currentPlayer.repeatMode
        val shuffleMode = currentPlayer.shuffleModeEnabled
        val trackParams = currentPlayer.trackSelectionParameters
        
        Log.i(TAG, "saved position: $position, isPlaying: $isPlaying")
        
        playerView.player = null
        releaseController()
        Log.i(TAG, "old player release")
        
        prefs.edit().putInt("decoder_mode", newMode).apply()
        decoderButton.text = decoderLabelShort(newMode)
        showGestureHint(if (isRollback) "Decoder error, rolling back..." else "Switching decoder…")
        
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = "com.example.localproplayer.RESTART_SESSION"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        mainHandler.postDelayed({
            reconnectAfterDecoderSwitch(oldMode, items, index, position, isPlaying, speed, volume, repeatMode, shuffleMode, trackParams, isRollback)
        }, 500)
    }
    
    private fun reconnectAfterDecoderSwitch(
        oldMode: Int, items: List<MediaItem>, index: Int, position: Long,
        isPlaying: Boolean, speed: Float, volume: Float, repeatMode: Int, shuffleMode: Boolean,
        trackParams: TrackSelectionParameters, isRollback: Boolean
    ) {
        Log.i(TAG, "controller reconnection")
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed || controllerFuture !== future) {
                isSwitchingDecoder = false
                return@addListener
            }
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null) {
                Log.e(TAG, "decoder error/timeout: failed to reconnect controller")
                isSwitchingDecoder = false
                errorPanel.visibility = View.VISIBLE
                return@addListener
            }
            player = controller
            controller.addListener(playerListener)
            
            controller.setMediaItems(items, index, position)
            controller.setPlaybackParameters(PlaybackParameters(speed))
            controller.volume = volume
            controller.repeatMode = repeatMode
            controller.shuffleModeEnabled = shuffleMode
            controller.trackSelectionParameters = trackParams
            
            Log.i(TAG, "restored position: $position")
            
            var completed = false
            val timeoutRunnable = Runnable {
                if (completed) return@Runnable
                completed = true
                Log.e(TAG, "decoder error/timeout: player preparation timed out")
                isSwitchingDecoder = false
                if (!isRollback) {
                    android.widget.Toast.makeText(this@PlayerActivity, "Decoder timeout. Rolling back...", android.widget.Toast.LENGTH_LONG).show()
                    performDecoderSwitch(oldMode, oldMode, isRollback = true)
                } else {
                    errorPanel.visibility = View.VISIBLE
                }
            }
            decoderSwitchTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 3000L)
            
            controller.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (completed) return
                    Log.i(TAG, "replacement player state: $state")
                    if (state == Player.STATE_READY) {
                        completed = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        isSwitchingDecoder = false
                        
                        playerView.player = controller
                        if (isPlaying) {
                            controller.playWhenReady = true
                            controller.play()
                            Log.i(TAG, "playback resumed")
                        } else {
                            controller.playWhenReady = false
                        }
                        mainHandler.removeCallbacks(hideGestureHint)
                        mainHandler.postDelayed(hideGestureHint, GESTURE_HINT_DURATION_MS)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (completed) return
                    completed = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    isSwitchingDecoder = false
                    Log.e(TAG, "decoder error/timeout: ${error.errorCodeName} - ${error.cause?.message ?: error.message}")
                    if (!isRollback) {
                        android.widget.Toast.makeText(this@PlayerActivity, "Decoder unsupported. Rolling back...", android.widget.Toast.LENGTH_LONG).show()
                        performDecoderSwitch(oldMode, oldMode, isRollback = true)
                    } else {
                        errorPanel.visibility = View.VISIBLE
                    }
                }
            })
            controller.prepare()
        }, Executor { command -> mainHandler.post(command) })
    }

    private fun setVideoMode(mode: VideoMode) {
        videoMode = mode
        videoZoom = 1.0f
        playerView.resizeMode = mode.resizeMode
        applyVideoZoom()
    }

    private fun changeVideoZoom(delta: Float) {
        videoZoom = (videoZoom + delta).coerceIn(0.5f, 3.0f)
        applyVideoZoom()
    }

    private fun applyVideoZoom() {
        playerView.videoSurfaceView?.apply {
            scaleX = videoZoom
            scaleY = videoZoom
            if (videoZoom > 1.0f) {
                val maxPanX = (width * videoZoom - width) / 2f
                val maxPanY = (height * videoZoom - height) / 2f
                panX = panX.coerceIn(-maxPanX, maxPanX)
                panY = panY.coerceIn(-maxPanY, maxPanY)
            } else {
                panX = 0f
                panY = 0f
            }
            translationX = panX
            translationY = panY
        }
        if (::zoomValueLabel.isInitialized) {
            zoomValueLabel.text = "${videoMode.label} · ${"%.1f".format(videoZoom)}×"
        }
    }

    private fun showGestureHint(message: String) {
        gestureHint.text = message
        gestureHint.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideGestureHint)
        mainHandler.removeCallbacks(showPlaybackError)
    }

    private fun formatSeekHint(deltaMs: Long): String {
        val seconds = deltaMs / 1000
        val sign = if (seconds < 0) "−" else "+"
        return "$sign${kotlin.math.abs(seconds)} sec"
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun currentVideoUri(): Uri? = player?.currentMediaItem?.mediaId?.let(Uri::parse) ?: intent.data
    private fun progressKey(uri: Uri) = "resume_position_${Uri.encode(uri.toString())}"
    private fun externalSubtitleKey(uri: Uri) = "external_subtitle_${Uri.encode(uri.toString())}"

    private fun persistPlaybackProgress() {
        val currentPlayer = player ?: return
        val uri = currentPlayer.currentMediaItem?.mediaId?.let(Uri::parse) ?: intent.data ?: return
        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration
        prefs.edit().putLong("duration_${Uri.encode(uri.toString())}", duration).apply()
        val key = progressKey(uri)
        if (duration <= 0L) return
        prefs.edit().putLong("duration_${Uri.encode(uri.toString())}", duration).apply()
        if (position < MIN_RESUME_POSITION_MS || position.toDouble() / duration >= WATCHED_FRACTION) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putLong(key, position).apply()
        }
        resumePosition = position
    }

    private fun releaseController() {
        playerView.player = null
        player?.removeListener(playerListener)
        player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistPlaybackProgress()
        outState.putLong(STATE_POSITION, player?.currentPosition ?: resumePosition)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(hideGestureHint)
        mainHandler.removeCallbacks(showPlaybackError)
        mainHandler.removeCallbacks(hideZoomControls)
        mainHandler.removeCallbacks(saveProgress)
        mainHandler.removeCallbacks(abRepeatTicker)
        mainHandler.removeCallbacks(sleepTimerTicker)
        persistPlaybackProgress()
        resumePosition = player?.currentPosition ?: resumePosition
        
        if (!isInPictureInPictureMode && !isChangingConfigurations) player?.pause()
        
        releaseController()
        unregisterReceiver(fallbackReceiver)
        super.onStop()
    }

    private fun iconButton(iconResId: Int, initialLabel: String?, description: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = initialLabel
            contentDescription = description
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isAllCaps = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            
            if (iconResId != 0) {
                val drawable = getDrawable(iconResId)?.mutate()
                drawable?.setTint(Color.WHITE)
                setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
                compoundDrawablePadding = dp(2)
            }
            
            val ripple = android.util.TypedValue().apply {
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
            }
            background = getDrawable(ripple.resourceId)
            setOnClickListener { action() }
            minimumWidth = dp(56)
            minimumHeight = dp(56)
        }
    }
    
    private fun controlGroup(vararg buttons: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(160, 16, 19, 25))
                cornerRadius = dp(24f).toFloat() // Pill shape
            }
            background = shape
            setPadding(dp(4), dp(4), dp(4), dp(4))
            
            for (button in buttons) {
                addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val STATE_POSITION = "playback_position"
        const val TAG = "LocalProPlayer"
        const val GESTURE_HINT_DURATION_MS = 900L
        const val CONTROLLER_TIMEOUT_MS = 3_000L
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        const val MIN_RESUME_POSITION_MS = 5_000L
        const val WATCHED_FRACTION = 0.95
        const val DOUBLE_TAP_SEEK_MS = 10_000L
        const val REQUEST_EXTERNAL_SUBTITLE = 1003
        const val EXTRA_QUEUE_URIS = "queue_uris"
        const val EXTRA_QUEUE_INDEX = "queue_index"
        const val DECODER_RETRY_NOTICE_DELAY_MS = 1_500L
        const val AB_REPEAT_CHECK_MS = 250L
        const val AB_REPEAT_MIN_MS = 1_000L
    }

    private data class AudioTrackOption(val label: String, val group: Tracks.Group, val trackIndex: Int, val selected: Boolean)
    private data class SubtitleTrackOption(val label: String, val group: Tracks.Group, val trackIndex: Int, val selected: Boolean)
    private enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME }
    private enum class VideoMode(val label: String, val resizeMode: Int) {
        FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
        STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
        CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    }
}




