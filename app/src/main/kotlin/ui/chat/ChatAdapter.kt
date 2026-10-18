// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.annotation.SuppressLint
import android.content.res.Resources
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.net.toUri
import com.squareup.picasso.Picasso
import java.net.URLConnection
import java.text.DateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.FT_NOT_STARTED
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.core.vo.isComplete
import com.stoyko.smessenger.core.vo.isRejected
import com.stoyko.smessenger.core.vo.isStarted
import com.stoyko.smessenger.domain.feature.TransferMonitor
import com.stoyko.smessenger.media.VideoNoteMeta
import com.stoyko.smessenger.media.VideoNoteView
import com.stoyko.smessenger.ui.ProgressRingView

private const val TAG = "ChatAdapter"
private const val IMAGE_TO_SCREEN_RATIO = 0.75

private fun FileTransfer.isImage() = try {
    URLConnection.guessContentTypeFromName(fileName)?.startsWith("image/") == true
} catch (e: Exception) {
    Log.e(TAG, e.toString())
    false
}

private fun inflateView(type: ChatItemType, inflater: LayoutInflater): View = inflater.inflate(
    when (type) {
        ChatItemType.SentMessage -> R.layout.chat_message_sent
        ChatItemType.ReceivedMessage -> R.layout.chat_message_received
        ChatItemType.SentAction -> R.layout.chat_action_sent
        ChatItemType.ReceivedAction -> R.layout.chat_action_received
        ChatItemType.SentFileTransfer, ChatItemType.ReceivedFileTransfer -> R.layout.chat_filetransfer
    },
    null,
    true,
)

private enum class ChatItemType {
    ReceivedMessage,
    SentMessage,
    ReceivedAction,
    SentAction,
    ReceivedFileTransfer,
    SentFileTransfer,
}

/** Действия с файлом в чате. */
enum class FtAction { PlayVoice, PlayVideoNote, Cancel, Accept, Reject, Transcribe, Retry }

private val timeFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

private class MessageViewHolder(row: View) {
    val emojiAnim: ImageView? = row.findViewById(R.id.emojiAnim)
    val message: TextView = row.findViewById(R.id.message)
    val timestamp: TextView = row.findViewById(R.id.timestamp)

    // Есть только у обычных сообщений (у «действий» - нет).
    val linkPreview: View? = row.findViewById(R.id.linkPreview)
    val linkPreviewImage: ImageView? = row.findViewById(R.id.linkPreviewImage)
    val linkPreviewLabel: TextView? = row.findViewById(R.id.linkPreviewLabel)
    val cardBox: FrameLayout? = row.findViewById(R.id.cardBox)
}

private class FileTransferViewHolder(row: View) {
    val container: RelativeLayout = row.findViewById(R.id.fileTransfer)
    val fileName: TextView = row.findViewById(R.id.fileName)
    val fileSize: TextView = row.findViewById(R.id.fileSize)
    val progress: ProgressBar = row.findViewById(R.id.progress)
    val state: TextView = row.findViewById(R.id.state)
    val timestamp: TextView = row.findViewById(R.id.timestamp)
    val acceptLayout: View = row.findViewById(R.id.acceptLayout)
    val accept: Button = row.findViewById(R.id.accept)
    val reject: Button = row.findViewById(R.id.reject)
    val cancelLayout: View = row.findViewById(R.id.cancelLayout)
    val cancel: Button = row.findViewById(R.id.cancel)
    val completedLayout: View = row.findViewById(R.id.completedLayout)
    val imagePreview: ImageView = row.findViewById(R.id.imagePreview)
    val imageRing: ProgressRingView = row.findViewById(R.id.imageRing)
    val imageInfo: TextView = row.findViewById(R.id.imageInfo)
    val bubble: View = row.findViewById(R.id.container)
    val fileInfo: View = row.findViewById(R.id.fileInfo)
    val fileRing: ProgressRingView = row.findViewById(R.id.fileRing)
    val fileIcon: TextView = row.findViewById(R.id.fileIcon)
    val videoPlayIcon: TextView = row.findViewById(R.id.videoPlayIcon)
    val videoDuration: TextView = row.findViewById(R.id.videoDuration)
    val stickerAnim: ImageView = row.findViewById(R.id.stickerAnim)
    val voiceLayout: View = row.findViewById(R.id.voiceLayout)
    val voicePlay: ImageButton = row.findViewById(R.id.voicePlay)
    val voiceRing: ProgressRingView = row.findViewById(R.id.voiceRing)
    val voiceWave: WaveformView = row.findViewById(R.id.voiceWave)
    val voiceTime: TextView = row.findViewById(R.id.voiceTime)
    val voiceTranscribe: ImageButton = row.findViewById(R.id.voiceTranscribe)
    val videoNoteBox: FrameLayout = row.findViewById(R.id.videoNoteBox)
    val videoNote: VideoNoteView = row.findViewById(R.id.videoNote)
    val videoNoteRing: ProgressRingView = row.findViewById(R.id.videoNoteRing)
    val caption: TextView = row.findViewById(R.id.ftCaption)
    var voiceKey: String? = null
    var voiceDurationMs: Long = 0
    var voiceReady = false
    var ft: FileTransfer? = null
}

/** Ключ голосового для проигрывателя - адрес файла (уникален для сообщения). */
fun FileTransfer.voiceKey(): String = destination

/** 1-3 эмодзи без текста показываются крупно и без пузыря. */
/** Время отправки из имени файла стикера (…_<мс>.webp). */
/** Расширения файлов: такие «слова» в тексте - имена файлов, а не адреса сайтов. */
private val FILE_EXTS = setOf(
    "apk", "apks", "xapk", "zip", "rar", "7z", "tar", "gz", "iso", "exe", "dll", "msi", "bin", "pdf", "doc", "docx", "xls",
    "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml", "jpg", "jpeg", "png", "gif", "webp", "heic", "mp3", "m4a", "ogg", "opus",
    "wav", "flac", "mp4", "mkv", "mov", "avi", "webm", "3gp", "js", "css", "kt", "java", "py", "c", "cpp", "h", "md",
)

private val STICKER_TIME = Regex("_(\\d{12,14})\\.(webp|png|jpg|jpeg|gif)$")

fun isJumboEmoji(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    var count = 0
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        val len = Character.charCount(cp)
        val type = Character.getType(cp)
        when {
            // Модификаторы (тон кожи, вариации, соединители) не считаем отдельными эмодзи.
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F -> Unit
            type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF -> {
                // Эмодзи, соединённые 200D, - одно изображение.
                val joined = i > 0 && t.codePointBefore(i) == 0x200D
                if (!joined) count++
            }
            else -> return false
        }
        i += len
    }
    return count in 1..3
}

