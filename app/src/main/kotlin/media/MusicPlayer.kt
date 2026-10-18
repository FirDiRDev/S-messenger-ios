// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import com.stoyko.smessenger.R

/**
 * Музыка из чатов в фоне: один проигрыватель на всё приложение, уведомление с кнопками
 * ⏮ ⏯ ⏭ ✕ (и в шторке, и на экране блокировки), управление гарнитурой, очередь из треков того же чата.
 */
object MusicPlayer {
    private const val TAG = "MusicPlayer"
    private const val CHANNEL = "S-messenger music"
    private const val NOTIFICATION_ID = 0x5E31

    const val ACTION_TOGGLE = "com.stoyko.smessenger.music.TOGGLE"
    const val ACTION_NEXT = "com.stoyko.smessenger.music.NEXT"
    const val ACTION_PREV = "com.stoyko.smessenger.music.PREV"
    const val ACTION_STOP = "com.stoyko.smessenger.music.STOP"

    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr")

    private var app: Context? = null
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    var queue: List<Pair<Uri, String>> = emptyList()
        private set
    var index = 0
        private set
    var prepared = false
        private set
    var repeat = false
        private set
    var speed = 1f
        private set

    val current: Pair<Uri, String>? get() = queue.getOrNull(index)
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val position: Int get() = if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0
    val duration: Int get() = if (prepared) runCatching { player?.duration ?: 0 }.getOrDefault(0) else 0

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() {
        listeners.toList().forEach { runCatching { it() } }
        updateNotification()
    }

    /** Треки из той же папки (того же чата), по времени получения. */
    private fun buildQueue(uri: Uri, name: String): List<Pair<Uri, String>> {
        if (uri.scheme != "file") return listOf(uri to name)
        val f = File(uri.path ?: return listOf(uri to name))
        val siblings = f.parentFile?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO && !it.name.startsWith("voice") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        return if (siblings.any { it.absolutePath == f.absolutePath }) siblings.map { Uri.fromFile(it) to it.name } else listOf(uri to name)
    }

    /** Музыкальный ли это файл (не голосовое). */
    fun isMusic(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO && !name.startsWith("voice")

    /**
     * Очередь из открытого чата: все треки этого чата по порядку сообщений.
     * Ставит экран чата; «⏭/⏮» листают по ней и сами включают следующий трек.
     */
    @Volatile
    var queueProvider: (() -> List<Pair<Uri, String>>)? = null

    /** Плейлист (например, треки из одного сообщения): играть с [from], ⏭/⏮ - по списку. */
    fun playList(ctx: Context, list: List<Pair<Uri, String>>, from: Int) {
        if (list.isEmpty()) return
        app = ctx.applicationContext
        queue = list
        index = from.coerceIn(0, list.lastIndex)
        start()
    }

    /** Открыть трек: если он уже играет - ничего не меняем. */
    fun play(ctx: Context, uri: Uri, name: String) {
        app = ctx.applicationContext
        if (current?.first == uri && player != null) return
        val chat = runCatching { queueProvider?.invoke() }.getOrNull().orEmpty()
        val at = chat.indexOfFirst { it.first == uri }.takeIf { it >= 0 } ?: chat.indexOfLast { it.second == name }
        if (chat.size > 1 && at >= 0) {
            // Трек из чата: играем именно тот адрес, по которому его открыли.
            queue = chat.toMutableList().also { it[at] = uri to name }
            index = at
        } else {
            queue = buildQueue(uri, name)
            index = queue.indexOfFirst { it.first == uri }.coerceAtLeast(0)
        }
        start()
    }

    private fun start() {
        val ctx = app ?: return
        val (uri, _) = current ?: return
        releasePlayer()
        com.stoyko.smessenger.ui.ThemeMusic.hold("music")
        runCatching { com.stoyko.smessenger.ui.chat.VoicePlayer.stop() }
        val mp = MediaPlayer()
        player = mp
        prepared = false
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mp.setDataSource(ctx, uri)
            mp.setOnPreparedListener {
                prepared = true
                applySpeed()
                it.start()
                changed()
            }
            mp.setOnCompletionListener {
                when {
                    repeat -> {
                        it.seekTo(0)
                        it.start()
                    }
                    index < queue.size - 1 -> next()
                    else -> changed()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "error $what/$extra")
                // Формат не поддерживается кодеками телефона - сказать и предложить другое приложение.
                if (!prepared) {
                    val (u, n) = current ?: (uri to "")
                    runCatching {
                        android.widget.Toast.makeText(ctx, com.stoyko.smessenger.R.string.audio_cant_play, android.widget.Toast.LENGTH_LONG).show()
                        com.stoyko.smessenger.ui.chat.FileTypes.openWith(ctx, u, n)
                    }
                }
                if (index < queue.size - 1) next() else stop()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "start: $e")
            stop()
            return
        }
        ensureSession()
        changed()
    }

