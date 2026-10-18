// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.MsgButtons
import org.json.JSONObject

/**
 * Приветствие для новых участников группы или канала.
 *
 * Администратор задаёт приветствие: крупная анимированная иллюстрация (эмодзи), текст с
 * разметкой и цветные кнопки ([MsgButtons]: правила, сайт, «Представиться» и т. п.).
 * Его видят ТОЛЬКО новые участники - отдельной карточкой при входе, в общую ленту оно не
 * попадает и чат не засоряет. «Новый» - вступил не раньше, чем [W.days] дней назад; карточка
 * показывается один раз на версию приветствия.
 *
 * Доставка: в группах ядра новый участник не получает старую историю, поэтому приветствие
 * хранится как настройка группы и уходит отдельным пакетом - всем при изменении и каждому
 * новому участнику при входе (от администраторов). Принимается только от администраторов.
 */
object Welcome {
    /** [message] - текст приветствия вместе со служебным заголовком кнопок ([ChannelPost]). */
    data class W(
        val message: String = "",
        val emoji: String = "",
        val days: Int = 7,
        val version: Long = 0,
    ) {
        val isOn get() = message.isNotBlank()
    }

    const val MAX_TEXT = 500
    const val MAX_PACKET_JSON = 4000

    private val open = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("group_welcome", Context.MODE_PRIVATE)

    fun get(ctx: Context, chatId: String): W = runCatching {
        fromJson(JSONObject(prefs(ctx).getString(chatId, "{}")!!))
    }.getOrDefault(W())

    fun toJson(w: W): String = JSONObject()
        .put("m", w.message)
        .put("e", w.emoji.take(16))
        .put("d", w.days.coerceIn(1, 90))
        .put("v", w.version)
        .toString()

    private fun fromJson(o: JSONObject) = W(
        o.optString("m"),
        o.optString("e").take(16),
        o.optInt("d", 7).coerceIn(1, 90),
        o.optLong("v", 0),
    )

    /** Изменить у себя (администратор) - возвращает JSON для рассылки. */
    fun update(ctx: Context, chatId: String, w: W): String {
        val s = w.copy(version = System.currentTimeMillis())
        prefs(ctx).edit().putString(chatId, toJson(s)).apply()
        changed()
        return toJson(s)
    }

    /** Пришло от администратора: применяем, если новее. */
    fun onRemote(ctx: Context, chatId: String, json: String) {
        if (json.length > MAX_PACKET_JSON) return
        val w = runCatching { fromJson(JSONObject(json)) }.getOrNull() ?: return
        if (w.version <= get(ctx, chatId).version) return
        prefs(ctx).edit().putString(chatId, toJson(w)).apply()
        changed()
    }

    // ------------------------------------------------------------------ кто «новый»

    /** Я вступил в группу (по приглашению или ID) - запоминаем время (только первый раз). */
    fun markJoined(ctx: Context, chatId: String) {
        val p = prefs(ctx)
        if (!p.contains("j_$chatId")) p.edit().putLong("j_$chatId", System.currentTimeMillis()).apply()
    }

    private fun joinedAt(ctx: Context, chatId: String): Long = prefs(ctx).getLong("j_$chatId", 0L)

    /** Показать приветствие этому участнику сейчас? */
    fun shouldShow(ctx: Context, chatId: String, isAdmin: Boolean): Boolean {
        if (isAdmin) return false
        val w = get(ctx, chatId)
        if (!w.isOn) return false
        val joined = joinedAt(ctx, chatId)
        // Время вступления неизвестно (группа была до обновления) - участник не новый.
        if (joined <= 0L) return false
        if (System.currentTimeMillis() - joined > w.days * 24L * 3600_000L) return false
        return prefs(ctx).getLong("seen_$chatId", 0L) < w.version
    }

    fun markSeen(ctx: Context, chatId: String) {
        prefs(ctx).edit().putLong("seen_$chatId", get(ctx, chatId).version).apply()
    }

    // ------------------------------------------------------------------ сжатие пакета

    /** JSON → байты пакета (zlib, если большой). Первый байт тела: '{' - обычный, 0x78 - сжатый. */
    fun pack(json: String): ByteArray {
        val raw = json.toByteArray(Charsets.UTF_8)
        if (raw.size <= 1100) return raw
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(raw.size + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    fun unpack(data: ByteArray, offset: Int): String? {
        if (data.size <= offset) return null
        if (data[offset] != 0x78.toByte()) return String(data, offset, data.size - offset, Charsets.UTF_8)
        return runCatching {
            val inf = java.util.zip.Inflater()
            inf.setInput(data, offset, data.size - offset)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(2048)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_PACKET_JSON * 4) break
            }
            inf.end()
            out.toString("UTF-8")
        }.getOrNull()
    }

    // ------------------------------------------------------------------ карточка

