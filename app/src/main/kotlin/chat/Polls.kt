// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.groups.ChannelPost
import org.json.JSONArray
import org.json.JSONObject

/**
 * Умные опросы - в личных чатах, группах и каналах.
 *
 *  - медиа: обложка-иллюстрация и эмодзи у каждого варианта (анимированные эмодзи чата);
 *  - один или несколько ответов, анонимный или открытый (видно, кто как голосовал);
 *  - жёсткие ограничения: срок (после него голоса не принимаются), лимит голосов (засчитываются
 *    первые N проголосовавших по времени), без переголосования, только жители выбранных стран
 *    (страна - по SIM-карте/региону телефона голосующего); в канале голосовать могут только
 *    подписчики - посторонние в канал не попадают;
 *  - график: как менялись голоса во времени (по каждому варианту).
 *
 * Опрос - служебный заголовок сообщения ([ChannelPost], ключ «q»); текст сообщения - вопрос
 * (старые версии видят его обычным текстом). Голос - скрытое сообщение «⁣📊V:{json}»: в личном
 * чате не показывается, в группе хранится в истории (его получат и те, кто был не в сети) и
 * скрыт из ленты. Каждый клиент считает голоса сам, по одним правилам - результаты совпадают.
 */
object Polls {
    private const val VOTE = "⁣📊V:"

    const val MAX_OPTIONS = 8
    const val MAX_OPTION_TEXT = 40
    const val MAX_QUESTION = 200

    class Opt(val text: String, val emoji: String)

    class Poll(
        val uid: String,
        val cover: String,
        val options: List<Opt>,
        val multi: Boolean,
        val anon: Boolean,
        val countries: List<String>,
        val deadline: Long,
        val maxVotes: Int,
        val lock: Boolean,
    )

    class Vote(val voter: String, val name: String, val opts: List<Int>, val ts: Long, val country: String)

    /** Что нужно опросу от экрана чата. */
    class Host(
        /** Отправить служебное сообщение-голос в этот чат. */
        val send: (String) -> Unit,
        /** Мой ключ в этом чате (для групп - ключ участника группы). */
        val myKey: String,
        /** Имя по ключу (для открытых опросов). */
        val nameOf: (String) -> String = { it.take(8) },
    )

    // ------------------------------------------------------------------ формат

    fun toJson(p: Poll): JSONObject = JSONObject()
        .put("u", p.uid)
        .apply {
            if (p.cover.isNotBlank()) put("c", p.cover.take(16))
            if (p.multi) put("m", 1)
            if (p.anon) put("a", 1)
            if (p.countries.isNotEmpty()) put("cc", JSONArray(p.countries.take(10)))
            if (p.deadline > 0) put("d", p.deadline)
            if (p.maxVotes > 0) put("n", p.maxVotes)
            if (p.lock) put("l", 1)
        }
        .put(
            "o",
            JSONArray().apply {
                p.options.take(MAX_OPTIONS).forEach { o ->
                    put(JSONObject().put("t", o.text.take(MAX_OPTION_TEXT)).apply { if (o.emoji.isNotBlank()) put("e", o.emoji.take(16)) })
                }
            },
        )

