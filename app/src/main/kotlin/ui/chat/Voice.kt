// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Voice"
private const val MIN_VOICE_MS = 700L
private const val WAVE_BARS = 40
private const val WAVE_LEVELS = 36 // уровень кодируется одной цифрой base36 (0..z)
private const val SAMPLE_MS = 60L

/**
 * Сведения о голосовом, «зашитые» в имя файла: voice_<время>_d<мс>_w<волна>.m4a.
 * Так длительность и форма волны доходят до собеседника без изменений протокола.
 */
object VoiceMeta {
    fun isVoice(name: String) = name.startsWith("voice_") && name.endsWith(".m4a")

    fun durationMs(name: String): Long? =
        Regex("_d(\\d+)").find(name)?.groupValues?.get(1)?.toLongOrNull()

    fun waveform(name: String): IntArray? {
        val w = Regex("_w([0-9a-z]+)").find(name)?.groupValues?.get(1) ?: return null
        return IntArray(w.length) { Character.digit(w[it], WAVE_LEVELS).coerceAtLeast(0) }
    }

    /** Псевдоволна для голосовых без записанной волны (старые версии) - стабильная для одного файла. */
    fun fallbackWave(seed: String): IntArray {
        var h = seed.hashCode()
        return IntArray(WAVE_BARS) {
            h = h * 1103515245 + 12345
            6 + ((h ushr 16) and 0x7fff) % 22
        }
    }

    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** Голосовое сообщение (AAC, .m4a). Передаётся как файл - со сквозным шифрованием ядра. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = ArrayList<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = try {
                r.maxAmplitude
            } catch (e: Exception) {
                0
            }
            levels.add(amp)
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    val isRecording get() = recorder != null

    /** Запись началась (true) / закончилась (false) - чтобы собеседник видел «записывает аудио». */
    var onState: ((Boolean) -> Unit)? = null
    val elapsedMs get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    fun start(): Boolean {
        if (recorder != null) return true
        // Не в кэше: система может очистить кэш, и свои голосовые перестали бы проигрываться.
        val dir = File(context.filesDir, "outgoing/voice").apply { mkdirs() }
        val f = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(f.absolutePath)
            com.stoyko.smessenger.ui.ThemeMusic.hold("rec")
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            levels.clear()
            handler.postDelayed(sampler, SAMPLE_MS)
            runCatching { onState?.invoke(true) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            com.stoyko.smessenger.ui.ThemeMusic.release("rec")
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    /** Возвращает готовый файл голосового или null, если запись слишком короткая/не удалась. */
    fun stop(): File? {
        val r = recorder ?: return null
        com.stoyko.smessenger.ui.ThemeMusic.release("rec")
        runCatching { onState?.invoke(false) }
        handler.removeCallbacks(sampler)
        recorder = null
        val f = file
        file = null
        val duration = System.currentTimeMillis() - startedAt
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { r.release() }
        }
        if (!ok || f == null || duration < MIN_VOICE_MS) {
            f?.delete()
            return null
        }
        val named = File(f.parentFile, "voice_${System.currentTimeMillis()}_d${duration}_w${encodeWave()}.m4a")
        return if (f.renameTo(named)) named else f
    }

    fun cancel() {
        stop()?.delete()
    }

    private fun encodeWave(): String {
        if (levels.isEmpty()) return ""
        val peak = max(levels.maxOrNull() ?: 1, 1).toFloat()
        val sb = StringBuilder()
        for (i in 0 until WAVE_BARS) {
            val from = i * levels.size / WAVE_BARS
            val to = max(from + 1, (i + 1) * levels.size / WAVE_BARS).coerceAtMost(levels.size)
            var m = 0
            for (k in from until to) m = max(m, levels[k])
            // Корень делает тихие места заметнее.
            val level = (Math.sqrt((m / peak).toDouble()) * (WAVE_LEVELS - 1)).toInt().coerceIn(1, WAVE_LEVELS - 1)
            sb.append(Character.forDigit(level, WAVE_LEVELS))
        }
        return sb.toString()
    }
}

/**
 * Общий проигрыватель голосовых и видеосообщений («кружков»):
 *  - одно сообщение за раз, «пауза/продолжить», перемотка касанием по волне;
 *  - продолжает играть, когда вы уходите в другой чат или канал (внизу/вверху - мини-плеер);
 *  - повторное прослушивание того же голосового всегда начинается заново (раньше проигрыватель
 *    оставался «в конце файла», и голосовое нельзя было послушать снова, пока не включишь другое);
 *  - скорость 1x / 1.5x / 2x.
 * [key] - уникальный ключ сообщения (например, путь файла).
 */
object VoicePlayer {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    var currentKey: String? = null
        private set
    var currentUri: Uri? = null
        private set

