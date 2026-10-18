// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors
import com.stoyko.smessenger.ui.chat.VoiceMeta
import com.stoyko.smessenger.ui.chat.VoicePlayer

/** «Кружки»: vnote_<время>_d<мс>.mp4. */
object VideoNoteMeta {
    fun isVideoNote(name: String) = name.startsWith("vnote_") && name.endsWith(".mp4")
    fun durationMs(name: String): Long? = VoiceMeta.durationMs(name)
}

/** Первый кадр видео (обложка «кружка»/истории) - в фоне, с кэшем в памяти. */
object VideoThumbs {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "video-thumbs") }
    private val main = Handler(Looper.getMainLooper())

    fun cached(key: String): Bitmap? = cache.get(key)

    fun load(context: Context, key: String, uri: Uri, maxSide: Int, into: (Bitmap?) -> Unit) {
        cache.get(key)?.let {
            into(it)
            return
        }
        val app = context.applicationContext
        executor.execute {
            val bmp = try {
                val mmr = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file") mmr.setDataSource(uri.path) else mmr.setDataSource(app, uri)
                    val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { scale(it, maxSide) }
                } finally {
                    runCatching { mmr.release() }
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { into(bmp) }
        }
    }

    private fun scale(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val k = maxSide.toFloat() / m
        val s = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        if (s !== b) b.recycle()
        return s
    }
}

/**
 * «Кружок» в чате: круглая обложка, при нажатии видео играет прямо в круге (со звуком),
 * вокруг - полоса проигрывания, внизу - длительность. Если уйти из чата, видео продолжит
 * играть в плавающем кружке поверх экранов.
 */
class VideoNoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val thumb = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF2C2950.toInt())
    }
    private val texture = TextureView(context)
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(0x80000000.toInt())
        }
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val arcRect = RectF()

    private var key: String? = null
    private var uri: Uri? = null
    private var durationMs = 0L
    private var surface: Surface? = null
    private var registered = false
    private var playingProgress = -1f
    private var videoShown = false

    // Во время просмотра кружок плавно увеличивается (до ~80% ширины экрана), после - обратно.
    private var enlarged = false
    private var sizeAnim: android.animation.ValueAnimator? = null
    private val smallSize get() = (200 * density).toInt()
    private val largeSize get() = minOf(resources.displayMetrics.widthPixels * 0.8f, 360 * density).toInt()

    private fun setBig(big: Boolean, animate: Boolean = true) {
        val box = parent as? View ?: return
        val lp = box.layoutParams ?: return
        val to = if (big) largeSize else smallSize
        if (enlarged == big && (lp.width == to || sizeAnim?.isRunning == true)) return
        enlarged = big
        sizeAnim?.cancel()
        val from = if (lp.width > 0) lp.width else smallSize
        if (!animate || from == to) {
            lp.width = to
            lp.height = to
            box.layoutParams = lp
            return
        }
        sizeAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                lp.width = v
                lp.height = v
                box.layoutParams = lp
            }
            start()
        }
    }

    init {
        setWillNotDraw(false)
        addView(thumb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.alpha = 0f
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (10 * density).toInt()
        })
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                sync()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                unregister()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                // Первый кадр пришёл - прячем обложку.
                if (!videoShown && isCurrent()) {
                    videoShown = true
                    texture.alpha = 1f
                }
            }
        }
    }

    /** ready = файл уже есть на устройстве (получен или свой). */
    fun bind(key: String, uri: Uri?, durationMs: Long, ready: Boolean) {
        if (this.key != key) {
            unregister()
            videoShown = false
            texture.alpha = 0f
            thumb.setImageBitmap(null)
            this.key = key
            // Строка списка переиспользуется - размер по состоянию нового кружка.
            post { setBig(isCurrent(), animate = false) }
        }
        this.uri = uri
        this.durationMs = durationMs
        texture.scaleX = 1f
        texture.scaleY = 1f
        if (ready && uri != null) NoteAspect.apply(context, key, uri, texture)
        if (ready && uri != null) {
            val cached = VideoThumbs.cached(key)
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                VideoThumbs.load(context, key, uri, 360) { b -> if (this.key == key && b != null) thumb.setImageBitmap(b) }
            }
        }
        sync()
    }

    private fun isCurrent() = key != null && key == VoicePlayer.currentKey && VoicePlayer.isVideo

    /** Обновить состояние (вызывается проигрывателем ~10 раз в секунду). */
    fun sync() {
        val k = key
        val current = isCurrent()
        val s = surface
        if (current && s != null && isAttachedToWindow) {
            if (!registered) {
                registered = true
                VoicePlayer.setVideoTarget(this, k!!, s, 1)
            }
        } else if (registered) {
            unregister()
        }
        if (!current && videoShown) {
            videoShown = false
            texture.alpha = 0f
        }
        val pos = if (current) VoicePlayer.positionMs(k!!) else 0L
        val total = if (current) VoicePlayer.durationMs(k!!).takeIf { it > 0 } ?: durationMs else durationMs
        label.text = if (current) VoiceMeta.format(pos) else VoiceMeta.format(total)
        if (current != enlarged) setBig(current)
        val p = if (current) VoicePlayer.progress(k!!) else -1f
        if (p != playingProgress) {
            playingProgress = p
            invalidate()
        }
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        VoicePlayer.setVideoTarget(this, key ?: "", null, 1)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        unregister()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (playingProgress >= 0f) {
            val inset = arcPaint.strokeWidth
            arcRect.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(arcRect, -90f, 360f * playingProgress, false, arcPaint)
        }
    }
}

