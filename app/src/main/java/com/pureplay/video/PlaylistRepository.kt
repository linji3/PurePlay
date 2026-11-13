package com.pureplay.video

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AudioTrack(
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L
)

data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: MutableList<AudioTrack>
)

object PlaylistRepository {

    private const val PREFS_NAME = "pureplay_playlists"
    private const val KEY_DATA = "playlists_json"

    fun getAllPlaylists(context: Context): MutableList<UserPlaylist> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DATA, null) ?: return mutableListOf()
        val list = mutableListOf<UserPlaylist>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val tracksArr = obj.optJSONArray("tracks") ?: JSONArray()
                val tracks = mutableListOf<AudioTrack>()
                for (j in 0 until tracksArr.length()) {
                    val t = tracksArr.getJSONObject(j)
                    tracks.add(
                        AudioTrack(
                            uri = t.getString("uri"),
                            title = t.getString("title"),
                            artist = t.optString("artist", ""),
                            durationMs = t.optLong("durationMs", 0L)
                        )
                    )
                }
                list.add(UserPlaylist(id, name, tracks))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun getPlaylist(context: Context, id: String): UserPlaylist? {
        return getAllPlaylists(context).firstOrNull { it.id == id }
    }

    fun createPlaylist(context: Context, name: String): UserPlaylist {
        val all = getAllPlaylists(context)
        val playlist = UserPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "新建歌单" },
            tracks = mutableListOf()
        )
        all.add(0, playlist)
        saveAll(context, all)
        return playlist
    }

    fun renamePlaylist(context: Context, id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val all = getAllPlaylists(context)
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(name = trimmed)
            saveAll(context, all)
        }
    }

    fun deletePlaylist(context: Context, id: String) {
        val all = getAllPlaylists(context)
        if (all.removeAll { it.id == id }) {
            saveAll(context, all)
        }
    }

    fun addTrack(context: Context, playlistId: String, track: AudioTrack): Boolean {
        val all = getAllPlaylists(context)
        val playlist = all.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.tracks.any { it.uri == track.uri }) {
            return false
        }
        playlist.tracks.add(track)
        saveAll(context, all)
        return true
    }

    fun removeTrack(context: Context, playlistId: String, uri: String) {
        val all = getAllPlaylists(context)
        val playlist = all.firstOrNull { it.id == playlistId } ?: return
        if (playlist.tracks.removeAll { it.uri == uri }) {
            saveAll(context, all)
        }
    }

    private fun saveAll(context: Context, playlists: List<UserPlaylist>) {
        val arr = JSONArray()
        for (p in playlists) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val tracksArr = JSONArray()
            for (t in p.tracks) {
                val tObj = JSONObject()
                tObj.put("uri", t.uri)
                tObj.put("title", t.title)
                tObj.put("artist", t.artist)
                tObj.put("durationMs", t.durationMs)
                tracksArr.put(tObj)
            }
            obj.put("tracks", tracksArr)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, arr.toString())
            .apply()
    }
}