    /** Подпись для мини-плеера («Голосовое · Имя»). */
    var title: String = ""
        private set

    /** Сейчас играет видеосообщение («кружок»). */
    var isVideo: Boolean = false
        private set

    var speed: Float = 1f
        private set

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Вызывается ~10 раз в секунду во время проигрывания и при смене состояния (экран чата). */
    var onUpdate: (() -> Unit)? = null

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Дополнительные слушатели (мини-плеер, плавающий «кружок»). */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun dispatch() {
        runCatching { onUpdate?.invoke() }
        for (l in listeners) runCatching { l() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            dispatch()
            if (player != null && isPlaying) handler.postDelayed(this, 100)
        }
    }

    private fun kick() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun isVoice(name: String) = VoiceMeta.isVoice(name)

    fun positionMs(key: String): Long =
        if (key == currentKey) runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else 0L

    fun durationMs(key: String): Long =
        if (key == currentKey) runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L) else 0L

    fun progress(key: String): Float {
        val d = durationMs(key)
        return if (d > 0) (positionMs(key).toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    // ---------- аудиофокус: звонок или другое приложение - пауза ----------

    private var focusRequest: Any? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            handler.post { pause() }
        }
    }

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(focusListener, handler)
                    .build()
                am.requestAudioFocus(req)
                focusRequest = req
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus: $e")
        }
    }

    private fun abandonFocus() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? android.media.AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) {
        }
        focusRequest = null
    }

    // ---------- видео («кружки»): куда выводить картинку ----------

    private class Target(val key: String, val surface: android.view.Surface, val priority: Int)

    private val targets = LinkedHashMap<Any, Target>()
    private var appliedSurface: android.view.Surface? = null

    /**
     * Поверхность для видео сообщения [key]. priority: 1 - сам «кружок» в чате, 0 - плавающий
     * «кружок» поверх экранов. surface == null - убрать. Картинка идёт в самую приоритетную.
     */
    fun setVideoTarget(owner: Any, key: String, surface: android.view.Surface?, priority: Int) {
        if (surface == null) targets.remove(owner) else targets[owner] = Target(key, surface, priority)
        applySurface()
        dispatch()
    }

    /** Есть ли «кружок» этого сообщения на экране (тогда плавающий не нужен). */
    fun hasInlineTarget(): Boolean {
        val key = currentKey ?: return false
        return targets.values.any { it.key == key && it.priority > 0 }
    }

    private fun applySurface() {
        val p = player ?: return
        if (!isVideo) return
        val key = currentKey
        val best = targets.values.filter { it.key == key && it.surface.isValid }.maxByOrNull { it.priority }?.surface
        if (best === appliedSurface) return
        appliedSurface = best
        try {
            p.setSurface(best)
        } catch (e: Exception) {
            Log.w(TAG, "setSurface: $e")
        }
    }

    // ---------- управление ----------

    /** Нажатие на «play»: то же сообщение - пауза/продолжить, другое - начать его сначала. */
    fun toggle(context: Context, key: String, uri: Uri, title: String = "", video: Boolean = false) {
        appContext = context.applicationContext
        val p = player
        if (p != null && key == currentKey) {
            try {
                if (p.isPlaying) {
                    p.pause()
                } else {
                    // Закончилось (или почти) - начинаем с начала, а не «стоим в конце».
                    val d = p.duration
                    if (d > 0 && p.currentPosition >= d - 250) p.seekTo(0)
                    requestFocus(context)
                    p.start()
                }
            } catch (e: Exception) {
                // Проигрыватель в плохом состоянии - пересоздаём и играем заново.
                stop()
                start(context, key, uri, title, video)
                return
            }
            kick()
            return
        }
        stop()
        start(context, key, uri, title, video)
    }

    private fun start(context: Context, key: String, uri: Uri, title: String, video: Boolean) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(context, uri)
            mp.setOnCompletionListener { finished() }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "player error $what/$extra")
                handler.post { stop() }
                true
            }
            player = mp
            currentKey = key
            currentUri = uri
            this.title = title
            isVideo = video
            appliedSurface = null
            applySurface()
            mp.prepare()
            applySpeed(mp)
            requestFocus(context)
            mp.start()
            kick()
        } catch (e: Exception) {
            Log.e(TAG, "play", e)
            Toast.makeText(context, com.stoyko.smessenger.R.string.voice_play_failed, Toast.LENGTH_SHORT).show()
            stop()
        }
    }

    /** Совместимость со старым вызовом (группы). */
    fun toggle(context: Context, uri: Uri) = toggle(context, uri.toString(), uri)

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        kick()
    }

    fun resume() {
        val ctx = appContext ?: return
        val key = currentKey ?: return
        val uri = currentUri ?: return
        if (!isPlaying) toggle(ctx, key, uri, title, isVideo)
    }

    /**
     * Следующее голосовое/кружок в чате после доигранного (ключ, адрес, это кружок?) -
     * задаёт открытый чат. Так несколько присланных подряд голосовых играют одно за другим.
     */
    var nextProvider: ((String) -> Triple<String, Uri, Boolean>?)? = null

    private fun finished() {
        // Сообщение доиграно: освобождаем проигрыватель; если есть следующее голосовое - играем его.
        val key = currentKey
        val ctx = appContext
        val t = title
        val next = key?.let { k -> runCatching { nextProvider?.invoke(k) }.getOrNull() }
        stop()
        if (next != null && ctx != null) handler.postDelayed({ if (player == null) start(ctx, next.first, next.second, t, next.third) }, 250)
    }

    fun seek(key: String, fraction: Float) {
        val p = player ?: return
        if (key != currentKey) return
        runCatching { p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toInt()) }
        dispatch()
    }

    /** 1x → 1.5x → 2x → 1x (Android 6+). */
    fun cycleSpeed(): Float {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player?.let { applySpeed(it) }
        dispatch()
        return speed
    }

    private fun applySpeed(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
            // На некоторых устройствах смена скорости запускает проигрывание - возвращаем паузу.
            if (!wasPlaying && mp.isPlaying) mp.pause()
        } catch (e: Exception) {
            Log.w(TAG, "speed: $e")
        }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        val p = player
        player = null
        runCatching { p?.stop() }
        runCatching { p?.release() }
        currentKey = null
        currentUri = null
        isVideo = false
        appliedSurface = null
        abandonFocus()
        dispatch()
    }
}