    /**
     * Карточка приветствия для нового участника: иллюстрация, текст, кнопки, «Понятно».
     * [host] - действия кнопок (быстрый ответ в группу, каталог).
     */
    fun show(ctx: Context, chatId: String, groupName: String, host: MsgButtons.Host?, preview: Boolean = false) {
        val w = get(ctx, chatId)
        if (!w.isOn) return
        // Одна карточка за раз (приветствие могло прийти, пока экран открывался).
        if (!preview && !open.add(chatId)) return
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        if (w.emoji.isNotBlank()) {
            val big = TextView(ctx).apply {
                text = w.emoji
                textSize = 64f
                gravity = Gravity.CENTER
            }
            root.addView(big, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Живая иллюстрация: анимированный эмодзи (если есть готовая анимация) поверх статичного.
            runCatching {
                val anim = android.widget.ImageView(ctx)
                root.addView(anim, 0, LinearLayout.LayoutParams(dp(120), dp(120)))
                anim.visibility = View.GONE
                val parts = com.stoyko.smessenger.chat.AnimatedEmoji.split(w.emoji)
                if (parts.isNotEmpty()) {
                    com.stoyko.smessenger.chat.AnimatedEmoji.playRow(anim, parts.take(3), loop = 2) {
                        anim.visibility = View.VISIBLE
                        big.visibility = View.GONE
                    }
                }
            }
            if (com.stoyko.smessenger.ui.Kinetics.enabled) {
                big.scaleX = 0.3f
                big.scaleY = 0.3f
                big.animate().scaleX(1f).scaleY(1f).setDuration(520)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
            }
        }
        root.addView(
            TextView(ctx).apply {
                text = if (preview) ctx.getString(R.string.welcome_preview_title) else ctx.getString(R.string.welcome_title, groupName.ifBlank { "…" })
                textSize = 19f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, dp(8), 0, dp(6))
            },
        )
        val body = TextView(ctx).apply {
            text = com.stoyko.smessenger.chat.Markup.render(w.message, 0xCCFFFFFF.toInt())
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x1A808080)
            }
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        com.stoyko.smessenger.chat.RichSpans.attach(body) { com.stoyko.smessenger.chat.Markup.render(w.message, 0xCCFFFFFF.toInt()) }
        // Кнопки приветствия (правила, сайт, «Представиться»…).
        MsgButtons.bind(root, body, w.message, false, host)
        if (preview) {
            root.addView(
                TextView(ctx).apply {
                    text = ctx.getString(R.string.welcome_preview_hint, w.days)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    alpha = 0.7f
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }
        AlertDialog.Builder(ctx)
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(R.string.welcome_ok, null)
            .setOnDismissListener {
                if (!preview) {
                    markSeen(ctx, chatId)
                    open.remove(chatId)
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ редактор

    /**
     * Редактор приветствия (администратор): иллюстрация-эмодзи, текст, сколько дней считать
     * участника новым, кнопки. [onSave] - готовое приветствие (W с пустой версией) или null -
     * «Выключить».
     */
    fun editor(ctx: Context, chatId: String, initialText: String, onSave: (W?) -> Unit) {
        val cur = get(ctx, chatId)
        val (_, curText) = ChannelPost.parse(cur.message)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(
            TextView(ctx).apply {
                setText(R.string.welcome_editor_hint)
                textSize = 13f
                alpha = 0.75f
            },
        )
        val emoji = EditText(ctx).apply {
            setHint(R.string.welcome_emoji_hint)
            setText(cur.emoji.ifBlank { "👋" })
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(16))
            textSize = 24f
            gravity = Gravity.CENTER
        }
        box.addView(emoji)
        val text = EditText(ctx).apply {
            setHint(R.string.welcome_text_hint)
            setText(initialText.ifBlank { curText })
            minLines = 3
            maxLines = 10
            filters = arrayOf(InputFilter.LengthFilter(MAX_TEXT))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(text)
        val daysOpts = intArrayOf(1, 3, 7, 14, 30)
        var days = cur.days.takeIf { it in daysOpts } ?: 7
        val daysRow = TextView(ctx).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(4))
        }
        fun showDays() {
            daysRow.text = ctx.getString(R.string.welcome_days, days)
        }
        showDays()
        daysRow.setOnClickListener {
            days = daysOpts[(daysOpts.indexOf(days) + 1) % daysOpts.size]
            showDays()
        }
        box.addView(daysRow)
        val withButtons = CheckBox(ctx).apply {
            setText(R.string.welcome_with_buttons)
            isChecked = MsgButtons.of(cur.message).isNotEmpty()
        }
        box.addView(withButtons)

        val b = AlertDialog.Builder(ctx)
            .setTitle(R.string.welcome_menu)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (cur.isOn) b.setNeutralButton(R.string.welcome_off) { _, _ -> onSave(null) }
        val dialog = b.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = text.text.toString().trim()
            if (t.isEmpty()) {
                text.error = ctx.getString(R.string.welcome_text_hint)
                return@setOnClickListener
            }
            val e = emoji.text.toString().trim()
            dialog.dismiss()
            if (withButtons.isChecked) {
                // Второй шаг - кнопки (заголовок с кнопками хранится вместе с текстом).
                MsgButtons.editor(ctx, t, games = false, shop = false, base = ChannelPost.Meta(welcome = true)) { out ->
                    onSave(W(out, e, days))
                }
            } else {
                onSave(W(t, e, days))
            }
        }
    }

    /** Подпись строки в «Информации» группы. */
    fun summary(ctx: Context, chatId: String): String {
        val w = get(ctx, chatId)
        if (!w.isOn) return ctx.getString(R.string.gs_off)
        return (w.emoji + " " + ChannelPost.strip(w.message).replace('\n', ' ')).trim().take(24)
    }
}
