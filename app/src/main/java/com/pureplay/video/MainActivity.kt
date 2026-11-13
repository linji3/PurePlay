package com.pureplay.video

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureplay.video.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: VideoAdapter

    data class VideoItem(
        val uri: Uri,
        val title: String,
        val durationMs: Long,
        val sizeBytes: Long
    )

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            openPlayer(uri, FormatUtils.resolveDisplayName(this, uri))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.btnGrantPermission.visibility = View.GONE
            loadLocalVideos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter(videoList) { item ->
            openPlayer(item.uri, item.title)
        }
        binding.recyclerVideos.layoutManager = LinearLayoutManager(this)
        binding.recyclerVideos.adapter = adapter

        binding.btnPickVideo.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*"))
        }

        binding.btnGrantPermission.setOnClickListener {
            permissionLauncher.launch(requiredPermission())
        }

        checkAndLoadVideos()
    }

    override fun onResume() {
        super.onResume()
        if (hasVideoPermission() && videoList.isEmpty()) {
            loadLocalVideos()
        }
    }

    private fun requiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasVideoPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            requiredPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndLoadVideos() {
        if (hasVideoPermission()) {
            binding.btnGrantPermission.visibility = View.GONE
            loadLocalVideos()
        } else {
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.VISIBLE
        }
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
            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
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
                videoList.clear()
                videoList.addAll(items)
                adapter.notifyDataSetChanged()
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun openPlayer(uri: Uri, title: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
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
}
