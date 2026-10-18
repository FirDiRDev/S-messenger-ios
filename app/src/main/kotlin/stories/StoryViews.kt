// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.stories

import com.stoyko.smessenger.ui.safeDismiss
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/** Кто показывается в полосе историй. */
class StoryOwner(val key: String, val name: String, val avatar: Bitmap?, val stories: List<Story>, val unviewed: Boolean)

/**
 * Просмотр историй на весь экран: полоски сверху, касание справа/слева -
 * следующая/предыдущая, удержание - пауза, смахнуть вниз - закрыть.
 */
@SuppressLint("ClickableViewAccessibility")
class StoryViewer(
    private val context: Context,
    private val owners: List<StoryOwner>,
    startOwner: Int,
    private val onViewed: (Story) -> Unit,
    private val onDelete: ((Story) -> Unit)?,
    private val onAddMore: (() -> Unit)?,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val root = FrameLayout(context)
    private val image = ImageView(context)
    private val texture = TextureView(context)
    private val bars = BarsView(context)
    private val title = TextView(context)
    private val actions = LinearLayout(context)
    private val caption = TextView(context)
    private var ownerIndex = startOwner.coerceIn(0, (owners.size - 1).coerceAtLeast(0))
    private var storyIndex = 0
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var durationMs = StoryMeta.PHOTO_SHOW_MS
    private var paused = false

    private val tick = object : Runnable {
        override fun run() {
            // Окно уже закрыто или экран пересоздан - не листаем дальше.
            if (!dialog.isShowing) return
            val p = progress()
            bars.current = p
            if (p >= 1f) next() else main.postDelayed(this, 50)
        }
    }

    fun show() {
        if (owners.isEmpty() || owners[ownerIndex].stories.isEmpty()) return
        root.setBackgroundColor(Color.BLACK)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // Затемнение сверху, чтобы полоски и имя читались на светлом фото.
        val shade = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        }
        root.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        root.addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            topMargin = dp(36)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(48)
            leftMargin = dp(16)
            rightMargin = dp(56)
        })
        val close = TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { dialog.safeDismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(40) })
        // Подпись к истории - внизу на мягком затемнении.
        val bottomShade = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0))
        }
        root.addView(bottomShade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), Gravity.BOTTOM))
        caption.setTextColor(Color.WHITE)
        caption.textSize = 16f
        caption.gravity = Gravity.CENTER
        caption.setShadowLayer(4 * density, 0f, 1 * density, 0xAA000000.toInt())
        caption.setPadding(dp(20), 0, dp(20), 0)
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(84)
        })
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        root.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(28)
        })

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                player?.setSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        var downX = 0f
        var downY = 0f
        var downAt = 0L
        // Направление жеста: 0 - ещё не ясно, 1 - вбок (листаем истории), 2 - вниз (закрыть).
        var axis = 0
        var vt: VelocityTracker? = null
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Палец лёг, пока страница ещё доворачивается - доводим её сразу,
                    // чтобы следующий свайп начинался без ожидания.
                    finishFlipNow()
                    downX = e.x
                    downY = e.y
                    downAt = SystemClock.uptimeMillis()
                    axis = 0
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    pause()
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    // Небольшой порог: свайп распознаётся сразу, без «протягивания» через весь экран.
                    if (axis == 0 && (kotlin.math.abs(dx) > dp(10) || kotlin.math.abs(dy) > dp(10))) {
                        axis = if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 0.8f) 1 else 2
                    }
                    if (axis == 1) {
                        dragFlip(dx, e.y / root.height.coerceAtLeast(1))
                    } else if (axis == 2 && dy > 0) {
                        root.translationY = dy
                        root.alpha = 1f - (dy / root.height).coerceIn(0f, 0.6f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (axis == 1) {
                        releaseFlip(dx, vx, e.actionMasked == MotionEvent.ACTION_UP)
                    } else if (axis == 2 && dy > root.height / 6f) {
                        dialog.safeDismiss()
                    } else {
                        root.animate().translationY(0f).alpha(1f).setDuration(150).start()
                        val tap = SystemClock.uptimeMillis() - downAt < 250 && kotlin.math.abs(dx) < dp(20)
                        resume()
                        if (tap && e.actionMasked == MotionEvent.ACTION_UP) {
                            if (e.x < root.width / 3f) prev() else next()
                        }
                    }
                }
            }
            true
        }
        root.fitSystemBars(top = false)
        // Окно: сама история, под ней - тень от поднятой страницы, сверху - перелистываемая страница.
        frame.setBackgroundColor(Color.BLACK)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        underShade.setBackgroundColor(Color.BLACK)
        underShade.visibility = View.GONE
        frame.addView(underShade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.visibility = View.GONE
        frame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(frame)
        dialog.setOnDismissListener { release() }
        dialog.show()
        showCurrent()
    }

    private fun owner() = owners[ownerIndex]

    // ---------------- перелистывание «как страница книги» ----------------
    //
    // В начале свайпа текущая история фотографируется в «страницу» поверх окна, а под ней сразу
    // открывается следующая (или предыдущая). Лист загибается за пальцем, как в книге: виден
    // сгиб, обратная сторона бумаги и тень на следующей странице. Отпустили дальше ~1/5 ширины или резко махнули -
    // страница доворачивается и открывает новую историю; иначе ложится обратно.

    private val frame = FrameLayout(context)
    private val page = PageView(context)
    private val underShade = View(context)
    private var pageBmp: Bitmap? = null

    /** 1 - листаем вперёд, -1 - назад, 0 - не листаем. */
    private var flipDir = 0
    private var flipFrom: Pair<Int, Int>? = null
    private var flipAnim: android.animation.ValueAnimator? = null
    private var flipEnd: (() -> Unit)? = null
    private var progressNow = 0f

    private fun canStep(dir: Int): Boolean = if (dir > 0) {
        storyIndex + 1 < owner().stories.size || ownerIndex + 1 < owners.size
    } else {
        storyIndex > 0 || ownerIndex > 0
    }

    private fun step(dir: Int) {
        if (dir > 0) {
            if (storyIndex + 1 < owner().stories.size) {
                storyIndex++
            } else {
                ownerIndex++
                storyIndex = 0
            }
        } else {
            if (storyIndex > 0) {
                storyIndex--
            } else {
                ownerIndex--
                storyIndex = 0
            }
        }
    }

    /** Снимок текущей истории (с кадром видео) - это и есть перелистываемая страница. */
    private fun snapshot(): Bitmap? = runCatching {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return@runCatching null
        val b = pageBmp?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { pageBmp = it }
        b.eraseColor(Color.BLACK)
        val c = Canvas(b)
        root.draw(c)
        if (texture.visibility == View.VISIBLE && texture.isAvailable) {
            texture.bitmap?.let { tb ->
                c.drawBitmap(tb, null, android.graphics.RectF(texture.left.toFloat(), texture.top.toFloat(), texture.right.toFloat(), texture.bottom.toFloat()), null)
                tb.recycle()
            }
        }
        b
    }.getOrNull()

    private fun startFlip(dir: Int): Boolean {
        if (!canStep(dir)) return false
        val shot = snapshot() ?: return false
        flipDir = dir
        flipFrom = ownerIndex to storyIndex
        page.bmp = shot
        page.forward = dir > 0
        page.progress = 0f
        page.visibility = View.VISIBLE
        underShade.alpha = 0.5f
        underShade.visibility = View.VISIBLE
        // Под страницей уже открыта новая история (пока стоит на паузе).
        step(dir)
        showCurrent()
        pause()
        return true
    }

    /** p: 0 - страница лежит, 1 - перевёрнута (стоит ребром). */
    private fun setFlip(p: Float) {
        progressNow = p.coerceIn(0f, 1f)
        val eased = progressNow
        // Вперёд - правый край идёт на нас и уходит влево; назад - наоборот.
        page.progress = eased
        underShade.alpha = 0.22f * (1f - eased)
    }

    private fun dragFlip(dx: Float, fy: Float = 0.5f) {
        page.fingerY = fy.coerceIn(0f, 1f)
        if (flipAnim != null) return
        if (flipDir == 0) {
            val dir = if (dx < 0) 1 else -1
            if (!startFlip(dir)) {
                // Дальше листать некуда - лёгкая «резинка».
                root.translationX = dx * 0.22f
                return
            }
        }
        // Сгиб листа идёт прямо под пальцем; отпустить можно уже через 1/5 экрана - лист доведётся сам.
        val w = root.width.coerceAtLeast(1) * 0.9f
        setFlip(-flipDir * dx / w)
    }

    private fun releaseFlip(dx: Float, vx: Float, up: Boolean) {
        if (flipDir == 0) {
            resume()
            if (root.translationX != 0f) {
                root.animate().translationX(0f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
            }
            // Вперёд с последней истории - закрываем просмотр.
            if (up && dx < -root.width / 5f && !canStep(1)) dialog.safeDismiss()
            return
        }
        val fling = 450 * density
        val towards = -flipDir * vx // > 0 - палец летит в сторону перелистывания
        val commit = up && towards > -fling * 0.6f && (progressNow > 0.2f || towards > fling)
        animateFlip(commit)
    }

    private fun animateFlip(commit: Boolean) {
        val from = progressNow
        val to = if (commit) 1f else 0f
        val left = kotlin.math.abs(to - from)
        val anim = android.animation.ValueAnimator.ofFloat(from, to)
        anim.duration = (120 + 260 * left).toLong()
        anim.interpolator = android.view.animation.DecelerateInterpolator(1.4f)
        anim.addUpdateListener { setFlip(it.animatedValue as Float) }
        val end = {
            flipAnim = null
            flipEnd = null
            endFlip(commit)
        }
        flipEnd = end
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled && flipEnd === end) end()
            }
        })
        flipAnim = anim
        anim.start()
    }

    private fun endFlip(commit: Boolean) {
        if (!commit) {
            // Страница легла обратно - возвращаем прежнюю историю под ней.
            flipFrom?.let { (o, st) ->
                ownerIndex = o
                storyIndex = st
                showCurrent()
            }
        }
        flipDir = 0
        flipFrom = null
        page.visibility = View.GONE
        page.progress = 0f
        underShade.visibility = View.GONE
        progressNow = 0f
        resume()
    }

    /** Довести перелистывание сразу (новое касание во время анимации). */
    private fun finishFlipNow() {
        val a = flipAnim ?: return
        val end = flipEnd
        flipEnd = null
        flipAnim = null
        a.cancel()
        end?.invoke()
    }

    /** Автоматически перевернуть страницу (смена человека по таймеру или касанию). */
    private fun autoFlip(dir: Int): Boolean {
        if (flipDir != 0 || root.width <= 0 || !startFlip(dir)) return false
        animateFlip(true)
        return true
    }

    /**
     * Перелистываемая страница - как настоящий лист книги: угол листа загибается за пальцем,
     * линия сгиба слегка наклонена (палец выше - лист загибается от верхнего угла), под
     * загнутой частью видна обратная сторона бумаги с просвечивающей картинкой, у сгиба -
     * тень на следующей странице и затемнение на изгибе.
     */
    private class PageView(context: Context) : View(context) {
        var bmp: Bitmap? = null
            set(v) {
                field = v
                invalidate()
            }

        /** true - листаем вперёд (лист уходит влево), false - назад. */
        var forward = true

        /** 0 - лист лежит, 1 - перевёрнут целиком. */
        var progress = 0f
            set(v) {
                field = v
                invalidate()
            }

        /** Где палец по высоте (0 - верх, 1 - низ): от этого наклон сгиба. */
        var fingerY = 0.5f

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fold = Paint(Paint.ANTI_ALIAS_FLAG)
        private val big = 100000f

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            if (b.isRecycled) return
            val w = width.toFloat()
            val h = height.toFloat()
            val p = progress.coerceIn(0f, 1f)
            if (p <= 0.001f) {
                canvas.drawBitmap(b, 0f, 0f, paint)
                return
            }
            // Линия сгиба: от края листа к противоположному (чуть дальше - чтобы лист ушёл целиком).
            val f = if (forward) w - p * w * 1.08f else p * w * 1.08f
            val cy = h / 2
            // Наклон: тянем за нижний угол - сгиб наклоняется в одну сторону, за верхний - в другую.
            val tilt = (fingerY - 0.5f) * 28f * (1f - p * 0.6f) * (if (forward) 1f else -1f)
            val flapW = minOf(if (forward) w - f else f, w)

            // 1. Лежащая часть листа.
            canvas.save()
            canvas.rotate(tilt, f, cy)
            if (forward) canvas.clipRect(-big, -big, f, big) else canvas.clipRect(f, -big, big, big)
            canvas.rotate(-tilt, f, cy)
            canvas.drawBitmap(b, 0f, 0f, paint)
            canvas.restore()

            // 2. Тень от загнутого листа на следующей странице (у самого сгиба).
            canvas.save()
            canvas.rotate(tilt, f, cy)
            val sw = (w * 0.14f) * (0.4f + 0.6f * minOf(1f, flapW / (w * 0.3f)))
            val a = (110 * (1f - p * 0.7f)).toInt().coerceIn(0, 255)
            if (forward) {
                shadow.shader = LinearGradient(f, 0f, f + sw, 0f, Color.argb(a, 0, 0, 0), 0, Shader.TileMode.CLAMP)
                canvas.drawRect(f, -big, f + sw, big, shadow)
            } else {
                shadow.shader = LinearGradient(f, 0f, f - sw, 0f, Color.argb(a, 0, 0, 0), 0, Shader.TileMode.CLAMP)
                canvas.drawRect(f - sw, -big, f, big, shadow)
            }
            canvas.restore()

            // 3. Загнутая часть - обратная сторона листа (зеркально, картинка просвечивает сквозь бумагу).
            canvas.save()
            canvas.rotate(tilt, f, cy)
            if (forward) canvas.clipRect(-big, -big, f, big) else canvas.clipRect(f, -big, big, big)
            canvas.save()
            canvas.scale(-1f, 1f, f, cy)
            canvas.rotate(-tilt, f, cy)
            canvas.drawBitmap(b, 0f, 0f, paint)
            paper.color = 0xD9F4EFE6.toInt()
            canvas.drawRect(0f, 0f, w, h, paper)
            canvas.restore()
            // Изгиб: у сгиба лист темнее (бумага выгибается), к краю - светлее, с бликом.
            val fw = minOf(flapW, w * 0.5f)
            if (fw > 1f) {
                val x0 = if (forward) f - fw else f + fw
                fold.shader = LinearGradient(
                    x0, 0f, f, 0f,
                    intArrayOf(0x00000000, 0x22FFFFFF, 0x10000000, 0x55000000),
                    floatArrayOf(0f, 0.45f, 0.8f, 1f),
                    Shader.TileMode.CLAMP,
                )
                // Только поверх загнутой части: отражённая страница - её контур.
                canvas.save()
                canvas.scale(-1f, 1f, f, cy)
                canvas.rotate(-tilt, f, cy)
                canvas.clipRect(0f, 0f, w, h)
                canvas.rotate(tilt, f, cy)
                canvas.scale(-1f, 1f, f, cy)
                if (forward) canvas.drawRect(x0, -big, f, big, fold) else canvas.drawRect(f, -big, x0, big, fold)
                canvas.restore()
            }
            canvas.restore()
        }
    }

    // Соседние фото декодируются заранее в фоне - смена истории без паузы.
    private val bitmaps = android.util.LruCache<String, Bitmap>(4)

    private fun preloadNeighbors() {
        val o = owner()
        val paths = listOfNotNull(
            o.stories.getOrNull(storyIndex + 1),
            o.stories.getOrNull(storyIndex - 1),
            owners.getOrNull(ownerIndex + 1)?.stories?.firstOrNull(),
            owners.getOrNull(ownerIndex - 1)?.stories?.firstOrNull(),
        ).filter { !it.video }.map { it.path }.filter { bitmaps.get(it) == null }
        if (paths.isEmpty()) return
        Thread {
            for (p in paths) decodeFile(p)?.let { b -> main.post { bitmaps.put(p, b) } }
        }.start()
    }

    private fun progress(): Float {
        if (paused) return ((pausedAt - startedAt - pausedTotal).toFloat() / durationMs).coerceIn(0f, 1f)
        return ((SystemClock.uptimeMillis() - startedAt - pausedTotal).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private fun showCurrent() {
        main.removeCallbacks(tick)
        releasePlayer()
        val o = owner()
        val s = o.stories.getOrNull(storyIndex) ?: return
        bars.count = o.stories.size
        bars.index = storyIndex
        bars.current = 0f
        val ago = ((System.currentTimeMillis() - s.createdAt) / 60_000L).coerceAtLeast(0)
        val agoText = if (ago < 60) {
            context.getString(R.string.story_minutes_ago, ago)
        } else {
            context.getString(R.string.story_hours_ago, ago / 60)
        }
        title.text = o.name + "  ·  " + agoText
        val cap = StoryMeta.caption(context, s.id)
        caption.text = cap
        caption.visibility = if (cap.isBlank()) View.GONE else View.VISIBLE
        if (cap.isNotBlank()) {
            caption.alpha = 0f
            caption.translationY = dp(12).toFloat()
            caption.animate().alpha(1f).translationY(0f).setDuration(320).start()
        }
        buildActions(s)
        onViewed(s)
        paused = false
        pausedTotal = 0
        if (s.video) {
            image.setImageBitmap(null)
            texture.visibility = View.VISIBLE
            try {
                val mp = MediaPlayer()
                mp.setDataSource(s.path)
                surface?.let { mp.setSurface(it) }
                mp.setOnVideoSizeChangedListener { _, w, h -> fitVideo(w, h) }
                mp.prepare()
                durationMs = mp.duration.toLong().takeIf { it > 0 } ?: s.durationMs.coerceAtLeast(1000)
                mp.setOnCompletionListener { next() }
                mp.start()
                player = mp
                com.stoyko.smessenger.ui.ThemeMusic.hold("story")
            } catch (e: Exception) {
                durationMs = 1500
            }
        } else {
            texture.visibility = View.GONE
            image.setImageBitmap(decode(s.path))
            durationMs = StoryMeta.PHOTO_SHOW_MS
        }
        startedAt = SystemClock.uptimeMillis()
        main.post(tick)
        preloadNeighbors()
    }

    private fun fitVideo(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val vw = root.width.toFloat()
        val vh = root.height.toFloat()
        val scale = minOf(vw / w, vh / h)
        texture.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
    }

    private fun decode(path: String): Bitmap? = bitmaps.get(path) ?: decodeFile(path)?.also { bitmaps.put(path, it) }

    private fun decodeFile(path: String): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        val max = maxOf(o.outWidth, o.outHeight)
        var sample = 1
        while (max / sample > 2048) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    private fun buildActions(s: Story) {
        actions.removeAllViews()
        fun pill(text: String, onClick: () -> Unit) = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x55FFFFFF)
            }
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        }
        if (s.owner == StoryMeta.ME) {
            onAddMore?.let { add ->
                actions.addView(pill("＋ " + context.getString(R.string.story_add)) {
                    dialog.safeDismiss()
                    add()
                }, lp)
            }
            onDelete?.let { del ->
                actions.addView(pill("🗑 " + context.getString(R.string.delete)) {
                    del(s)
                    dialog.safeDismiss()
                }, lp)
            }
        }
    }

    private fun next(animate: Boolean = true) {
        val o = owner()
        if (storyIndex + 1 < o.stories.size) {
            storyIndex++
            showCurrent()
        } else if (ownerIndex + 1 < owners.size) {
            // Следующий человек - перелистываем страницу.
            if (animate && autoFlip(1)) return
            ownerIndex++
            storyIndex = 0
            showCurrent()
        } else {
            dialog.safeDismiss()
        }
    }

    private fun prev() {
        if (storyIndex > 0) {
            storyIndex--
            showCurrent()
        } else if (ownerIndex > 0) {
            if (autoFlip(-1)) return
            ownerIndex--
            storyIndex = 0
            showCurrent()
        } else {
            showCurrent()
        }
    }

    private fun pause() {
        if (paused) return
        paused = true
        pausedAt = SystemClock.uptimeMillis()
        runCatching { player?.pause() }
    }

    private fun resume() {
        if (!paused) return
        paused = false
        pausedTotal += SystemClock.uptimeMillis() - pausedAt
        runCatching { player?.start() }
    }

    private fun releasePlayer() {
        val p = player
        player = null
        com.stoyko.smessenger.ui.ThemeMusic.release("story")
        runCatching { p?.stop() }
        runCatching { p?.release() }
    }

    private fun release() {
        main.removeCallbacks(tick)
        flipEnd = null
        flipAnim?.cancel()
        flipAnim = null
        page.bmp = null
        pageBmp?.recycle()
        pageBmp = null
        releasePlayer()
    }

    /** Полоски прогресса сверху. */
    private class BarsView(context: Context) : View(context) {
        var count = 1
            set(v) {
                field = v
                invalidate()
            }
        var index = 0
            set(v) {
                field = v
                invalidate()
            }
        var current = 0f
            set(v) {
                field = v
                invalidate()
            }
        private val d = context.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onDraw(canvas: Canvas) {
            val gap = 4 * d
            val w = (width - gap * (count - 1)) / count
            val r = height / 2f
            for (i in 0 until count) {
                val x = i * (w + gap)
                canvas.drawRoundRect(x, 0f, x + w, height.toFloat(), r, r, bg)
                val f = when {
                    i < index -> 1f
                    i == index -> current
                    else -> 0f
                }
                if (f > 0f) canvas.drawRoundRect(x, 0f, x + w * f, height.toFloat(), r, r, fg)
            }
        }
    }
}