    fun toggle() {
        val mp = player ?: return start()
        if (!prepared) return
        if (mp.isPlaying) mp.pause() else mp.start()
        changed()
    }

    fun seekTo(ms: Int) {
        if (prepared) runCatching { player?.seekTo(ms.coerceIn(0, duration)) }
        changed()
    }

    fun seekBy(ms: Int) = seekTo(position + ms)

    fun next() {
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        start()
    }

    fun prev() {
        if (queue.isEmpty()) return
        // Как в плеерах: если трек играет дольше 3 с - в начало, иначе - предыдущий.
        if (position > 3000) {
            seekTo(0)
            return
        }
        index = (index - 1 + queue.size) % queue.size
        start()
    }

    fun toggleRepeat() {
        repeat = !repeat
        changed()
    }

    fun cycleSpeed(): Float {
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        speed = speeds[(speeds.indexOfFirst { it == speed } + 1) % speeds.size]
        applySpeed()
        changed()
        return speed
    }

    private fun applySpeed() {
        if (Build.VERSION.SDK_INT >= 23 && prepared) {
            runCatching {
                val mp = player ?: return
                val wasPlaying = mp.isPlaying
                mp.playbackParams = mp.playbackParams.setSpeed(speed)
                if (!wasPlaying) mp.pause()
            }
        }
    }

    fun stop() {
        com.stoyko.smessenger.ui.ThemeMusic.release("music")
        releasePlayer()
        queue = emptyList()
        index = 0
        runCatching { session?.isActive = false }
        runCatching { session?.release() }
        session = null
        app?.let { (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }
        listeners.toList().forEach { runCatching { it() } }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    // ---------- Управление с экрана блокировки и гарнитуры ----------

    private fun ensureSession() {
        if (session != null) return
        val ctx = app ?: return
        session = MediaSession(ctx, "smessenger-music").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = toggle()
                    override fun onPause() = toggle()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = prev()
                    override fun onStop() = stop()
                    override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                },
            )
            isActive = true
        }
    }

    private fun pending(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            code,
            Intent(ctx, MusicReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )

    @Suppress("DEPRECATION")
    private fun updateNotification() {
        val ctx = app ?: return
        val (_, name) = current ?: return
        val s = session ?: return
        val playing = isPlaying
        runCatching {
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, name.substringBeforeLast('.'))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "StoykoMessenger")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
                    .build(),
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO,
                    )
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position.toLong(), if (playing) speed else 0f)
                    .build(),
            )
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.music_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    },
                )
            }
            Notification.Builder(ctx, CHANNEL)
        } else {
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_LOW)
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0x5E32, it, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        }
        val subtitle = if (queue.size > 1) ctx.getString(R.string.music_track_of, index + 1, queue.size) else ctx.getString(R.string.music_from_chat)
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(name.substringBeforeLast('.'))
            .setContentText(subtitle)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(pending(ctx, ACTION_STOP, 4))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, ctx.getString(R.string.music_prev), pending(ctx, ACTION_PREV, 1)).build())
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    ctx.getString(if (playing) R.string.music_pause else R.string.music_play),
                    pending(ctx, ACTION_TOGGLE, 2),
                ).build(),
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, ctx.getString(R.string.music_next), pending(ctx, ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, ctx.getString(R.string.music_stop), pending(ctx, ACTION_STOP, 4)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
        open?.let { b.setContentIntent(it) }
        runCatching { nm.notify(NOTIFICATION_ID, b.build()) }
    }

    /** Если трек открыт, но уведомлений нет - вызвать при изменении прогресса не нужно: шторка рисует его сама. */
    fun refresh() = main.post { changed() }
}

/** Кнопки уведомления музыки. */
class MusicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MusicPlayer.ACTION_TOGGLE -> MusicPlayer.toggle()
            MusicPlayer.ACTION_NEXT -> MusicPlayer.next()
            MusicPlayer.ACTION_PREV -> MusicPlayer.prev()
            MusicPlayer.ACTION_STOP -> MusicPlayer.stop()
        }
    }
}
