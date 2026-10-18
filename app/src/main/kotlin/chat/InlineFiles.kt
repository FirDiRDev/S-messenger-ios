// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.core.vo.isComplete
import com.stoyko.smessenger.core.vo.isStarted
import com.stoyko.smessenger.media.MusicPlayer

/**
 * Файлы и музыка прямо в тексте: документы, аудио и архивы встраиваются в любое место абзаца,
 * как ссылки, а не отдельным блоком внизу. Несколько треков в одном сообщении собираются в
 * плейлист (кнопка «▶ Плейлист» и переход ⏭/⏮ по трекам этого сообщения).
 *
 * Как устроено: сами файлы уходят обычной передачей ядра (со сквозным шифрованием), а в тексте
 * на их месте - метка «⁣📎<размер>|<имя>⁣». В чате метка рисуется кнопкой-ссылкой с иконкой
 * и состоянием загрузки, а отдельные строки этих файлов в ленте скрыты (чтобы не дублировать).
 * Старые версии увидят «📎…имя» в тексте и файлы отдельными строками.
 */
object InlineFiles {
    private const val INV = "⁣"
    private val RE = Regex("$INV📎(\\d{1,12})\\|([^$INV\\n]{1,200})$INV")

    data class Ref(val name: String, val size: Long)

    /** Как ссылку на файл видит экран чата. */
    class Target(val name: String, val ready: Boolean, val progress: Int, val audio: Boolean, val uri: Uri?, val open: () -> Unit)

