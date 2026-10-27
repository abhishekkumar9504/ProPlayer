package com.example.localproplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var softwareRetryMediaId: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            persistCurrentPosition()
            if (exoPlayer?.isPlaying == true) progressHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.localproplayer.RESTART_SESSION") {
            android.util.Log.i("LocalProPlayer", "old player release")
            exoPlayer?.pause()
            
            mediaSession?.release()
            exoPlayer?.release()
            
            android.util.Log.i("LocalProPlayer", "new player creation")
            val mode = getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE).getInt(KEY_DECODER_MODE, DECODER_MODE_AUTO)
            val newPlayer = createPlayer(mode)
            exoPlayer = newPlayer
            
            android.util.Log.i("LocalProPlayer", "MediaSession recreation")
            mediaSession = createMediaSession(newPlayer)
            
            return START_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createMediaSession(player: ExoPlayer): MediaSession {
        return MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val restored = restoreQueue()
                    if (restored.isEmpty()) {
                        return Futures.immediateFailedFuture(IllegalStateException("No saved local video to resume"))
                    }
                    val prefs = getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE)
                    val startIndex = prefs.getInt(KEY_QUEUE_INDEX, 0).coerceIn(restored.indices)
                    val selected = if (isForPlayback) restored else listOf(restored[startIndex])
                    val selectedIndex = if (isForPlayback) startIndex else 0
                    val selectedUri = selected[selectedIndex].localConfiguration?.uri
                    val startPosition = selectedUri?.let { prefs.getLong(progressKey(it), 0L) } ?: 0L
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(selected, selectedIndex, startPosition)
                    )
                }
            })
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        val mode = getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE).getInt(KEY_DECODER_MODE, DECODER_MODE_AUTO)
        val player = createPlayer(mode)
        exoPlayer = player
        mediaSession = createMediaSession(player)
    }

    private fun createPlayer(mode: Int): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(this).apply {
            when (mode) {
                DECODER_MODE_HARDWARE -> {
                    setEnableDecoderFallback(false)
                    setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                }
                DECODER_MODE_SOFTWARE -> {
                    setEnableDecoderFallback(false)
                    setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
                }
                else -> {
                    setEnableDecoderFallback(true)
                    setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                }
            }
        }
        return ExoPlayer.Builder(this, renderersFactory).build().apply {
            setHandleAudioBecomingNoisy(true)
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    progressHandler.removeCallbacks(saveProgressRunnable)
                    if (isPlaying) progressHandler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_INTERVAL_MS)
                    else persistCurrentPosition()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    persistQueueState()
                    if (mediaItem?.mediaId != softwareRetryMediaId) softwareRetryMediaId = null
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    retryWithSoftwareDecoder()
                }
            })
        }
    }

    private fun retryWithSoftwareDecoder() {
        val mode = getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE).getInt(KEY_DECODER_MODE, DECODER_MODE_AUTO)
        if (mode != DECODER_MODE_AUTO) return
        val currentPlayer = exoPlayer ?: return
        val item = currentPlayer.currentMediaItem ?: return
        if (softwareRetryMediaId == item.mediaId) return
        softwareRetryMediaId = item.mediaId
        
        android.util.Log.e("LocalProPlayer", "Decoder error in Auto mode: broadcasting RETRY_SOFTWARE")
        getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE).edit().putInt(KEY_DECODER_MODE, DECODER_MODE_SOFTWARE).apply()
        sendBroadcast(android.content.Intent("com.example.localproplayer.RETRY_SOFTWARE"))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun persistCurrentPosition() {
        val currentPlayer = exoPlayer ?: return
        val itemUri = currentPlayer.currentMediaItem?.localConfiguration?.uri ?: return
        val duration = currentPlayer.duration
        if (duration <= 0L) return
        val position = currentPlayer.currentPosition
        val prefs = getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE)
        val key = progressKey(itemUri)
        prefs.edit().putLong(durationKey(itemUri), duration).apply()
        if (position < MIN_RESUME_POSITION_MS || position.toDouble() / duration >= WATCHED_FRACTION) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putLong(key, position).apply()
        }
        persistQueueState()
    }

    private fun persistQueueState() {
        val currentPlayer = exoPlayer ?: return
        val items = (0 until currentPlayer.mediaItemCount).map(currentPlayer::getMediaItemAt)
        if (items.isEmpty()) return
        val array = JSONArray()
        items.forEach { item ->
            val itemUri = item.localConfiguration?.uri ?: return@forEach
            val title = item.mediaMetadata.title?.toString().orEmpty()
            array.put(JSONObject().put(JSON_URI, itemUri.toString()).put(JSON_TITLE, title))
        }
        val currentUri = currentPlayer.currentMediaItem?.localConfiguration?.uri ?: return
        val selectedIndex = items.indexOfFirst { it.localConfiguration?.uri == currentUri }.coerceAtLeast(0)
        getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE).edit()
            .putString(KEY_QUEUE_JSON, array.toString())
            .putInt(KEY_QUEUE_INDEX, selectedIndex)
            .apply()
    }

    private fun restoreQueue(): List<MediaItem> {
        val raw = getSharedPreferences(PREFS_LIBRARY, MODE_PRIVATE).getString(KEY_QUEUE_JSON, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val entry = json.getJSONObject(index)
                    val uri = Uri.parse(entry.getString(JSON_URI))
                    val title = entry.optString(JSON_TITLE, uri.lastPathSegment ?: "Video")
                    add(
                        MediaItem.Builder()
                            .setMediaId(uri.toString())
                            .setUri(uri)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                            .build()
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun progressKey(uri: Uri) = "resume_position_${Uri.encode(uri.toString())}"
    private fun durationKey(uri: Uri) = "duration_${Uri.encode(uri.toString())}"

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val currentPlayer = exoPlayer
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(saveProgressRunnable)
        persistCurrentPosition()
        mediaSession?.run {
            player.pause()
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    private companion object {
        const val PREFS_LIBRARY = "library"
        const val KEY_DECODER_MODE = "decoder_mode"
        const val DECODER_MODE_AUTO = 0
        const val DECODER_MODE_HARDWARE = 1
        const val DECODER_MODE_SOFTWARE = 2
        const val KEY_QUEUE_JSON = "session_queue_json"
        const val KEY_QUEUE_INDEX = "session_queue_index"
        const val JSON_URI = "uri"
        const val JSON_TITLE = "title"
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        const val MIN_RESUME_POSITION_MS = 5_000L
        const val WATCHED_FRACTION = 0.95
    }
}


