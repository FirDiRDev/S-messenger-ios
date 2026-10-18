// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Оформление постов и сообщений (чаты, группы, каналы).
 *
 * МОДЕЛЬ - JSON-документ: корень - массив блоков (см. docs/rich-text-schema.md):
 *   paragraph { text }               - абзац (внутри - строчная разметка, см. ниже)
 *   header { level: 1|2, text }      - заголовок
 *   bullet-list { items: [text] }    - маркированный список
 *   table { rows: [[cell]], header } - таблица (первая строка - шапка, если header = true)
 *   tg-spoiler { text }              - скрытый текст (искры, растворяются по нажатию)
 *   collapsible-quote { text, collapsed } - сворачиваемая цитата
 *   quote { text }                   - обычная цитата
 *
 * ПЕРЕДАЧА - компактная построчная разметка (её видят и старые версии как обычный текст, и она
 * не ломается, если ядро делит длинное сообщение на части):
 *   # Заголовок      ## Подзаголовок      • пункт (или «- пункт»)
 *   | A | B |  (строки таблицы подряд; строка «|---|» - разделитель шапки)
 *   > цитата         >> сворачиваемая цитата (строки подряд)      ||скрытый текст||
 * Строчная разметка: **жирный**, __курсив__, ~~зачёркнутый~~, ||спойлер||, ⁣😀⁣ - живой эмодзи.
 * [toJson]/[fromJson] и [toMarkup]/[parse] переводят одно в другое без потерь.
 */
object RichText {
    private const val INV = "⁣"

    sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Header(val level: Int, val text: String) : Block()
        data class BulletList(val items: List<String>) : Block()
        data class Table(val rows: List<List<String>>, val header: Boolean) : Block()
        data class Spoiler(val text: String) : Block()
        data class CollapsibleQuote(val text: String, val collapsed: Boolean = true) : Block()
        data class Quote(val text: String) : Block()
    }

    // ------------------------------------------------------------------ JSON

    fun toJson(blocks: List<Block>): JSONArray {
        val a = JSONArray()
        for (b in blocks) {
            a.put(
                when (b) {
                    is Block.Paragraph -> JSONObject().put("type", "paragraph").put("text", b.text)
                    is Block.Header -> JSONObject().put("type", "header").put("level", b.level).put("text", b.text)
                    is Block.BulletList -> JSONObject().put("type", "bullet-list").put("items", JSONArray(b.items))
                    is Block.Table -> JSONObject().put("type", "table").put("header", b.header)
                        .put("rows", JSONArray().apply { b.rows.forEach { put(JSONArray(it)) } })
                    is Block.Spoiler -> JSONObject().put("type", "tg-spoiler").put("text", b.text)
                    is Block.CollapsibleQuote -> JSONObject().put("type", "collapsible-quote").put("text", b.text).put("collapsed", b.collapsed)
                    is Block.Quote -> JSONObject().put("type", "quote").put("text", b.text)
                },
            )
        }
        return a
    }

    fun fromJson(a: JSONArray): List<Block> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        when (o.optString("type")) {
            "paragraph" -> Block.Paragraph(o.optString("text"))
            "header" -> Block.Header(o.optInt("level", 1).coerceIn(1, 2), o.optString("text"))
            "bullet-list" -> o.optJSONArray("items")?.let { arr -> Block.BulletList((0 until arr.length()).map { arr.optString(it) }) }
            "table" -> o.optJSONArray("rows")?.let { rows ->
                Block.Table(
                    (0 until rows.length()).map { r -> rows.optJSONArray(r)?.let { c -> (0 until c.length()).map { c.optString(it) } }.orEmpty() },
                    o.optBoolean("header", true),
                )
            }
            "tg-spoiler" -> Block.Spoiler(o.optString("text"))
            "collapsible-quote" -> Block.CollapsibleQuote(o.optString("text"), o.optBoolean("collapsed", true))
            "quote" -> Block.Quote(o.optString("text"))
            else -> null
        }
    }

    /** Пример документа со всеми типами блоков (показывается в редакторе, кнопка «{ } JSON»). */
    val SAMPLE: List<Block> = listOf(
        Block.Header(1, "Итоги недели"),
        Block.Paragraph("Привет! Это **жирный**, __курсив__ и ~~зачёркнутый~~ текст, а ещё ||спойлер|| прямо в строке ${INV}🔥$INV."),
        Block.BulletList(listOf("Вышла версия 4.8", "Опросы и кнопки в сообщениях", "Файлы прямо в тексте")),
        Block.Table(listOf(listOf("Платформа", "Загрузки"), listOf("Android", "12 400"), listOf("ПК", "3 100")), header = true),
        Block.Spoiler("Секрет: в следующей версии - живые анимации"),
        Block.CollapsibleQuote("Длинная цитата из обсуждения.\nВторая строка видна после раскрытия.\nИ третья."),
    )

    // ------------------------------------------------------------------ разметка ↔ блоки

    fun toMarkup(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is Block.Paragraph -> b.text
            is Block.Header -> (if (b.level >= 2) "## " else "# ") + b.text.replace('\n', ' ')
            is Block.BulletList -> b.items.joinToString("\n") { "• " + it.replace('\n', ' ') }
            is Block.Table -> b.rows.mapIndexed { i, r ->
                val line = "| " + r.joinToString(" | ") { it.replace('|', '/').replace('\n', ' ') } + " |"
                if (i == 0 && b.header && b.rows.size > 1) line + "\n|" + r.joinToString("|") { "---" } + "|" else line
            }.joinToString("\n")
            is Block.Spoiler -> "||" + b.text + "||"
            is Block.CollapsibleQuote -> b.text.lines().joinToString("\n") { ">> $it" }
            is Block.Quote -> b.text.lines().joinToString("\n") { "> $it" }
        }
    }

    private val TABLE_SEP = Regex("^\\|\\s*:?-{2,}.*$")

    fun parse(text: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = text.split('\n')
        var i = 0
        val para = ArrayList<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(para.joinToString("\n"))
            para.clear()
        }
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trimStart()
            when {
                t.startsWith("## ") -> { flush(); out += Block.Header(2, t.substring(3).trim()); i++ }
                t.startsWith("# ") -> { flush(); out += Block.Header(1, t.substring(2).trim()); i++ }
                isBullet(t) -> {
                    flush()
                    val items = ArrayList<String>()
                    while (i < lines.size && isBullet(lines[i].trimStart())) { items += lines[i].trimStart().substring(2).trim(); i++ }
                    out += Block.BulletList(items)
                }
                isRow(t) -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    var header = false
                    while (i < lines.size && isRow(lines[i].trim())) {
                        val r = lines[i].trim()
                        if (TABLE_SEP.matches(r)) {
                            if (rows.size == 1) header = true
                        } else {
                            rows += r.trim('|').split('|').map { it.trim() }
                        }
                        i++
                    }
                    if (rows.size == 1 && rows[0].size == 1) para += lines[i - 1] else out += Block.Table(rows, header)
                }
                t.startsWith(">>") -> {
                    flush()
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">>")) { q += lines[i].trimStart().removePrefix(">>").removePrefix(" "); i++ }
                    out += Block.CollapsibleQuote(q.joinToString("\n"))
                }
                t.startsWith(">") -> {
                    flush()
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().let { it.startsWith(">") && !it.startsWith(">>") }) {
                        q += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++
                    }
                    out += Block.Quote(q.joinToString("\n"))
                }
                t.startsWith("||") && t.endsWith("||") && t.length > 4 && t.indexOf("||", 2) == t.length - 2 -> {
                    flush(); out += Block.Spoiler(t.substring(2, t.length - 2)); i++
                }
                else -> { para += l; i++ }
            }
        }
        flush()
        return out
    }

    private fun isBullet(t: String) = (t.startsWith("• ") || t.startsWith("- ") || (t.startsWith("* ") && !t.startsWith("**"))) && t.length > 2
    private fun isRow(t: String) = t.length >= 3 && t.startsWith("|") && t.endsWith("|") && !t.startsWith("||")

    /** Есть ли в тексте оформление (чтобы не разбирать обычные сообщения). */
    fun looksRich(text: String): Boolean =
        text.contains("**") || text.contains("__") || text.contains("~~") || text.contains("||") ||
            text.contains("$INV") || text.lineSequence().any { l ->
                val t = l.trimStart()
                t.startsWith("# ") || t.startsWith("## ") || t.startsWith(">") || isBullet(t) || isRow(t.trim())
            }

    // ------------------------------------------------------------------ отрисовка

    /**
     * Текст с оформлением → Spannable. Спойлеры, сворачиваемые цитаты и живые эмодзи «оживают»
     * после [RichSpans.attach] к TextView. [expanded] - ключи раскрытых цитат.
     */
    fun render(text: String, key: String = text.hashCode().toString()): CharSequence {
        if (!looksRich(text)) return text
        val sb = SpannableStringBuilder()
        val blocks = parse(text)
        blocks.forEachIndexed { idx, b ->
            if (sb.isNotEmpty()) sb.append('\n')
            when (b) {
                is Block.Paragraph -> inline(sb, b.text)
                is Block.Header -> {
                    val s = sb.length
                    inline(sb, b.text)
                    sb.setSpan(RelativeSizeSpan(if (b.level == 1) 1.35f else 1.18f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.BulletList -> b.items.forEachIndexed { j, it ->
                    if (j > 0) sb.append('\n')
                    val s = sb.length
                    inline(sb, it)
                    sb.setSpan(BulletSpan(14), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.Table -> table(sb, b)
                is Block.Spoiler -> {
                    val s = sb.length
                    sb.append(b.text)
                    RichSpans.spoiler(sb, s, sb.length)
                }
                is Block.Quote -> {
                    val s = sb.length
                    inline(sb, b.text)
                    sb.setSpan(QuoteSpan(0xCCFFFFFF.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.CollapsibleQuote -> RichSpans.collapsible(sb, "$key:$idx", b.text) { part -> inline(sb, part) }
            }
        }
        return sb
    }

    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|\\|\\|(.+?)\\|\\||$INV([^$INV\\s\\p{L}\\p{N}|]{1,16})$INV")

    /** Строчная разметка: жирный, курсив, зачёркнутый, спойлер, живой эмодзи (вложенность - один уровень). */
    fun inline(sb: SpannableStringBuilder, text: String) {
        var last = 0
        for (m in INLINE.findAll(text)) {
            sb.append(text, last, m.range.first)
            val s = sb.length
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> { inline(sb, g[1]); sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[2].isNotEmpty() -> { inline(sb, g[2]); sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[3].isNotEmpty() -> { inline(sb, g[3]); sb.setSpan(StrikethroughSpan(), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                g[4].isNotEmpty() -> { sb.append(g[4]); RichSpans.spoiler(sb, s, sb.length) }
                g[5].isNotEmpty() -> { sb.append(g[5]); RichSpans.emoji(sb, s, sb.length, g[5]) }
            }
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
    }

    /** Таблица моноширинной сеткой (рамка из линий), шапка - жирная. */
    private fun table(sb: SpannableStringBuilder, t: Block.Table) {
        val cols = t.rows.maxOfOrNull { it.size } ?: return
        val rows = t.rows.map { r -> List(cols) { r.getOrElse(it) { "" } } }
        val w = IntArray(cols) { c -> rows.maxOf { it[c].length }.coerceIn(1, 18) }
        fun cell(s: String, c: Int) = (if (s.length > w[c]) s.take(w[c] - 1) + "…" else s).padEnd(w[c])
        fun border(l: Char, m: Char, r: Char) = l + w.joinToString(m.toString()) { "─".repeat(it + 2) } + r
        val start = sb.length
        sb.append(border('┌', '┬', '┐')).append('\n')
        rows.forEachIndexed { i, r ->
            val rs = sb.length
            sb.append("│ ").append(r.mapIndexed { c, s -> cell(s, c) }.joinToString(" │ ")).append(" │")
            if (i == 0 && t.header) sb.setSpan(StyleSpan(Typeface.BOLD), rs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
            if (i == 0 && t.header && rows.size > 1) sb.append(border('├', '┼', '┤')).append('\n')
        }
        sb.append(border('└', '┴', '┘'))
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.86f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Текст без разметки - для уведомлений, цитат, списка чатов (спойлер - «▒▒▒»). */
    fun plain(text: String): String {
        if (!looksRich(text)) return text
        return text.lineSequence().joinToString("\n") { l ->
            val t = l.trimStart()
            when {
                t.startsWith("## ") -> t.substring(3)
                t.startsWith("# ") -> t.substring(2)
                t.startsWith(">> ") -> t.substring(3)
                t.startsWith("> ") -> t.substring(2)
                isBullet(t) -> "• " + t.substring(2)
                TABLE_SEP.matches(t.trim()) -> ""
                else -> l
            }
        }.replace(Regex("\\|\\|(.+?)\\|\\|"), "▒▒▒")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("~~(.+?)~~"), "$1")
            .replace(INV, "")
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }
}