/**
 * Полоса историй над списком чатов: «Моя история» (+) и контакты с историями.
 * Непросмотренные - в цветном кольце, просмотренные - в сером.
 */
class StoriesStrip(context: Context) : HorizontalScrollView(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val row = LinearLayout(context)

    /** Нажатие на «Мою историю» и на историю контакта. */
    var onMine: (() -> Unit)? = null
    var onMineLong: (() -> Unit)? = null
    var onOwner: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(8), dp(8), dp(8), dp(4))
        addView(row)
    }

    fun bind(myName: String, myAvatar: Bitmap?, mine: List<Story>, owners: List<StoryOwner>) {
        row.removeAllViews()
        row.addView(item(context.getString(R.string.story_mine), myAvatar, myName, mine.isNotEmpty(), mine.isNotEmpty(), true, -1))
        owners.forEachIndexed { i, o -> row.addView(item(o.name, o.avatar, o.name, true, o.unviewed, false, i)) }
    }

    private fun item(label: String, avatar: Bitmap?, name: String, ring: Boolean, bright: Boolean, mine: Boolean, index: Int): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        val circle = RingAvatar(context, avatar, name, ring, bright, plus = mine)
        box.addView(circle, LinearLayout.LayoutParams(dp(62), dp(62)))
        box.addView(
            TextView(context).apply {
                text = label
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                @Suppress("DEPRECATION")
                setTextColor(context.resources.getColor(R.color.textPrimary))
            },
            LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
        box.setOnClickListener {
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
            if (mine) onMine?.invoke() else onOwner?.invoke(index)
        }
        if (mine) {
            box.setOnLongClickListener {
                onMineLong?.invoke()
                true
            }
        }
        return box
    }

    /** Круглый аватар в градиентном кольце. */
    private class RingAvatar(
        context: Context,
        private val avatar: Bitmap?,
        private val name: String,
        private val ring: Boolean,
        private val bright: Boolean,
        private val plus: Boolean,
    ) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = android.graphics.Path()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            if (ring) {
                ringPaint.shader = if (bright) {
                    LinearGradient(0f, height.toFloat(), width.toFloat(), 0f, 0xFF00C2A8.toInt(), 0xFF7B6CF6.toInt(), Shader.TileMode.CLAMP)
                } else {
                    null
                }
                ringPaint.color = if (bright) Color.WHITE else 0xFF8A8FA3.toInt()
                canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth / 2, ringPaint)
            }
            val inner = r - 5 * d
            if (avatar != null) {
                canvas.save()
                path.reset()
                path.addCircle(cx, cy, inner, android.graphics.Path.Direction.CW)
                canvas.clipPath(path)
                val s = maxOf(inner * 2 / avatar.width, inner * 2 / avatar.height)
                canvas.translate(cx - avatar.width * s / 2, cy - avatar.height * s / 2)
                canvas.scale(s, s)
                canvas.drawBitmap(avatar, 0f, 0f, bmpPaint)
                canvas.restore()
            } else {
                fill.color = 0xFF5B4BDB.toInt() + (name.hashCode() and 0x3F3F3F)
                canvas.drawCircle(cx, cy, inner, fill)
                text.textSize = inner * 0.9f
                val letter = name.trim().take(1).uppercase().ifEmpty { "?" }
                canvas.drawText(letter, cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
            if (plus) {
                val pr = 9 * d
                val px = cx + inner * 0.72f
                val py = cy + inner * 0.72f
                fill.color = Color.WHITE
                canvas.drawCircle(px, py, pr + 2 * d, fill)
                fill.color = 0xFF00C2A8.toInt()
                canvas.drawCircle(px, py, pr, fill)
                text.textSize = pr * 1.6f
                canvas.drawText("+", px, py - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }
}
