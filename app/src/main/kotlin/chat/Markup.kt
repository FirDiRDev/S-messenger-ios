// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Разметка сообщений для функций поверх обычного текста
 * (всё идёт внутри сообщений - значит, со сквозным шифрованием ядра):
 *  - ответ:     ⁣↩<цитата>⁣<текст>
 *  - реакция:   ⁣♥R:<ключ сообщения>:<эмодзи или ->          (в чате не показывается)
 *  - закреп:    ⁣📌P:<ключ>|<цитата>  или  ⁣📌P:-            (в чате не показывается)
 *  - прочитано: ⁣👁R:<ключ последнего прочитанного>          (в чате не показывается, в базу не пишется)
 *  - изменить:  ⁣✏E:<ключ>|<новый текст>, удалить у всех: ⁣🗑D:<ключ>   (личные чаты)
 *  - группы:    ⁣✏G:<uid>|<текст>, ⁣🗑G:<uid>, комментарий к посту: ⁣💬C:<uid поста>|<текст>
 *  - изменённое сообщение хранится с меткой ⁣✎ в начале (показывается «изменено»)
 * ⁣ - невидимый разделитель U+2063.
 */
object Markup {
    private const val INV = "⁣"
    private const val REPLY = "$INV↩"
    private const val REACTION = "$INV♥R:"
    private const val PIN = "$INV📌P:"
    private const val READ = "$INV👁R:"
    private const val EDIT = "$INV✏E:"
    private const val DELETE = "$INV🗑D:"
    private const val GEDIT = "$INV✏G:"
    private const val GDELETE = "$INV🗑G:"
    private const val COMMENT = "$INV💬C:"
    private const val EDITED = "$INV✎"
    private const val PEDIT = "$INV✏P:"

    /** Длинный новый текст: до стольких байт (уходит несколькими частями). */
    const val EDIT_LONG_MAX_BYTES = 40_000

    /** Максимальная длина нового текста при изменении (служебное сообщение не должно делиться на части). */
    const val EDIT_MAX_BYTES = 1000
    const val SNIPPET_MAX = 90

    fun isReaction(text: String) = text.startsWith(REACTION)
    fun isPin(text: String) = text.startsWith(PIN)

    /** Служебные сообщения, которые не показываются строками в чате. */
    fun isHidden(text: String) = Polls.isVote(text) || isReaction(text) || isPin(text) || isRead(text) || isEdit(text) || isDelete(text) || Captions.isCaption(text) || Gifts.isStatus(text) || CoinGift.isStatus(text) || text.startsWith(com.stoyko.smessenger.domain.feature.RESEND_PREFIX) || Cards.isInviteAvatar(text) || com.stoyko.smessenger.stories.StoryMeta.isCaptionMessage(text)

    // ---------- Изменение и удаление (личные чаты) ----------

    fun isEdit(text: String) = text.startsWith(EDIT) || text.startsWith(PEDIT)

    /** Часть изменения длинного сообщения. */
    class PartEdit(val key: String, val index: Int, val total: Int, val keys: List<String>, val chunk: String)

    /**
     * Изменение длинного сообщения - несколькими служебными сообщениями (одно не должно
     * превышать лимит ядра): «ключ|номер/всего|ключи частей исходного|кусок текста».
     * Ключи частей нужны получателю: длинное сообщение у него могло прийти несколькими строками.
     */
    fun editParts(key: String, original: String, newStored: String): List<String> {
        val chunkKeys = (listOf(key) + original.chunked(1372).map { key(it) }).distinct().joinToString(",")
        val pieces = ArrayList<String>()
        val sb = StringBuilder()
        var bytes = 0
        for (ch in newStored) {
            val b = ch.toString().toByteArray().size
            if (bytes + b > 800 && !(Character.isLowSurrogate(ch) && sb.isNotEmpty())) {
                pieces += sb.toString()
                sb.clear()
                bytes = 0
            }
            sb.append(ch)
            bytes += b
        }
        if (sb.isNotEmpty() || pieces.isEmpty()) pieces += sb.toString()
        return pieces.mapIndexed { i, p -> "$PEDIT$key|$i/${pieces.size}|$chunkKeys|$p" }
    }

