package com.pureplay.video

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureplay.video.databinding.ActivityPlaylistDetailBinding

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
    }

    private lateinit var binding: ActivityPlaylistDetailBinding
    private var playlistId: String = ""
    private val tracks = mutableListOf<AudioTrack>()
    private lateinit var adapter: TrackAdapter

    private val pickAudioFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        var addedCount = 0
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val title = FormatUtils.resolveDisplayName(this, uri)
            if (PlaylistRepository.addTrack(this, playlistId, AudioTrack(uri.toString(), title))) {
                addedCount++
            }
        }
        reloadPlaylist()
        Toast.makeText(this, "已添加 $addedCount 首歌曲", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID) ?: run {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        adapter = TrackAdapter(
            tracks = tracks,
            onPlay = { index ->
                AudioPlaybackHelper.playTracks(this, tracks, index) {
                    startActivity(Intent(this, AudioPlayerActivity::class.java))
                }
            },
            onRemove = { track ->
                PlaylistRepository.removeTrack(this, playlistId, track.uri)
                reloadPlaylist()
            }
        )
        binding.recyclerTracks.layoutManager = LinearLayoutManager(this)
        binding.recyclerTracks.adapter = adapter

        binding.btnPlayAll.setOnClickListener {
            if (tracks.isEmpty()) {
                Toast.makeText(this, "歌单暂无歌曲", Toast.LENGTH_SHORT).show()
            } else {
                AudioPlaybackHelper.playTracks(this, tracks, 0) {
                    startActivity(Intent(this, AudioPlayerActivity::class.java))
                }
            }
        }

        binding.btnAddFromFiles.setOnClickListener {
            showAddSourceMenu()
        }

        reloadPlaylist()
    }

    private fun showAddSourceMenu() {
        val options = arrayOf("从本地扫描音乐中选择", "从文件管理器选择音频文件")
        MaterialAlertDialogBuilder(this)
            .setTitle("添加音乐到歌单")
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickFromMediaStore()
                } else {
                    pickAudioFilesLauncher.launch(arrayOf("audio/*"))
                }
            }
            .show()
    }

    private fun pickFromMediaStore() {
        Thread {
            val localTracks = mutableListOf<AudioTrack>()
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
                        localTracks.add(AudioTrack(uri.toString(), title, artist, dur))
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                if (localTracks.isEmpty()) {
                    Toast.makeText(this, "未扫描到本地音乐，请尝试从文件管理器选择", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = localTracks.map {
                    if (it.artist.isNotEmpty() && it.artist != "<unknown>") "${it.title} - ${it.artist}" else it.title
                }.toTypedArray()
                val checked = BooleanArray(localTracks.size)
                MaterialAlertDialogBuilder(this)
                    .setTitle("勾选要添加的歌曲")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("添加") { _, _ ->
                        var count = 0
                        for (i in localTracks.indices) {
                            if (checked[i]) {
                                if (PlaylistRepository.addTrack(this, playlistId, localTracks[i])) {
                                    count++
                                }
                            }
                        }
                        reloadPlaylist()
                        Toast.makeText(this, "已添加 $count 首歌曲", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun reloadPlaylist() {
        val playlist = PlaylistRepository.getPlaylist(this, playlistId) ?: run {
            finish()
            return
        }
        binding.tvPlaylistName.text = playlist.name
        binding.tvTrackCount.text = "${playlist.tracks.size} 首"
        tracks.clear()
        tracks.addAll(playlist.tracks)
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private class TrackAdapter(
        private val tracks: List<AudioTrack>,
        private val onPlay: (Int) -> Unit,
        private val onRemove: (AudioTrack) -> Unit
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

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
            val item = tracks[position]
            holder.tvTitle.text = item.title
            val artist = if (item.artist.isNotEmpty() && item.artist != "<unknown>") item.artist else "本地音频"
            val dur = if (item.durationMs > 0) "  ·  ${FormatUtils.formatDuration(item.durationMs)}" else ""
            holder.tvMeta.text = "$artist$dur"
            holder.btnAction.setImageResource(R.drawable.ic_delete)
            holder.itemView.setOnClickListener { onPlay(position) }
            holder.btnAction.setOnClickListener { onRemove(item) }
        }

        override fun getItemCount(): Int = tracks.size
    }
}
