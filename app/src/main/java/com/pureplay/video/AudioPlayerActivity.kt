package com.pureplay.video

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.pureplay.video.databinding.ActivityAudioPlayerBinding

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var isUserSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 300)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateMetadata()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updatePlayModeLabel()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updatePlayModeLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()
        connectController()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalAudioIntent(intent)
    }

    private fun connectController() {
        val future = AudioPlaybackHelper.buildControllerFuture(this)
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                handleExternalAudioIntent(intent)
                updateMetadata()
                updatePlayModeLabel()
                binding.btnPlayPause.setImageResource(
                    if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                handler.post(progressRunnable)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleExternalAudioIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        intent.data = null
        val title = FormatUtils.resolveDisplayName(this, uri)
        val track = AudioTrack(uri = uri.toString(), title = title)
        val c = controller ?: return
        c.setMediaItem(AudioPlaybackHelper.toMediaItem(track))
        c.prepare()
        c.play()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }

        binding.btnPrev.setOnClickListener {
            controller?.seekToPreviousMediaItem()
        }

        binding.btnNext.setOnClickListener {
            controller?.seekToNextMediaItem()
        }

        binding.btnPlayMode.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            when {
                c.shuffleModeEnabled -> {
                    c.shuffleModeEnabled = false
                    c.repeatMode = Player.REPEAT_MODE_ALL
                }
                c.repeatMode == Player.REPEAT_MODE_ONE -> {
                    c.repeatMode = Player.REPEAT_MODE_ALL
                    c.shuffleModeEnabled = true
                }
                else -> {
                    c.shuffleModeEnabled = false
                    c.repeatMode = Player.REPEAT_MODE_ONE
                }
            }
            updatePlayModeLabel()
        }

        binding.btnAddToPlaylist.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val item = c.currentMediaItem
            if (item == null) {
                Toast.makeText(this, "当前没有正在播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uriStr = item.mediaId.ifEmpty { item.localConfiguration?.uri?.toString() ?: "" }
            val title = item.mediaMetadata.title?.toString() ?: "未知歌曲"
            val artist = item.mediaMetadata.artist?.toString() ?: ""
            val dur = c.duration.coerceAtLeast(0L)
            PlaylistDialogs.showAddToPlaylistDialog(
                this,
                AudioTrack(uri = uriStr, title = title, artist = artist, durationMs = dur)
            )
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.duration?.coerceAtLeast(1L) ?: 1L
                    binding.tvCurrentTime.text = FormatUtils.formatDuration((dur * progress) / 1000L)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                val dur = controller?.duration?.coerceAtLeast(0L) ?: 0L
                controller?.seekTo((dur * (sb?.progress ?: 0)) / 1000L)
            }
        })
    }

    private fun updateMetadata() {
        val c = controller ?: return
        val item = c.currentMediaItem
        binding.tvTrackTitle.text = item?.mediaMetadata?.title ?: "未在播放"
        binding.tvTrackArtist.text = item?.mediaMetadata?.artist ?: "PurePlay"
        val count = c.mediaItemCount.coerceAtLeast(1)
        val idx = (c.currentMediaItemIndex + 1).coerceIn(1, count)
        binding.tvQueueInfo.text = "$idx/$count"
        updateProgress()
    }

    private fun updatePlayModeLabel() {
        val c = controller ?: return
        binding.btnPlayMode.text = when {
            c.shuffleModeEnabled -> "随机"
            c.repeatMode == Player.REPEAT_MODE_ONE -> "单曲"
            else -> "顺序"
        }
    }

    private fun updateProgress() {
        val c = controller ?: return
        if (isUserSeeking) return
        val dur = c.duration.coerceAtLeast(0L)
        val pos = c.currentPosition.coerceAtLeast(0L)
        binding.tvCurrentTime.text = FormatUtils.formatDuration(pos)
        binding.tvTotalTime.text = FormatUtils.formatDuration(dur)
        binding.seekBar.progress = if (dur > 0) ((pos * 1000L) / dur).toInt().coerceIn(0, 1000) else 0
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