    fun parsePartEdit(text: String): PartEdit? = runCatching {
        if (!text.startsWith(PEDIT)) return null
        val parts = text.removePrefix(PEDIT).split('|', limit = 4)
        val (i, n) = parts[1].split('/').map { it.toInt() }
        if (n !in 1..200 || i !in 0 until n) return null
        PartEdit(parts[0], i, n, parts[2].split(',').filter { it.isNotBlank() }.take(64), parts[3])
    }.getOrNull()

    private val partBuf = HashMap<String, Array<String?>>()

    /** Собрать части; вернёт (ключи, полный текст), когда пришли все. */
    @Synchronized
    fun assemble(pk: String, p: PartEdit): Pair<List<String>, String>? {
        val k = pk + ":" + p.key
        val arr = partBuf[k]?.takeIf { it.size == p.total } ?: arrayOfNulls<String>(p.total).also { partBuf[k] = it }
        arr[p.index] = p.chunk
        if (arr.any { it == null }) return null
        partBuf.remove(k)
        return p.keys to arr.joinToString("")
    }
    fun isDelete(text: String) = text.startsWith(DELETE)
    fun edit(key: String, newStored: String) = "$EDIT$key|$newStored"
    fun delete(key: String) = DELETE + key

    /** (ключ, новый текст для хранения) */
    fun parseEdit(text: String): Pair<String, String>? {
        if (!text.startsWith(EDIT)) return null
        val rest = text.removePrefix(EDIT)
        val i = rest.indexOf('|')
        return if (i <= 0) null else rest.substring(0, i) to rest.substring(i + 1)
    }

    fun parseDelete(text: String): String? = if (isDelete(text)) text.removePrefix(DELETE).takeIf { it.isNotEmpty() } else null

    fun isEdited(text: String) = text.startsWith(EDITED)

    /** Текст для хранения после изменения: метка «изменено» + прежняя цитата ответа + новый текст. */
    fun edited(original: String, newText: String): String {
        val quote = parseReply(original).first
        val body = if (quote != null) REPLY + quote + INV + newText else newText
        // Бизнес-пост канала: карточка товара/кнопка сохраняются при правке текста.
        val meta = com.stoyko.smessenger.groups.ChannelPost.meta(stripEdited(original))
        return EDITED + if (meta != null) com.stoyko.smessenger.groups.ChannelPost.wrap(meta, body) else body
    }

    /** Изменение целиком (с заголовком): живое сообщение с новыми параметрами. */
    fun editedRaw(newRaw: String): String = EDITED + stripEdited(newRaw)

    private fun stripEdited(text: String) = if (text.startsWith(EDITED)) text.substring(EDITED.length) else text

    // ---------- Группы и каналы: изменение, удаление, комментарии ----------

    fun isGroupEdit(text: String) = text.startsWith(GEDIT)
    fun isGroupDelete(text: String) = text.startsWith(GDELETE)
    fun isComment(text: String) = text.startsWith(COMMENT)

    /** Служебные сообщения группы (не показываются строками). */
    fun isGroupControl(text: String) = isGroupEdit(text) || isGroupDelete(text) || text.startsWith(GPEDIT)

    private const val GPEDIT = "$INV✏Q:"

    /** Изменение длинного сообщения в группе/канале - частями по ~800 байт. */
    fun groupEditParts(uid: String, newStored: String): List<String> {
        val pieces = ArrayList<String>()
        val sb = StringBuilder()
        var bytes = 0
        for (ch in newStored) {
            val b = ch.toString().toByteArray().size
            if (bytes + b > 800 && !(Character.isLowSurrogate(ch) && sb.isNotEmpty())) {
                pieces += sb.toString()
                sb.clear()
                bytes = 0
            }
            sb.append(ch)
            bytes += b
        }
        if (sb.isNotEmpty() || pieces.isEmpty()) pieces += sb.toString()
        return pieces.mapIndexed { i, p -> "$GPEDIT$uid|$i/${pieces.size}|$p" }
    }

    /** (uid, номер, всего, кусок) */
    fun parseGroupPartEdit(text: String): PartEdit? = runCatching {
        if (!text.startsWith(GPEDIT)) return null
        val parts = text.removePrefix(GPEDIT).split('|', limit = 3)
        val (i, n) = parts[1].split('/').map { it.toInt() }
        if (n !in 1..200 || i !in 0 until n) return null
        PartEdit(parts[0], i, n, emptyList(), parts[2])
    }.getOrNull()