/** Форма волны голосового с закраской проигранной части. */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    var levels: IntArray = IntArray(0)
        set(value) {
            field = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var playedColor: Int = 0xFFFFFFFF.toInt()
    var restColor: Int = 0x80FFFFFF.toInt()

    /** Касание по волне - перемотка (fraction 0..1). */
    var onSeek: ((Float) -> Unit)? = null

    private var downX = 0f
    private var dragging = false
    private var longFired = false
    private var lastTapAt = 0L
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        longFired = true
        com.stoyko.smessenger.chat.VoiceTextUi.forwardLongPress(this)
    }

    /**
     * Касание по волне: нажатие - перемотка, ведение пальцем - перемотка следом,
     * удержание - меню сообщения (с реакциями), двойное касание - быстрая реакция.
     * Раньше волна забирала все касания и на голосовое нельзя было поставить реакцию.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cb = onSeek ?: return super.onTouchEvent(event)
        val frac = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                longFired = false
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!longFired && (dragging || kotlin.math.abs(event.x - downX) > slop)) {
                if (!dragging) {
                    dragging = true
                    removeCallbacks(longPress)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                cb(frac)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!longFired && !dragging) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTapAt < 320) {
                        lastTapAt = 0L
                        doubleTapToList(event)
                    } else {
                        lastTapAt = now
                        cb(frac)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    /** Двойное касание по волне - как двойное касание по сообщению (быстрая реакция). */
    private fun doubleTapToList(event: MotionEvent) {
        var p = parent
        var child: View = this
        while (p != null && p !is com.stoyko.smessenger.ui.SwipeReplyListView) {
            if (p is View) child = p
            p = p.parent
        }
        val list = p as? com.stoyko.smessenger.ui.SwipeReplyListView ?: return
        val pos = list.getPositionForView(child)
        if (pos < 0) return
        val a = IntArray(2)
        val b = IntArray(2)
        getLocationOnScreen(a)
        list.getLocationOnScreen(b)
        list.onDoubleTap?.invoke(pos, event.x + a[0] - b[0], event.y + a[1] - b[1])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = levels
        if (data.isEmpty() || width == 0) return
        val bar = 2.5f * density
        val gap = 1.5f * density
        val count = min(data.size, ((width + gap) / (bar + gap)).toInt()).coerceAtLeast(1)
        val maxH = height.toFloat()
        val minH = 2.5f * density
        for (i in 0 until count) {
            val v = data[i * data.size / count] / (WAVE_LEVELS - 1f)
            val h = max(minH, v * maxH)
            val x = i * (bar + gap)
            rect.set(x, (maxH - h) / 2f, x + bar, (maxH + h) / 2f)
            paint.color = if ((i + 0.5f) / count <= progress) playedColor else restColor
            canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
        }
    }
}

