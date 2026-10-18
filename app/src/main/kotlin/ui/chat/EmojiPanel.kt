// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R

/**
 * Все эмодзи по разделам: недавние, смайлы и эмоции, люди и жесты, животные и
 * природа, еда, путешествия и транспорт, занятия, предметы, символы, флаги. Сверху - поиск
 * (по-русски и по-английски), снизу - вкладки разделов с плавающей «таблеткой».
 * Показываются только эмодзи, которые умеет рисовать этот Android (без «квадратиков»).
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val onPick: (String) -> Unit) : LinearLayout(context) {
    private class Item(val emoji: String, val search: String)
    private class Section(val icon: String, val title: Int, val items: List<Item>)

    private sealed class Row {
        class Header(val title: String, val section: Int) : Row()
        class Emojis(val items: List<String>, val section: Int) : Row()
    }

    private val d = resources.displayMetrics.density
    private val cell = (44 * d).toInt()
    private val textColor = ContextCompat.getColor(context, R.color.textPrimary)
    private val subColor = ContextCompat.getColor(context, R.color.textSecondary)
    private val accent = ContextCompat.getColor(context, R.color.colorPrimary)

    private var sections: List<Section> = emptyList()
    private var rows: List<Row> = emptyList()
    private var columns = 8
    private var query = ""
    private var selected = 0
    private val tabs = ArrayList<TextView>()

    private val list = ListView(context).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        selector = android.graphics.drawable.ColorDrawable(0)
        clipToPadding = false
        setPadding((4 * d).toInt(), 0, (4 * d).toInt(), (4 * d).toInt())
    }

    // Плавающая «таблетка» под выбранной вкладкой (Tab Pill Glide).
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E5B4BDB }
    private val pill = RectF()
    private val tabRow = object : LinearLayout(context) {
        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRoundRect(pill, 14 * d, 14 * d, pillPaint)
            super.onDraw(canvas)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val r = rows[position]) {
                is Row.Header -> {
                    val tv = (convertView as? TextView) ?: TextView(context).apply {
                        textSize = 13f
                        setTextColor(subColor)
                        paint.isFakeBoldText = true
                        setPadding((10 * d).toInt(), (10 * d).toInt(), 0, (4 * d).toInt())
                    }
                    tv.text = r.title
                    tv
                }
                is Row.Emojis -> {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply { orientation = HORIZONTAL }
                    while (row.childCount < columns) {
                        row.addView(
                            TextView(context).apply {
                                textSize = 27f
                                gravity = Gravity.CENTER
                                setOnClickListener { v ->
                                    val e = (v as TextView).text?.toString().orEmpty()
                                    if (e.isEmpty()) return@setOnClickListener
                                    // Нажатие: эмодзи «подпрыгивает».
                                    v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(90).withEndAction {
                                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                                    }.start()
                                    onPick(e)
                                }
                            },
                            LayoutParams(0, cell, 1f),
                        )
                    }
                    for (i in 0 until row.childCount) {
                        val tv = row.getChildAt(i) as TextView
                        val e = r.items.getOrNull(i)
                        tv.visibility = if (i < columns) View.VISIBLE else View.GONE
                        tv.text = e.orEmpty()
                        tv.isClickable = e != null
                    }
                    row
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        val search = EditText(context).apply {
            setHint(R.string.emoji_search_hint)
            textSize = 15f
            setSingleLine()
            setTextColor(textColor)
            setHintTextColor(subColor)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(ContextCompat.getColor(context, R.color.dialogItem))
            }
            setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: Editable?) {
                    query = e?.toString()?.trim()?.lowercase().orEmpty()
                    rebuild()
                    list.setSelection(0)
                }
            })
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        tabRow.orientation = HORIZONTAL
        tabRow.setPadding((4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }, LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt()))

        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                if (query.isNotEmpty()) return
                val r = rows.getOrNull(first) ?: return
                val sec = when (r) {
                    is Row.Header -> r.section
                    is Row.Emojis -> r.section
                }
                if (sec != selected) select(sec, scroll = false)
            }
        })
        list.adapter = adapter
        load()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = ((w - 8 * d) / cell).toInt().coerceIn(6, 12)
        if (c != columns) {
            columns = c
            post { rebuild() }
        }
    }

    /** Эмодзи из asset-файла (группа, символ, версия, названия). Фильтр по версии Android. */
    private fun load() {
        val maxVersion = when {
            Build.VERSION.SDK_INT >= 34 -> 15.0
            Build.VERSION.SDK_INT >= 33 -> 14.0
            Build.VERSION.SDK_INT >= 31 -> 13.1
            Build.VERSION.SDK_INT >= 30 -> 13.0
            Build.VERSION.SDK_INT >= 29 -> 12.0
            Build.VERSION.SDK_INT >= 28 -> 11.0
            Build.VERSION.SDK_INT >= 26 -> 5.0
            Build.VERSION.SDK_INT >= 24 -> 4.0
            else -> 2.0
        }
        val groups = HashMap<Int, MutableList<Item>>()
        runCatching {
            context.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size < 5) return@forEach
                    val v = p[2].toDoubleOrNull() ?: 1.0
                    if (v > maxVersion) return@forEach
                    val g = p[0].toIntOrNull() ?: return@forEach
                    groups.getOrPut(g) { ArrayList() } += Item(p[1], p[3] + " " + p[4])
                }
            }
        }
        val recent = RecentEmoji.list(context).map { Item(it, "") }
        val defs = listOf(
            Triple(0, "😀", R.string.emoji_g_smileys),
            Triple(1, "👋", R.string.emoji_g_people),
            Triple(3, "🐻", R.string.emoji_g_animals),
            Triple(4, "🍔", R.string.emoji_g_food),
            Triple(5, "✈️", R.string.emoji_g_travel),
            Triple(6, "⚽", R.string.emoji_g_activities),
            Triple(7, "💡", R.string.emoji_g_objects),
            Triple(8, "🔣", R.string.emoji_g_symbols),
            Triple(9, "🏳️", R.string.emoji_g_flags),
        )
        val list = ArrayList<Section>()
        if (recent.isNotEmpty()) list += Section("🕘", R.string.emoji_recent, recent)
        defs.forEach { (g, icon, title) -> groups[g]?.takeIf { it.isNotEmpty() }?.let { list += Section(icon, title, it) } }
        sections = list
        buildTabs()
        rebuild()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        tabs.clear()
        sections.forEachIndexed { i, s ->
            val tv = TextView(context).apply {
                text = s.icon
                textSize = 21f
                gravity = Gravity.CENTER
                alpha = 0.55f
                setOnClickListener { select(i, scroll = true) }
            }
            tabs += tv
            tabRow.addView(tv, LayoutParams((46 * d).toInt(), LayoutParams.MATCH_PARENT))
        }
        post { select(0, scroll = false, animate = false) }
    }

    private fun select(index: Int, scroll: Boolean, animate: Boolean = true) {
        selected = index
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.animate().alpha(if (on) 1f else 0.55f).scaleX(if (on) 1.15f else 1f).scaleY(if (on) 1.15f else 1f)
                .setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
        }
        val tab = tabs.getOrNull(index) ?: return
        val target = RectF(tab.left + 3 * d, 4 * d, tab.right - 3 * d, tabRow.height - 4 * d)
        if (!animate || pill.isEmpty) {
            pill.set(target)
            tabRow.invalidate()
        } else {
            val from = RectF(pill)
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = OvershootInterpolator(1.3f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    pill.set(
                        from.left + (target.left - from.left) * f, target.top,
                        from.right + (target.right - from.right) * f, target.bottom,
                    )
                    tabRow.invalidate()
                }
                start()
            }
        }
        (tabRow.parent as? HorizontalScrollView)?.smoothScrollTo((tab.left - tabRow.width / 3).coerceAtLeast(0), 0)
        if (scroll) {
            val pos = rows.indexOfFirst { it is Row.Header && it.section == index }
            if (pos >= 0) list.setSelectionFromTop(pos, 0)
        }
    }

    private fun rebuild() {
        val out = ArrayList<Row>()
        if (query.isNotEmpty()) {
            val found = sections.filter { it.title != R.string.emoji_recent }.flatMap { it.items }
                .filter { it.search.contains(query) }.map { it.emoji }.distinct()
            out += Row.Header(context.getString(if (found.isEmpty()) R.string.emoji_search_empty else R.string.emoji_search_results), 0)
            found.chunked(columns).forEach { out += Row.Emojis(it, 0) }
        } else {
            sections.forEachIndexed { i, s ->
                out += Row.Header(context.getString(s.title), i)
                s.items.map { it.emoji }.chunked(columns).forEach { out += Row.Emojis(it, i) }
            }
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    /** Мягкое появление панели. */
    fun appear() {
        alpha = 0f
        translationY = 12 * d
        animate().alpha(1f).translationY(0f).setDuration(220).start()
    }
}