    fun fromJson(o: JSONObject?): Poll? {
        if (o == null) return null
        val uid = o.optString("u").takeIf { it.matches(Regex("[0-9a-z]{6,24}")) } ?: return null
        val arr = o.optJSONArray("o") ?: return null
        val opts = (0 until minOf(arr.length(), MAX_OPTIONS)).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Opt(it.optString("t").take(MAX_OPTION_TEXT), it.optString("e").take(16)) }?.takeIf { it.text.isNotBlank() }
        }
        if (opts.size < 2) return null
        val cc = o.optJSONArray("cc")
        return Poll(
            uid,
            o.optString("c").take(16),
            opts,
            o.optInt("m", 0) == 1,
            o.optInt("a", 0) == 1,
            if (cc == null) emptyList() else (0 until minOf(cc.length(), 10)).map { cc.optString(it).uppercase().take(2) }.filter { it.length == 2 },
            o.optLong("d", 0L).coerceAtLeast(0L),
            o.optInt("n", 0).coerceIn(0, 1_000_000),
            o.optInt("l", 0) == 1,
        )
    }

    fun of(raw: String): Poll? = ChannelPost.meta(if (raw.startsWith("⁣✎")) raw.substring(2) else raw)?.poll

    fun isVote(text: String) = text.startsWith(VOTE)

    private fun voteText(uid: String, opts: List<Int>, country: String, ts: Long) =
        VOTE + JSONObject().put("u", uid).put("o", JSONArray(opts)).put("c", country).put("t", ts).toString()

    private class Parsed(val uid: String, val opts: List<Int>, val country: String, val ts: Long)

    private fun parseVote(text: String): Parsed? = runCatching {
        val o = JSONObject(text.removePrefix(VOTE))
        val a = o.getJSONArray("o")
        Parsed(
            o.getString("u"),
            (0 until minOf(a.length(), MAX_OPTIONS)).map { a.getInt(it) }.filter { it in 0 until MAX_OPTIONS }.distinct(),
            o.optString("c").uppercase().take(2),
            o.optLong("t", 0L),
        )
    }.getOrNull()

    // ------------------------------------------------------------------ хранение голосов

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("polls", Context.MODE_PRIVATE)

    /**
     * Журнал голосов опроса: все голоса по порядку (переголосование - новая запись того же
     * голосующего). Последняя запись голосующего - его текущий голос; по журналу строится график.
     */
    @Synchronized
    private fun log(ctx: Context, uid: String): List<Vote> = runCatching {
        val a = JSONArray(prefs(ctx).getString("l_$uid", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val oa = o.getJSONArray("o")
            Vote(o.getString("v"), o.optString("n"), (0 until oa.length()).map { oa.getInt(it) }, o.getLong("t"), o.optString("c"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun append(ctx: Context, uid: String, v: Vote): Boolean {
        val cur = log(ctx, uid)
        // Повтор того же голоса (история группы, повторная доставка) - не дублируем.
        if (cur.any { it.voter == v.voter && it.ts == v.ts }) return false
        val next = (cur + v).sortedBy { it.ts }.takeLast(3000)
        val a = JSONArray()
        next.forEach {
            a.put(JSONObject().put("v", it.voter).put("n", it.name.take(40)).put("o", JSONArray(it.opts)).put("t", it.ts).put("c", it.country))
        }
        prefs(ctx).edit().putString("l_$uid", a.toString()).apply()
        return true
    }

    /** Пришёл голос (личный чат или группа). [voter] - ключ отправителя. */
    fun onVote(ctx: Context, voter: String, name: String, text: String): Boolean {
        val p = parseVote(text) ?: return isVote(text)
        // Время голоса - не из будущего (иначе можно было бы обойти срок опроса).
        val ts = p.ts.coerceIn(1L, System.currentTimeMillis() + 60_000L)
        if (append(ctx, p.uid, Vote(voter, name, p.opts, ts, p.country))) changed()
        return true
    }

    /** Личный чат: служебный голос от собеседника - учесть, в чат не писать. */
    fun onIncoming(ctx: Context, fromPk: String, text: String): Boolean {
        if (!isVote(text)) return false
        onVote(ctx, fromPk, "", text)
        return true
    }

    /** Страна голосующего: по SIM-карте, затем по сети и региону телефона. */
    fun myCountry(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val sim = runCatching { tm?.simCountryIso }.getOrNull().orEmpty()
        val net = runCatching { tm?.networkCountryIso }.getOrNull().orEmpty()
        val loc = java.util.Locale.getDefault().country.orEmpty()
        return (sim.ifBlank { net }.ifBlank { loc }).uppercase().take(2)
    }

    // ------------------------------------------------------------------ подсчёт

    class Result(
        val counts: IntArray,
        val voters: Int,
        val mine: List<Int>,
        val closed: Boolean,
        val full: Boolean,
        /** Засчитанные голоса (текущие) - для открытых опросов. */
        val current: List<Vote>,
        /** Журнал засчитанных изменений - для графика. */
        val history: List<Vote>,
    )

    /**
     * Подсчёт по единым правилам: голос после срока не засчитывается; при лимите - только
     * первые N голосующих (по времени первого голоса); «без переголосования» - действует
     * первый голос; страна - из голоса (каждое приложение пишет свою).
     */
    fun tally(ctx: Context, p: Poll, myKey: String): Result {
        val all = log(ctx, p.uid).filter { v ->
            (p.deadline <= 0 || v.ts <= p.deadline) &&
                (p.countries.isEmpty() || v.country in p.countries) &&
                v.opts.all { it in p.options.indices } &&
                (p.multi || v.opts.size <= 1)
        }
        val firstTs = HashMap<String, Long>()
        all.forEach { v -> if (v.voter !in firstTs) firstTs[v.voter] = v.ts }
        val allowed = firstTs.entries.sortedBy { it.value }.map { it.key }
            .let { if (p.maxVotes > 0) it.take(p.maxVotes) else it }.toSet()
        val history = all.filter { it.voter in allowed }.let { list ->
            if (!p.lock) list else list.groupBy { it.voter }.values.map { it.first() }.sortedBy { it.ts }
        }
        val current = history.groupBy { it.voter }.values.map { it.last() }.filter { it.opts.isNotEmpty() }
        val counts = IntArray(p.options.size)
        current.forEach { v -> v.opts.forEach { counts[it]++ } }
        val mine = current.firstOrNull { it.voter == myKey }?.opts.orEmpty()
        val closed = p.deadline > 0 && System.currentTimeMillis() > p.deadline
        val full = p.maxVotes > 0 && allowed.size >= p.maxVotes && myKey !in allowed
        return Result(counts, current.size, mine, closed, full, current, history)
    }

    /** Проголосовать (или снять голос - пустой список). */
    fun vote(ctx: Context, p: Poll, host: Host, opts: List<Int>) {
        val country = myCountry(ctx)
        if (p.countries.isNotEmpty() && country !in p.countries) {
            Toast.makeText(ctx, t("Опрос только для: " + p.countries.joinToString(", ") { flag(it) + " " + it }, "Poll only for: " + p.countries.joinToString(", ") { flag(it) + " " + it }), Toast.LENGTH_LONG).show()
            return
        }
        val r = tally(ctx, p, host.myKey)
        when {
            r.closed -> return Toast.makeText(ctx, t("Опрос завершён", "The poll is closed"), Toast.LENGTH_SHORT).show()
            r.full -> return Toast.makeText(ctx, t("Лимит голосов исчерпан", "Vote limit reached"), Toast.LENGTH_SHORT).show()
            p.lock && r.mine.isNotEmpty() -> return Toast.makeText(ctx, t("Переголосовать нельзя", "You can't change your vote"), Toast.LENGTH_SHORT).show()
        }
        val ts = System.currentTimeMillis()
        val text = voteText(p.uid, opts, country, ts)
        append(ctx, p.uid, Vote(host.myKey, "", opts, ts, country))
        changed()
        host.send(text)
    }

    // ------------------------------------------------------------------ карточка в пузыре

    private const val TAG_BOX = "poll_card"

    /** Показать карточку опроса под [anchor] (текст сообщения - вопрос). Без опроса - убрать. */
    fun bind(row: ViewGroup, anchor: View, raw: String, host: Host?) {
        val old = row.findViewWithTag<View>(TAG_BOX)
        val p = runCatching { of(raw) }.getOrNull()
        if (p == null || host == null) {
            old?.let { row.removeView(it) }
            return
        }
        val ctx = row.context
        val r = tally(ctx, p, host.myKey)
        val sig = p.uid + ":" + r.voters + ":" + r.counts.joinToString(",") + ":" + r.mine + ":" + r.closed
        if (old != null && old.getTag(com.stoyko.smessenger.R.id.tag_card_sig) == sig) return
        old?.let { row.removeView(it) }
        val card = card(ctx, p, r, host)
        card.tag = TAG_BOX
        card.setTag(com.stoyko.smessenger.R.id.tag_card_sig, sig)
        val w = minOf(dp(ctx, 290), (ctx.resources.displayMetrics.widthPixels * 0.82f).toInt())
        val lp = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = (anchor.layoutParams as? LinearLayout.LayoutParams)?.gravity ?: Gravity.START
            topMargin = dp(ctx, 4)
        }
        val i = row.indexOfChild(anchor)
        row.addView(card, if (i >= 0) i + 1 else row.childCount, lp)
    }

    private fun card(ctx: Context, p: Poll, r: Result, host: Host): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(0x26FFFFFF)
                setStroke(dp(ctx, 1), 0x33FFFFFF)
            }
        }
        if (p.cover.isNotBlank()) {
            box.addView(
                TextView(ctx).apply {
                    text = p.cover
                    textSize = 44f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val showResults = r.mine.isNotEmpty() || r.closed || r.full
        val total = r.counts.sum().coerceAtLeast(1)
        val picked = HashSet<Int>()
        p.options.forEachIndexed { i, o ->
            val pct = r.counts[i] * 100 / total
            val chosen = i in r.mine
            val rowV = TextView(ctx).apply {
                val mark = when {
                    chosen -> "✅ "
                    showResults -> ""
                    p.multi -> "☐ "
                    else -> ""
                }
                text = mark + (if (o.emoji.isNotBlank()) o.emoji + "  " else "") + o.text + if (showResults) "   $pct%" else ""
                textSize = 15f
                setTextColor(Color.WHITE)
                if (chosen) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                background = bar(ctx, if (showResults) pct else 0, chosen)
            }
            rowV.setOnClickListener {
                rowV.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (showResults && !(r.mine.isNotEmpty() && !p.lock && !r.closed)) {
                    if (!p.anon) whoVoted(ctx, p, r, host, i)
                    return@setOnClickListener
                }
                if (p.multi && !showResults) {
                    if (!picked.add(i)) picked.remove(i)
                    rowV.text = (if (i in picked) "☑ " else "☐ ") + (if (o.emoji.isNotBlank()) o.emoji + "  " else "") + o.text
                } else {
                    vote(ctx, p, host, listOf(i))
                }
            }
            // Открытый опрос: удержание варианта - кто за него проголосовал.
            if (!p.anon) {
                rowV.setOnLongClickListener {
                    whoVoted(ctx, p, r, host, i)
                    true
                }
            }
            box.addView(
                rowV,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 6)
                },
            )
        }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (p.multi && !showResults) {
            actions.addView(smallBtn(ctx, t("🗳 Голосовать", "🗳 Vote")) {
                if (picked.isEmpty()) {
                    Toast.makeText(ctx, t("Отметьте варианты", "Pick options"), Toast.LENGTH_SHORT).show()
                } else {
                    vote(ctx, p, host, picked.sorted())
                }
            })
        }
        if (r.mine.isNotEmpty() && !p.lock && !r.closed) {
            actions.addView(smallBtn(ctx, t("↩ Отменить голос", "↩ Retract")) { vote(ctx, p, host, emptyList()) })
        }
        actions.addView(smallBtn(ctx, t("📈 График", "📈 Chart")) { chart(ctx, p, r) })
        box.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
        box.addView(
            TextView(ctx).apply {
                text = footer(ctx, p, r)
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
                setPadding(0, dp(ctx, 6), 0, 0)
            },
        )
        return box
    }

    private fun footer(ctx: Context, p: Poll, r: Result): String {
        val parts = ArrayList<String>()
        parts += t("${r.voters} голос.", "${r.voters} votes")
        parts += if (p.anon) t("анонимный", "anonymous") else t("открытый", "public")
        if (p.multi) parts += t("несколько ответов", "multiple answers")
        if (p.deadline > 0) {
            val f = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            parts += if (r.closed) t("завершён", "closed") else t("до ", "until ") + f.format(java.util.Date(p.deadline))
        }
        if (p.maxVotes > 0) parts += t("лимит ${p.maxVotes}", "limit ${p.maxVotes}")
        if (p.lock) parts += t("без переголосования", "no re-voting")
        if (p.countries.isNotEmpty()) parts += p.countries.joinToString(" ") { flag(it) }
        return "📊 " + parts.joinToString(" · ")
    }

    private fun bar(ctx: Context, pct: Int, chosen: Boolean): android.graphics.drawable.Drawable {
        val r = dp(ctx, 12).toFloat()
        val bg = GradientDrawable().apply {
            cornerRadius = r
            setColor(0x1FFFFFFF)
        }
        val fill = GradientDrawable().apply {
            cornerRadius = r
            setColor(if (chosen) 0x8022C55E.toInt() else 0x553B82F6)
        }
        val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL).apply { level = pct.coerceIn(0, 100) * 100 }
        return LayerDrawable(arrayOf(bg, clip))
    }

    private fun smallBtn(ctx: Context, text: String, click: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 10).toFloat()
            setColor(0x33FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
        setOnClickListener { click() }
    }

    private fun whoVoted(ctx: Context, p: Poll, r: Result, host: Host, option: Int) {
        val names = r.current.filter { option in it.opts }.sortedBy { it.ts }.map { v ->
            if (v.voter == host.myKey) t("Вы", "You") else v.name.ifBlank { host.nameOf(v.voter) }
        }
        AlertDialog.Builder(ctx)
            .setTitle((if (p.options[option].emoji.isNotBlank()) p.options[option].emoji + " " else "") + p.options[option].text)
            .setMessage(if (names.isEmpty()) t("Пока никто", "Nobody yet") else names.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun flag(cc: String): String {
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return cc
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    // ------------------------------------------------------------------ график

    /** Цвета линий графика (по вариантам). */
    private val LINE = intArrayOf(
        0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFEF4444.toInt(), 0xFFF59E0B.toInt(),
        0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(), 0xFFEC4899.toInt(), 0xFF84CC16.toInt(),
    )

    private fun chart(ctx: Context, p: Poll, r: Result) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), 0)
        }
        box.addView(PollChart(ctx, p, r.history), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 220)))
        p.options.forEachIndexed { i, o ->
            box.addView(
                TextView(ctx).apply {
                    text = "●  " + (if (o.emoji.isNotBlank()) o.emoji + " " else "") + o.text + " - " + r.counts[i]
                    setTextColor(LINE[i % LINE.size])
                    textSize = 14f
                    setPadding(0, dp(ctx, 4), 0, 0)
                },
            )
        }
        if (r.history.isEmpty()) {
            box.addView(TextView(ctx).apply { text = t("Голосов пока нет", "No votes yet") })
        }
        AlertDialog.Builder(ctx)
            .setTitle(t("📈 Голоса во времени", "📈 Votes over time"))
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Ступенчатый график: число голосов за каждый вариант после каждого изменения. */
    private class PollChart(ctx: Context, val p: Poll, history: List<Vote>) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val times = ArrayList<Long>()
        private val series = Array(p.options.size) { ArrayList<Int>() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            strokeJoin = Paint.Join.ROUND
        }
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55888888
            strokeWidth = 1f * d
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 10f * d
        }

        init {
            val state = HashMap<String, List<Int>>()
            val counts = IntArray(p.options.size)
            fun snap(t: Long) {
                times += t
                counts.forEachIndexed { i, c -> series[i] += c }
            }
            if (history.isNotEmpty()) snap(history.first().ts - 1)
            for (v in history) {
                state[v.voter]?.forEach { counts[it]-- }
                state[v.voter] = v.opts
                v.opts.forEach { counts[it]++ }
                snap(v.ts)
            }
            if (history.isNotEmpty()) snap(maxOf(System.currentTimeMillis(), history.last().ts))
        }

        override fun onDraw(c: Canvas) {
            val left = 28 * d
            val bottom = height - 18 * d
            val top = 8 * d
            val right = width - 8 * d
            c.drawLine(left, bottom, right, bottom, axis)
            c.drawLine(left, top, left, bottom, axis)
            if (times.size < 2) return
            val maxV = series.maxOf { s -> s.maxOrNull() ?: 0 }.coerceAtLeast(1)
            val t0 = times.first()
            val t1 = times.last().coerceAtLeast(t0 + 1)
            fun x(t: Long) = left + (right - left) * ((t - t0).toFloat() / (t1 - t0))
            fun y(v: Int) = bottom - (bottom - top) * (v.toFloat() / maxV)
            c.drawText(maxV.toString(), 4 * d, top + 10 * d, label)
            c.drawText("0", 4 * d, bottom, label)
            val fmt = java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.getDefault())
            c.drawText(fmt.format(java.util.Date(t0)), left, height - 4 * d, label)
            val end = fmt.format(java.util.Date(t1))
            c.drawText(end, right - label.measureText(end), height - 4 * d, label)
            series.forEachIndexed { i, s ->
                line.color = LINE[i % LINE.size]
                val path = Path()
                path.moveTo(x(times[0]), y(s[0]))
                for (k in 1 until times.size) {
                    path.lineTo(x(times[k]), y(s[k - 1]))
                    path.lineTo(x(times[k]), y(s[k]))
                }
                c.drawPath(path, line)
            }
        }
    }

    // ------------------------------------------------------------------ создание

    /** Новый опрос: вопрос, обложка, варианты с эмодзи, режимы и ограничения. [onDone] - готовое сообщение. */
    fun editor(ctx: Context, initialQuestion: String, onDone: (String) -> Unit) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
        }
        fun field(hint: String, text: String = "", max: Int = 60, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(ctx).apply {
            this.hint = hint
            setText(text)
            filters = arrayOf(InputFilter.LengthFilter(max))
            inputType = type
        }
        val question = field(t("Вопрос", "Question"), initialQuestion, MAX_QUESTION, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        box.addView(question)
        val cover = field(t("Обложка - эмодзи (необязательно)", "Cover - emoji (optional)"), "", 16)
        box.addView(cover)
        val optsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(optsBox)
        val optRows = ArrayList<Pair<EditText, EditText>>()
        fun addOpt(e: String = "", txt: String = "") {
            if (optRows.size >= MAX_OPTIONS) return
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val em = field("🙂", e, 16).apply { gravity = Gravity.CENTER }
            val tx = field(t("Вариант ${optRows.size + 1}", "Option ${optRows.size + 1}"), txt, MAX_OPTION_TEXT)
            line.addView(em, LinearLayout.LayoutParams(dp(ctx, 56), ViewGroup.LayoutParams.WRAP_CONTENT))
            line.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            optsBox.addView(line)
            optRows += em to tx
        }
        addOpt("👍", t("Да", "Yes"))
        addOpt("👎", t("Нет", "No"))
        box.addView(
            TextView(ctx).apply {
                text = t("＋ Добавить вариант", "＋ Add option")
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
                setOnClickListener { addOpt() }
            },
        )
        val multi = CheckBox(ctx).apply { text = t("Несколько ответов", "Multiple answers") }
        val anon = CheckBox(ctx).apply {
            text = t("Анонимный (не показывать, кто как голосовал)", "Anonymous (hide who voted for what)")
            isChecked = true
        }
        val lock = CheckBox(ctx).apply { text = t("Без переголосования", "No re-voting") }
        box.addView(multi)
        box.addView(anon)
        box.addView(lock)
        val deadlines = listOf(0L to t("без срока", "no deadline"), 3_600_000L to t("1 час", "1 hour"), 86_400_000L to t("1 день", "1 day"), 3 * 86_400_000L to t("3 дня", "3 days"), 7 * 86_400_000L to t("неделя", "a week"))
        var dl = 0
        val dlRow = TextView(ctx).apply {
            textSize = 15f
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
        }
        fun showDl() {
            dlRow.text = t("⏳ Срок: ", "⏳ Deadline: ") + deadlines[dl].second + t("  (нажмите)", "  (tap)")
        }
        showDl()
        dlRow.setOnClickListener {
            dl = (dl + 1) % deadlines.size
            showDl()
        }
        box.addView(dlRow)
        val maxVotes = field(t("Лимит голосов (пусто - без лимита)", "Vote limit (empty - unlimited)"), "", 7, InputType.TYPE_CLASS_NUMBER)
        box.addView(maxVotes)
        val countries = field(t("Только страны: RU, BY, KZ… (пусто - все)", "Only countries: US, GB, DE… (empty - all)"), "", 40, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        box.addView(countries)
        box.addView(
            TextView(ctx).apply {
                text = t(
                    "Страна определяется по SIM-карте или региону телефона голосующего. В канале голосовать могут только подписчики.",
                    "The country comes from the voter's SIM card or phone region. In a channel only subscribers can vote.",
                )
                textSize = 12f
                alpha = 0.7f
            },
        )
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(t("📊 Новый опрос", "📊 New poll"))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(t("Отправить", "Send"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q = question.text.toString().trim()
            val opts = optRows.map { (e, tx) -> Opt(tx.text.toString().trim(), e.text.toString().trim()) }.filter { it.text.isNotBlank() }
            if (q.isEmpty()) {
                question.error = t("Напишите вопрос", "Write the question")
                return@setOnClickListener
            }
            if (opts.size < 2) {
                Toast.makeText(ctx, t("Нужно хотя бы 2 варианта", "At least 2 options needed"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cc = countries.text.toString().uppercase().split(Regex("[^A-Z]+")).filter { it.length == 2 }.distinct()
            val poll = Poll(
                uid = System.currentTimeMillis().toString(36) + (100000..999999).random().toString(36),
                cover = cover.text.toString().trim(),
                options = opts,
                multi = multi.isChecked,
                anon = anon.isChecked,
                countries = cc,
                deadline = deadlines[dl].first.let { if (it > 0) System.currentTimeMillis() + it else 0L },
                maxVotes = maxVotes.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                lock = lock.isChecked,
            )
            val out = ChannelPost.wrap(ChannelPost.Meta(poll = poll), "📊 $q")
            val head = out.length - ("📊 $q").length
            if (out.substring(0, head).toByteArray().size > 1000) {
                Toast.makeText(ctx, t("Слишком длинные варианты - сократите текст", "Options are too long - shorten them"), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onDone(out)
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)
}