/**
 * Плавающий «кружок» поверх всех экранов: видео продолжает играть, когда вы ушли из чата
 *. Нажатие - пауза/продолжить, «✕» - закрыть, можно перетащить пальцем.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FloatingVideoNote(context: Context, private val host: FrameLayout) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val size = (132 * density).toInt()
    private val texture = TextureView(context)
    private var surface: Surface? = null
    private var registered = false
    private val listener: () -> Unit = { update() }
    private val close = TextView(context).apply {
        text = "✕"
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xCC000000.toInt())
        }
    }
    private val circle = FrameLayout(context)

    init {
        circle.addView(texture, LayoutParams(size, size))
        circle.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        circle.clipToOutline = true
        circle.elevation = 10 * density
        addView(circle, LayoutParams(size, size))
        val c = (26 * density).toInt()
        addView(close, LayoutParams(c, c, Gravity.TOP or Gravity.END))
        close.elevation = 12 * density
        close.setOnClickListener { VoicePlayer.stop() }
        visibility = GONE
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                update()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (registered) {
                    registered = false
                    VoicePlayer.setVideoTarget(this@FloatingVideoNote, "", null, 0)
                }
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        circle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = translationX
                    startY = translationY
                    moved = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12 * density) {
                        moved = true
                        translationX = startX + dx
                        translationY = startY + dy
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (VoicePlayer.isPlaying) VoicePlayer.pause() else VoicePlayer.resume()
                    } else {
                        // Прилипает к ближайшему краю.
                        val hostW = host.width
                        val left = (hostW - size) / 2f + translationX < hostW / 2f
                        val target = if (left) -(hostW - size) / 2f + 12 * density else (hostW - size) / 2f - 12 * density
                        animate().translationX(target).setDuration(200).start()
                    }
                }
            }
            true
        }
    }

    fun install() {
        val lp = LayoutParams(size + (6 * density).toInt(), size + (6 * density).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = (96 * density).toInt()
        host.addView(this, lp)
        post {
            translationX = (host.width - size) / 2f - 12 * density
        }
        VoicePlayer.addListener(listener)
        update()
    }

    fun uninstall() {
        VoicePlayer.removeListener(listener)
        host.removeView(this)
    }

    private fun update() {
        val need = VoicePlayer.isVideo && VoicePlayer.currentKey != null && !VoicePlayer.hasInlineTarget()
        if (need && visibility != VISIBLE) {
            visibility = VISIBLE
            scaleX = 0.4f
            scaleY = 0.4f
            alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
        } else if (!need && visibility == VISIBLE && !registered) {
            visibility = GONE
        }
        val key = VoicePlayer.currentKey
        val s = surface
        if (need && s != null && key != null) {
            if (!registered) {
                VoicePlayer.currentUri?.let { NoteAspect.apply(context, key, it, texture) }
                registered = true
                VoicePlayer.setVideoTarget(this, key, s, 0)
            }
        } else if (registered) {
            registered = false
            VoicePlayer.setVideoTarget(this, "", null, 0)
            if (!need) visibility = GONE
        }
    }
}

/**
 * Пропорции «кружка» при показе: новые кружки (CameraX) записываются кадром 3:4/9:16 с пометкой
 * поворота, старые - квадратом. В круге видео показывается по центру без растягивания.
 */
object NoteAspect {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun apply(context: Context, key: String, uri: Uri, view: View) {
        cache[key]?.let {
            set(view, it)
            return
        }
        val app = context.applicationContext
        pool.execute {
            val aspect = try {
                val m = MediaMetadataRetriever()
                try {
                    m.setDataSource(app, uri)
                    val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                    val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                    val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w <= 0f || h <= 0f) 1f else if (rot % 180 != 0) h / w else w / h
                } finally {
                    runCatching { m.release() }
                }
            } catch (e: Exception) {
                1f
            }
            cache[key] = aspect
            main.post { set(view, aspect) }
        }
    }

    /** Квадратный вид растягивает кадр - возвращаем пропорции, лишнее обрезает круг. */
    private fun set(view: View, aspect: Float) {
        if (aspect <= 0f || kotlin.math.abs(aspect - 1f) < 0.02f) {
            view.scaleX = 1f
            view.scaleY = 1f
        } else if (aspect < 1f) {
            view.scaleX = 1f
            view.scaleY = 1f / aspect
        } else {
            view.scaleX = aspect
            view.scaleY = 1f
        }
    }
}
