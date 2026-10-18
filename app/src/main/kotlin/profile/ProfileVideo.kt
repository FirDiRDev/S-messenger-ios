// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.FileTransferManager
import com.stoyko.smessenger.domain.tox.Tox

/**
 * Видео в профиле (до 10 секунд), как «живая» аватарка: у вас и у друзей в профиле и в шапке
 * чата оно играет по кругу без звука.
 *
 * Если видео длиннее 10 с - выбирается фрагмент (окно выбора [ProfileVideoPicker]). Фрагмент
 * вырезается без перекодирования (быстро и без потери качества), звук убирается.
 * Друзьям уходит так же, как дополнительные фото профиля: объявление «вот моё видео» →
 * друг просит файл, если его ещё нет → файл приходит молча, в чат не попадает.
 */
class ProfileVideo @Inject constructor(
    private val context: Context,
    private val tox: Tox,
    private val fileTransferManager: FileTransferManager,
) {
    init {
        val prevHidden = fileTransferManager.hiddenIncoming
        fileTransferManager.hiddenIncoming = { pk, name -> isVideoFile(name) || prevHidden?.invoke(pk, name) == true }
        val prevDone = fileTransferManager.onIncomingComplete
        fileTransferManager.onIncomingComplete = { ft ->
            if (isVideoFile(ft.fileName)) receive(ft) else prevDone?.invoke(ft)
        }
    }

    /** Сообщить другу, какое у меня видео (пусто - видео нет). */
    fun announce(pk: PublicKey) {
        val f = mine(context)
        // Кто видео ни разу не ставил - ничего не шлём (лишних служебных сообщений нет).
        if (f == null && !prefs(context).getBoolean("ever", false)) return
        runCatching { tox.sendMessage(pk, LIST + (f?.let { hashOf(it) } ?: ""), MessageType.Normal) }
    }

    /** Служебное сообщение о видео. true - обработано (в чат не писать). */
    fun onIncoming(pk: String, text: String): Boolean {
        when {
            text.startsWith(LIST) -> {
                val hash = text.removePrefix(LIST).trim().takeIf { it.matches(HASH) }
                prefs(context).edit().putString("peer_$pk", hash.orEmpty()).apply()
                val dir = peerDir(context, pk)
                dir.listFiles()?.forEach { f -> if (f.nameWithoutExtension != hash) f.delete() }
                if (hash != null && !File(dir, "$hash.mp4").exists()) {
                    runCatching { tox.sendMessage(PublicKey(pk), REQUEST + hash, MessageType.Normal) }
                }
                bump()
                return true
            }
            text.startsWith(REQUEST) -> {
                val f = mine(context) ?: return true
                if (hashOf(f) == text.removePrefix(REQUEST).trim()) {
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
                        fileTransferManager.create(PublicKey(pk), uri, showInChat = false)
                    }.onFailure { Log.w(TAG, "send video: $it") }
                }
                return true
            }
        }
        return false
    }

    private fun receive(ft: FileTransfer) {
        val hash = ft.fileName.removePrefix(FILE_PREFIX).substringBefore('.')
        if (!hash.matches(HASH)) return
        runCatching {
            val src = File(Uri.parse(ft.destination).path ?: return)
            val target = File(peerDir(context, ft.publicKey), "$hash.mp4")
            if (!src.renameTo(target)) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
            bump()
        }.onFailure { Log.w(TAG, "receive video: $it") }
    }

    companion object {
        private const val TAG = "ProfileVideo"
        const val MAX_MS = 10_000L
        private const val LIST = "\u2063🎞L:"
        private const val REQUEST = "\u2063🎞R:"
        private const val FILE_PREFIX = "smpv_"
        private val HASH = Regex("[0-9a-f]{16}")
        private val main = Handler(Looper.getMainLooper())
        private val listeners = LinkedHashSet<() -> Unit>()

        fun listen(l: () -> Unit) = main.post { listeners += l }
        fun unlisten(l: () -> Unit) = main.post { listeners -= l }
        private fun bump() = main.post { listeners.toList().forEach { runCatching { it() } } }

        fun isVideoFile(name: String) = name.startsWith(FILE_PREFIX)
        fun isVideoMessage(text: String) = text.startsWith(LIST) || text.startsWith(REQUEST)

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("profile_video", Context.MODE_PRIVATE)
        private fun myDir(ctx: Context) = File(ctx.filesDir, "profile_video").apply { mkdirs() }
        private fun peerDir(ctx: Context, pk: String) = File(File(ctx.filesDir, "peer_video"), pk).apply { mkdirs() }
        private fun hashOf(f: File) = f.name.removePrefix(FILE_PREFIX).substringBefore('.')

        /** Моё видео профиля (или null). */
        fun mine(ctx: Context): File? = myDir(ctx).listFiles()?.firstOrNull { isVideoFile(it.name) && it.extension == "mp4" }

        /** Видео профиля друга (или null). */
        fun peer(ctx: Context, pk: String): File? {
            val hash = prefs(ctx).getString("peer_$pk", "").orEmpty()
            if (hash.isBlank()) return null
            return File(peerDir(ctx, pk), "$hash.mp4").takeIf { it.exists() }
        }

        fun remove(ctx: Context) {
            myDir(ctx).listFiles()?.forEach { it.delete() }
            bump()
        }

        /** Длительность видео, мс. */
        fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { r.release() }
            }
        }.getOrDefault(0L)

        /**
         * Вырезать из [uri] фрагмент [startMs]…[startMs]+10 с (без звука, без перекодирования)
         * и сделать его видео профиля. Возвращает файл или null.
         */
        fun set(ctx: Context, uri: Uri, startMs: Long): File? = runCatching {
            val tmp = File(myDir(ctx), "tmp.mp4")
            tmp.delete()
            trim(ctx, uri, startMs * 1000, (startMs + MAX_MS) * 1000, tmp)
            if (!tmp.exists() || tmp.length() < 1024) return null
            val hash = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).take(8).joinToString("") { "%02x".format(it) }
            myDir(ctx).listFiles()?.forEach { if (it != tmp) it.delete() }
            val target = File(myDir(ctx), "$FILE_PREFIX$hash.mp4")
            tmp.renameTo(target)
            prefs(ctx).edit().putBoolean("ever", true).apply()
            bump()
            target
        }.onFailure { Log.w(TAG, "set: $it") }.getOrNull()

        /** Копирование видеодорожки в интервале [fromUs, toUs) - с ближайшего ключевого кадра. */
        private fun trim(ctx: Context, uri: Uri, fromUs: Long, toUs: Long, out: File) {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) {
                ex.release()
                throw IllegalStateException("нет видеодорожки")
            }
            ex.selectTrack(track)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            runCatching {
                val r = MediaMetadataRetriever()
                // Освобождается и при ошибке (иначе «A resource failed to call close» в журнале).
                try {
                    r.setDataSource(ctx, uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { muxer.setOrientationHint(it) }
                } finally {
                    runCatching { r.release() }
                }
            }
            val dst = muxer.addTrack(format)
            muxer.start()
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
            val buf = ByteBuffer.allocate(maxOf(size, 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var first = -1L
            try {
                while (true) {
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    val t = ex.sampleTime
                    if (t > toUs) break
                    if (first < 0) first = t
                    info.offset = 0
                    info.presentationTimeUs = t - first
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(dst, buf, info)
                    if (!ex.advance()) break
                }
            } finally {
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
                ex.release()
            }
        }

        /**
         * Поверх аватарки [avatar] (любой разметки): аватарка аккуратно оборачивается в рамку
         * с тем же местом и id, сверху - живое видео. Нет видео - видна обычная аватарка.
         */
        fun attachOver(avatar: View, file: File?) {
            val parent = avatar.parent as? ViewGroup ?: return
            val frame: ViewGroup = if (parent.tag == WRAP) {
                parent
            } else {
                if (file == null) return
                val f = FrameLayout(avatar.context).apply { tag = WRAP }
                val index = parent.indexOfChild(avatar)
                val lp = avatar.layoutParams
                parent.removeViewAt(index)
                // Рамка встаёт на место аватарки (с её id - чтобы разметка вокруг не сдвинулась).
                f.id = avatar.id
                avatar.id = View.generateViewId()
                f.translationX = avatar.translationX
                f.translationY = avatar.translationY
                f.elevation = avatar.elevation
                f.addView(avatar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                parent.addView(f, index, lp)
                f
            }
            attach(frame, file)
            // Видео - строго внутри круга фото (не на его рамке).
            val inset = (avatar as? com.google.android.material.imageview.ShapeableImageView)?.strokeWidth?.toInt() ?: 0
            frame.findViewWithTag<View>(TAG)?.setPadding(inset, inset, inset, inset)
        }

        private const val WRAP = "profile_video_wrap"

        /** То, что двигать/масштабировать вместо аватарки (рамка с видео, если аватарка обёрнута). */
        fun outer(avatar: View): View = (avatar.parent as? View)?.takeIf { it.tag == WRAP } ?: avatar

        /**
         * Живое видео-аватар в [container] (круг, без звука, по кругу). Возвращает true, если
         * видео есть и показано. Плеер сам останавливается, когда вид уходит с экрана.
         */
        fun attach(container: ViewGroup, file: File?): Boolean {
            val old = container.findViewWithTag<View>(TAG)
            if (file == null || !file.exists()) {
                old?.let { container.removeView(it) }
                return false
            }
            if (old != null && old.getTag(com.stoyko.smessenger.R.id.tag_btn_norm) == file.absolutePath) return true
            old?.let { container.removeView(it) }
            val view = LoopVideo(container.context, file).apply {
                tag = TAG
                setTag(com.stoyko.smessenger.R.id.tag_btn_norm, file.absolutePath)
            }
            // Видео - поверх аватарки: у аватарки бывает своя тень (elevation), и тогда без этого
            // она рисовалась ПОВЕРХ видео - видео было не видно (так и было в 3.4.0).
            var top = 0f
            for (i in 0 until container.childCount) top = maxOf(top, container.getChildAt(i).elevation + container.getChildAt(i).translationZ)
            view.elevation = top + 1f * container.resources.displayMetrics.density
            container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return true
        }
    }

    /** Круглое беззвучное видео по кругу (TextureView - обрезается по кругу, в отличие от SurfaceView). */
    private class LoopVideo(ctx: Context, private val file: File) : FrameLayout(ctx), TextureView.SurfaceTextureListener {
        private val texture = TextureView(ctx)
        private var player: MediaPlayer? = null
        private var surface: Surface? = null

        /** Пропорции кадра с учётом поворота (снятое вертикально видео - вертикальное). */
        private val aspect: Float = runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w <= 0f || h <= 0f) 0f else if (rot % 180 != 0) h / w else w / h
            } finally {
                r.release()
            }
        }.getOrDefault(0f)

        init {
            // Круг - строго по центру и квадратный (в неквадратной рамке раньше получался овал).
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val side = minOf(view.width - paddingLeft - paddingRight, view.height - paddingTop - paddingBottom)
                    val l = (view.width - side) / 2
                    val t = (view.height - side) / 2
                    outline.setOval(l, t, l + side, t + side)
                }
            }
            clipToOutline = true
            texture.surfaceTextureListener = this
            addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val side = minOf(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom).coerceAtLeast(1)
            (texture.layoutParams as? LayoutParams)?.let { lp ->
                if (lp.width != side || lp.height != side) {
                    lp.width = side
                    lp.height = side
                    lp.gravity = android.view.Gravity.CENTER
                    post {
                        texture.requestLayout()
                        invalidateOutline()
                        applyCrop()
                    }
                }
            }
        }

        private var lastVw = 0
        private var lastVh = 0

        private fun applyCrop() = crop(lastVw, lastVh)

        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            val s = Surface(st)
            surface = s
            runCatching {
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setSurface(s)
                    setVolume(0f, 0f)
                    isLooping = true
                    setOnVideoSizeChangedListener { _, vw, vh -> crop(vw, vh) }
                    setOnInfoListener { _, what, _ ->
                        // Первый кадр показан - кадрирование ещё раз (размеры уже точно известны).
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) post { applyCrop() }
                        false
                    }
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }.onFailure { Log.w(TAG, "play: $it") }
        }

        /** Заполнить круг без искажения (центр кадра). */
        private fun crop(vw: Int, vh: Int) {
            lastVw = vw
            lastVh = vh
            val tw = texture.width
            val th = texture.height
            if (tw <= 0 || th <= 0) return
            // Пропорции: из метаданных (с поворотом), иначе - из размера кадра плеера.
            val a = if (aspect > 0f) aspect else if (vw > 0 && vh > 0) vw.toFloat() / vh else return
            val view = tw.toFloat() / th
            val sx: Float
            val sy: Float
            if (a > view) {
                sx = a / view
                sy = 1f
            } else {
                sx = 1f
                sy = view / a
            }
            // Заполнить круг без искажения: лишнее по краям обрезается, центр кадра - в центре круга.
            texture.setTransform(android.graphics.Matrix().apply { setScale(sx, sy, tw / 2f, th / 2f) })
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCrop()

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            runCatching { player?.release() }
            player = null
            runCatching { surface?.release() }
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }
}