    fun groupEdit(uid: String, newStored: String) = "$GEDIT$uid|$newStored"
    fun groupDelete(uid: String) = GDELETE + uid
    fun comment(postUid: String, text: String) = "$COMMENT$postUid|$text"

    fun parseGroupEdit(text: String): Pair<String, String>? {
        if (!isGroupEdit(text)) return null
        val rest = text.removePrefix(GEDIT)
        val i = rest.indexOf('|')
        return if (i <= 0) null else rest.substring(0, i) to rest.substring(i + 1)
    }

    fun parseGroupDelete(text: String): String? =
        if (isGroupDelete(text)) text.removePrefix(GDELETE).takeIf { it.isNotEmpty() } else null

    /** (uid поста, текст комментария) */
    fun parseComment(text: String): Pair<String, String>? {
        val t = stripEdited(text)
        if (!t.startsWith(COMMENT)) return null
        val rest = t.removePrefix(COMMENT)
        val i = rest.indexOf('|')
        return if (i <= 0) null else rest.substring(0, i) to rest.substring(i + 1)
    }

    /** Комментарий (в том числе изменённый). */
    fun isAnyComment(text: String) = parseComment(text) != null

    /** Шаблон SQL LIKE для поиска служебных сообщений к uid (изменение/удаление). */
    fun controlLikePatterns(uid: String) = listOf("$GEDIT$uid|%", "$GDELETE$uid", "$GPEDIT$uid|%")

    fun groupPartPattern(uid: String) = "$GPEDIT$uid|%"

    // ---------- Прочитано ----------

    fun isRead(text: String) = text.startsWith(READ)
    fun read(key: String) = READ + key
    fun parseRead(text: String): String? = if (isRead(text)) text.removePrefix(READ).takeIf { it.isNotEmpty() } else null

    // ---------- Ответы ----------

    fun snippet(text: String): String {
        val body = RichText.plain(body(text)).replace('\n', ' ').replace(INV, "").trim()
        return if (body.length > SNIPPET_MAX) body.take(SNIPPET_MAX - 1) + "…" else body
    }

    fun reply(quoted: String, text: String) = REPLY + snippet(quoted) + INV + text

    /** (цитата или null, текст без цитаты). */
    fun parseReply(raw: String): Pair<String?, String> {
        // Служебный заголовок бизнес-поста канала (товар, кнопка, «без звука») - не текст.
        val text = com.stoyko.smessenger.groups.ChannelPost.strip(stripEdited(raw))
        if (!text.startsWith(REPLY)) return null to text
        val end = text.indexOf(INV, REPLY.length)
        if (end < 0) return null to text
        return text.substring(REPLY.length, end) to text.substring(end + INV.length)
    }

    fun body(text: String) = parseReply(text).second

    /** Ключ сообщения - одинаковый у обоих собеседников (по тексту). */
    fun key(text: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(body(text).trim().toByteArray())
        return d.take(6).joinToString("") { "%02x".format(it) }
    }

    // ---------- Реакции ----------

    fun reaction(key: String, emoji: String?) = "$REACTION$key:${emoji ?: "-"}"

    /** (ключ, эмодзи или null - реакция снята). */
    fun parseReaction(text: String): Pair<String, String?>? {
        if (!isReaction(text)) return null
        val rest = text.removePrefix(REACTION)
        val i = rest.indexOf(':')
        if (i <= 0) return null
        val e = rest.substring(i + 1)
        return rest.substring(0, i) to e.takeIf { it.isNotEmpty() && it != "-" }
    }

    // ---------- Закреплённое ----------

    fun pin(key: String?, snippet: String) = if (key == null) "$PIN-" else "$PIN$key|${snippet.replace(INV, "")}"

    /** Открепить одно сообщение у собеседника. */
    fun unpinOne(key: String) = "$PIN-$key"

    /** Ключ сообщения, которое собеседник открепил (одно), или null. */
    fun parseUnpinOne(text: String): String? {
        if (!isPin(text)) return null
        val rest = text.removePrefix(PIN)
        return if (rest.length > 1 && rest.startsWith("-") && !rest.contains('|')) rest.substring(1) else null
    }

