// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.groups.ChannelPost
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first

/**
 * Кнопки внутри сообщений: цветные кнопки с эмодзи прямо в пузыре - в личных чатах,
 * группах и каналах. Хранятся в служебном заголовке сообщения ([ChannelPost], ключ «k»),
 * поэтому идут со сквозным шифрованием, переживают правку текста и не видны в уведомлениях,
 * цитатах и поиске.
 *
 * Действия кнопок:
 *  - [ACT_URL]   - открыть ссылку (сайт, магазин, запись) или позвонить;
 *  - [ACT_REPLY] - быстрый ответ: в чат уходит заданный текст (опросы «да/нет», выбор варианта);
 *  - [ACT_COPY]  - скопировать текст (промокод, номер, адрес);
 *  - [ACT_GAME]  - мини-игра в один клик: «chess» - приглашение в шахматы автору сообщения,
 *                  иначе - открыть раздел игр;
 *  - [ACT_SHOP]  - каталог товаров канала (в каналах) или магазин монет.
 *
 * До 6 кнопок, до 3 в ряд. Заголовок ограничен по размеру ([MAX_HEADER_BYTES]), чтобы
 * сообщение с кнопками не делилось ядром на части посреди заголовка.
 */
object MsgButtons {
    const val ACT_URL = "u"
    const val ACT_REPLY = "r"
    const val ACT_COPY = "c"
    const val ACT_GAME = "g"
    const val ACT_SHOP = "s"

    const val MAX_BUTTONS = 6
    const val MAX_PER_ROW = 3
    const val MAX_LABEL = 24
    const val MAX_VALUE = 120

    /** Заголовок с кнопками целиком помещается в первую часть сообщения ядра (1372 байта). */
    const val MAX_HEADER_BYTES = 900

    private const val TAG_BOX = "msg_buttons"

    /** Цвета кнопок: синяя, зелёная, красная, оранжевая, фиолетовая, «стекло». */
    val COLORS = intArrayOf(
        0xFF3B82F6.toInt(),
        0xFF22C55E.toInt(),
        0xFFEF4444.toInt(),
        0xFFF59E0B.toInt(),
        0xFF8B5CF6.toInt(),
        0x33FFFFFF,
    )

    data class B(val label: String, val color: Int, val act: String, val value: String)

    /** Что нужно кнопкам от экрана чата. */
    class Host(
        /** Отправить текст в этот же чат (быстрый ответ). */
        val send: ((String) -> Unit)? = null,
        /** Ключ собеседника (личный чат) - для приглашения в игру. */
        val peer: String? = null,
        /** Открыть каталог канала (только в каналах). */
        val catalog: (() -> Unit)? = null,
    )

    // ------------------------------------------------------------------ формат

    fun toJson(rows: List<List<B>>): JSONArray {
        val a = JSONArray()
        for (row in rows) {
            val r = JSONArray()
            for (b in row) {
                r.put(
                    JSONObject().put("l", b.label.take(MAX_LABEL)).put("a", b.act)
                        .apply {
                            if (b.color != 0) put("c", b.color.coerceIn(0, COLORS.lastIndex))
                            if (b.value.isNotEmpty()) put("v", b.value.take(MAX_VALUE))
                        },
                )
            }
            if (r.length() > 0) a.put(r)
        }
        return a
    }

    fun fromJson(a: JSONArray?): List<List<B>> {
        if (a == null) return emptyList()
        val rows = ArrayList<List<B>>()
        var total = 0
        for (i in 0 until a.length()) {
            val r = a.optJSONArray(i) ?: continue
            val row = ArrayList<B>()
            for (j in 0 until minOf(r.length(), MAX_PER_ROW)) {
                if (total >= MAX_BUTTONS) break
                val o = r.optJSONObject(j) ?: continue
                val label = o.optString("l").take(MAX_LABEL)
                val act = o.optString("a")
                if (label.isBlank() || act !in setOf(ACT_URL, ACT_REPLY, ACT_COPY, ACT_GAME, ACT_SHOP)) continue
                row += B(label, o.optInt("c", 0).coerceIn(0, COLORS.lastIndex), act, o.optString("v").take(MAX_VALUE))
                total++
            }
            if (row.isNotEmpty()) rows += row
        }
        return rows
    }