    /** Имя файла - как его сохранит получатель (без запрещённых символов). */
    fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n$INV]"), "_").trim().take(200)

    fun token(name: String, size: Long) = "$INV📎$size|${clean(name)}$INV"

    fun has(text: String) = text.contains("$INV📎")

    fun refs(text: String): List<Ref> = RE.findAll(text).mapNotNull { m ->
        m.groupValues[1].toLongOrNull()?.let { Ref(m.groupValues[2], it) }
    }.toList()

    fun isAudio(name: String) = MusicPlayer.isMusic(name)

    private fun icon(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            isAudio(name) -> "🎵"
            ext in setOf("zip", "rar", "7z", "tar", "gz", "apk", "xz") -> "🗜"
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") -> "🖼"
            ext in setOf("mp4", "mkv", "webm", "mov", "3gp") -> "🎬"
            ext in setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "md") -> "📄"
            else -> "📎"
        }
    }

    // ------------------------------------------------------------------ показ

    /**
     * Заменить метки файлов в [text] на кнопки-ссылки. [resolve] - найти файл в этом чате
     * (null - ещё не пришёл). Несколько треков - в конце кнопка «▶ Плейлист (N)».
     */
    fun decorate(ctx: Context, text: CharSequence, resolve: (Ref) -> Target?): CharSequence {
        if (!text.contains("$INV📎")) return text
        val sb = text as? SpannableStringBuilder ?: SpannableStringBuilder(text)
        val matches = RE.findAll(sb.toString()).toList()
        if (matches.isEmpty()) return sb
        val targets = matches.map { m -> m.groupValues[1].toLongOrNull()?.let { resolve(Ref(m.groupValues[2], it)) } }
        val tracks = targets.filterNotNull().filter { it.audio && it.ready && it.uri != null }
        for (k in matches.indices.reversed()) {
            val m = matches[k]
            val name = m.groupValues[2]
            val tgt = targets[k]
            val state = when {
                tgt == null -> " ⏳"
                !tgt.ready && tgt.progress in 0..99 -> " ${tgt.progress}%"
                !tgt.ready -> " ⬇"
                else -> ""
            }
            val chip = " " + icon(name) + " " + shortName(name) + state + " "
            sb.replace(m.range.first, m.range.last + 1, chip)
            val s = m.range.first
            val e = s + chip.length
            sb.setSpan(BackgroundColorSpan(0x40FFFFFF), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        widget.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        when {
                            tgt == null -> Toast.makeText(ctx, t(ctx, "Файл ещё не пришёл", "The file hasn't arrived yet"), Toast.LENGTH_SHORT).show()
                            tgt.audio && tgt.ready && tgt.uri != null && tracks.size > 1 -> playList(ctx, tracks, tracks.indexOf(tgt).coerceAtLeast(0))
                            else -> tgt.open()
                        }
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = 0xFFFFE08A.toInt()
                    }
                },
                s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (tracks.size > 1) {
            val label = "\n ▶ " + t(ctx, "Плейлист", "Playlist") + " (${tracks.size}) "
            val s = sb.length + 1
            sb.append(label)
            val e = sb.length
            sb.setSpan(BackgroundColorSpan(0x5522C55E), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xFFFFFFFF.toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = playList(ctx, tracks, 0)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                },
                s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    private fun shortName(n: String): String = if (n.length <= 28) n else n.take(18) + "…" + n.takeLast(8)

    private fun playList(ctx: Context, tracks: List<Target>, start: Int) {
        val list = tracks.mapNotNull { tr -> tr.uri?.let { it to tr.name } }
        if (list.isEmpty()) return
        MusicPlayer.playList(ctx, list, start)
        Toast.makeText(ctx, t(ctx, "▶ Плейлист: ${list.size} трек.", "▶ Playlist: ${list.size} tracks"), Toast.LENGTH_SHORT).show()
    }

    /** Ссылка на файл личного чата по передаче ядра. */
    fun target(ft: FileTransfer, open: () -> Unit): Target {
        val ready = ft.outgoing || ft.isComplete()
        val pct = if (ft.fileSize > 0) (ft.progress * 100 / ft.fileSize).toInt().coerceIn(0, 100) else -1
        val uri = runCatching { Uri.parse(ft.destination) }.getOrNull()?.takeIf { ft.destination.isNotBlank() }
        return Target(ft.fileName, ready, if (ft.isStarted()) pct else -1, isAudio(ft.fileName), uri, open)
    }

    /** Найти передачу по ссылке: имя + размер, затем только размер (если однозначно), затем имя. */
    fun find(fts: List<FileTransfer>, r: Ref, outgoing: Boolean): FileTransfer? {
        val own = fts.filter { it.outgoing == outgoing }
        return own.lastOrNull { it.fileName == r.name && it.fileSize == r.size }
            ?: own.filter { it.fileSize == r.size }.singleOrNull()
            ?: own.lastOrNull { clean(it.fileName) == r.name }
    }

    /** Личный чат: скрыть отдельные строки файлов, встроенных в текст сообщений того же отправителя. */
    fun hideEmbedded(list: List<Message>, fts: List<FileTransfer>): List<Message> {
        val texts = list.filter { it.type == MessageType.Normal && has(it.message) }
        if (texts.isEmpty()) return list
        val embedded = HashSet<Int>()
        for (m in texts) {
            for (r in refs(m.message)) {
                find(fts, r, m.sender == Sender.Sent)?.let { embedded += it.id }
            }
        }
        if (embedded.isEmpty()) return list
        return list.filterNot { it.type == MessageType.FileTransfer && it.correlationId in embedded }
    }

    // ------------------------------------------------------------------ группы и каналы

    /** Файл группы по ссылке: от того же отправителя, имя + размер, затем только размер. */
    fun findGroup(all: List<com.stoyko.smessenger.core.vo.GroupMessage>, r: Ref, senderKey: String): com.stoyko.smessenger.core.vo.GroupMessage? {
        val own = all.filter { it.isFile() && it.senderKey == senderKey }
        return own.lastOrNull { clean(it.fileName) == r.name && it.fileSize == r.size }
            ?: own.filter { it.fileSize == r.size }.singleOrNull()
    }

    fun targetGroup(m: com.stoyko.smessenger.core.vo.GroupMessage, open: () -> Unit): Target {
        val ready = m.outgoing || m.isComplete()
        val pct = if (m.fileSize > 0) (m.progress * 100 / m.fileSize).toInt().coerceIn(0, 100) else -1
        val uri = m.filePath.takeIf { it.isNotBlank() }?.let { Uri.fromFile(java.io.File(it)) }
        return Target(m.fileName, ready, pct, isAudio(m.fileName), uri, open)
    }

    /** Группа: скрыть строки файлов, встроенных в текст сообщений того же отправителя. */
    fun hideEmbeddedGroup(list: List<com.stoyko.smessenger.core.vo.GroupMessage>): List<com.stoyko.smessenger.core.vo.GroupMessage> {
        val texts = list.filter { !it.isFile() && has(it.message) }
        if (texts.isEmpty()) return list
        val hidden = HashSet<String>()
        for (m in texts) for (r in refs(m.message)) findGroup(list, r, m.senderKey)?.let { hidden += it.msgUid }
        return if (hidden.isEmpty()) list else list.filterNot { it.isFile() && it.msgUid in hidden }
    }

    // ------------------------------------------------------------------ составление

    /** Имя и размер выбранного файла (как его увидит получатель). */
    fun describe(ctx: Context, uri: Uri): Ref? = runCatching {
        if (uri.scheme == "file") {
            val f = java.io.File(uri.path ?: return null)
            return Ref(clean(f.name), f.length())
        }
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val n = c.getString(0) ?: return null
            val s = if (c.isNull(1)) -1L else c.getLong(1)
            if (s < 0) null else Ref(clean(n), s)
        }
    }.getOrNull()

    /**
     * Редактор «Текст с файлами»: курсор - туда, где должен быть файл, «📎 Вставить файл» -
     * метка встаёт в это место. [pick] - открыть выбор файлов (результат - в колбэк).
     * [onDone] - готовый текст и файлы (в порядке появления в тексте).
     */
    fun compose(ctx: Context, initial: String, pick: (((List<Uri>) -> Unit) -> Unit), onDone: (String, List<Uri>) -> Unit) {
        val attached = LinkedHashMap<String, Uri>()
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val edit = EditText(ctx).apply {
            setText(initial)
            hint = t(ctx, "Текст. Поставьте курсор и вставьте файл или трек", "Text. Place the cursor and insert a file or track")
            minLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(edit)
        box.addView(
            com.stoyko.smessenger.ui.Btn.tonal(ctx, t(ctx, "📎 Вставить файл или музыку", "📎 Insert a file or music")) {
                pick { uris ->
                    for (u in uris) {
                        val r = describe(ctx, u) ?: continue
                        val tok = token(r.name, r.size)
                        attached[tok] = u
                        val at = edit.selectionStart.coerceIn(0, edit.text.length)
                        edit.text.insert(at, " $tok ")
                    }
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt()).apply { topMargin = (6 * d).toInt() },
        )
        box.addView(
            android.widget.TextView(ctx).apply {
                text = t(
                    ctx,
                    "Файлы уйдут со сквозным шифрованием, а в тексте станут кнопками-ссылками. Несколько треков - плейлист.",
                    "Files are sent end-to-end encrypted and become link buttons in the text. Several tracks make a playlist.",
                )
                textSize = 12f
                alpha = 0.7f
                setPadding(0, (8 * d).toInt(), 0, 0)
            },
        )
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(t(ctx, "📎 Текст с файлами", "📎 Text with files"))
            .setView(box)
            .setPositiveButton(t(ctx, "Отправить", "Send"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = edit.text.toString().trim()
            // Только те файлы, чьи метки остались в тексте, - по порядку появления.
            val used = RE.findAll(text).mapNotNull { attached[it.value] }.distinct().toList()
            if (text.isEmpty()) return@setOnClickListener
            if (used.isEmpty()) {
                Toast.makeText(ctx, t(ctx, "Вставьте хотя бы один файл", "Insert at least one file"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onDone(text, used)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun t(ctx: Context, ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)
}