class ChatAdapter(private val inflater: LayoutInflater, private val resources: Resources) :
    BaseAdapter(),
    android.widget.SectionIndexer {
    // ---------- Быстрая прокрутка с датой: тяните ползунок справа - видно, за какое число сообщения ----------
    private var sectionNames: Array<String> = emptyArray()
    private var sectionStarts: IntArray = IntArray(0)
    private var sectionOfPos: IntArray = IntArray(0)
    private val dayFormat = java.text.SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

    private fun rebuildSections() {
        val names = ArrayList<String>()
        val starts = ArrayList<Int>()
        val of = IntArray(messages.size)
        var lastDay = -1L
        messages.forEachIndexed { i, m ->
            val day = m.timestamp / 86_400_000L
            if (names.isEmpty() || day != lastDay) {
                lastDay = day
                names += if (m.timestamp > 0) dayFormat.format(java.util.Date(m.timestamp)) else "…"
                starts += i
            }
            of[i] = names.size - 1
        }
        sectionNames = names.toTypedArray()
        sectionStarts = starts.toIntArray()
        sectionOfPos = of
    }

    override fun getSections(): Array<Any> = sectionNames.map { it as Any }.toTypedArray()
    override fun getPositionForSection(section: Int): Int = sectionStarts.getOrElse(section.coerceIn(0, (sectionStarts.size - 1).coerceAtLeast(0))) { 0 }
    override fun getSectionForPosition(position: Int): Int = sectionOfPos.getOrElse(position) { 0 }

    private val density = resources.displayMetrics.density
    private val ru = Locale.getDefault().language == "ru"

    /** Оформление строки: обычный пузырь, служебная строка по центру или крупные эмодзи без пузыря. */
    private fun styleMessage(vh: MessageViewHolder, outgoing: Boolean, system: Boolean, jumbo: Boolean) {
        val m = vh.message
        when {
            system -> {
                m.setBackgroundResource(R.drawable.bg_system_line)
                m.textSize = 13f
                val p = (6 * density).toInt()
                m.setPadding(p * 2, p, p * 2, p)
            }
            jumbo -> {
                m.background = null
                m.textSize = 56f
                m.setPadding(0, 0, 0, 0)
            }
            else -> {
                m.setBackgroundResource(if (outgoing) R.drawable.sent_message_bubble else R.drawable.received_message_bubble)
                look.shapeBubble(m, outgoing)
                m.textSize = com.stoyko.smessenger.ui.UiPrefs.messageTextSize
                m.setPadding(bubblePadH, bubblePadV, bubblePadH, bubblePadV)
            }
        }
    }

    private val look = ChatLook(inflater.context)
    private val animatedEmoji = HashSet<Long>()

    /** Стикеры, у которых уже проиграно появление. */
    private val stickerEntrance = HashSet<Int>()

    /** Выбранные сообщения (режим выбора для пересылки пачкой). */
    var selectedIds: Set<Long> = emptySet()

    /** Крупные эмодзи без готовой анимации: у каждого вида своё движение (сердце бьётся, смех трясётся…). */
    private fun animateEmoji(v: TextView) {
        com.stoyko.smessenger.chat.EmojiMotion.play(v, v.text.toString())
    }

    /**
     * Имена файлов (app.apk, report.pdf…) и цитаты ответа - не ссылки: раньше автоссылки делали
     * из них «ссылку», нажатие уходило в браузер, а не к исходному сообщению или файлу.
     */
    /** Файл из текста сообщения: готов - открыть/включить, ждёт приёма - принять. */
    private fun openInline(ft: FileTransfer) {
        val ctx = inflater.context
        when {
            ft.outgoing || ft.isComplete() -> {
                if (com.stoyko.smessenger.media.MusicPlayer.isMusic(ft.fileName)) {
                    runCatching { com.stoyko.smessenger.media.MusicPlayer.play(ctx, android.net.Uri.parse(ft.destination), ft.fileName) }
                } else {
                    FileTypes.open(ctx, ft.destination, ft.fileName)
                }
            }
            !ft.isStarted() && !ft.isRejected() -> onFtAction?.invoke(FtAction.Accept, ft)
            else -> android.widget.Toast.makeText(
                ctx,
                if (ft.fileSize > 0) "⬇ ${ft.progress * 100 / ft.fileSize}%" else "⬇",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun stripFileLinks(tv: TextView) {
        val sp = tv.text as? android.text.Spannable ?: return
        val quotes = sp.getSpans(0, sp.length, android.text.style.QuoteSpan::class.java)
        for (span in sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java)) {
            val start = sp.getSpanStart(span)
            val end = sp.getSpanEnd(span)
            val inQuote = quotes.any { q -> start >= sp.getSpanStart(q) && end <= sp.getSpanEnd(q) }
            val label = sp.subSequence(start, end).toString().lowercase()
            val ext = label.substringAfterLast('.', "").substringBefore('/').substringBefore('?')
            val isFileName = !label.contains("/") && !label.startsWith("http") && ext in FILE_EXTS
            if (inQuote || isFileName) sp.removeSpan(span)
        }
    }

    private fun keepYouTubeInside(tv: TextView) {
        val sp = tv.text as? android.text.Spannable ?: return
        for (span in sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java)) {
            val url = span.url ?: continue
            val start = sp.getSpanStart(span)
            val end = sp.getSpanEnd(span)
            val isShorts = url.contains("/shorts") && com.stoyko.smessenger.media.YouTube.videoId(url) == null
            val vid = com.stoyko.smessenger.media.YouTube.videoId(url)
            if (vid == null && !isShorts && !url.contains("youtube.com") && !url.contains("youtu.be")) continue
            sp.removeSpan(span)
            sp.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        if (vid != null) {
                            com.stoyko.smessenger.media.YouTube.play(widget.context, vid)
                        } else {
                            com.stoyko.smessenger.media.YouTube.open(widget.context, url)
                        }
                    }
                },
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * YouTube в сообщении - ОДНИМ пузырём: обложка видео с ▶ стоит прямо в пузыре над текстом
     * (раньше ссылка и превью были двумя отдельными блоками, и на превью нельзя было поставить
     * реакцию). Касание обложки - смотреть в мессенджере; удержание и двойное касание по всему
     * пузырю - реакции и меню, как у обычного сообщения.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindLinkPreview(vh: MessageViewHolder, text: String) {
        vh.linkPreview?.visibility = View.GONE
        val tv = vh.message
        // Ссылки YouTube в тексте тоже открываются внутри мессенджера, а не в приложении YouTube.
        keepYouTubeInside(tv)
        val id = if (look.linkPreviews && text.isNotEmpty()) com.stoyko.smessenger.media.YouTube.videoId(text) else null
        if (id == null) {
            if (tv.getTag(R.id.tag_yt_id) != null) {
                tv.setTag(R.id.tag_yt_id, null)
                tv.setTag(R.id.tag_yt_target, null)
                tv.setCompoundDrawablesRelative(null, null, null, null)
                tv.setOnTouchListener(null)
            }
            return
        }
        val w = (240 * density).toInt()
        val h = (135 * density).toInt()
        if (tv.getTag(R.id.tag_yt_id) != id) {
            tv.setTag(R.id.tag_yt_id, id)
            val shorts = com.stoyko.smessenger.media.YouTube.isShorts(text)
            val cached = ytCache.get(id)
            if (cached != null) {
                setTop(tv, cached, w, h)
            } else {
                // Место под обложку сразу - пузырь не прыгает, когда она загрузится.
                setTop(tv, ytCard(null, w, h, shorts), w, h)
                val target = object : com.squareup.picasso.Target {
                    override fun onBitmapLoaded(bitmap: android.graphics.Bitmap, from: Picasso.LoadedFrom) {
                        val card = ytCard(bitmap, w, h, shorts)
                        ytCache.put(id, card)
                        if (tv.getTag(R.id.tag_yt_id) == id) setTop(tv, card, w, h)
                    }

                    override fun onBitmapFailed(e: Exception?, errorDrawable: android.graphics.drawable.Drawable?) = Unit
                    override fun onPrepareLoad(placeHolderDrawable: android.graphics.drawable.Drawable?) = Unit
                }
                tv.setTag(R.id.tag_yt_target, target)
                Picasso.get().load(com.stoyko.smessenger.media.YouTube.thumbnail(id)).into(target)
            }
        }
        // Касание по обложке - смотреть; всё остальное (удержание, двойное касание) - как обычно.
        var downAt = 0L
        var downY = 0f
        var downX = 0f
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = android.os.SystemClock.uptimeMillis()
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    val quick = android.os.SystemClock.uptimeMillis() - downAt < 300
                    val still = kotlin.math.abs(e.x - downX) < 20 * density && kotlin.math.abs(e.y - downY) < 20 * density
                    if (quick && still && e.y < v.paddingTop + h + 4 * density) {
                        com.stoyko.smessenger.media.YouTube.play(v.context, id)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private val ytCache = android.util.LruCache<String, android.graphics.Bitmap>(24)

    private fun setTop(tv: TextView, bmp: android.graphics.Bitmap, w: Int, h: Int) {
        val dr = android.graphics.drawable.BitmapDrawable(tv.resources, bmp).apply { setBounds(0, 0, w, h) }
        tv.setCompoundDrawablesRelative(null, dr, null, null)
        tv.compoundDrawablePadding = (6 * density).toInt()
    }

    /** Обложка для пузыря: картинка видео (по центру, со скруглением), кнопка ▶ и подпись. */
    private fun ytCard(src: android.graphics.Bitmap?, w: Int, h: Int, shorts: Boolean): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val r = 12 * density
        val clip = android.graphics.Path().apply { addRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        if (src == null) {
            c.drawColor(0xFF2A2A3A.toInt())
        } else {
            val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val sw = src.width * scale
            val sh = src.height * scale
            c.drawBitmap(src, null, android.graphics.RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2), p)
        }
        // Кнопка ▶.
        p.color = 0xCC000000.toInt()
        c.drawCircle(w / 2f, h / 2f, 26 * density, p)
        p.color = android.graphics.Color.WHITE
        val tri = android.graphics.Path().apply {
            moveTo(w / 2f - 8 * density, h / 2f - 12 * density)
            lineTo(w / 2f + 14 * density, h / 2f)
            lineTo(w / 2f - 8 * density, h / 2f + 12 * density)
            close()
        }
        c.drawPath(tri, p)
        // Подпись «YouTube» / «YouTube Shorts».
        val label = if (shorts) "YouTube Shorts" else "YouTube"
        p.textSize = 11 * density
        p.isFakeBoldText = true
        val tw = p.measureText(label)
        p.color = 0xB3000000.toInt()
        c.drawRoundRect(android.graphics.RectF(6 * density, h - 24 * density, 18 * density + tw, h - 6 * density), 9 * density, 9 * density, p)
        p.color = android.graphics.Color.WHITE
        c.drawText(label, 12 * density, h - 11 * density, p)
        return out
    }

    private val bubblePadH = (8 * density).toInt()
    private val bubblePadV = (8 * density).toInt()

    // Анимируем только сообщения, пришедшие/отправленные после открытия чата.
    private var newestAnimatedId: Long? = null

    var messages: List<Message> = listOf()
        set(value) {
            if (newestAnimatedId == null) newestAnimatedId = value.maxOfOrNull { it.id } ?: 0L
            field = value
            rebuildSections()
        }
    var fileTransfers: List<FileTransfer> = listOf()
        set(v) {
            field = v
            // Поиск передачи по номеру - за O(1): раньше каждая строка с файлом перебирала весь
            // список (в больших чатах на слабых телефонах прокрутка дёргалась).
            ftById = v.associateBy { it.id }
        }
    private var ftById: Map<Int, FileTransfer> = emptyMap()
    /** О каких передачах уже сказали в журнал (один раз, а не на каждую перерисовку). */
    private val ftMissingLogged = HashSet<Int>()

    /** Реакции под сообщением (задаёт экран чата). */
    var reactionsFor: ((Message) -> String)? = null

    /** Реакции по эмодзи: (эмодзи, поставил я, поставил собеседник). */
    var reactionParts: ((Message) -> List<Triple<String, Boolean, Boolean>>)? = null

    /** Маленькие аватарки того, кто поставил реакцию (собеседник и я). */
    var peerBadge: android.graphics.Bitmap? = null
    var myBadge: android.graphics.Bitmap? = null

    /** Строка реакций: эмодзи и рядом кружок-аватарка того, кто её поставил. */
    private fun reactionsText(m: Message): CharSequence {
        val parts = reactionParts?.invoke(m)
        if (parts == null || parts.isEmpty()) return reactionsFor?.invoke(m).orEmpty()
        // Крупные «таблетки»: эмодзи, число и аватарки поставивших.
        runCatching { return ReactionChips.build(inflater.context, parts, peerBadge, myBadge) }
        val sb = SpannableStringBuilder()
        val size = (15 * density).toInt()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) sb.append("  ")
            sb.append(p.first)
            for ((who, bmp) in listOf(p.third to peerBadge, p.second to myBadge)) {
                if (!who || bmp == null) continue
                sb.append(" ")
                val st = sb.length
                sb.append("●")
                val dr = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(inflater.context.resources, bmp).apply {
                    isCircular = true
                    setBounds(0, 0, size, size)
                }
                sb.setSpan(android.text.style.ImageSpan(dr, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** Текст поиска - подсвечивается в сообщениях. */
    var highlight: String? = null

    /** Наши сообщения с индексом <= readIndex собеседник прочитал (✓✓). */
    var readIndex: Int = -1

    /** Нажатия на элементы файлов (играть, отменить, принять...). */
    var onFtAction: ((FtAction, FileTransfer) -> Unit)? = null

    // Видимые на экране голосовые/кружки - обновляем их прогресс без перерисовки всего списка.
    private val voiceHolders = HashSet<FileTransferViewHolder>()

    // Видимые файлы - для живого прогресса загрузки (МБ, скорость).
    private val ftHolders = HashSet<FileTransferViewHolder>()
    /** Длительность видео по адресу файла (читается один раз). */
    private val durations = HashMap<String, String>()

    /** Вызывается проигрывателем ~10 раз в секунду. */
    fun refreshVoice() {
        for (vh in voiceHolders) updateVoice(vh)
    }

    /** Вызывается экраном ~5 раз в секунду, пока идут передачи. */
    fun refreshTransfers() {
        for (vh in ftHolders) vh.ft?.let { updateTransfer(vh, it) }
    }

    private fun updateVoice(vh: FileTransferViewHolder) {
        val key = vh.voiceKey ?: return
        if (vh.videoNoteBox.visibility == View.VISIBLE) {
            vh.videoNote.sync()
            return
        }
        val current = key == VoicePlayer.currentKey
        val playing = current && VoicePlayer.isPlaying
        vh.voicePlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        vh.voiceWave.progress = if (current) VoicePlayer.progress(key) else 0f
        vh.voiceTime.text = when {
            !vh.voiceReady -> resources.getString(R.string.voice_loading)
            current -> VoiceMeta.format(VoicePlayer.positionMs(key)) + " / " +
                VoiceMeta.format(VoicePlayer.durationMs(key).takeIf { it > 0 } ?: vh.voiceDurationMs)
            else -> VoiceMeta.format(vh.voiceDurationMs)
        }
    }

    override fun getCount(): Int = messages.size
    override fun getItem(position: Int): Any = messages[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = ChatItemType.entries.size
    override fun getItemViewType(position: Int): Int = with(messages[position]) {
        when (type) {
            MessageType.Normal -> when (sender) {
                Sender.Sent -> ChatItemType.SentMessage.ordinal
                Sender.Received -> ChatItemType.ReceivedMessage.ordinal
            }
            MessageType.Action -> when (sender) {
                Sender.Sent -> ChatItemType.SentAction.ordinal
                Sender.Received -> ChatItemType.ReceivedAction.ordinal
            }
            MessageType.FileTransfer -> when (sender) {
                Sender.Sent -> ChatItemType.SentFileTransfer.ordinal
                Sender.Received -> ChatItemType.ReceivedFileTransfer.ordinal
            }
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = buildView(position, convertView, parent)
        val message = messages[position]
        // Режим выбора: выделенные сообщения подсвечены.
        // Кружки-галочки и подсветку выбранных рисует сам список (SwipeReplyListView) - в одном
        // месте слева, независимо от сдвига сообщения.
        if (android.os.Build.VERSION.SDK_INT >= 23) view.foreground = null
        if (android.os.Build.VERSION.SDK_INT < 23 && selectedIds.isEmpty()) view.alpha = 1f
        val newest = newestAnimatedId
        view.translationX = 0f
        if (newest != null && message.id > newest) {
            newestAnimatedId = message.id
            if (com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.MESSAGES)) com.stoyko.smessenger.ui.Safe.run("appear") { com.stoyko.smessenger.ui.anim.MessageAppear.play(view, message.sender == Sender.Sent, parent) }
        } else {
            com.stoyko.smessenger.ui.Safe.run("appear") { com.stoyko.smessenger.ui.anim.MessageAppear.reset(view) }
        }
        // В режиме выбора входящие сообщения сдвигаются вправо, чтобы галочка их не закрывала.
        if (selectedIds.isNotEmpty() && message.sender == Sender.Received) view.translationX = 34 * density
        return view
    }

    /** Нажатие на цитату ответа (задаёт экран чата): перейти к исходному сообщению. */
    var onReplyTap: ((Message) -> Unit)? = null

    /** Нажатие на строку реакций под сообщением - поставить другую или убрать свою. */
    var onReactionsTap: ((Message) -> Unit)? = null

    private fun bindReactionsTap(tv: TextView, message: Message, has: Boolean) {
        if (has && onReactionsTap != null) {
            tv.setOnClickListener { onReactionsTap?.invoke(message) }
        } else {
            tv.setOnClickListener(null)
            tv.isClickable = false
        }
    }

    private fun addReplyTap(tv: TextView, message: Message) {
        val cur = tv.text ?: return
        val sp = cur as? android.text.Spannable ?: android.text.SpannableString(cur)
        val q = sp.getSpans(0, sp.length, android.text.style.QuoteSpan::class.java).firstOrNull() ?: return
        val start = sp.getSpanStart(q)
        val end = sp.getSpanEnd(q)
        if (start < 0 || end <= start) return
        sp.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                widget.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onReplyTap?.invoke(message)
            }

            override fun updateDrawState(ds: android.text.TextPaint) = Unit
        }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sp !== cur) tv.text = sp
        if (tv.movementMethod !is android.text.method.LinkMovementMethod) tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    /** «12:30 ✓✓» - время и статус нашего сообщения. */
    private fun timeWithStatus(position: Int, message: Message, time: CharSequence): CharSequence {
        if (message.sender != Sender.Sent) return time
        val sb = SpannableStringBuilder(time)
        val (mark, color) = when {
            message.timestamp == 0L && message.type != MessageType.FileTransfer -> " 🕓" to 0xFF8A8FA3.toInt()
            position <= readIndex -> " ✓✓" to 0xFF00C2A8.toInt()
            else -> " ✓" to 0xFF8A8FA3.toInt()
        }
        val start = sb.length
        sb.append(mark)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun tapOnDown(v: View, action: () -> Unit) {
        // Список часто перерисовывается во время передачи - обычный «клик» мог теряться.
        v.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
            true
        }
    }

    private fun buildView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val type = ChatItemType.entries[getItemViewType(position)]) {
            ChatItemType.ReceivedMessage, ChatItemType.SentMessage,
            ChatItemType.ReceivedAction, ChatItemType.SentAction,
            -> {
                val message = messages[position]
                val view: View
                val vh: MessageViewHolder

                if (convertView != null) {
                    view = convertView
                    vh = view.tag as MessageViewHolder
                } else {
                    view = inflateView(type, inflater)
                    vh = MessageViewHolder(view)
                    view.tag = vh
                }

                val unsent = message.timestamp == 0L
                val guardLine = com.stoyko.smessenger.chat.ScreenGuard.isMarker(message.message)
                val system = com.stoyko.smessenger.chat.AutoDelete.isMarker(message.message) || guardLine
                val quoted = com.stoyko.smessenger.chat.Markup.parseReply(message.message).first != null
                val jumbo = !system && !quoted && isJumboEmoji(message.message)
                // Текст сообщения: оформление + файлы и музыка прямо в тексте (кнопками-ссылками).
                val sent = message.sender == Sender.Sent
                val richText: () -> CharSequence = {
                    com.stoyko.smessenger.chat.InlineFiles.decorate(
                        inflater.context,
                        com.stoyko.smessenger.chat.Markup.render(message.message, 0xCCFFFFFF.toInt(), highlight),
                    ) { r ->
                        com.stoyko.smessenger.chat.InlineFiles.find(fileTransfers, r, sent)?.let { ft ->
                            com.stoyko.smessenger.chat.InlineFiles.target(ft) { openInline(ft) }
                        }
                    }
                }
                vh.message.text = if (guardLine) {
                    com.stoyko.smessenger.chat.ScreenGuard.systemText(inflater.context, message.message, message.sender == Sender.Sent)
                } else if (system) {
                    com.stoyko.smessenger.chat.AutoDelete.systemText(
                        inflater.context,
                        message.message,
                        message.sender == Sender.Sent,
                    )
                } else {
                    richText()
                }
                stripFileLinks(vh.message)
                // Спойлеры с искрами, сворачиваемые цитаты, живые эмодзи в строке.
                com.stoyko.smessenger.chat.RichSpans.attach(vh.message, if (guardLine || system) null else richText)
                // Цитата в ответе - нажатие переносит к исходному сообщению (раньше нажатие
                // часто не доходило: текст со ссылками «съедал» касания).
                if (quoted && !system && !guardLine) com.stoyko.smessenger.ui.Safe.run("reply-tap") { addReplyTap(vh.message, message) }
                if (message.type == MessageType.Normal) styleMessage(vh, message.sender == Sender.Sent, system, jumbo)
                // «Живые» эмодзи. Если строка перерисовывается для того же сообщения (например, пока
                // грузится файл), анимацию не перезапускаем - раньше она заново стартовала, текст то
                // прятался, то показывался, высота строки менялась и чат «дёргался».
                val anim0 = vh.emojiAnim
                val sameEmoji = jumbo && anim0 != null && anim0.getTag(R.id.tag_emoji_bound) == message.id
                if (sameEmoji) {
                    bindLinkPreview(vh, "")
                } else {
                anim0?.setTag(R.id.tag_emoji_bound, if (jumbo) message.id else null)
                vh.message.animate().cancel()
                com.stoyko.smessenger.chat.EmojiMotion.cancel(vh.message)
                vh.message.visibility = View.VISIBLE
                vh.emojiAnim?.let { com.stoyko.smessenger.chat.AnimatedEmoji.stop(it) }
                val anim = vh.emojiAnim
                val parts = if (jumbo) com.stoyko.smessenger.chat.AnimatedEmoji.split(message.message) else emptyList()
                if (jumbo && anim != null && look.animatedEmoji && message.type == MessageType.Normal && parts.size in 1..3) {
                    // 1-3 эмодзи - настоящие анимированные (Noto), несколько - в ряд. Пока грузятся -
                    // обычные с «характерным» движением; нет готовой анимации - движение и остаётся.
                    val first = animatedEmoji.add(message.id)
                    if (first) animateEmoji(vh.message)
                    com.stoyko.smessenger.chat.AnimatedEmoji.playRow(anim, parts, loop = if (first) 2 else 0) {
                        com.stoyko.smessenger.chat.EmojiMotion.cancel(vh.message)
                        vh.message.visibility = View.GONE
                    }
                } else if (jumbo && message.type == MessageType.Normal && animatedEmoji.add(message.id)) {
                    animateEmoji(vh.message)
                }
                bindLinkPreview(vh, if (system || message.type != MessageType.Normal) "" else message.message)
                }
                val time: CharSequence = if (!unsent) {
                    com.stoyko.smessenger.transport.bt.BtMarks.tag(message.publicKey, message.id, message.message, message.sender == Sender.Sent) + timeFormatter.format(message.timestamp)
                } else {
                    resources.getText(R.string.sending)
                }
                val reactions: CharSequence = if (system) "" else reactionsText(message)
                val shownTime: CharSequence = if (com.stoyko.smessenger.chat.Markup.isEdited(message.message)) {
                    resources.getString(R.string.edited_mark) + " " + time
                } else {
                    time
                }
                val withStatus = if (system) shownTime else timeWithStatus(position, message, shownTime)
                vh.timestamp.text = if (reactions.isNotEmpty()) TextJoin.join(reactions, withStatus) else withStatus
                bindReactionsTap(vh.timestamp, message, reactions.isNotEmpty())

                vh.timestamp.visibility = if (position == messages.lastIndex || unsent || reactions.isNotEmpty() ||
                    (message.sender == Sender.Sent && position == readIndex)
                ) {
                    View.VISIBLE
                } else {
                    val next = messages[position + 1]
                    if (next.timestamp != 0L &&
                        next.sender == message.sender &&
                        next.timestamp - message.timestamp < 60_000
                    ) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }

                com.stoyko.smessenger.ui.Safe.run("card") { applyCard(vh, message) }
                // Кнопки внутри сообщения (ответ, ссылка, копирование, игра).
                (view as? ViewGroup)?.let { row ->
                    val withButtons = message.type == MessageType.Normal && !system && vh.cardBox?.visibility != View.VISIBLE
                    com.stoyko.smessenger.ui.Safe.run("buttons") {
                        com.stoyko.smessenger.chat.MsgButtons.bind(
                            row,
                            vh.message,
                            if (withButtons) message.message else "",
                            message.sender == Sender.Sent,
                            buttonHost,
                        )
                    }
                    // Опрос: варианты, проценты, график - под вопросом.
                    com.stoyko.smessenger.ui.Safe.run("poll") {
                        com.stoyko.smessenger.chat.Polls.bind(row, vh.message, if (withButtons) message.message else "", pollHost)
                    }
                    // HTML-пузырь: мини-приложение / виджет под подписью.
                    com.stoyko.smessenger.ui.Safe.run("html") {
                        com.stoyko.smessenger.chat.HtmlBubbles.bind(row, vh.message, if (withButtons) message.message else "", buttonHost, autoPlay = position == messages.lastIndex && com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.LIVE))
                    }
                }
                view
            }
            ChatItemType.ReceivedFileTransfer, ChatItemType.SentFileTransfer -> {
                val message = messages[position]
                val fileTransfer = ftById[message.correlationId] ?: run {
                    // Обычно это первые доли секунды открытия чата: сообщения уже пришли из базы,
                    // а список передач - ещё нет (через миг экран обновится). Пишем один раз.
                    if (ftMissingLogged.add(message.correlationId)) Log.d(TAG, "ft ${message.correlationId} not loaded yet")
                    FileTransfer("", 0, 0, 0, "", message.sender == Sender.Sent)
                }

                val view: View
                val vh: FileTransferViewHolder

                if (convertView != null) {
                    view = convertView
                    vh = view.tag as FileTransferViewHolder
                } else {
                    view = inflateView(type, inflater)
                    vh = FileTransferViewHolder(view)
                    view.tag = vh
                }
                vh.ft = fileTransfer
                bindFileTransfer(vh, fileTransfer, parent)

                // Реакции на фото, видео, музыку и файлы - как на обычные сообщения.
                val ftReactions = reactionsText(message)
                val ftTime = timeWithStatus(position, message, timeFormatter.format(message.timestamp))
                vh.timestamp.text = if (ftReactions.isNotEmpty()) TextJoin.join(ftReactions, ftTime) else ftTime
                bindReactionsTap(vh.timestamp, message, ftReactions.isNotEmpty())
                // Подпись к файлу (уходит вместе с файлом и показывается под ним).
                bindCaption(vh, fileTransfer)
                vh.timestamp.visibility = if (position == messages.lastIndex || position == readIndex || ftReactions.isNotEmpty()) {
                    View.VISIBLE
                } else {
                    val next = messages[position + 1]
                    if (next.sender == message.sender && next.timestamp - message.timestamp < 60_000) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }

                vh.container.gravity = if (fileTransfer.outgoing) Gravity.END else Gravity.START
                view
            }
        }

    /** Нажата кнопка карточки: (сообщение, true - принять / false - отклонить). */
    var onCard: ((Message, Boolean) -> Unit)? = null

    /** Что нужно кнопкам внутри сообщений от экрана чата (отправка быстрого ответа, собеседник). */
    var buttonHost: com.stoyko.smessenger.chat.MsgButtons.Host? = null

    /** Опросы: отправка голоса в этот чат и мой ключ. */
    var pollHost: com.stoyko.smessenger.chat.Polls.Host? = null

    /** Перевод монет: (сообщение, принять?, монета на карточке - откуда летят монеты). */
    var onCoinCard: ((Message, Boolean, View) -> Unit)? = null

    /** Карточка контакта или приглашения в группу вместо обычного пузыря. */
    private fun applyCard(vh: MessageViewHolder, m: Message) {
        val box = vh.cardBox ?: return
        val text = m.message
        val gift = com.stoyko.smessenger.chat.Gifts.isGift(text)
        val coins = com.stoyko.smessenger.chat.CoinGift.isGift(text)
        val game = com.stoyko.smessenger.games.GameInvite.isCard(text)
        if (m.type != MessageType.Normal || !(gift || coins || game || com.stoyko.smessenger.chat.Cards.isCard(text))) {
            if (box.visibility != View.GONE) {
                box.removeAllViews()
                box.visibility = View.GONE
            }
            return
        }
        vh.message.visibility = View.GONE
        vh.emojiAnim?.visibility = View.GONE
        vh.linkPreview?.visibility = View.GONE
        box.visibility = View.VISIBLE
        if (game) {
            val GameInvite = com.stoyko.smessenger.games.GameInvite
            val sig = "game:" + m.sender.name + ":" + GameInvite.signature(inflater.context, text)
            if (box.childCount == 1 && box.getChildAt(0).tag == sig) return
            box.removeAllViews()
            val card = GameInvite.chatCard(inflater.context, text, m.sender == Sender.Received) { notifyDataSetChanged() }
            card.tag = sig
            box.addView(card)
        } else if (coins) {
            val CoinGift = com.stoyko.smessenger.chat.CoinGift
            val sig = "coins:" + m.sender.name + ":" + text + ":" + (CoinGift.parse(text)?.let { CoinGift.state(inflater.context, it.uid) } ?: "")
            if (box.childCount == 1 && box.getChildAt(0).tag == sig) return
            box.removeAllViews()
            val card = CoinGift.chatCard(inflater.context, text, m.sender == Sender.Received) { accept, from ->
                onCoinCard?.invoke(m, accept, from)
                notifyDataSetChanged()
            }
            card.tag = sig
            box.addView(card)
        } else if (gift) {
            // Подарок: анимация без конца, подпись и «Принять» / «Отклонить».
            // Та же карточка в том же состоянии не пересобирается при каждой прокрутке
            // (раньше подарок заново создавался и «мигал», а список дёргался).
            val Gifts = com.stoyko.smessenger.chat.Gifts
            val sig = "gift:" + m.sender.name + ":" + text + ":" + (Gifts.parse(text)?.let { Gifts.state(inflater.context, it.uid) } ?: "")
            if (box.childCount == 1 && box.getChildAt(0).tag == sig) return
            box.removeAllViews()
            val card = Gifts.chatCard(inflater.context, text, m.sender == Sender.Received) { accept ->
                onCard?.invoke(m, accept)
                notifyDataSetChanged()
            }
            card.tag = sig
            box.addView(card)
        } else {
            box.removeAllViews()
            box.addView(cardView(m))
        }
    }

    private fun cardView(m: Message): View {
        val ctx = inflater.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val Cards = com.stoyko.smessenger.chat.Cards
        val received = m.sender == Sender.Received
        val contact = Cards.parseContact(m.message)
        val invite = if (contact == null) Cards.invite(ctx, m.message) else null
        // Приглашение с описанием: группа или канал, описание, аватарка.
        val meta = if (contact == null && invite == null) Cards.parseMeta(m.message) else null
        val isInv = invite != null || meta != null
        val key = Cards.keyFor(m.message, m.timestamp)
        val state = Cards.state(ctx, key)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                when {
                    meta?.channel == true -> intArrayOf(0xFFF97316.toInt(), 0xFFDB2777.toInt())
                    isInv -> intArrayOf(0xFF0EA5E9.toInt(), 0xFF5B4BDB.toInt())
                    else -> intArrayOf(0xFF7B6CF6.toInt(), 0xFF4B3CCB.toInt())
                },
            ).apply { cornerRadius = 18 * d }
            elevation = 3 * d
            layoutParams = FrameLayout.LayoutParams(dp(264), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(
            TextView(ctx).apply {
                text = when {
                    meta?.channel == true -> ctx.getString(R.string.card_invite_channel_label)
                    isInv -> ctx.getString(R.string.card_invite_label)
                    else -> ctx.getString(R.string.card_contact_label)
                }
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
                paint.isFakeBoldText = true
            },
        )
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        val title = when {
            contact != null -> contact.name.ifBlank { ctx.getString(R.string.contact_default_name) }
            invite != null -> invite.name.ifBlank { ctx.getString(R.string.group_default_name) }
            meta != null -> meta.name.ifBlank { ctx.getString(R.string.group_default_name) }
            else -> ctx.getString(R.string.card_expired)
        }
        val avatarBytes = meta?.let { Cards.inviteAvatar(ctx, it.chatId) }
        val avatarBmp = avatarBytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (avatarBmp != null) {
            row.addView(
                android.widget.ImageView(ctx).apply {
                    setImageDrawable(
                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(ctx.resources, avatarBmp).apply { isCircular = true },
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(dp(2), 0x66FFFFFF)
                    }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                },
                android.widget.LinearLayout.LayoutParams(dp(52), dp(52)),
            )
        } else row.addView(
            TextView(ctx).apply {
                text = if (meta?.channel == true) "📢" else if (isInv) "👥" else title.take(1).uppercase()
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x33FFFFFF)
                    setStroke(dp(2), 0x66FFFFFF)
                }
            },
            android.widget.LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        val texts = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(
            TextView(ctx).apply {
                text = title
                textSize = 17f
                maxLines = 2
                setTextColor(android.graphics.Color.WHITE)
                paint.isFakeBoldText = true
            },
        )
        texts.addView(
            TextView(ctx).apply {
                text = when {
                    contact != null -> "ID " + contact.id.take(8) + "…" + contact.id.takeLast(6)
                    invite != null -> ctx.getString(R.string.card_invite_sub)
                    meta != null -> ctx.getString(if (meta.channel) R.string.card_invite_channel_sub else R.string.card_invite_sub)
                    else -> ""
                }
                textSize = 12f
                typeface = if (contact != null) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT
                setTextColor(0xDDFFFFFF.toInt())
            },
        )
        row.addView(texts, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        if (!meta?.desc.isNullOrBlank()) {
            root.addView(
                TextView(ctx).apply {
                    text = meta!!.desc
                    textSize = 13f
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xEEFFFFFF.toInt())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(0x22FFFFFF)
                    }
                },
                android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) },
            )
        }
        val canAct = received && (contact != null || isInv)
        if (canAct && state == null) {
            val buttons = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            fun button(label: Int, primary: Boolean, accept: Boolean) = TextView(ctx).apply {
                setText(label)
                textSize = 14f
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                setTextColor(if (primary) 0xFF4B3CCB.toInt() else android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(if (primary) android.graphics.Color.WHITE else 0x33FFFFFF)
                }
                setOnClickListener {
                    Cards.setState(ctx, key, if (accept) "ok" else "no")
                    onCard?.invoke(m, accept)
                    notifyDataSetChanged()
                }
                com.stoyko.smessenger.ui.Kinetics.squish(this, 0.92f)
            }
            buttons.addView(
                button(R.string.card_decline, primary = false, accept = false),
                android.widget.LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) },
            )
            buttons.addView(
                button(
                    when {
                        meta?.channel == true -> R.string.card_subscribe
                        isInv -> R.string.card_join
                        else -> R.string.card_add
                    },
                    primary = true,
                    accept = true,
                ),
                android.widget.LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) },
            )
            root.addView(buttons)
            com.stoyko.smessenger.ui.Buttons3d.applyTree(buttons)
        } else {
            root.addView(
                TextView(ctx).apply {
                    text = when {
                        !received -> ctx.getString(R.string.card_shared)
                        state == "ok" -> ctx.getString(if (isInv) R.string.card_joined else R.string.card_added)
                        state == "no" -> ctx.getString(R.string.card_declined)
                        else -> ""
                    }
                    textSize = 13f
                    setTextColor(0xE6FFFFFF.toInt())
                },
            )
        }
        return root
    }

    private fun bindCaption(vh: FileTransferViewHolder, ft: FileTransfer) {
        val cap = com.stoyko.smessenger.chat.Captions.get(inflater.context, ft.publicKey, ft.outgoing, ft.fileName, ft.fileSize)
        // Расшифровка голосового - под ним, как подпись.
        val voiceText = if (VoiceMeta.isVoice(ft.fileName)) com.stoyko.smessenger.chat.VoiceTranscriber.get(inflater.context, ft.fileName, ft.fileSize) else null
        val c = listOfNotNull(cap?.takeIf { it.isNotBlank() }, voiceText?.let { "📝 $it" }).joinToString("\n").ifBlank { null }
        if (c.isNullOrBlank()) {
            vh.caption.visibility = View.GONE
        } else {
            vh.caption.visibility = View.VISIBLE
            vh.caption.textSize = com.stoyko.smessenger.ui.UiPrefs.messageTextSize
            vh.caption.text = com.stoyko.smessenger.chat.Markup.render(c, 0xCCFFFFFF.toInt(), highlight)
            com.stoyko.smessenger.chat.RichSpans.attach(vh.caption)
            stripFileLinks(vh.caption)
        }
    }

    private fun bindFileTransfer(vh: FileTransferViewHolder, ft: FileTransfer, parent: ViewGroup) {
        val isVoice = VoiceMeta.isVoice(ft.fileName)
        val isVideoNote = VideoNoteMeta.isVideoNote(ft.fileName)
        val sticker = isStickerName(ft.fileName)
        val ready = ft.isComplete() || (ft.outgoing && !ft.isRejected())
        val key = ft.voiceKey()

        // Кнопки строки (принять/отклонить/отмена).
        tapOnDown(vh.accept) { onFtAction?.invoke(FtAction.Accept, ft) }
        tapOnDown(vh.reject) { onFtAction?.invoke(FtAction.Reject, ft) }
        tapOnDown(vh.cancel) { onFtAction?.invoke(FtAction.Cancel, ft) }

        // --- вид пузыря ---
        vh.voiceLayout.visibility = View.GONE
        vh.videoNoteBox.visibility = View.GONE
        vh.fileInfo.visibility = View.VISIBLE
        vh.completedLayout.visibility = View.GONE
        vh.imageRing.visibility = View.GONE
        vh.imageInfo.visibility = View.GONE
        vh.acceptLayout.visibility = View.GONE
        vh.cancelLayout.visibility = View.GONE
        vh.progress.visibility = View.GONE
        vh.state.visibility = View.GONE
        vh.voiceRing.visibility = View.GONE
        vh.videoNoteRing.visibility = View.GONE
        vh.fileRing.visibility = View.GONE
        vh.videoPlayIcon.visibility = View.GONE
        vh.videoDuration.visibility = View.GONE
        com.stoyko.smessenger.chat.AnimatedEmoji.stop(vh.stickerAnim)
        (vh.stickerAnim.parent as? View)?.let {
            it.animate().cancel()
            com.stoyko.smessenger.chat.EmojiMotion.cancel(it)
        }
        vh.imagePreview.alpha = 1f
        com.stoyko.smessenger.chat.KbGif.unbind(vh.imagePreview)
        vh.imagePreview.tag = null
        vh.imagePreview.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        vh.imagePreview.minimumWidth = 0
        vh.imagePreview.minimumHeight = 0
        FileTypes.applyBadge(vh.fileIcon, ft.fileName)

        if (isVoice || isVideoNote) {
            voiceHolders.add(vh)
            vh.voiceKey = key
            vh.voiceReady = ready
            vh.voiceDurationMs = VoiceMeta.durationMs(ft.fileName) ?: 0L
        } else {
            voiceHolders.remove(vh)
            vh.voiceKey = null
        }

        when {
            isVideoNote -> {
                vh.fileInfo.visibility = View.GONE
                vh.videoNoteBox.visibility = View.VISIBLE
                vh.bubble.background = null
                vh.bubble.setPadding(0, 0, 0, 0)
                vh.videoNote.bind(key, if (ready) playableUri(ft) else null, vh.voiceDurationMs, ready)
                tapOnDown(vh.videoNote) { if (ready) onFtAction?.invoke(FtAction.PlayVideoNote, ft) }
                tapOnDown(vh.videoNoteRing) { onFtAction?.invoke(FtAction.Cancel, ft) }
            }
            isVoice -> {
                vh.voiceLayout.visibility = View.VISIBLE
                vh.fileInfo.visibility = View.GONE
                vh.bubble.setBackgroundResource(
                    if (ft.outgoing) R.drawable.sent_message_bubble else R.drawable.received_message_bubble,
                )
                com.stoyko.smessenger.ui.SeasonTint.bubble(vh.bubble, ft.outgoing)
                val pad = (10 * density).toInt()
                vh.bubble.setPadding(pad, pad, (pad * 1.4f).toInt(), pad)
                vh.voiceWave.levels = VoiceMeta.waveform(ft.fileName) ?: VoiceMeta.fallbackWave(ft.fileName)
                vh.voiceWave.onSeek = { f -> VoicePlayer.seek(key, f) }
                tapOnDown(vh.voicePlay) { if (ready) onFtAction?.invoke(FtAction.PlayVoice, ft) }
                tapOnDown(vh.voiceRing) { onFtAction?.invoke(FtAction.Cancel, ft) }
                // «📝» у длительности - расшифровать голосовое в текст.
                vh.voiceTime.setOnClickListener { if (ready) onFtAction?.invoke(FtAction.Transcribe, ft) }
                // Полноценная кнопка «Расшифровать» вместо значка 📝 у времени.
                vh.voiceTranscribe.visibility = if (ready) View.VISIBLE else View.GONE
                vh.voiceTranscribe.setOnClickListener {
                    it.animate().scaleX(0.82f).scaleY(0.82f).setDuration(80).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
                    onFtAction?.invoke(FtAction.Transcribe, ft)
                }
                vh.voiceTranscribe.setOnLongClickListener { com.stoyko.smessenger.chat.VoiceTextUi.forwardLongPress(it) }
                vh.voiceTime.setOnLongClickListener { com.stoyko.smessenger.chat.VoiceTextUi.forwardLongPress(it) }
                updateVoice(vh)
            }
            sticker && ready -> {
                // Стикер - картинка без рамки и подписей.
                vh.bubble.background = null
                vh.bubble.setPadding(0, 0, 0, 0)
                vh.fileInfo.visibility = View.GONE
                vh.completedLayout.visibility = View.VISIBLE
                val px = (150 * density).toInt()
                // GIF с клавиатуры - крупнее и проигрывается по кругу, как живой эмодзи.
                if (!com.stoyko.smessenger.chat.KbGif.bind(vh.imagePreview, ft.destination, ft.fileName, (200 * density).toInt())) {
                    Picasso.get().load(ft.destination).resize(px, px).centerInside().into(vh.imagePreview)
                }
                // Стикер из встроенного набора - анимированный (статичная картинка видна, пока грузится).
                val code = com.stoyko.smessenger.chat.AnimatedEmoji.stickerCode(ft.fileName)
                // Свежий стикер (только что пришёл или отправлен) появляется со своей «характерной»
                // анимацией: сердце бьётся, смех трясётся, огонь трепещет… Старые - без движения.
                val box = vh.stickerAnim.parent as? View
                val sentAt = STICKER_TIME.find(ft.fileName)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (box != null && look.animatedEmoji && stickerEntrance.add(ft.id) &&
                    abs(System.currentTimeMillis() - sentAt) < 60_000
                ) {
                    com.stoyko.smessenger.chat.EmojiMotion.entrance(box, code?.let { com.stoyko.smessenger.chat.EmojiMotion.fromCode(it) }.orEmpty())
                }
                if (code != null && look.animatedEmoji) {
                    com.stoyko.smessenger.chat.AnimatedEmoji.play(vh.stickerAnim, code, loop = 3) {
                        // Размер задаёт статичная картинка - её не убираем, а делаем прозрачной.
                        vh.imagePreview.alpha = 0f
                    }
                }
            }
            ft.isImage() && ready -> {
                vh.bubble.setBackgroundResource(R.drawable.bg_media_bubble)
                vh.fileInfo.visibility = View.GONE
                vh.completedLayout.visibility = View.VISIBLE
                // Режим для слабых устройств: превью на 3/4 размера и в экономном формате
                // (вдвое меньше памяти на картинку) - прокрутка не дёргается, кэш вмещает больше.
                val lite = com.stoyko.smessenger.ui.LiteMode.on
                // На планшете фото в ленте не растягивается во весь экран (не шире 460 dp).
                val targetWidth = com.stoyko.smessenger.ui.Adaptive.mediaWidthPx(inflater.context, IMAGE_TO_SCREEN_RATIO.toFloat()) * (if (lite) 0.75f else 1f)
                if (!com.stoyko.smessenger.chat.MediaSpoiler.isHidden(ft.fileName, ft.destination) &&
                    com.stoyko.smessenger.chat.KbGif.bind(vh.imagePreview, ft.destination, ft.fileName, targetWidth.roundToInt())
                ) {
                    tapOnDown(vh.imageRing) { onFtAction?.invoke(FtAction.Cancel, ft) }
                } else Picasso.get()
                    .load(ft.destination)
                    .resize(targetWidth.roundToInt(), 0)
                    .onlyScaleDown()
                    .apply { if (lite && !ft.fileName.endsWith(".png", true) && !ft.fileName.endsWith(".webp", true)) config(android.graphics.Bitmap.Config.RGB_565) }
                    .into(vh.imagePreview)
                tapOnDown(vh.imageRing) { onFtAction?.invoke(FtAction.Cancel, ft) }
            }
            FileTypes.isVideo(ft.fileName) && ready -> {
                // Видео - кадром с кнопкой ▶, открывается во встроенном плеере.
                vh.bubble.setBackgroundResource(R.drawable.bg_media_bubble)
                vh.fileInfo.visibility = View.GONE
                vh.completedLayout.visibility = View.VISIBLE
                vh.videoPlayIcon.visibility = View.VISIBLE
                Picasso.get().cancelRequest(vh.imagePreview)
                val w = com.stoyko.smessenger.ui.Adaptive.mediaWidthPx(inflater.context, IMAGE_TO_SCREEN_RATIO.toFloat())
                FileTypes.loadThumb(inflater.context, ft.destination, vh.imagePreview, w)
                val dur = durations[ft.destination]
                if (dur != null) {
                    vh.videoDuration.text = dur
                    vh.videoDuration.visibility = if (dur.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    val dest = ft.destination
                    val app = inflater.context.applicationContext
                    Thread {
                        val t = FileTypes.durationText(app, dest)
                        vh.videoDuration.post {
                            durations[dest] = t
                            if (vh.ft?.destination == dest && t.isNotEmpty()) {
                                vh.videoDuration.text = t
                                vh.videoDuration.visibility = View.VISIBLE
                            }
                        }
                    }.start()
                }
                tapOnDown(vh.imageRing) { onFtAction?.invoke(FtAction.Cancel, ft) }
            }
            else -> {
                vh.bubble.setBackgroundResource(
                    if (ft.outgoing) R.drawable.sent_message_bubble else R.drawable.received_message_bubble,
                )
                com.stoyko.smessenger.ui.SeasonTint.bubble(vh.bubble, ft.outgoing)
                val pad = (10 * density).toInt()
                vh.bubble.setPadding(pad, pad, (pad * 1.4f).toInt(), pad)
                vh.fileName.text = com.stoyko.smessenger.chat.MediaSpoiler.clean(ft.fileName)
                tapOnDown(vh.fileRing) { onFtAction?.invoke(FtAction.Cancel, ft) }
            }
        }

        // Фото/видео под спойлером - размытая пелена с искрами до нажатия.
        com.stoyko.smessenger.ui.Safe.run("spoiler") {
            val media = vh.completedLayout.visibility == View.VISIBLE
            com.stoyko.smessenger.chat.MediaSpoiler.bind(vh.imagePreview, if (media) ft.fileName else null, ft.destination)
        }

        // Входящий файл ждёт решения (если автоприём выключен).
        if (!ft.outgoing && !ft.isStarted() && !ft.isRejected()) {
            vh.acceptLayout.visibility = View.VISIBLE
        }
        ftHolders.add(vh)
        updateTransfer(vh, ft)
    }

    /** Состояние передачи: круг с прогрессом и крестиком, «1,2 / 5,4 МБ · 350 КБ/с». */
    private fun updateTransfer(vh: FileTransferViewHolder, ft: FileTransfer) {
        val live = TransferMonitor.get(TransferMonitor.key(ft.id))
        val complete = ft.isComplete()
        val rejected = ft.isRejected()
        val waiting = ft.outgoing && ft.progress == FT_NOT_STARTED && live == null
        val active = !complete && !rejected && (live != null || (ft.outgoing && !waiting) || (!ft.outgoing && ft.isStarted()))
        val fraction = when {
            live != null -> live.fraction
            ft.fileSize > 0 && ft.progress > 0 -> (ft.progress.toFloat() / ft.fileSize).coerceIn(0f, 1f)
            else -> -1f
        }
        val info = when {
            rejected -> resources.getString(R.string.cancelled)
            complete -> Formatter.formatShortFileSize(inflater.context, ft.fileSize)
            live != null -> TransferMonitor.describe(live, ru)
            waiting -> resources.getString(R.string.ft_waiting_contact) + "  ·  " + resources.getString(R.string.ft_retry_short)
            else -> TransferMonitor.size(ft.progress.coerceAtLeast(0), ru) + " / " + TransferMonitor.size(ft.fileSize, ru) +
                if (!complete && !rejected && live == null && (ft.outgoing || ft.isStarted())) "  ·  " + resources.getString(R.string.ft_retry_short) else ""
        }
        // Нажатие на подпись «… · ⟳ Повторить» - загрузить заново с места обрыва.
        val retry = View.OnClickListener { onFtAction?.invoke(FtAction.Retry, ft) }
        val canRetry = waiting || (!complete && !rejected && live == null && (ft.outgoing || ft.isStarted()))
        vh.fileSize.setOnClickListener(if (canRetry) retry else null)
        vh.imageInfo.setOnClickListener(if (canRetry) retry else null)
        if (!canRetry) {
            vh.fileSize.isClickable = false
            vh.imageInfo.isClickable = false
        }
        val isVoice = vh.voiceLayout.visibility == View.VISIBLE
        val isNote = vh.videoNoteBox.visibility == View.VISIBLE
        val isImage = vh.completedLayout.visibility == View.VISIBLE && vh.bubble.background != null

        fun ring(r: ProgressRingView, show: Boolean) {
            r.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                r.icon = ProgressRingView.Icon.Cancel
                r.setProgress(if (waiting) -1f else fraction)
            }
        }
        val showRing = (active || waiting) && !rejected
        when {
            isNote -> ring(vh.videoNoteRing, showRing)
            isVoice -> {
                ring(vh.voiceRing, showRing && !ft.outgoing)
                vh.voicePlay.visibility = if (showRing && !ft.outgoing) View.INVISIBLE else View.VISIBLE
                if (showRing && ft.outgoing) vh.voiceTime.text = info
            }
            isImage -> {
                ring(vh.imageRing, showRing)
                if (vh.videoPlayIcon.visibility != View.GONE) {
                    vh.videoPlayIcon.visibility = if (showRing) View.INVISIBLE else View.VISIBLE
                }
                vh.imageInfo.visibility = if (showRing) View.VISIBLE else View.GONE
                vh.imageInfo.text = info
            }
            vh.fileInfo.visibility == View.VISIBLE -> {
                ring(vh.fileRing, showRing)
                vh.fileIcon.visibility = if (showRing) View.GONE else View.VISIBLE
                vh.fileSize.text = info
            }
        }
        if (rejected && (isVoice || isNote)) {
            vh.state.visibility = View.VISIBLE
            vh.state.text = info.lowercase(Locale.getDefault())
        }
    }

    private fun playableUri(ft: FileTransfer): android.net.Uri {
        val u = ft.destination.toUri()
        return if (u.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(
                inflater.context,
                "${com.stoyko.smessenger.BuildConfig.APPLICATION_ID}.fileprovider",
                java.io.File(u.path!!),
            )
        } else {
            u
        }
    }
}