    /** (ключ, цитата) или (null, "") - открепить. */
    fun parsePin(text: String): Pair<String?, String>? {
        if (!isPin(text)) return null
        val rest = text.removePrefix(PIN)
        if (rest == "-") return null to ""
        val i = rest.indexOf('|')
        return if (i <= 0) null to "" else rest.substring(0, i) to rest.substring(i + 1)
    }

    /**
     * Текст для показа: цитата ответа - полосой сверху, найденное в поиске - подсветкой.
     */
    fun render(text: String, quoteColor: Int, highlight: String? = null, highlightColor: Int = 0x66FFD54F): CharSequence {
        val (quote, body) = parseReply(text)
        val sb = SpannableStringBuilder()
        if (quote != null) {
            val start = sb.length
            sb.append(quote)
            sb.setSpan(QuoteSpan(quoteColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xD9FFFFFF.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
        }
        val bodyStart = sb.length
        // Оформление (заголовки, списки, таблицы, спойлеры, цитаты, живые эмодзи) - см. RichText.
        val rich = RichText.render(body)
        sb.append(rich)
        val shown = rich.toString()
        if (!highlight.isNullOrBlank()) {
            var i = shown.indexOf(highlight, ignoreCase = true)
            while (i >= 0) {
                sb.setSpan(
                    BackgroundColorSpan(highlightColor),
                    bodyStart + i,
                    bodyStart + i + highlight.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                i = shown.indexOf(highlight, i + highlight.length, ignoreCase = true)
            }
        }
        // «↪ Переслано:» - крупнее и жирнее, видно сразу.
        for (prefix in FORWARD_PREFIXES) {
            if (body.startsWith(prefix) && shown.startsWith(prefix)) {
                val end = bodyStart + prefix.trimEnd('\n').length
                sb.setSpan(StyleSpan(Typeface.BOLD), bodyStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.12f), bodyStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                break
            }
        }
        return sb
    }

    /** Заголовки пересланных сообщений (как в strings.xml forwarded_prefix, обе локали). */
    private val FORWARD_PREFIXES = listOf("↪ Переслано:\n", "↪ Forwarded:\n")
}

/**
 * Реакции и закреплённые сообщения личных чатов (хранятся на устройстве,
 * синхронизируются с собеседником служебными сообщениями).
 */
@Singleton
class ChatExtras @Inject constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("chat_extras", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun bump() {
        _changes.value = _changes.value + 1
    }

    /** Реакции к сообщению: «мои» и «собеседника». */
    fun reactions(pk: String, key: String): Pair<String?, String?> {
        val v = prefs.getString("r_${pk}_$key", null) ?: return null to null
        val parts = v.split('\u0001')
        return parts.getOrNull(0)?.ifEmpty { null } to parts.getOrNull(1)?.ifEmpty { null }
    }

    fun setReaction(pk: String, key: String, emoji: String?, mine: Boolean) {
        val (m, t) = reactions(pk, key)
        val nm = if (mine) emoji else m
        val nt = if (mine) t else emoji
        prefs.edit().apply {
            if (nm == null && nt == null) {
                remove("r_${pk}_$key")
            } else {
                putString("r_${pk}_$key", (nm ?: "") + '\u0001' + (nt ?: ""))
            }
        }.apply()
        bump()
    }

    /**
     * Строка реакций под сообщением: у каждого может быть несколько реакций,
     * одинаковые складываются («❤️ 2  👍  😂»).
     */
    fun reactionLine(pk: String, key: String): String {
        val (m, t) = reactions(pk, key)
        val counts = LinkedHashMap<String, Int>()
        (split(t) + split(m)).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.entries.joinToString("  ") { (e, n) -> if (n > 1) "$e $n" else e }
    }

    companion object Multi {
        /** Набор реакций из строки «❤️,👍». */
        fun split(s: String?): List<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        /** Добавить или убрать эмодзи из набора (null - убрать все). */
        fun toggle(current: String?, emoji: String?): String? {
            if (emoji == null) return null
            val set = LinkedHashSet(split(current))
            if (!set.remove(emoji)) set.add(emoji)
            return set.takeIf { it.isNotEmpty() }?.joinToString(",")
        }

        fun has(current: String?, emoji: String) = emoji in split(current)
    }

    /** (ключ, цитата) закреплённого сообщения. */
    /**
     * Закреплённые сообщения чата - сколько угодно (последнее - в конце списка).
     * Хранятся как JSON; старый единственный закреп переносится автоматически.
     */
    fun pins(pk: String): List<Pair<String, String>> {
        val raw = prefs.getString("pins_$pk", null)
        if (raw == null) {
            val v = prefs.getString("pin_$pk", null) ?: return emptyList()
            val i = v.indexOf('|')
            return if (i <= 0) emptyList() else listOf(v.substring(0, i) to v.substring(i + 1))
        }
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("k") to o.optString("s") } }
        }.getOrDefault(emptyList())
    }

