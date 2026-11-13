package com.pureplay.video

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

object AudioPlaybackHelper {

    const val EXTRA_FOLDER_URI = "extra_folder_uri"
    const val EXTRA_FOLDER_NAME = "extra_folder_name"
    const val EXTRA_DISPLAY_NAME = "extra_display_name"

    fun buildControllerFuture(context: Context): ListenableFuture<MediaController> {
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, AudioPlaybackService::class.java)
        )
        return MediaController.Builder(context.applicationContext, token).buildAsync()
    }

    fun toMediaItem(track: AudioTrack): MediaItem {
        val extras = Bundle().apply {
            if (track.folderUri.isNotEmpty()) putString(EXTRA_FOLDER_URI, track.folderUri)
            if (track.folderName.isNotEmpty()) putString(EXTRA_FOLDER_NAME, track.folderName)
            if (track.displayName.isNotEmpty()) putString(EXTRA_DISPLAY_NAME, track.displayName)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifEmpty { "PurePlay" })
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.uri)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(metadata)
            .build()
    }

    fun playTracks(
        context: Context,
        tracks: List<AudioTrack>,
        startIndex: Int = 0,
        onStarted: (() -> Unit)? = null
    ) {
        if (tracks.isEmpty()) return
        val future = buildControllerFuture(context)
        future.addListener({
            try {
                val controller = future.get()
                val items = tracks.map { toMediaItem(it) }
                controller.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
                if (controller.repeatMode == Player.REPEAT_MODE_OFF) {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                }
                controller.prepare()
                controller.play()
                onStarted?.invoke()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