/** Реакции и время в одной строке под сообщением. */
private object TextJoin {
    fun join(reactions: CharSequence, time: CharSequence): CharSequence = SpannableStringBuilder(reactions).append("   ").append(time)
}

/** Кружок с галочкой слева от сообщения в режиме выбора + подсветка выбранного. */
private class SelectionMark(private val checked: Boolean, private val density: Float) : android.graphics.drawable.Drawable() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00C2A8.toInt() }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xCCFFFFFF.toInt()
    }
    private val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val tick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    }
    private val tint = android.graphics.Paint().apply { color = 0x2E5B4BDB }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (checked) canvas.drawRect(b, tint)
        val r = 11 * density
        val cx = b.left + 18 * density
        val cy = b.exactCenterY()
        canvas.drawCircle(cx, cy, r + 1.5f * density, shadow)
        if (checked) {
            canvas.drawCircle(cx, cy, r, fill)
            val path = android.graphics.Path().apply {
                moveTo(cx - r * 0.45f, cy + r * 0.02f)
                lineTo(cx - r * 0.1f, cy + r * 0.38f)
                lineTo(cx + r * 0.5f, cy - r * 0.32f)
            }
            canvas.drawPath(path, tick)
        } else {
            canvas.drawCircle(cx, cy, r, ring)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