    private fun savePins(pk: String, list: List<Pair<String, String>>) {
        val a = org.json.JSONArray()
        list.forEach { (k, sn) -> a.put(org.json.JSONObject().put("k", k).put("s", sn)) }
        prefs.edit().putString("pins_$pk", a.toString()).remove("pin_$pk").apply()
        bump()
    }

    /** Последнее закреплённое (для совместимости). */
    fun pinned(pk: String): Pair<String, String>? = pins(pk).lastOrNull()

    fun isPinned(pk: String, key: String) = pins(pk).any { it.first == key }

    /** Добавить закреп (key != null) или снять все (key == null). */
    fun setPinned(pk: String, key: String?, snippet: String) {
        if (key == null) {
            savePins(pk, emptyList())
            return
        }
        val list = pins(pk).filter { it.first != key } + (key to snippet)
        savePins(pk, list.takeLast(200))
    }

    fun unpin(pk: String, key: String) = savePins(pk, pins(pk).filter { it.first != key })

    /**
     * Сообщение изменили - его ключ (по тексту) стал другим: реакции и закреп переносим на
     * новый ключ, иначе после правки они «пропадали».
     */
    fun moveKey(pk: String, oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        prefs.getString("r_${pk}_$oldKey", null)?.let { v ->
            prefs.edit().putString("r_${pk}_$newKey", v).remove("r_${pk}_$oldKey").apply()
        }
        val p = pins(pk)
        if (p.any { it.first == oldKey }) savePins(pk, p.map { if (it.first == oldKey) newKey to it.second else it })
        bump()
    }

    // ---------- Прочитано ----------

    /** Ключ нашего последнего сообщения, которое собеседник прочитал. */
    fun readKey(pk: String): String? = prefs.getString("read_$pk", null)

    /** Что мы последний раз сообщили собеседнику как прочитанное (чтобы не повторять). */
    fun lastReadSent(pk: String): String? = prefs.getString("readsent_$pk", null)

    fun setLastReadSent(pk: String, key: String) {
        prefs.edit().putString("readsent_$pk", key).apply()
    }

    /** Служебное входящее сообщение (реакция/закреп/прочитано) - применить. true - не показывать в чате. */
    fun onIncoming(pk: String, text: String): Boolean {
        // Запрет скриншотов и пересылки от собеседника: применяем, строкой в чате остаётся.
        if (ScreenGuard.isMarker(text)) {
            runCatching { ScreenGuard.onIncoming(appContext, pk, text) }
            return false
        }
        Markup.parseRead(text)?.let { key ->
            prefs.edit().putString("read_$pk", key).apply()
            bump()
            return true
        }
        if (Markup.isRead(text)) return true
        Markup.parseReaction(text)?.let { (key, emoji) ->
            setReaction(pk, key, emoji, mine = false)
            return true
        }
        Markup.parseUnpinOne(text)?.let { key ->
            unpin(pk, key)
            return true
        }
        Markup.parsePin(text)?.let { (key, snippet) ->
            setPinned(pk, key, snippet)
            return true
        }
        return false
    }

    object Quick {
        val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🔥", "👏", "🙏")
    }
}

/** Выбор реакции: недавние, свои (убрать) и сотни эмодзи по разделам с прокруткой. */
fun showReactionPicker(context: Context, current: String?, onPick: (String?) -> Unit) =
    ReactionPicker.show(context, current, onPick)
