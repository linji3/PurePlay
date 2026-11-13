package com.pureplay.video

import android.content.ContentUris
import android.content.Intent
import android.os.Build
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
import java.io.File

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
    }

    private sealed class PlaylistRow {
        data class FolderHeader(val folderName: String, val count: Int, val firstIndex: Int) : PlaylistRow()
        data class TrackItem(val track: AudioTrack, val playIndex: Int) : PlaylistRow()
    }

    private lateinit var binding: ActivityPlaylistDetailBinding
    private var playlistId: String = ""
    private val tracks = mutableListOf<AudioTrack>()
    private val rows = mutableListOf<PlaylistRow>()
    private lateinit var adapter: TrackAdapter

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        Toast.makeText(this, "正在扫描文件夹音频...", Toast.LENGTH_SHORT).show()
        Thread {
            val scanned = FolderAudioScanner.scanAudioFiles(this, treeUri)
            val addedCount = PlaylistRepository.addTracks(this, playlistId, scanned)
            runOnUiThread {
                reloadPlaylist()
                val msg = when {
                    scanned.isEmpty() -> "该文件夹内未找到音频文件"
                    addedCount == 0 -> "文件夹中的歌曲均已在歌单中"
                    else -> "已从文件夹添加 $addedCount 首歌曲"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private val pickAudioFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val picked = mutableListOf<AudioTrack>()
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val displayName = FormatUtils.resolveDisplayName(this, uri)
            val title = displayName.substringBeforeLast('.').ifEmpty { displayName }
            picked.add(AudioTrack(uri = uri.toString(), title = title, displayName = displayName))
        }
        val addedCount = PlaylistRepository.addTracks(this, playlistId, picked)
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
            rows = rows,
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
        val options = arrayOf(
            "选择文件夹批量添加（推荐，支持同文件夹歌词）",
            "从本地扫描音乐中选择",
            "从文件管理器选择音频文件"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("添加音乐到歌单")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickFolderLauncher.launch(null)
                    1 -> pickFromMediaStore()
                    else -> pickAudioFilesLauncher.launch(arrayOf("audio/*"))
                }
            }
            .show()
    }

    private fun pickFromMediaStore() {
        Thread {
            val localTracks = mutableListOf<AudioTrack>()
            val projection = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection.add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            }
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection.toTypedArray(),
                    selection,
                    null,
                    "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                    } else -1
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "未知歌曲"
                        val artist = cursor.getString(artistCol) ?: ""
                        val dur = cursor.getLong(durCol)
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                        val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                        val folderName = when {
                            bucketCol >= 0 -> cursor.getString(bucketCol).orEmpty()
                            !dataPath.isNullOrEmpty() -> File(dataPath).parentFile?.name.orEmpty()
                            else -> ""
                        }
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        localTracks.add(
                            AudioTrack(
                                uri = uri.toString(),
                                title = title,
                                artist = artist,
                                durationMs = dur,
                                folderName = folderName,
                                displayName = displayName
                            )
                        )
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
                        val selected = localTracks.filterIndexed { i, _ -> checked[i] }
                        val count = PlaylistRepository.addTracks(this, playlistId, selected)
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
        rows.clear()

        val grouped = linkedMapOf<String, MutableList<AudioTrack>>()
        for (t in playlist.tracks) {
            val key = t.folderName.ifEmpty { "单曲 / 未分组" }
            grouped.getOrPut(key) { mutableListOf() }.add(t)
        }

        for ((folder, groupTracks) in grouped) {
            val firstIdx = tracks.size
            rows.add(PlaylistRow.FolderHeader(folder, groupTracks.size, firstIdx))
            for (t in groupTracks) {
                rows.add(PlaylistRow.TrackItem(t, tracks.size))
                tracks.add(t)
            }
        }

        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private class TrackAdapter(
        private val rows: List<PlaylistRow>,
        private val onPlay: (Int) -> Unit,
        private val onRemove: (AudioTrack) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_TRACK = 1
        }

        class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
            val tvFolderCount: TextView = view.findViewById(R.id.tvFolderCount)
        }

        class TrackVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
            val btnAction: ImageButton = view.findViewById(R.id.btnAction)
        }

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is PlaylistRow.FolderHeader -> TYPE_HEADER
                is PlaylistRow.TrackItem -> TYPE_TRACK
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(inflater.inflate(R.layout.item_folder_header, parent, false))
            } else {
                TrackVH(inflater.inflate(R.layout.item_audio, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is PlaylistRow.FolderHeader -> {
                    val h = holder as HeaderVH
                    h.tvFolderName.text = row.folderName
                    h.tvFolderCount.text = "${row.count} 首"
                    h.itemView.setOnClickListener { onPlay(row.firstIndex) }
                }
                is PlaylistRow.TrackItem -> {
                    val h = holder as TrackVH
                    val item = row.track
                    h.tvTitle.text = item.title
                    val artist = if (item.artist.isNotEmpty() && item.artist != "<unknown>") item.artist else "本地音频"
                    val dur = if (item.durationMs > 0) "  ·  ${FormatUtils.formatDuration(item.durationMs)}" else ""
                    h.tvMeta.text = "$artist$dur"
                    h.btnAction.setImageResource(R.drawable.ic_delete)
                    h.itemView.setOnClickListener { onPlay(row.playIndex) }
                    h.btnAction.setOnClickListener { onRemove(item) }
                }
            }
        }

        override fun getItemCount(): Int = rows.size
    }
}
