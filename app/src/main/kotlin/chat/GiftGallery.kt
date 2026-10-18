// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.Kinetics

/**
 * Подарки в моём профиле:
 *  - [fillShowcase] - витрина в профиле: только подарки, которые я сам выбрал (до 12);
 *  - [open] - галерея «Мои подарки»: все принятые подарки. Окно плавно «выходит» из кнопки,
 *    а подарки красиво и по очереди выстраиваются в ряды. Нажатие - подарок крупно и кнопка
 *    «Поставить в профиль / Убрать из профиля»; удержание - то же одним жестом.
 */
object GiftGallery {

    /** Витрина в моём профиле. [onOpenGallery] - открыть галерею «Мои подарки» из нажатой кнопки. */
    fun fillShowcase(box: LinearLayout, onOpenGallery: (View) -> Unit) {
        val ctx = box.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        box.removeAllViews()
        val all = Gifts.received(ctx)
        if (all.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val shown = Gifts.showcase(ctx)
            .mapNotNull { g -> GiftCatalog.resolve(g.id, g.emoji)?.let { it to g } }
        val accent = ContextCompat.getColor(ctx, R.color.settingsAccent)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        // Заголовок: «Витрина подарков 5/12» и ссылка «Все подарки (N) ›».
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(12), dp(6))
        }
        head.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.gifts_showcase_title, shown.size, Gifts.SHOWCASE_MAX)
                textSize = 14f
                setTextColor(accent)
                setTypeface(Typeface.DEFAULT_BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.gifts_all_link, all.size)
                textSize = 13f
                setTextColor(accent)
                setPadding(dp(10), dp(6), dp(8), dp(6))
                setOnClickListener { onOpenGallery(this) }
                Kinetics.squish(this, 0.94f)
            },
        )
        box.addView(head)
        if (shown.isEmpty()) {
            box.addView(
                TextView(ctx).apply {
                    setText(R.string.gifts_showcase_empty)
                    textSize = 13f
                    setTextColor(cSub)
                    setPadding(dp(20), 0, dp(20), dp(8))
                },
            )
            // Кнопка открывает галерею, и окно «выходит» именно из неё.
            lateinit var pick: View
            pick = Btn.tonal(ctx, ctx.getString(R.string.gifts_showcase_pick)) { onOpenGallery(pick) }
            box.addView(
                pick,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                    bottomMargin = dp(8)
                },
            )
            return
        }
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val counts = all.groupingBy { GiftCatalog.resolve(it.id, it.emoji)?.id ?: -1 }.eachCount()
        // На витрине одинаковые подарки - одной ячейкой со счётчиком.
        shown.distinctBy { it.first.id }.forEachIndexed { i, (g, gift) ->
            val same = all.filter { GiftCatalog.resolve(it.id, it.emoji)?.id == g.id }
            val n = counts[g.id] ?: 1
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(FrameLayout(ctx).apply {
                    addView(GiftArt(ctx, g, playEmoji = false), FrameLayout.LayoutParams(dp(70), dp(70)))
                    if (n > 1) addView(countBadge(ctx, n), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP or Gravity.END))
                }, LinearLayout.LayoutParams(dp(70), dp(70)))
                addView(
                    TextView(ctx).apply {
                        text = if (gift.fromName.isNotBlank()) ctx.getString(R.string.gift_from_short, gift.fromName) else g.shortName()
                        textSize = 10f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setTextColor(cSub)
                        setPadding(0, dp(3), 0, 0)
                    },
                )
                setOnClickListener {
                    Kinetics.burst(this)
                    if (same.size > 1) {
                        showStack(ctx, same.map { g to it }, null)
                    } else {
                        Gifts.showGift(ctx, g, gift, gift.fromName)
                    }
                }
            }
            grid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(6))
                },
            )
            Kinetics.springIn(cell, i * 40L)
        }
        box.addView(grid)
    }

    /** Красивый счётчик одинаковых подарков: «×2». */
    private fun countBadge(ctx: Context, n: Int) = TextView(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        text = "×$n"
        textSize = 11.5f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF6B9D.toInt(), 0xFF7B6CF6.toInt())).apply {
            cornerRadius = 11 * d
            setStroke((1.5f * d).toInt(), Color.WHITE)
        }
        elevation = 3 * d
        // Лёгкое «биение» при появлении.
        scaleX = 0.4f
        scaleY = 0.4f
        animate().scaleX(1f).scaleY(1f).setStartDelay(350).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /**
     * Стопка одинаковых подарков: все экземпляры по очереди - от кого, когда, записка; нажатие -
     * подарок крупно. [toggle] - добавить/убрать экземпляр с витрины (в галерее).
     */
    private fun showStack(ctx: Context, group: List<Pair<GiftCatalog.Gift, Gifts.Gift>>, toggle: ((Gifts.Gift) -> Unit)?) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(group[0].first.name() + "  ×" + group.size)
            .setView(android.widget.ScrollView(ctx).apply { addView(list) })
            .setPositiveButton(R.string.close, null)
            .create()
        val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        group.sortedByDescending { it.second.at }.forEachIndexed { i, (g, gift) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(GiftArt(ctx, g, playEmoji = false), LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(ctx).apply {
                        text = if (gift.fromName.isNotBlank()) ctx.getString(R.string.gift_from_short, gift.fromName) else g.shortName()
                        textSize = 14.5f
                        setTypeface(Typeface.DEFAULT_BOLD)
                    })
                    addView(TextView(ctx).apply {
                        text = fmt.format(java.util.Date(gift.at)) + if (gift.note.isNotBlank()) " · " + gift.note else ""
                        textSize = 12.5f
                        alpha = 0.75f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (toggle != null) {
                    addView(TextView(ctx).apply {
                        text = if (Gifts.inShowcase(ctx, gift)) "📌" else "＋📌"
                        textSize = 16f
                        setPadding(dp(8), dp(6), dp(4), dp(6))
                        setOnClickListener {
                            toggle(gift)
                            text = if (Gifts.inShowcase(ctx, gift)) "📌" else "＋📌"
                        }
                    })
                }
                setOnClickListener { Gifts.showGift(ctx, g, gift, gift.fromName) }
            }
            list.addView(row)
            row.alpha = 0f
            row.translationX = dp(24).toFloat()
            row.animate().alpha(1f).translationX(0f).setStartDelay(80L + i * 50L).setDuration(260).setInterpolator(OvershootInterpolator(1.2f)).start()
        }
        dialog.show()
    }

    /**
     * Галерея всех подаренных мне подарков. [anchor] - кнопка, из которой окно плавно «выходит».
     * [onShowcaseChanged] - витрину поменяли (обновить профиль и разослать карточку).
     */
    fun open(anchor: View, onShowcaseChanged: () -> Unit) {
        val ctx = anchor.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val items = Gifts.received(ctx)
            .mapNotNull { g -> GiftCatalog.resolve(g.id, g.emoji)?.let { it to g } }
        // Одинаковые подарки складываются в одну ячейку со счётчиком «2», «3»…
        val groups = items.groupBy { it.first.id }.values.toList()
        if (items.isEmpty()) {
            Toast.makeText(ctx, R.string.gifts_gallery_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF21E1B4B.toInt(), 0xF20B1020.toInt()),
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(36), dp(14), 0)
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- шапка ----
        val legendary = items.count { it.first.rarity == GiftCatalog.Rarity.LEGENDARY }
        val rare = items.count { it.first.rarity == GiftCatalog.Rarity.RARE }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.gifts_gallery_title, items.size)
                textSize = 22f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            },
        )
        val sub = TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        titles.addView(sub)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }
        head.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        column.addView(head)
        val hint = TextView(ctx).apply {
            setText(R.string.gifts_gallery_hint)
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(8), 0, dp(10))
        }
        column.addView(hint)

        fun refreshSub() {
            val n = Gifts.showcase(ctx).size
            sub.text = buildString {
                append(ctx.getString(R.string.gifts_gallery_on_profile, n, Gifts.SHOWCASE_MAX))
                if (legendary > 0) append("   ★ ").append(legendary)
                if (rare > 0) append("   ◆ ").append(rare)
            }
        }
        refreshSub()

        // ---- сетка: подарки выстраиваются по очереди ----
        val appeared = HashSet<Int>()
        val cols = com.stoyko.smessenger.ui.Adaptive.columns(ctx)
        val cellSize = (ctx.resources.displayMetrics.widthPixels - dp(28) - dp(8) * (cols - 1)) / cols
        val grid = GridView(ctx).apply {
            numColumns = cols
            verticalSpacing = dp(10)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, 0, 0, dp(28))
            isVerticalScrollBarEnabled = false
        }
        lateinit var adapter: BaseAdapter
        adapter = object : BaseAdapter() {
            override fun getCount() = groups.size
            override fun getItem(position: Int) = groups[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val group = groups[position]
                val (g, _) = group[0]
                val cell = (convertView as? FrameLayout)?.takeIf { it.tag == position } ?: FrameLayout(ctx).apply {
                    tag = position
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    val art = (cellSize * 0.92f).toInt()
                    col.addView(GiftArt(ctx, g, playEmoji = false), LinearLayout.LayoutParams(art, art))
                    col.addView(
                        TextView(ctx).apply {
                            text = g.shortName()
                            textSize = 10f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER
                            setTextColor(0xDDFFFFFF.toInt())
                            setPadding(dp(2), dp(4), dp(2), 0)
                        },
                    )
                    addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // Значок «на витрине».
                    addView(
                        TextView(ctx).apply {
                            text = "📌"
                            textSize = 13f
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(0xFF5B4BDB.toInt())
                                setStroke(dp(2), Color.WHITE)
                            }
                            tag = "pin"
                        },
                        FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END),
                    )
                    // Счётчик одинаковых подарков.
                    if (group.size > 1) addView(countBadge(ctx, group.size), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22), Gravity.TOP or Gravity.START))
                }
                cell.findViewWithTag<View>("pin")?.visibility = if (group.any { Gifts.inShowcase(ctx, it.second) }) View.VISIBLE else View.GONE
                // Каждый подарок «встаёт в ряд» один раз: снизу, с лёгким пружинящим увеличением.
                if (appeared.add(position) && Kinetics.enabled) {
                    cell.alpha = 0f
                    cell.translationY = dp(36).toFloat()
                    cell.scaleX = 0.6f
                    cell.scaleY = 0.6f
                    cell.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setStartDelay(180L + (position % 24) * 38L)
                        .setDuration(420)
                        .setInterpolator(OvershootInterpolator(1.3f))
                        .start()
                }
                return cell
            }
        }
        grid.adapter = adapter

        fun toggle(gift: Gifts.Gift, cell: View?) {
            val wasOn = Gifts.inShowcase(ctx, gift)
            if (!Gifts.toggleShowcase(ctx, gift)) {
                Toast.makeText(ctx, ctx.getString(R.string.gifts_showcase_full, Gifts.SHOWCASE_MAX), Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(ctx, if (wasOn) R.string.gifts_showcase_removed else R.string.gifts_showcase_added, Toast.LENGTH_SHORT).show()
            cell?.let { Kinetics.burst(it) }
            adapter.notifyDataSetChanged()
            refreshSub()
            onShowcaseChanged()
        }
        grid.setOnItemClickListener { _, view, position, _ ->
            val group = groups[position]
            if (group.size > 1) {
                showStack(ctx, group) { gift -> toggle(gift, view) }
                return@setOnItemClickListener
            }
            val (g, gift) = group[0]
            val label = ctx.getString(if (Gifts.inShowcase(ctx, gift)) R.string.gifts_showcase_unpin else R.string.gifts_showcase_pin)
            Gifts.showGift(ctx, g, gift, gift.fromName, label to { toggle(gift, view) })
        }
        grid.setOnItemLongClickListener { _, view, position, _ ->
            toggle(groups[position][0].second, view)
            true
        }
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }

        // ---- плавный «выход» окна из кнопки и обратно ----
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val px = loc[0] + anchor.width / 2f
        val py = loc[1] + anchor.height / 2f
        var closing = false
        fun dismissAnimated() {
            if (closing) return
            closing = true
            if (!Kinetics.enabled) {
                runCatching { dialog.dismiss() }
                return
            }
            root.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { runCatching { dialog.dismiss() } }
                .start()
        }
        close.setOnClickListener { dismissAnimated() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                dismissAnimated()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            if (!Kinetics.enabled) return@setOnShowListener
            root.pivotX = px
            root.pivotY = py
            root.scaleX = 0.15f
            root.scaleY = 0.15f
            root.alpha = 0f
            root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        dialog.show()
    }
}