/**
 * Кнопка записи:
 *  - короткое нажатие переключает «микрофон ↔ камера» (голосовое или «кружок»);
 *  - микрофон: удерживайте - идёт запись (таймер в поле ввода), отпустите - голосовое уйдёт,
 *    сдвиньте палец влево - запись отменится;
 *  - камера: удерживайте - откроется запись «кружка» (сначала выбор камеры).
 * [ensureMic]/[ensureCamera] должны вернуть true, если разрешения уже есть (иначе запросить их).
 */
@SuppressLint("ClickableViewAccessibility")
fun setupRecordButton(
    button: android.widget.ImageView,
    input: EditText,
    recorder: VoiceRecorder,
    recordingHint: String,
    ensureMic: () -> Boolean,
    ensureCamera: () -> Boolean,
    onVoice: (File) -> Unit,
    onVideoNote: (File) -> Unit,
) {
    val context = button.context
    val prefs = context.getSharedPreferences("record_button", Context.MODE_PRIVATE)
    val density = context.resources.displayMetrics.density
    val cancelDistance = 110 * density
    val handler = Handler(Looper.getMainLooper())
    var cameraMode = prefs.getBoolean("camera", false)
    var normalHint: CharSequence? = null
    var pulse: android.animation.ValueAnimator? = null
    var downX = 0f
    var cancelled = false
    var blink = false
    var holdStarted = false

    fun applyIcon(animate: Boolean) {
        val res = if (cameraMode) com.stoyko.smessenger.R.drawable.ic_videocam else com.stoyko.smessenger.R.drawable.ic_mic
        button.contentDescription = context.getString(
            if (cameraMode) com.stoyko.smessenger.R.string.video_message else com.stoyko.smessenger.R.string.voice_message,
        )
        if (!animate) {
            button.setImageResource(res)
            return
        }
        // Смена значка: сжатие с поворотом и «выпрыгивание» нового.
        button.animate().scaleX(0.2f).scaleY(0.2f).rotation(-90f).setDuration(110).withEndAction {
            button.setImageResource(res)
            button.rotation = 90f
            button.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        }.start()
    }
    applyIcon(false)

    val timer = object : Runnable {
        override fun run() {
            if (!recorder.isRecording) return
            blink = !blink
            val dot = if (blink) "🔴" else "⭕"
            input.hint = "$dot ${VoiceMeta.format(recorder.elapsedMs)}   $recordingHint"
            handler.postDelayed(this, 500)
        }
    }

    fun finishUi(v: View) {
        handler.removeCallbacks(timer)
        pulse?.cancel()
        pulse = null
        v.alpha = 1f
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(150).start()
        input.isEnabled = true
        if (normalHint != null) input.hint = normalHint
    }

    fun startVoice(v: View) {
        if (!ensureMic()) return
        VoicePlayer.stop()
        if (!recorder.start()) return
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        cancelled = false
        normalHint = input.hint
        input.isEnabled = false
        v.animate().scaleX(1.7f).scaleY(1.7f).setDuration(150).start()
        pulse = android.animation.ValueAnimator.ofFloat(1f, 0.45f).apply {
            duration = 650
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { a -> v.alpha = a.animatedValue as Float }
            start()
        }
        handler.post(timer)
    }

    val holdRunnable = Runnable {
        holdStarted = true
        if (cameraMode) {
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (ensureCamera()) {
                VoicePlayer.stop()
                com.stoyko.smessenger.media.CaptureDialog(context, story = false, onResult = onVideoNote).show()
            }
        } else {
            startVoice(button)
        }
    }

    button.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.rawX
                holdStarted = false
                cancelled = false
                handler.postDelayed(holdRunnable, 220)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (recorder.isRecording && !cancelled) {
                    val dx = min(0f, e.rawX - downX)
                    v.translationX = dx
                    if (-dx > cancelDistance) {
                        // Сдвинули влево - отмена.
                        cancelled = true
                        recorder.cancel()
                        finishUi(v)
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        input.animate().translationX(12 * density).setDuration(50).withEndAction {
                            input.animate().translationX(0f).setInterpolator(
                                android.view.animation.OvershootInterpolator(6f),
                            ).setDuration(250).start()
                        }.start()
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                if (!holdStarted) {
                    // Короткое нажатие - переключить микрофон/камеру.
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        cameraMode = !cameraMode
                        prefs.edit().putBoolean("camera", cameraMode).apply()
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        applyIcon(true)
                    }
                } else if (recorder.isRecording) {
                    finishUi(v)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !cancelled) {
                        val f = recorder.stop()
                        if (f != null) {
                            onVoice(f)
                        } else {
                            Toast.makeText(context, com.stoyko.smessenger.R.string.voice_hold_hint, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        recorder.cancel()
                    }
                } else {
                    finishUi(v)
                }
                true
            }
            else -> true
        }
    }
}
