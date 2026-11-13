package com.pureplay.video

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PlaylistDialogs {

    fun showCreatePlaylistDialog(context: Context, onCreated: (UserPlaylist) -> Unit) {
        val input = EditText(context).apply {
            hint = "输入歌单名称"
            setSingleLine(true)
        }
        val container = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("新建歌单")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                } else {
                    val created = PlaylistRepository.createPlaylist(context, name)
                    onCreated(created)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showAddToPlaylistDialog(context: Context, track: AudioTrack) {
        val playlists = PlaylistRepository.getAllPlaylists(context)
        if (playlists.isEmpty()) {
            showCreatePlaylistDialog(context) { created ->
                PlaylistRepository.addTrack(context, created.id, track)
                Toast.makeText(context, "已添加到「${created.name}」", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val names = playlists.map { "${it.name} (${it.tracks.size})" } + "+ 新建歌单"
        MaterialAlertDialogBuilder(context)
            .setTitle("添加到歌单")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    showCreatePlaylistDialog(context) { created ->
                        PlaylistRepository.addTrack(context, created.id, track)
                        Toast.makeText(context, "已添加到「${created.name}」", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val target = playlists[which]
                    val added = PlaylistRepository.addTrack(context, target.id, track)
                    val msg = if (added) "已添加到「${target.name}」" else "该歌曲已在「${target.name}」中"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
