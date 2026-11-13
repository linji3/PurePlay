package com.pureplay.video

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.pureplay.video.databinding.ActivityPlayerBinding
import kotlin.math.abs
import kotlin.math.max

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        private const val PREFS_POSITIONS = "playback_positions"
        private const val SEEK_STEP_MS = 10_000L
        private const val AUTO_HIDE_DELAY_MS = 3_500L
        private const val MIN_SCALE = 0.75f
        private const val MAX_SCALE = 6.0f
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var isLocked = false
    private var isUserSeeking = false
    private var isLongPressSpeed = false
    private var normalSpeed = 1.0f
    private val speedOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeLabels = arrayOf("适应", "填充", "拉伸")
    private var resizeIndex = 0

    // Pinch-to-zoom & pan state
    private var scaleFactor = 1.0f
    private var transX = 0f
    private var transY = 0f
    private var isScaling = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Swipe gesture state
    private enum class SwipeMode { NONE, PAN, SEEK, BRIGHTNESS, VOLUME }
    private var swipeMode = SwipeMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var touchSlop = 0
    private var swipeStartPosition = 0L
    private var swipeTargetPosition = 0L
    private var swipeStartBrightness = 0.5f
    private var swipeVolumeFloat = 0f
    private lateinit var audioManager: AudioManager
    private var maxVolume = 15

    // Consecutive double-tap accumulation
    private var lastDoubleTapSide = 0 // -1 left, 1 right
    private var doubleTapAccumMs = 0L
    private var lastDoubleTapTime = 0L

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideHudRunnable = Runnable {
        binding.tvCenterHud.animate().alpha(0f).setDuration(200).withEndAction {
            binding.tvCenterHud.visibility = View.GONE
            binding.tvCenterHud.alpha = 1f
        }.start()
    }
    private val hideDoubleTapLeftRunnable = Runnable { binding.tvDoubleTapLeft.visibility = View.GONE }
    private val hideDoubleTapRightRunnable = Runnable { binding.tvDoubleTapRight.visibility = View.GONE }
    private val hideUnlockRunnable = Runnable { binding.btnUnlockFloating.visibility = View.GONE }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        setupControls()
        setupGestures()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        saveCurrentPosition()
        handleIntent(intent)
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: run {
            finish()
            return
        }
        currentUri = uri
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: FormatUtils.resolveDisplayName(this, uri)
        binding.tvVideoTitle.text = title
        initPlayer(uri)
    }

    private fun initPlayer(uri: Uri) {
        releasePlayer()
        resetZoom(animated = false)

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()
        player = exoPlayer
        binding.playerView.player = exoPlayer

        val savedPos = getSavedPosition(uri)
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        if (savedPos > 2000L) {
            exoPlayer.seekTo(savedPos)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                if (isPlaying) {
                    scheduleAutoHide()
                } else {
                    handler.removeCallbacks(hideControlsRunnable)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    showControls()
                    savePosition(uri, 0L)
                }
                updateProgress()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    // Auto-orient landscape for wide videos if user hasn't forced orientation
                    if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
                        videoSize.width > videoSize.height * 1.15f
                    ) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "无法播放该视频格式: ${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        handler.post(progressRunnable)
        scheduleAutoHide()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleAutoHide()
        }

        binding.btnRewind.setOnClickListener {
            seekBy(-SEEK_STEP_MS)
            showDoubleTapFeedback(-1, SEEK_STEP_MS)
            scheduleAutoHide()
        }

        binding.btnForward.setOnClickListener {
            seekBy(SEEK_STEP_MS)
            showDoubleTapFeedback(1, SEEK_STEP_MS)
            scheduleAutoHide()
        }

        binding.btnResetZoom.setOnClickListener {
            resetZoom(animated = true)
            scheduleAutoHide()
        }

        binding.btnRotate.setOnClickListener {
            requestedOrientation = if (
                resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            scheduleAutoHide()
        }

        binding.btnLock.setOnClickListener {
            setLocked(true)
        }

        binding.btnUnlockFloating.setOnClickListener {
            setLocked(false)
        }

        binding.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speedOptions.size
            normalSpeed = speedOptions[speedIndex]
            player?.setPlaybackSpeed(normalSpeed)
            binding.btnSpeed.text = "${normalSpeed}x"
            showCenterHud("倍速 ${normalSpeed}x")
            scheduleAutoHide()
        }

        binding.btnAspect.setOnClickListener {
            resizeIndex = (resizeIndex + 1) % resizeModes.size
            binding.playerView.resizeMode = resizeModes[resizeIndex]
            binding.btnAspect.text = resizeLabels[resizeIndex]
            resetZoom(animated = true)
            showCenterHud("画面: ${resizeLabels[resizeIndex]}")
            scheduleAutoHide()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = player?.duration?.coerceAtLeast(1L) ?: 1L
                    val target = (dur * progress) / 1000L
                    binding.tvCurrentTime.text = FormatUtils.formatDuration(target)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                val dur = player?.duration?.coerceAtLeast(0L) ?: 0L
                val target = (dur * (sb?.progress ?: 0)) / 1000L
                player?.seekTo(target)
                scheduleAutoHide()
            }
        })
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (isLocked) return false
                isScaling = true
                swipeMode = SwipeMode.NONE
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = getVideoSurface() ?: return false
                val oldScale = scaleFactor
                val newScale = (oldScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = newScale / oldScale

                val cx = target.width / 2f
                val cy = target.height / 2f
                val fx = detector.focusX
                val fy = detector.focusY

                // Keep focal point stationary while zooming + follow two-finger pan
                transX = (transX - (fx - cx)) * factor + (fx - cx) + (fx - lastFocusX)
                transY = (transY - (fy - cy)) * factor + (fy - cy) + (fy - lastFocusY)
                lastFocusX = fx
                lastFocusY = fy

                scaleFactor = newScale
                clampTranslation(target)
                applyTransform(target)

                showCenterHud(String.format("%.1fx", scaleFactor))
                binding.btnResetZoom.visibility = if (scaleFactor > 1.02f) View.VISIBLE else View.GONE
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                if (scaleFactor < 1.0f) {
                    resetZoom(animated = true)
                }
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    toggleFloatingUnlock()
                } else {
                    toggleControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return true
                val w = binding.gestureLayer.width.coerceAtLeast(1)
                val x = e.x
                val now = System.currentTimeMillis()
                when {
                    x < w * 0.35f -> {
                        if (lastDoubleTapSide == -1 && now - lastDoubleTapTime < 850L) {
                            doubleTapAccumMs += SEEK_STEP_MS
                        } else {
                            doubleTapAccumMs = SEEK_STEP_MS
                        }
                        lastDoubleTapSide = -1
                        lastDoubleTapTime = now
                        seekBy(-SEEK_STEP_MS)
                        showDoubleTapFeedback(-1, doubleTapAccumMs)
                    }
                    x > w * 0.65f -> {
                        if (lastDoubleTapSide == 1 && now - lastDoubleTapTime < 850L) {
                            doubleTapAccumMs += SEEK_STEP_MS
                        } else {
                            doubleTapAccumMs = SEEK_STEP_MS
                        }
                        lastDoubleTapSide = 1
                        lastDoubleTapTime = now
                        seekBy(SEEK_STEP_MS)
                        showDoubleTapFeedback(1, doubleTapAccumMs)
                    }
                    else -> {
                        togglePlayPause()
                        val playing = player?.isPlaying == true
                        showCenterHud(if (playing) "播放" else "暂停")
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isLocked || isScaling || swipeMode != SwipeMode.NONE) return
                if (player?.isPlaying == true) {
                    isLongPressSpeed = true
                    player?.setPlaybackSpeed(2.0f)
                    binding.tvSpeedBadge.visibility = View.VISIBLE
                }
            }
        })

        binding.gestureLayer.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastPanX = event.x
                    lastPanY = event.y
                    swipeMode = SwipeMode.NONE
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    swipeMode = SwipeMode.NONE
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    swipeMode = SwipeMode.NONE
                    val remainingIdx = if (event.actionIndex == 0) 1 else 0
                    if (remainingIdx < event.pointerCount) {
                        downX = event.getX(remainingIdx)
                        downY = event.getY(remainingIdx)
                        lastPanX = downX
                        lastPanY = downY
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isLocked || isScaling || isLongPressSpeed || event.pointerCount > 1) {
                        return@setOnTouchListener true
                    }

                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (swipeMode == SwipeMode.NONE) {
                        if (scaleFactor > 1.02f && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            swipeMode = SwipeMode.PAN
                            lastPanX = event.x
                            lastPanY = event.y
                        } else if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                            swipeMode = SwipeMode.SEEK
                            swipeStartPosition = player?.currentPosition ?: 0L
                            swipeTargetPosition = swipeStartPosition
                        } else if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                            val halfWidth = binding.gestureLayer.width / 2f
                            if (downX < halfWidth) {
                                swipeMode = SwipeMode.BRIGHTNESS
                                val lp = window.attributes
                                swipeStartBrightness = if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
                            } else {
                                swipeMode = SwipeMode.VOLUME
                                swipeVolumeFloat = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                            }
                        }
                    }

                    when (swipeMode) {
                        SwipeMode.PAN -> {
                            val target = getVideoSurface()
                            if (target != null) {
                                transX += event.x - lastPanX
                                transY += event.y - lastPanY
                                lastPanX = event.x
                                lastPanY = event.y
                                clampTranslation(target)
                                applyTransform(target)
                            }
                        }

                        SwipeMode.SEEK -> {
                            val p = player ?: return@setOnTouchListener true
                            val dur = p.duration.coerceAtLeast(0L)
                            if (dur > 0) {
                                val width = binding.gestureLayer.width.coerceAtLeast(1)
                                val maxSwipeMs = minOf(120_000L, dur)
                                val deltaMs = ((dx / width) * maxSwipeMs).toLong()
                                swipeTargetPosition = (swipeStartPosition + deltaMs).coerceIn(0L, dur)
                                val diff = swipeTargetPosition - swipeStartPosition
                                val diffStr = (if (diff >= 0) "+" else "") + FormatUtils.formatDuration(diff)
                                showCenterHud(
                                    "${FormatUtils.formatDuration(swipeTargetPosition)} / ${FormatUtils.formatDuration(dur)}\n$diffStr"
                                )
                            }
                        }

                        SwipeMode.BRIGHTNESS -> {
                            val h = binding.gestureLayer.height.coerceAtLeast(1)
                            val delta = -dy / (h * 0.65f)
                            val newBright = (swipeStartBrightness + delta).coerceIn(0.01f, 1.0f)
                            val lp = window.attributes
                            lp.screenBrightness = newBright
                            window.attributes = lp
                            showCenterHud("亮度 ${(newBright * 100).toInt()}%")
                        }

                        SwipeMode.VOLUME -> {
                            val h = binding.gestureLayer.height.coerceAtLeast(1)
                            val delta = -(event.y - lastPanY) / (h * 0.65f) * maxVolume
                            lastPanY = event.y
                            swipeVolumeFloat = (swipeVolumeFloat + delta).coerceIn(0f, maxVolume.toFloat())
                            val volInt = swipeVolumeFloat.toInt()
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volInt, 0)
                            val pct = ((volInt.toFloat() / maxVolume) * 100).toInt()
                            showCenterHud("音量 $pct%")
                        }

                        SwipeMode.NONE -> {}
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressSpeed) {
                        isLongPressSpeed = false
                        player?.setPlaybackSpeed(normalSpeed)
                        binding.tvSpeedBadge.visibility = View.GONE
                    }
                    if (swipeMode == SwipeMode.SEEK) {
                        player?.seekTo(swipeTargetPosition)
                    }
                    swipeMode = SwipeMode.NONE
                }
            }
            true
        }
    }

    private fun getVideoSurface(): View? = binding.playerView.videoSurfaceView

    private fun clampTranslation(target: View) {
        val maxTx = max(0f, (target.width * scaleFactor - binding.playerView.width) / 2f + 120f)
        val maxTy = max(0f, (target.height * scaleFactor - binding.playerView.height) / 2f + 120f)
        transX = transX.coerceIn(-maxTx, maxTx)
        transY = transY.coerceIn(-maxTy, maxTy)
    }

    private fun applyTransform(target: View) {
        target.scaleX = scaleFactor
        target.scaleY = scaleFactor
        target.translationX = transX
        target.translationY = transY
    }

    private fun resetZoom(animated: Boolean) {
        scaleFactor = 1.0f
        transX = 0f
        transY = 0f
        binding.btnResetZoom.visibility = View.GONE
        val target = getVideoSurface() ?: return
        if (!animated) {
            applyTransform(target)
            return
        }
        val animSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(target, View.SCALE_X, target.scaleX, 1f),
                ObjectAnimator.ofFloat(target, View.SCALE_Y, target.scaleY, 1f),
                ObjectAnimator.ofFloat(target, View.TRANSLATION_X, target.translationX, 0f),
                ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, target.translationY, 0f)
            )
            duration = 220
            interpolator = DecelerateInterpolator()
        }
        animSet.start()
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) {
                p.seekTo(0)
            }
            p.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val target = if (dur > 0) {
            (p.currentPosition + deltaMs).coerceIn(0L, dur)
        } else {
            (p.currentPosition + deltaMs).coerceAtLeast(0L)
        }
        p.seekTo(target)
        updateProgress()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateProgress() {
        val p = player ?: return
        if (isUserSeeking) return
        val dur = p.duration.coerceAtLeast(0L)
        val pos = p.currentPosition.coerceAtLeast(0L)
        binding.tvCurrentTime.text = FormatUtils.formatDuration(pos)
        binding.tvTotalTime.text = FormatUtils.formatDuration(dur)
        if (dur > 0) {
            binding.seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        } else {
            binding.seekBar.progress = 0
        }
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.controlsOverlay.animate().alpha(1f).setDuration(150).start()
        scheduleAutoHide()
    }

    private fun hideControls() {
        controlsVisible = false
        handler.removeCallbacks(hideControlsRunnable)
        binding.controlsOverlay.animate().alpha(0f).setDuration(180).withEndAction {
            if (!controlsVisible) binding.controlsOverlay.visibility = View.GONE
        }.start()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true && !isUserSeeking) {
            handler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        if (locked) {
            hideControls()
            binding.btnUnlockFloating.visibility = View.VISIBLE
            handler.removeCallbacks(hideUnlockRunnable)
            handler.postDelayed(hideUnlockRunnable, 2000)
            showCenterHud(getString(R.string.locked_hint))
        } else {
            binding.btnUnlockFloating.visibility = View.GONE
            showControls()
            showCenterHud(getString(R.string.unlocked_hint))
        }
    }

    private fun toggleFloatingUnlock() {
        if (binding.btnUnlockFloating.visibility == View.VISIBLE) {
            binding.btnUnlockFloating.visibility = View.GONE
        } else {
            binding.btnUnlockFloating.visibility = View.VISIBLE
            handler.removeCallbacks(hideUnlockRunnable)
            handler.postDelayed(hideUnlockRunnable, 2500)
        }
    }

    private fun showCenterHud(text: String) {
        handler.removeCallbacks(hideHudRunnable)
        binding.tvCenterHud.animate().cancel()
        binding.tvCenterHud.alpha = 1f
        binding.tvCenterHud.text = text
        binding.tvCenterHud.visibility = View.VISIBLE
        handler.postDelayed(hideHudRunnable, 900)
    }

    private fun showDoubleTapFeedback(side: Int, accumMs: Long) {
        val sec = (accumMs / 1000).toInt()
        if (side < 0) {
            handler.removeCallbacks(hideDoubleTapLeftRunnable)
            binding.tvDoubleTapLeft.text = "-${sec}s"
            binding.tvDoubleTapLeft.visibility = View.VISIBLE
            handler.postDelayed(hideDoubleTapLeftRunnable, 650)
        } else {
            handler.removeCallbacks(hideDoubleTapRightRunnable)
            binding.tvDoubleTapRight.text = "+${sec}s"
            binding.tvDoubleTapRight.visibility = View.VISIBLE
            handler.postDelayed(hideDoubleTapRightRunnable, 650)
        }
    }

    private fun saveCurrentPosition() {
        val uri = currentUri ?: return
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0 && pos > 2000L && pos < dur - 3000L) {
            savePosition(uri, pos)
        } else if (dur > 0 && pos >= dur - 3000L) {
            savePosition(uri, 0L)
        }
    }

    private fun savePosition(uri: Uri, posMs: Long) {
        getSharedPreferences(PREFS_POSITIONS, MODE_PRIVATE)
            .edit()
            .putLong(uri.toString(), posMs)
            .apply()
    }

    private fun getSavedPosition(uri: Uri): Long {
        return getSharedPreferences(PREFS_POSITIONS, MODE_PRIVATE)
            .getLong(uri.toString(), 0L)
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
