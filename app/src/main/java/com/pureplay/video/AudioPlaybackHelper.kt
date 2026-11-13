package com.pureplay.video

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

object AudioPlaybackHelper {

    fun buildControllerFuture(context: Context): ListenableFuture<MediaController> {
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, AudioPlaybackService::class.java)
        )
        return MediaController.Builder(context.applicationContext, token).buildAsync()
    }

    fun toMediaItem(track: AudioTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifEmpty { "PurePlay" })
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