    /** Разложить список кнопок по рядам (по [perRow]). */
    fun rows(list: List<B>, perRow: Int = 2): List<List<B>> =
        list.take(MAX_BUTTONS).chunked(perRow.coerceIn(1, MAX_PER_ROW))

    fun of(raw: String): List<List<B>> = ChannelPost.meta(stripEdited(raw))?.buttons.orEmpty()

    private fun stripEdited(raw: String) = if (raw.startsWith("⁣✎")) raw.substring(2) else raw

    /** Сообщение с кнопками (текст + заголовок). null - заголовок слишком большой. */
    fun wrap(text: String, rows: List<List<B>>, base: ChannelPost.Meta = ChannelPost.Meta()): String? {
        val meta = base.copy(buttons = rows)
        val out = ChannelPost.wrap(meta, text)
        val head = out.length - text.length
        return if (out.substring(0, head).toByteArray().size > MAX_HEADER_BYTES) null else out
    }

    // ------------------------------------------------------------------ отрисовка

    /**
     * Показать кнопки под [anchor] в [row] (вертикальный LinearLayout строки чата).
     * Без кнопок - убирает ранее добавленный блок (строки списка переиспользуются).
     */
    fun bind(row: ViewGroup, anchor: View, raw: String, outgoing: Boolean, host: Host?) {
        val old = row.findViewWithTag<View>(TAG_BOX)
        val rows = runCatching { of(raw) }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            old?.let { row.removeView(it) }
            return
        }
        val sig = raw.hashCode()
        if (old != null && old.getTag(com.stoyko.smessenger.R.id.tag_card_sig) == sig) return
        old?.let { row.removeView(it) }
        val ctx = row.context
        val box = LinearLayout(ctx).apply {
            tag = TAG_BOX
            setTag(com.stoyko.smessenger.R.id.tag_card_sig, sig)
            orientation = LinearLayout.VERTICAL
        }
        for (r in rows) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEachIndexed { i, b ->
                line.addView(
                    button(ctx, b) { act(ctx, b, host) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(ctx, 6)
                    },
                )
            }
            box.addView(
                line,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 6)
                },
            )
        }
        val screen = ctx.resources.displayMetrics.widthPixels
        val w = minOf(dp(ctx, 290), (screen * 0.8f).toInt())
        val lp = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (outgoing) Gravity.END else Gravity.START
            marginStart = dp(ctx, 4)
            marginEnd = dp(ctx, 4)
        }
        val i = row.indexOfChild(anchor)
        row.addView(box, if (i >= 0) i + 1 else row.childCount, lp)
    }

    private fun button(ctx: Context, b: B, click: () -> Unit) = TextView(ctx).apply {
        text = b.label
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 2
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
        minHeight = dp(ctx, 40)
        val c = COLORS[b.color.coerceIn(0, COLORS.lastIndex)]
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            if (Color.alpha(c) == 255) {
                colors = intArrayOf(lighten(c), c)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            } else {
                setColor(c)
                setStroke(dp(ctx, 1), 0x55FFFFFF)
            }
        }
        contentDescription = b.label
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
            click()
        }
    }

    private fun lighten(c: Int): Int {
        fun ch(x: Int) = (x + (255 - x) * 0.18f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    // ------------------------------------------------------------------ действия

    private fun act(ctx: Context, b: B, host: Host?) {
        when (b.act) {
            ACT_URL -> open(ctx, ChannelPost.normalizeUrl(b.value))
            ACT_REPLY -> {
                val text = b.value.ifBlank { b.label }
                val send = host?.send
                if (send == null) {
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                } else {
                    send(text)
                }
            }
            ACT_COPY -> {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(b.label, b.value.ifBlank { b.label }))
                Toast.makeText(ctx, t(ctx, "Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
            }
            ACT_GAME -> game(ctx, b.value, host?.peer)
            ACT_SHOP -> {
                val catalog = host?.catalog
                if (catalog != null) catalog() else runCatching { CoinShop.show(ctx) }
            }
        }
    }

    private fun game(ctx: Context, which: String, peer: String?) {
        if (which == "chess" && !peer.isNullOrBlank()) {
            val name = runCatching {
                val app = ctx.applicationContext as com.stoyko.smessenger.App
                kotlinx.coroutines.runBlocking { app.component.contactRepository().get(peer).first()?.name }
            }.getOrNull().orEmpty().ifBlank { peer.take(8) }
            val s = com.stoyko.smessenger.games.GameNet.invite(ctx, peer, name, 0, "")
            if (s != null) {
                Toast.makeText(ctx, com.stoyko.smessenger.R.string.chess_invite_sent, Toast.LENGTH_SHORT).show()
                com.stoyko.smessenger.games.ChessGame.show(ctx, s.id)
                return
            }
        }
        runCatching { com.stoyko.smessenger.games.GamesHub.show(ctx) }
    }

    private fun open(ctx: Context, url: String) {
        if (url.isBlank()) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ редактор

    private class Draft(var label: String = "", var color: Int = 0, var act: String = ACT_REPLY, var value: String = "")

    /**
     * Редактор сообщения с кнопками: текст + до 6 кнопок (название с эмодзи, цвет, действие,
     * значение) + сколько кнопок в ряд. [games] - показывать действие «Игра»
     * (в личном чате), [shop] - «Каталог/магазин». Готовое сообщение - в [onDone].
     */
    fun editor(
        ctx: Context,
        initialText: String,
        games: Boolean,
        shop: Boolean,
        base: ChannelPost.Meta = ChannelPost.Meta(),
        onDone: (String) -> Unit,
    ) {
        val drafts = arrayListOf(Draft(t(ctx, "👍 Да", "👍 Yes"), 1, ACT_REPLY), Draft(t(ctx, "👎 Нет", "👎 No"), 2, ACT_REPLY))
        val acts = buildList {
            add(ACT_REPLY to t(ctx, "💬 Ответ в чат", "💬 Reply in chat"))
            add(ACT_URL to t(ctx, "🔗 Ссылка / телефон", "🔗 Link / phone"))
            add(ACT_COPY to t(ctx, "📋 Скопировать", "📋 Copy"))
            if (games) add(ACT_GAME to t(ctx, "🎮 Игра", "🎮 Game"))
            if (shop) add(ACT_SHOP to t(ctx, "🛍 Каталог", "🛍 Catalog"))
        }
        val colorNames = listOf(
            t(ctx, "Синяя", "Blue"), t(ctx, "Зелёная", "Green"), t(ctx, "Красная", "Red"),
            t(ctx, "Оранжевая", "Orange"), t(ctx, "Фиолетовая", "Purple"), t(ctx, "Стекло", "Glass"),
        )
        val pad = dp(ctx, 20)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 8), pad, 0)
        }
        val body = EditText(ctx).apply {
            hint = t(ctx, "Текст сообщения", "Message text")
            setText(initialText)
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(body)
        val perRowLabel = TextView(ctx).apply { setPadding(0, dp(ctx, 10), 0, 0) }
        var perRow = 2
        fun showPerRow() {
            perRowLabel.text = t(ctx, "Кнопок в ряд: $perRow  (нажмите, чтобы изменить)", "Buttons per row: $perRow  (tap to change)")
        }
        showPerRow()
        perRowLabel.setOnClickListener {
            perRow = perRow % MAX_PER_ROW + 1
            showPerRow()
        }
        box.addView(perRowLabel)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)

        fun rebuild() {
            list.removeAllViews()
            drafts.forEachIndexed { idx, d ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
                }
                val label = EditText(ctx).apply {
                    hint = t(ctx, "Кнопка ${idx + 1}: текст с эмодзи", "Button ${idx + 1}: text with emoji")
                    setText(d.label)
                    filters = arrayOf(InputFilter.LengthFilter(MAX_LABEL))
                    setSingleLine()
                    addTextChangedListener(simpleWatcher { d.label = it })
                }
                card.addView(label)
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val actSpin = Spinner(ctx).apply {
                    adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, acts.map { it.second })
                    setSelection(acts.indexOfFirst { it.first == d.act }.coerceAtLeast(0))
                }
                val colorSpin = Spinner(ctx).apply {
                    adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, colorNames)
                    setSelection(d.color)
                }
                line.addView(actSpin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
                line.addView(colorSpin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(line)
                val value = EditText(ctx).apply {
                    setText(d.value)
                    filters = arrayOf(InputFilter.LengthFilter(MAX_VALUE))
                    setSingleLine()
                    addTextChangedListener(simpleWatcher { d.value = it })
                }
                fun valueHint() {
                    value.hint = when (d.act) {
                        ACT_URL -> t(ctx, "Ссылка или телефон", "Link or phone")
                        ACT_COPY -> t(ctx, "Что скопировать (промокод, номер…)", "What to copy (promo code, number…)")
                        ACT_GAME -> t(ctx, "chess - шахматы, пусто - раздел игр", "chess - chess, empty - games")
                        ACT_SHOP -> t(ctx, "не нужно", "not needed")
                        else -> t(ctx, "Текст ответа (пусто - как на кнопке)", "Reply text (empty - same as button)")
                    }
                    value.isEnabled = d.act != ACT_SHOP
                    value.inputType = if (d.act == ACT_URL) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    }
                }
                valueHint()
                actSpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        d.act = acts[pos].first
                        if (d.act == ACT_GAME && d.value.isBlank()) value.setText("chess")
                        valueHint()
                    }

                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
                colorSpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        d.color = pos
                    }

                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
                card.addView(value)
                card.addView(
                    TextView(ctx).apply {
                        text = t(ctx, "✕ Убрать кнопку", "✕ Remove button")
                        setTextColor(0xFFEF4444.toInt())
                        setPadding(0, dp(ctx, 4), 0, 0)
                        setOnClickListener {
                            drafts.removeAt(idx)
                            rebuild()
                        }
                    },
                )
                list.addView(card)
            }
            if (drafts.size < MAX_BUTTONS) {
                list.addView(
                    TextView(ctx).apply {
                        text = t(ctx, "＋ Добавить кнопку", "＋ Add button")
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
                        setOnClickListener {
                            drafts += Draft()
                            rebuild()
                        }
                    },
                )
            }
        }
        rebuild()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(t(ctx, "Сообщение с кнопками", "Message with buttons"))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(t(ctx, "Отправить", "Send"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = body.text.toString().trim()
            val ready = drafts.filter { it.label.isNotBlank() }
                .map { B(it.label.trim(), it.color, it.act, it.value.trim()) }
            val bad = ready.firstOrNull { (it.act == ACT_URL || it.act == ACT_COPY) && it.value.isBlank() }
            when {
                text.isEmpty() -> body.error = t(ctx, "Напишите текст", "Write the text")
                ready.isEmpty() -> Toast.makeText(ctx, t(ctx, "Добавьте хотя бы одну кнопку", "Add at least one button"), Toast.LENGTH_SHORT).show()
                bad != null -> Toast.makeText(ctx, t(ctx, "Заполните значение у кнопки «${bad.label}»", "Fill in the value of “${bad.label}”"), Toast.LENGTH_LONG).show()
                else -> {
                    val out = wrap(text, rows(ready, perRow), base)
                    if (out == null) {
                        Toast.makeText(ctx, t(ctx, "Слишком много текста в кнопках - сократите подписи или ссылки", "Too much text in buttons - shorten labels or links"), Toast.LENGTH_LONG).show()
                    } else {
                        dialog.dismiss()
                        onDone(out)
                    }
                }
            }
        }
    }

    private fun simpleWatcher(set: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = set(s?.toString().orEmpty())
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @Suppress("UNUSED_PARAMETER")
    private fun t(ctx: Context, ru: String, en: String): String = com.stoyko.smessenger.ai.t(ru, en)
}
