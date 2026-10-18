// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.chat.FileTypes
import kotlin.math.abs

private const val TAG = "VideoPlayer"
private const val EXTRA_NAME = "name"
private const val HIDE_DELAY = 3500L
private const val PREFS = "video_positions"

/**
 * Встроенный видеоплеер «как у профессиональных»:
 * - поворот экрана без перезапуска (видео не прерывается и не закрывается), кнопка блокировки
 *   ориентации, горизонтальные ролики сами открываются горизонтально;
 * - двойное касание слева/справа - назад/вперёд на 10 с; свайп по горизонтали - перемотка;
 * - свайп вверх/вниз слева - яркость, справа - громкость; щипок - масштаб;
 * - скорость 0.25-2×, повтор, режимы «вписать / заполнить / растянуть»;
 * - «картинка в картинке», продолжение с места остановки, сохранить / открыть в другом приложении.
 */
class VideoPlayerActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var uri: Uri
    private var name: String = ""

    private var player: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var speed = 1f
    private var looping = false
    private var fitMode = 0 // 0 - вписать, 1 - заполнить (обрезка), 2 - растянуть
    private var zoom = 1f
    private var orientationMode = 0 // 0 - авто (по датчику), 1 - горизонтально, 2 - вертикально
    private var autoOriented = false
    private var wasPlayingBeforePause = true
    private var resumeAt = 0

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var controls: FrameLayout
    private lateinit var playPause: PlayerButton
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var speedBtn: PlayerButton
    private lateinit var fitBtn: PlayerButton
    private lateinit var rotateBtn: PlayerButton
    private lateinit var loopBtn: PlayerButton
    private lateinit var hint: TextView
    private lateinit var hud: GestureHud
    private lateinit var spinner: android.widget.ProgressBar
    private var userSeeking = false
    private lateinit var titleView: TextView

    /** Управление из шторки уведомлений и с гарнитуры. */
    private var session: android.media.session.MediaSession? = null
    private var receiverOn = false

    /** Поток из сети (ТВ-канал, фильм): без «сохранить», ошибка - без перехода в другое приложение. */
    private val isStream: Boolean get() = uri.scheme == "http" || uri.scheme == "https"
    private var live = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.stoyko.smessenger.ui.ScreenFit.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.stoyko.smessenger.ui.ThemeMusic.hold("video")
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        uri = data
        name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { data.lastPathSegment.orEmpty() }
        speed = savedInstanceState?.getFloat("speed") ?: 1f
        resumeAt = savedInstanceState?.getInt("pos") ?: savedPosition()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildUi()
        hideSystemBars()
        setupSession()
    }

    /** Повторный запуск (другой канал / фильм / нажали на уведомление) - окно уже открыто. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent.data ?: return
        if (::uri.isInitialized && data == uri && player != null) {
            // Нажали на уведомление - просто показываем плеер.
            showControls()
            return
        }
        switchTo(data, intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { data.lastPathSegment.orEmpty() })
    }

    // ---------------- список: предыдущий / следующий ----------------

    private fun hasList() = Playlist.items.size > 1

    private lateinit var prevBtn: PlayerButton
    private lateinit var nextBtn: PlayerButton

    /** Кнопки «предыдущий / следующий» - только когда открыт список (ТВ, кино). */
    private fun applyListUi() {
        val list = hasList()
        val gap = if (list) dp(14f) else dp(30f)
        prevBtn.visibility = if (list) View.VISIBLE else View.GONE
        nextBtn.visibility = if (list) View.VISIBLE else View.GONE
        (prevBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
        (nextBtn.layoutParams as LinearLayout.LayoutParams).marginStart = gap
        (playPause.layoutParams as LinearLayout.LayoutParams).setMargins(gap, 0, gap, 0)
        playPause.requestLayout()
    }

    private fun playAt(i: Int) {
        val items = Playlist.items
        if (items.isEmpty()) return
        val idx = ((i % items.size) + items.size) % items.size
        Playlist.index = idx
        val item = items[idx]
        flash(item.name)
        if (item.url != null) {
            switchTo(Uri.parse(item.url), item.name)
            return
        }
        spinner.visibility = View.VISIBLE
        val my = ++resolveGen
        Thread {
            val url = runCatching { item.resolve?.invoke() }.getOrNull()
            ui.post {
                if (isFinishing || my != resolveGen) return@post
                if (url == null) {
                    spinner.visibility = View.GONE
                    Toast.makeText(this, R.string.stream_cant_play, Toast.LENGTH_SHORT).show()
                } else {
                    switchTo(Uri.parse(url), item.name)
                }
            }
        }.start()
    }

    private var resolveGen = 0

    private fun step(delta: Int) {
        if (!hasList()) return
        val cur = Playlist.index.takeIf { it in Playlist.items.indices }
            ?: Playlist.items.indexOfFirst { it.url == uri.toString() }.coerceAtLeast(0)
        playAt(cur + delta)
    }

    /** Переключиться на другой ролик/канал без закрытия окна. */
    private fun switchTo(newUri: Uri, newName: String) {
        if (prepared) {
            val pos = currentMs()
            savePosition(if (pos >= durationMs() - 3_000) 0 else pos)
        }
        ui.removeCallbacks(progressRunnable)
        runCatching { player?.release() }
        player = null
        prepared = false
        live = false
        uri = newUri
        name = newName
        titleView.text = name
        applyListUi()
        resumeAt = savedPosition()
        videoW = 0
        videoH = 0
        seek.isEnabled = true
        seek.alpha = 1f
        seek.progress = 0
        seek.secondaryProgress = 0
        posText.text = "0:00"
        posText.setTextColor(Color.WHITE)
        durText.text = "0:00"
        spinner.visibility = View.VISIBLE
        updateSession()
        if (surface != null) open()
        showControls()
    }

    // ---------------- интерфейс ----------------

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun textButton(label: String, click: (View) -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        setOnClickListener {
            click(it)
            scheduleHide()
        }
    }

    /** Векторная кнопка плеера (стеклянный круг, пружина, волна). */
    private fun pb(kind: PlayerButton.Kind, big: Boolean = false, click: (View) -> Unit) = PlayerButton(this, kind, big).apply {
        setOnClickListener {
            click(it)
            scheduleHide()
        }
    }

    /** Кнопка-режим с подписью снизу. */
    private fun modeButton(button: PlayerButton, caption: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(button, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        addView(
            TextView(this@VideoPlayerActivity).apply {
                text = caption
                tag = "caption"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
            },
        )
    }

    private fun iconButton(icon: Int, sizeDp: Float, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val p = dp(sizeDp / 5)
        setPadding(p, p, p, p)
        setOnClickListener {
            click()
            scheduleHide()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply { surfaceTextureListener = this@VideoPlayerActivity }
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hud = GestureHud(this)
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * resources.displayMetrics.density
                setColor(0xAA101018.toInt())
            }
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // Загрузка / буферизация (сеть, большие файлы).
        spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            if (Build.VERSION.SDK_INT >= 21) indeterminateTintList = android.content.res.ColorStateList.valueOf(PlayerTheme.colors(this@VideoPlayerActivity)[0])
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56f), dp(56f), Gravity.CENTER))

        controls = FrameLayout(this)
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Верхняя панель: назад, название, скорость, меню.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(4f), dp(8f), dp(4f), dp(18f))
        }
        top.addView(pb(PlayerButton.Kind.BACK) { finish() }, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply { marginEnd = dp(8f) })
        titleView = TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedBtn = pb(PlayerButton.Kind.SPEED) { showSpeedMenu(it) }.apply { label = speedLabel() }
        top.addView(speedBtn, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply { marginEnd = dp(6f) })
        top.addView(
            pb(PlayerButton.Kind.MORE) { v ->
                val popup = PopupMenu(this, v)
                popup.menu.add(0, 1, 0, R.string.save_to_gallery)
                popup.menu.add(0, 2, 1, R.string.open_with)
                if (isStream) popup.menu.removeItem(1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPip()) popup.menu.add(0, 3, 2, R.string.player_pip)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> FileTypes.saveToPublic(this, uri, name)
                        2 -> FileTypes.openWith(this, uri, name)
                        3 -> enterPip()
                    }
                    true
                }
                popup.show()
            },
            LinearLayout.LayoutParams(dp(42f), dp(42f)),
        )
        controls.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Центр: -10 c, пауза/воспроизведение, +10 c.
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // Список каналов/фильмов - ещё «предыдущий» и «следующий» по краям.
        prevBtn = pb(PlayerButton.Kind.PREV) { step(-1) }
        nextBtn = pb(PlayerButton.Kind.NEXT) { step(1) }
        center.addView(prevBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        center.addView(pb(PlayerButton.Kind.REWIND) { seekBy(-10_000) }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        playPause = pb(PlayerButton.Kind.PLAY_PAUSE, big = true) { togglePlay() }
        center.addView(playPause, LinearLayout.LayoutParams(dp(78f), dp(78f)))
        center.addView(pb(PlayerButton.Kind.FORWARD) { seekBy(10_000) }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        center.addView(nextBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        applyListUi()
        controls.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Низ: время, полоса, кнопки режимов.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
            setPadding(dp(8f), dp(22f), dp(8f), dp(10f))
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        posText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "0:00"
        }
        durText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "0:00"
        }
        seek = SeekBar(this).apply {
            max = 1000
            // Фирменные цвета полосы и бегунка.
            progressTintList = android.content.res.ColorStateList.valueOf(PlayerTheme.colors(this@VideoPlayerActivity)[0])
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
            secondaryProgressTintList = android.content.res.ColorStateList.valueOf(0x8800C2A8.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) posText.text = fmt(durationMs() * progress / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    userSeeking = true
                    ui.removeCallbacks(hideRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    userSeeking = false
                    seekTo((durationMs() * sb.progress / 1000).toInt())
                    scheduleHide()
                }
            })
        }
        seekRow.addView(posText)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(durText)
        bottom.addView(seekRow)
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fitBtn = pb(PlayerButton.Kind.FIT) { cycleFit() }
        rotateBtn = pb(PlayerButton.Kind.ROTATE) { cycleOrientation() }
        loopBtn = pb(PlayerButton.Kind.LOOP) { toggleLoop() }
        val modeLp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        modes.addView(modeButton(fitBtn, fitLabel()), modeLp())
        modes.addView(modeButton(rotateBtn, rotateLabel()), modeLp())
        modes.addView(modeButton(loopBtn, loopLabel()), modeLp())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPip()) {
            modes.addView(modeButton(pb(PlayerButton.Kind.PIP) { enterPip() }, getString(R.string.player_pip_short)), modeLp())
        }
        bottom.addView(modes)
        controls.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Жесты на всём экране.
        val gestures = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (controls.visibility == View.VISIBLE) hideControls() else showControls()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val w = root.width
                    when {
                        e.x < w / 3f -> seekBy(-10_000).also { hud.show(GestureHud.Icon.SEEK_BACK, getString(com.stoyko.smessenger.R.string.player_seek_back)) }
                        e.x > w * 2 / 3f -> seekBy(10_000).also { hud.show(GestureHud.Icon.SEEK_FWD, getString(com.stoyko.smessenger.R.string.player_seek_fwd)) }
                        else -> togglePlay()
                    }
                    return true
                }
            },
        )
        val scaler = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
                    applyTransform()
                    hud.show(GestureHud.Icon.ZOOM, "${(zoom * 100).toInt()}%")
                    return true
                }
            },
        )
        var downX = 0f
        var downY = 0f
        var mode = 0 // 0 - не определено, 1 - перемотка, 2 - яркость, 3 - громкость
        var startPos = 0
        var startValue = 0f
        root.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            gestures.onTouchEvent(e)
            if (e.pointerCount > 1 || scaler.isInProgress) {
                mode = -1
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    mode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (mode == 0 && (abs(dx) > dp(24f) || abs(dy) > dp(24f))) {
                        mode = when {
                            abs(dx) > abs(dy) -> 1
                            downX < root.width / 2f -> 2
                            else -> 3
                        }
                        startPos = currentMs()
                        startValue = if (mode == 2) currentBrightness() else currentVolumeFraction()
                    }
                    when (mode) {
                        1 -> {
                            val delta = (dx / root.width * 90_000).toInt() // весь экран - 1,5 минуты
                            val target = (startPos + delta).coerceIn(0, durationMs())
                            hud.show(
                                if (delta >= 0) GestureHud.Icon.SEEK_FWD else GestureHud.Icon.SEEK_BACK,
                                (if (delta >= 0) "+" else "−") + fmt(abs(delta)) + "  ·  " + fmt(target),
                                if (durationMs() > 0) target.toFloat() / durationMs() else -1f,
                            )
                        }
                        2 -> {
                            val v = (startValue - dy / root.height).coerceIn(0.02f, 1f)
                            setBrightness(v)
                            hud.show(GestureHud.Icon.SUN, "${(v * 100).toInt()}%", v)
                        }
                        3 -> {
                            val v = (startValue - dy / root.height).coerceIn(0f, 1f)
                            setVolumeFraction(v)
                            hud.show(GestureHud.Icon.VOLUME, "${(v * 100).toInt()}%", v)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1) {
                        val delta = ((e.x - downX) / root.width * 90_000).toInt()
                        seekTo((startPos + delta).coerceIn(0, durationMs()))
                    }
                    mode = 0
                }
            }
            true
        }
        setContentView(root)
        showControls()
    }

    // ---------------- воспроизведение ----------------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        val p = player
        if (p != null) {
            runCatching { p.setSurface(surface) }
        } else {
            open()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyTransform()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        runCatching { player?.setSurface(null) }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun open() {
        val mp = MediaPlayer()
        player = mp
        try {
            if (isStream) {
                mp.setDataSource(this, uri, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) S-messenger"))
            } else {
                mp.setDataSource(this, uri)
            }
            mp.setSurface(surface)
            mp.isLooping = looping
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> spinner.visibility = View.VISIBLE
                    MediaPlayer.MEDIA_INFO_BUFFERING_END, MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> spinner.visibility = View.GONE
                }
                false
            }
            mp.setOnBufferingUpdateListener { _, pct -> if (!live) seek.secondaryProgress = pct * 10 }
            mp.setOnPreparedListener {
                prepared = true
                spinner.visibility = View.GONE
                // Прямой эфир (ТВ): длительности нет - полосу перемотки прячем, пишем «● LIVE».
                live = isStream && durationMs() <= 0
                if (live) {
                    seek.isEnabled = false
                    seek.alpha = 0.35f
                    posText.text = "● LIVE"
                    posText.setTextColor(0xFFEF4444.toInt())
                }
                videoW = it.videoWidth
                videoH = it.videoHeight
                durText.text = fmt(durationMs())
                autoOrient()
                applyTransform()
                if (!live && resumeAt in 5_000 until durationMs() - 5_000) {
                    it.seekTo(resumeAt)
                    flash(getString(R.string.player_resumed, fmt(resumeAt)))
                }
                applySpeed()
                it.start()
                updatePlayIcon()
                updateSession()
                ui.removeCallbacks(progressRunnable)
                ui.post(progressRunnable)
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                autoOrient()
                applyTransform()
            }
            mp.setOnCompletionListener {
                // Фильм из списка закончился - следующий.
                if (!looping && hasList() && !live) {
                    savePosition(0)
                    step(1)
                    return@setOnCompletionListener
                }
                if (!looping) {
                    updateSession()
                    updatePlayIcon()
                    showControls(keep = true)
                    savePosition(0)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "error $what/$extra")
                Toast.makeText(this, if (isStream) R.string.stream_cant_play else R.string.video_cant_play, Toast.LENGTH_LONG).show()
                if (isStream && hasList()) {
                    // Канал не открылся - окно не закрываем: можно переключить на другой.
                    spinner.visibility = View.GONE
                    prepared = false
                    showControls(keep = true)
                } else {
                    if (!isStream) FileTypes.openWith(this, uri, name)
                    finish()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "open: $e")
            Toast.makeText(this, if (isStream) R.string.stream_cant_play else R.string.video_cant_play, Toast.LENGTH_LONG).show()
            if (!isStream) runCatching { FileTypes.openWith(this, uri, name) }
            finish()
        }
    }

    private fun durationMs(): Int = if (prepared) runCatching { player?.duration ?: 0 }.getOrDefault(0).coerceAtLeast(0) else 0
    private fun currentMs(): Int = if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0
    private fun isPlaying(): Boolean = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun togglePlay() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (currentMs() >= durationMs() - 300) p.seekTo(0)
            applySpeed()
            p.start()
        }
        updatePlayIcon()
        updateSession()
        showControls()
    }

    private fun seekTo(ms: Int) {
        if (!prepared) return
        val target = ms.coerceIn(0, durationMs())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                player?.seekTo(target)
            }
        }
        posText.text = fmt(target)
    }

    private fun seekBy(delta: Int) = seekTo(currentMs() + delta)

    private fun updatePlayIcon() {
        playPause.setPlaying(isPlaying())
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val d = durationMs()
            if (d > 0 && !userSeeking && !live) {
                val pos = currentMs()
                seek.progress = (pos.toLong() * 1000 / d).toInt()
                posText.text = fmt(pos)
            }
            ui.postDelayed(this, 250)
        }
    }

    // ---------------- скорость, повтор, масштаб, ориентация ----------------

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun speedLabel() = if (speed == 1f) "1×" else "${speed.toString().removeSuffix(".0")}×"

    private fun showSpeedMenu(anchor: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.player_speed_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val popup = PopupMenu(this, anchor)
        speeds.forEachIndexed { i, s -> popup.menu.add(0, i, i, (if (s == speed) "✓ " else "") + "${s.toString().removeSuffix(".0")}×") }
        popup.setOnMenuItemClickListener { item ->
            speed = speeds[item.itemId]
            applySpeed()
            speedBtn.label = speedLabel()
            true
        }
        popup.show()
    }

    private fun applySpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !prepared) return
        val p = player ?: return
        val wasPlaying = p.isPlaying
        runCatching {
            p.playbackParams = p.playbackParams.setSpeed(speed)
            // Смена скорости на паузе на некоторых телефонах запускает видео - возвращаем паузу.
            if (!wasPlaying && p.isPlaying) p.pause()
        }.onFailure { Log.w(TAG, "speed: $it") }
    }

    private fun loopLabel() = getString(if (looping) R.string.player_loop_on else R.string.player_loop_off)

    private fun setCaption(button: PlayerButton, text: String) {
        ((button.parent as? ViewGroup)?.findViewWithTag<TextView>("caption"))?.text = text
    }

    private fun toggleLoop() {
        looping = !looping
        runCatching { player?.isLooping = looping }
        loopBtn.active = looping
        setCaption(loopBtn, loopLabel())
    }

    private fun fitLabel() = when (fitMode) {
        0 -> getString(R.string.player_fit)
        1 -> getString(R.string.player_fill)
        else -> getString(R.string.player_stretch)
    }

    private fun cycleFit() {
        fitMode = (fitMode + 1) % 3
        zoom = 1f
        fitBtn.mode = fitMode
        setCaption(fitBtn, fitLabel())
        flash(fitLabel())
        applyTransform()
    }

    /** Масштаб картинки: TextureView растянут на весь экран, пропорции задаём матрицей. */
    private fun applyTransform() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val videoAspect = videoW.toFloat() / videoH
        val viewAspect = vw / vh
        var sx = 1f
        var sy = 1f
        when (fitMode) {
            0 -> if (videoAspect > viewAspect) sy = viewAspect / videoAspect else sx = videoAspect / viewAspect
            1 -> if (videoAspect > viewAspect) sx = videoAspect / viewAspect else sy = viewAspect / videoAspect
            else -> Unit
        }
        val m = Matrix()
        m.setScale(sx * zoom, sy * zoom, vw / 2f, vh / 2f)
        texture.setTransform(m)
        texture.invalidate()
    }

    private fun rotateLabel() = when (orientationMode) {
        0 -> getString(R.string.player_rotate_auto)
        1 -> getString(R.string.player_rotate_landscape)
        else -> getString(R.string.player_rotate_portrait)
    }

    private fun cycleOrientation() {
        orientationMode = (orientationMode + 1) % 3
        applyOrientation()
        rotateBtn.mode = orientationMode
        setCaption(rotateBtn, rotateLabel())
        flash(rotateLabel())
    }

    private fun applyOrientation() {
        requestedOrientation = when (orientationMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    /** Горизонтальный ролик сразу открываем горизонтально (как в YouTube), один раз. */
    private fun autoOrient() {
        if (autoOriented || videoW <= 0 || videoH <= 0) return
        autoOriented = true
        if (videoW > videoH * 1.15f && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            orientationMode = 1
            applyOrientation()
            rotateBtn.mode = orientationMode
        setCaption(rotateBtn, rotateLabel())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Поворот: окно не пересоздаётся - просто пересчитываем пропорции.
        texture.post { applyTransform() }
        hideSystemBars()
    }

    // ---------------- яркость и громкость ----------------

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
    }

    private fun audio() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun currentVolumeFraction(): Float {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolumeFraction(v: Float) {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0) }
    }

    // ---------------- панели ----------------

    private val hideRunnable = Runnable { hideControls() }
    private val hintRunnable = Runnable { hint.visibility = View.GONE }

    private fun flash(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        ui.removeCallbacks(hintRunnable)
        ui.postDelayed(hintRunnable, 900)
    }

    private fun showControls(keep: Boolean = false) {
        controls.animate().cancel()
        controls.visibility = View.VISIBLE
        controls.alpha = 1f
        ui.removeCallbacks(hideRunnable)
        if (!keep) scheduleHide()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, HIDE_DELAY)
    }

    private fun hideControls() {
        if (userSeeking || !isPlaying()) return
        controls.animate().alpha(0f).setDuration(200).withEndAction { controls.visibility = View.GONE }.start()
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---------------- картинка в картинке ----------------

    private fun hasPip(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasPip()) return
        runCatching {
            val ratio = if (videoW > 0 && videoH > 0) {
                Rational(videoW, videoH).let { r ->
                    // Android принимает пропорции от 1:2.39 до 2.39:1.
                    when {
                        r.toFloat() > 2.39f -> Rational(239, 100)
                        r.toFloat() < 1 / 2.39f -> Rational(100, 239)
                        else -> r
                    }
                }
            } else {
                Rational(16, 9)
            }
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        }.onFailure { Log.w(TAG, "pip: $it") }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controls.visibility = View.GONE
            hint.visibility = View.GONE
        } else {
            showControls()
        }
    }

    // ---------------- жизненный цикл ----------------

    override fun onPause() {
        super.onPause()
        // ТВ и фильмы продолжают играть в фоне (звук) - управление в шторке уведомлений.
        // Видео из чата - на паузу, как раньше.
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip) {
            wasPlayingBeforePause = isPlaying()
            if (wasPlayingBeforePause && !isStream) runCatching { player?.pause() }
            updatePlayIconSafe()
            updateSession()
        }
        if (prepared) savePosition(currentMs())
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (prepared && wasPlayingBeforePause && !isPlaying()) {
            applySpeed()
            runCatching { player?.start() }
            updatePlayIconSafe()
        }
    }

    override fun onStop() {
        super.onStop()
        // Закрыли окно «картинка в картинке» (крестик / смахнули) - останавливаем полностью.
        // Раньше в этом случае звук продолжал играть, и выключить его было нельзя.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            finishAndRemoveTask()
            return
        }
        if (!isStream) runCatching { if (isPlaying()) player?.pause() }
        updateSession()
    }

    // ---------------- шторка уведомлений ----------------

    private val actionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACT_TOGGLE -> togglePlay()
                ACT_NEXT -> step(1)
                ACT_PREV -> step(-1)
                ACT_CLOSE -> finishAndRemoveTask()
            }
        }
    }

    private fun setupSession() {
        runCatching {
            val f = android.content.IntentFilter().apply {
                addAction(ACT_TOGGLE)
                addAction(ACT_NEXT)
                addAction(ACT_PREV)
                addAction(ACT_CLOSE)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(actionReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(actionReceiver, f)
            receiverOn = true
        }
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val nm = (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                nm.createNotificationChannel(
                    android.app.NotificationChannel(CHANNEL, getString(R.string.player_notify_channel), android.app.NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    },
                )
            }
        }
        session = runCatching {
            android.media.session.MediaSession(this, "S-messenger player").apply {
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() { if (!isPlaying()) togglePlay() }
                    override fun onPause() { if (isPlaying()) togglePlay() }
                    override fun onSkipToNext() = step(1)
                    override fun onSkipToPrevious() = step(-1)
                    override fun onStop() = finishAndRemoveTask()
                    override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                })
                isActive = true
            }
        }.getOrNull()
        updateSession()
    }

    private fun pending(action: String, code: Int): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            this, code, Intent(action).setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    /** Обновить состояние в шторке: название, играет/пауза, кнопки. */
    private fun updateSession() {
        if (isFinishing || isDestroyed) return
        val s = session ?: return
        val playing = isPlaying()
        runCatching {
            var actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_STOP
            if (!live) actions = actions or android.media.session.PlaybackState.ACTION_SEEK_TO
            if (hasList()) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
            s.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                        currentMs().toLong(), if (playing) speed else 0f,
                    ).build(),
            )
            val meta = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, getString(if (live) R.string.player_live else R.string.app_name))
            if (!live && durationMs() > 0) meta.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, durationMs().toLong())
            s.setMetadata(meta.build())
        }
        if (Build.VERSION.SDK_INT < 23) return
        runCatching {
            val open = android.app.PendingIntent.getActivity(
                this, 1,
                Intent(this, VideoPlayerActivity::class.java).setData(uri).putExtra(EXTRA_NAME, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            fun act(icon: Int, title: Int, action: String, code: Int) =
                android.app.Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), getString(title), pending(action, code)).build()
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, CHANNEL) else android.app.Notification.Builder(this)
            b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name)
                .setContentText(getString(if (live) R.string.player_live else if (playing) R.string.player_playing else R.string.player_paused))
                .setContentIntent(open)
                .setDeleteIntent(pending(ACT_CLOSE, 14))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            val compact = ArrayList<Int>()
            var n = 0
            if (hasList()) {
                b.addAction(act(R.drawable.ic_skip_previous, R.string.player_prev, ACT_PREV, 11))
                compact += n++
            }
            b.addAction(act(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) R.string.player_pause else R.string.player_play, ACT_TOGGLE, 12))
            compact += n++
            if (hasList()) {
                b.addAction(act(R.drawable.ic_skip_next, R.string.player_next, ACT_NEXT, 13))
                compact += n++
            }
            b.addAction(act(R.drawable.ic_close, R.string.player_close, ACT_CLOSE, 14))
            b.setStyle(
                android.app.Notification.MediaStyle()
                    .setMediaSession(s.sessionToken)
                    .setShowActionsInCompactView(*compact.take(3).toIntArray()),
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(NOTIFY_ID, b.build())
        }.onFailure { Log.w(TAG, "notify: $it") }
    }

    private fun closeSession() {
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(NOTIFY_ID) }
        runCatching { session?.isActive = false }
        runCatching { session?.release() }
        session = null
        if (receiverOn) runCatching { unregisterReceiver(actionReceiver) }
        receiverOn = false
    }

    private fun updatePlayIconSafe() = runCatching { updatePlayIcon() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("speed", speed)
        outState.putInt("pos", currentMs())
    }

    override fun onDestroy() {
        com.stoyko.smessenger.ui.ThemeMusic.release("video")
        ui.removeCallbacksAndMessages(null)
        closeSession()
        if (prepared) {
            val pos = currentMs()
            savePosition(if (pos >= durationMs() - 3_000) 0 else pos)
        }
        runCatching { player?.release() }
        player = null
        surface?.release()
        surface = null
        super.onDestroy()
    }

    // ---------------- место остановки ----------------

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savedPosition(): Int = runCatching { prefs().getInt(uri.toString(), 0) }.getOrDefault(0)

    private fun savePosition(ms: Int) {
        if (live) return
        runCatching {
            val p = prefs()
            // Храним не больше 200 последних видео.
            if (p.all.size > 200) p.edit().clear().apply()
            if (ms > 0) p.edit().putInt(uri.toString(), ms).apply() else p.edit().remove(uri.toString()).apply()
        }
    }

    /** Элемент списка: канал или фильм. [url] = null - адрес узнаём через [resolve] (фильмы). */
    class Item(val name: String, val url: String?, val resolve: (() -> String?)? = null)

    /** Текущий список для «предыдущий / следующий» (заполняет экран ТВ / Кино). */
    object Playlist {
        @Volatile
        var items: List<Item> = emptyList()

        @Volatile
        var index = -1
    }

    companion object {
        private const val CHANNEL = "player"
        private const val NOTIFY_ID = 0x5E1A
        private const val ACT_TOGGLE = "com.stoyko.smessenger.player.TOGGLE"
        private const val ACT_NEXT = "com.stoyko.smessenger.player.NEXT"
        private const val ACT_PREV = "com.stoyko.smessenger.player.PREV"
        private const val ACT_CLOSE = "com.stoyko.smessenger.player.CLOSE"

        /** Открыть со списком (ТВ-каналы, фильмы): в плеере работают «предыдущий / следующий». */
        fun startList(context: Context, items: List<Item>, index: Int, uri: Uri, name: String) {
            Playlist.items = items
            Playlist.index = index
            launch(context, uri, name)
        }

        fun fmt(ms: Int): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }

        fun start(context: Context, uri: Uri, name: String) {
            // Одиночное видео (из чата) - без списка.
            Playlist.items = emptyList()
            Playlist.index = -1
            launch(context, uri, name)
        }

        private fun launch(context: Context, uri: Uri, name: String) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_NAME, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
