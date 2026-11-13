package com.pureplay.video

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.pureplay.video.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private enum class Tab { VIDEO, AUDIO, PLAYLIST }

    private lateinit var binding: ActivityMainBinding
    private var currentTab = Tab.VIDEO

    private val videoList = mutableListOf<VideoItem>()
    private val audioList = mutableListOf<AudioTrack>()
    private val playlistList = mutableListOf<UserPlaylist>()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    data class VideoItem(
        val uri: Uri,
        val title: String,
        val durationMs: Long,
        val sizeBytes: Long
    )

    private val openVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistable(uri)
            openVideoPlayer(uri, FormatUtils.resolveDisplayName(this, uri))
        }
    }

    private val openAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistable(uri)
            val track = AudioTrack(uri.toString(), FormatUtils.resolveDisplayName(this, uri))
            AudioPlaybackHelper.playTracks(this, listOf(track), 0) {
                startActivity(Intent(this, AudioPlayerActivity::class.java))
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshCurrentTabData()
    }

    private val miniPlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateMiniPlayer()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateMiniPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateMiniPlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerList.layoutManager = LinearLayoutManager(this)

        binding.tabVideo.setOnClickListener { switchTab(Tab.VIDEO) }
        binding.tabAudio.setOnClickListener { switchTab(Tab.AUDIO) }
        binding.tabPlaylist.setOnClickListener { switchTab(Tab.PLAYLIST) }

        binding.btnPrimaryAction.setOnClickListener {
            when (currentTab) {
                Tab.VIDEO -> openVideoLauncher.launch(arrayOf("video/*"))
                Tab.AUDIO -> openAudioLauncher.launch(arrayOf("audio/*"))
                Tab.PLAYLIST -> PlaylistDialogs.showCreatePlaylistDialog(this) {
                    loadPlaylists()
                }
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            permissionLauncher.launch(requiredPermissions())
        }

        setupMiniPlayer()
        switchTab(Tab.VIDEO)
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentTabData()
        updateMiniPlayer()
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerBar.setOnClickListener {
            startActivity(Intent(this, AudioPlayerActivity::class.java))
        }
        binding.btnMiniPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.btnMiniNext.setOnClickListener {
            controller?.seekToNextMediaItem()
        }

        val future = AudioPlaybackHelper.buildControllerFuture(this)
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(miniPlayerListener)
                updateMiniPlayer()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateMiniPlayer() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null || c.mediaItemCount == 0) {
            binding.miniPlayerBar.visibility = View.GONE
            return
        }
        binding.miniPlayerBar.visibility = View.VISIBLE
        binding.tvMiniTitle.text = item.mediaMetadata.title ?: "正在播放音频"
        binding.btnMiniPlayPause.setImageResource(
            if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        updateTabStyles()
        when (tab) {
            Tab.VIDEO -> {
                binding.tvSubtitle.setText(R.string.pick_video_hint)
                binding.btnPrimaryAction.setText(R.string.pick_video)
                binding.btnPrimaryAction.setIconResource(R.drawable.ic_folder)
                binding.tvSectionTitle.setText(R.string.local_videos)
            }
            Tab.AUDIO -> {
                binding.tvSubtitle.setText(R.string.pick_audio_hint)
                binding.btnPrimaryAction.setText(R.string.pick_audio)
                binding.btnPrimaryAction.setIconResource(R.drawable.ic_music)
                binding.tvSectionTitle.setText(R.string.local_audios)
            }
            Tab.PLAYLIST -> {
                binding.tvSubtitle.setText(R.string.pick_audio_hint)
                binding.btnPrimaryAction.setText(R.string.create_playlist)
                binding.btnPrimaryAction.setIconResource(R.drawable.ic_add)
                binding.tvSectionTitle.setText(R.string.my_playlists)
            }
        }
        refreshCurrentTabData()
    }

    private fun updateTabStyles() {
        val activeBg = ContextCompat.getColor(this, R.color.bg_card)
        val activeText = Color.WHITE
        val inactiveText = ContextCompat.getColor(this, R.color.text_secondary)

        val tabs = listOf(
            binding.tabVideo to (currentTab == Tab.VIDEO),
            binding.tabAudio to (currentTab == Tab.AUDIO),
            binding.tabPlaylist to (currentTab == Tab.PLAYLIST)
        )
        for ((view, active) in tabs) {
            view.setBackgroundColor(if (active) activeBg else Color.TRANSPARENT)
            view.setTextColor(if (active) activeText else inactiveText)
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun refreshCurrentTabData() {
        when (currentTab) {
            Tab.VIDEO -> {
                val hasPerm = hasPermissionFor(Tab.VIDEO)
                binding.btnGrantPermission.visibility = if (hasPerm) View.GONE else View.VISIBLE
                if (hasPerm) loadLocalVideos() else showEmpty(getString(R.string.no_videos))
            }
            Tab.AUDIO -> {
                val hasPerm = hasPermissionFor(Tab.AUDIO)
                binding.btnGrantPermission.visibility = if (hasPerm) View.GONE else View.VISIBLE
                if (hasPerm) loadLocalAudios() else showEmpty(getString(R.string.no_audios))
            }
            Tab.PLAYLIST -> {
                binding.btnGrantPermission.visibility = View.GONE
                loadPlaylists()
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermissionFor(tab: Tab): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (tab == Tab.AUDIO) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadLocalVideos() {
        Thread {
            val items = mutableListOf<VideoItem>()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "视频"
                        val duration = cursor.getLong(durCol)
                        val size = cursor.getLong(sizeCol)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        items.add(VideoItem(uri, name, duration, size))
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                if (currentTab != Tab.VIDEO) return@runOnUiThread
                videoList.clear()
                videoList.addAll(items)
                binding.recyclerList.adapter = VideoAdapter(videoList) { item ->
                    openVideoPlayer(item.uri, item.title)
                }
                binding.tvEmpty.text = getString(R.string.no_videos)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun loadLocalAudios() {
        Thread {
            val items = mutableListOf<AudioTrack>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "未知歌曲"
                        val artist = cursor.getString(artistCol) ?: ""
                        val dur = cursor.getLong(durCol)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        items.add(AudioTrack(uri.toString(), title, artist, dur))
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                if (currentTab != Tab.AUDIO) return@runOnUiThread
                audioList.clear()
                audioList.addAll(items)
                binding.recyclerList.adapter = AudioAdapter(
                    items = audioList,
                    onPlay = { index ->
                        AudioPlaybackHelper.playTracks(this, audioList, index) {
                            startActivity(Intent(this, AudioPlayerActivity::class.java))
                        }
                    },
                    onAddToPlaylist = { track ->
                        PlaylistDialogs.showAddToPlaylistDialog(this, track)
                    }
                )
                binding.tvEmpty.text = getString(R.string.no_audios)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun loadPlaylists() {
        playlistList.clear()
        playlistList.addAll(PlaylistRepository.getAllPlaylists(this))
        binding.recyclerList.adapter = PlaylistAdapter(
            items = playlistList,
            onClick = { playlist ->
                val intent = Intent(this, PlaylistDetailActivity::class.java).apply {
                    putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
                }
                startActivity(intent)
            },
            onDelete = { playlist ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除歌单")
                    .setMessage("确定删除歌单「${playlist.name}」吗？不会删除本地音频文件。")
                    .setPositiveButton("删除") { _, _ ->
                        PlaylistRepository.deletePlaylist(this, playlist.id)
                        loadPlaylists()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        binding.tvEmpty.text = getString(R.string.no_playlists)
        binding.tvEmpty.visibility = if (playlistList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEmpty(msg: String) {
        binding.recyclerList.adapter = null
        binding.tvEmpty.text = msg
        binding.tvEmpty.visibility = View.VISIBLE
    }

    private fun takePersistable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun openVideoPlayer(uri: Uri, title: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.removeListener(miniPlayerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    private class VideoAdapter(
        private val items: List<VideoItem>,
        private val onClick: (VideoItem) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            val dur = FormatUtils.formatDuration(item.durationMs)
            val size = FormatUtils.formatSize(item.sizeBytes)
            holder.tvMeta.text = if (size.isNotEmpty()) "$dur  ·  $size" else dur
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class AudioAdapter(
        private val items: List<AudioTrack>,
        private val onPlay: (Int) -> Unit,
        private val onAddToPlaylist: (AudioTrack) -> Unit
    ) : RecyclerView.Adapter<AudioAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
            val btnAction: ImageButton = view.findViewById(R.id.btnAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_audio, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            val artist = if (item.artist.isNotEmpty() && item.artist != "<unknown>") item.artist else "本地音频"
            val dur = if (item.durationMs > 0) "  ·  ${FormatUtils.formatDuration(item.durationMs)}" else ""
            holder.tvMeta.text = "$artist$dur"
            holder.btnAction.setImageResource(R.drawable.ic_add)
            holder.itemView.setOnClickListener { onPlay(position) }
            holder.btnAction.setOnClickListener { onAddToPlaylist(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class PlaylistAdapter(
        private val items: List<UserPlaylist>,
        private val onClick: (UserPlaylist) -> Unit,
        private val onDelete: (UserPlaylist) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvCount: TextView = view.findViewById(R.id.tvCount)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_playlist, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvCount.text = "${item.tracks.size} 首歌曲"
            holder.itemView.setOnClickListener { onClick(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
