// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Редактор оформления постов и сообщений (личные чаты, группы, каналы).
 *
 * Панель блоков: заголовок, подзаголовок, список, таблица, спойлер, цитата, сворачиваемая
 * цитата; строчное: жирный, курсив, зачёркнутый; живые эмодзи прямо в строке. Ниже - живой
 * предпросмотр (как увидит собеседник: спойлер с искрами, раскрывающаяся цитата, таблица).
 * «{ } JSON» - документ в виде JSON-схемы блоков ([RichText.toJson]) - можно скопировать.
 * В поле работает меню над выделенным текстом ([SelectionMenu]).
 */
object RichEditor {
    private const val INV = "⁣"

    private val EMOJI = listOf(
        "😀", "😂", "🥰", "😎", "🤔", "😭", "😡", "🥳", "👍", "👎", "🙏", "👏",
        "🔥", "❤️", "💯", "✨", "🎉", "🚀", "⭐", "⚡", "🌈", "🍀", "☕", "🎁",
    )

    fun show(ctx: Context, initial: String, sendLabel: String? = null, onDone: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        val edit = EditText(ctx).apply {
            setText(initial)
            hint = t("Текст. Выделите слово - появится меню оформления", "Text. Select a word to get the formatting menu")
            minLines = 4
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val toolbar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun tool(label: String, action: () -> Unit) {
            toolbar.addView(
                TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0xFF4B3CCB.toInt())
                    }
                    setOnClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        action()
                        edit.requestFocus()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) },
            )
        }

        fun lineStart(pos: Int): Int = edit.text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0 || pos == 0) 0 else it + 1 }

        /** Добавить префикс к строкам выделения (или текущей строке). */
        fun prefixLines(prefix: String) {
            val s = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
            val e = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
            var i = lineStart(s)
            val text = edit.text
            var end = e
            while (i <= end && i <= text.length) {
                text.insert(i, prefix)
                end += prefix.length
                val nl = text.indexOf('\n', i)
                if (nl < 0 || nl >= end) break
                i = nl + 1
            }
        }

        fun wrapSel(m: String, placeholder: String) {
            val s = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
            val e = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
            if (e > s) {
                edit.text.insert(e, m)
                edit.text.insert(s, m)
            } else {
                edit.text.insert(s, m + placeholder + m)
                edit.setSelection(s + m.length, s + m.length + placeholder.length)
            }
        }

        fun insertBlock(block: String) {
            val s = edit.selectionStart.coerceAtLeast(0)
            val needNl = s > 0 && edit.text[s - 1] != '\n'
            edit.text.insert(s, (if (needNl) "\n" else "") + block + "\n")
        }

        tool("H1") { prefixLines("# ") }
        tool("H2") { prefixLines("## ") }
        tool("• " + t("Список", "List")) { prefixLines("• ") }
        tool("▦ " + t("Таблица", "Table")) {
            insertBlock(
                t("| Колонка 1 | Колонка 2 |\n|---|---|\n| текст | текст |", "| Column 1 | Column 2 |\n|---|---|\n| text | text |"),
            )
        }
        tool("▒ " + t("Спойлер", "Spoiler")) { wrapSel("||", t("скрытый текст", "hidden text")) }
        tool("❝ " + t("Цитата", "Quote")) { prefixLines("> ") }
        tool("❝▾ " + t("Сворачиваемая", "Collapsible")) { prefixLines(">> ") }
        tool("Ж") { wrapSel("**", t("жирный", "bold")) }
        tool("К") { wrapSel("__", t("курсив", "italic")) }
        tool("S̶") { wrapSel("~~", t("текст", "text")) }
        tool("😀 " + t("Живой эмодзи", "Live emoji")) { pickEmoji(ctx) { e -> edit.text.insert(edit.selectionStart.coerceAtLeast(0), INV + e + INV) } }
        tool("{ } JSON") { showJson(ctx, edit.text.toString()) }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolbar)
        })
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(
            TextView(ctx).apply {
                text = t("Предпросмотр", "Preview")
                textSize = 12f
                alpha = 0.7f
                setPadding(0, dp(8), 0, dp(4))
            },
        )
        val preview = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                colors = intArrayOf(0xFF7B6CF6.toInt(), 0xFF4B3CCB.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun refresh() {
            val src = edit.text.toString()
            val rebuild = { RichText.render(src, "editor") }
            preview.text = rebuild()
            RichSpans.attach(preview, rebuild)
        }
        refresh()
        val refreshLater = Runnable { refresh() }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                preview.removeCallbacks(refreshLater)
                preview.postDelayed(refreshLater, 180)
            }
        })
        SelectionMenu.install(edit)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(t("✨ Оформление", "✨ Formatting"))
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(sendLabel ?: t("Отправить", "Send"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = edit.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onDone(text)
        }
    }

    private fun pickEmoji(ctx: Context, onPick: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val grid = GridLayout(ctx).apply {
            columnCount = 6
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        }
        var dlg: AlertDialog? = null
        for (e in EMOJI) {
            grid.addView(
                TextView(ctx).apply {
                    text = e
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                    setOnClickListener {
                        onPick(e)
                        dlg?.dismiss()
                    }
                },
            )
        }
        dlg = AlertDialog.Builder(ctx)
            .setTitle(t("Живой эмодзи в строке", "Live emoji in the line"))
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Документ JSON-схемой: текущий текст или пример со всеми блоками. */
    private fun showJson(ctx: Context, text: String) {
        val blocks = if (text.isBlank()) RichText.SAMPLE else RichText.parse(text)
        val json = RichText.toJson(blocks).toString(2)
        val tv = TextView(ctx).apply {
            this.text = json
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val p = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (text.isBlank()) t("Пример документа (JSON)", "Sample document (JSON)") else t("Документ (JSON)", "Document (JSON)"))
            .setView(ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(t("Копировать", "Copy")) { _, _ ->
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("json", json))
                Toast.makeText(ctx, t("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)
}
