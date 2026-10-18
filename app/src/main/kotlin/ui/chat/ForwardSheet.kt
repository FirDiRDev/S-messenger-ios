// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Kinetics

/**
 * Пересылка: сетка чатов с аватарками на вкладках «Чаты» (Избранное и контакты),
 * «Группы» и «Каналы» - переключаются нажатием или свайпом влево/вправо; поиск, отметка нескольких получателей с анимацией галочки, комментарий к пересылке и
 * переключатель «Скрыть автора». Кнопка «Отправить» показывает, сколько чатов выбрано.
 */
object ForwardSheet {
    /** Получатель: id (ключ контакта, chatId группы или SAVED), подпись, буква/эмодзи для аватарки. */
    data class Target(val id: String, val title: String, val kind: Kind, val avatar: android.graphics.Bitmap? = null) {
        enum class Kind { SAVED, CONTACT, GROUP, CHANNEL }
    }

    data class Result(val targets: List<Target>, val comment: String, val hideAuthor: Boolean)

    fun show(ctx: Context, targets: List<Target>, done: (Result) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val cPrimary = ContextCompat.getColor(ctx, R.color.colorPrimary)
        val selected = LinkedHashSet<String>()
        // Вкладки: 0 - чаты, 1 - группы, 2 - каналы (пустые вкладки не показываем).
        fun tabOf(t: Target) = when (t.kind) {
            Target.Kind.SAVED, Target.Kind.CONTACT -> 0
            Target.Kind.GROUP -> 1
            Target.Kind.CHANNEL -> 2
        }
        val tabs = (0..2).filter { tab -> targets.any { tabOf(it) == tab } }.ifEmpty { listOf(0) }
        var tab = tabs.first()
        var query = ""
        var filtered = targets.filter { tabOf(it) == tab }

        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(
            View(ctx).apply { setBackgroundResource(R.drawable.bg_handle); alpha = 0.35f },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) },
        )
        root.addView(
            TextView(ctx).apply {
                setText(R.string.forward_to)
                textSize = 22f
                setTextColor(cText)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(18), 0, dp(18), dp(6))
            },
        )
        val search = EditText(ctx).apply {
            setHint(R.string.forward_search)
            setSingleLine()
            textSize = 17f
            setTextColor(cText)
            setHintTextColor(cSub)
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), 0, dp(14), dp(8))
        })
        // Вкладки «Чаты / Группы / Каналы».
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(8))
        }
        val tabViews = HashMap<Int, TextView>()
        val tabNames = intArrayOf(R.string.forward_tab_chats, R.string.forward_tab_groups, R.string.forward_tab_channels)
        lateinit var selectTab: (Int, Int) -> Unit
        for (t in tabs) {
            val tv = TextView(ctx).apply {
                setText(tabNames[t])
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { selectTab(t, 0) }
            }
            tabViews[t] = tv
            tabRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        if (tabs.size > 1) root.addView(tabRow)

        val colors = intArrayOf(0xFF5B4BDB.toInt(), 0xFF00C2A8.toInt(), 0xFFF5A524.toInt(), 0xFFE5484D.toInt(), 0xFF3B82F6.toInt(), 0xFFA855F7.toInt())
        val grid = GridView(ctx).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(92)
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = dp(6)
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        lateinit var refreshButton: () -> Unit
        val adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(p: Int) = filtered[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val t = filtered[p]
                val cell = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(6), 0, dp(4))
                    val box = FrameLayout(ctx).apply { tag = "box" }
                    box.addView(
                        TextView(ctx).apply {
                            tag = "avatar"
                            gravity = Gravity.CENTER
                            textSize = 24f
                            setTextColor(Color.WHITE)
                            paint.isFakeBoldText = true
                        },
                        FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER),
                    )
                    box.addView(
                        TextView(ctx).apply {
                            tag = "check"
                            text = "✓"
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            paint.isFakeBoldText = true
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(0xFF00C2A8.toInt())
                                setStroke(dp(2), Color.WHITE)
                            }
                        },
                        FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.END),
                    )
                    addView(box, LinearLayout.LayoutParams(dp(66), dp(66)))
                    addView(
                        TextView(ctx).apply {
                            tag = "name"
                            textSize = 14f
                            setTextColor(cText)
                            paint.isFakeBoldText = true
                            gravity = Gravity.CENTER
                            maxLines = 2
                            setPadding(dp(2), dp(4), dp(2), 0)
                        },
                        LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }
                val avatar = cell.findViewWithTag<TextView>("avatar")
                val check = cell.findViewWithTag<TextView>("check")
                val name = cell.findViewWithTag<TextView>("name")
                val on = t.id in selected
                if (t.avatar != null) {
                    avatar.text = ""
                    avatar.background = android.graphics.drawable.BitmapDrawable(ctx.resources, t.avatar)
                } else {
                    avatar.text = when (t.kind) {
                        Target.Kind.SAVED -> "⭐"
                        Target.Kind.GROUP -> "👥"
                        Target.Kind.CHANNEL -> "📢"
                        Target.Kind.CONTACT -> t.title.take(1).uppercase()
                    }
                    avatar.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (t.kind == Target.Kind.SAVED) cPrimary else colors[(t.id.hashCode() and 0x7FFFFFFF) % colors.size])
                    }
                }
                // Выбранный: аватарка чуть меньше и с кольцом, галочка видна.
                avatar.scaleX = if (on) 0.86f else 1f
                avatar.scaleY = if (on) 0.86f else 1f
                check.visibility = if (on) View.VISIBLE else View.INVISIBLE
                name.text = t.title
                name.setTextColor(if (on) cPrimary else cText)
                return cell
            }
        }
        grid.adapter = adapter
        fun applyFilter() {
            val q = query
            filtered = targets.filter { tabOf(it) == tab && (q.isEmpty() || it.title.lowercase().contains(q)) }
            adapter.notifyDataSetChanged()
        }
        fun paintTabs() {
            for ((t, tv) in tabViews) {
                val on = t == tab
                tv.setTextColor(if (on) Color.WHITE else cText)
                tv.paint.isFakeBoldText = true
                tv.alpha = if (on) 1f else 0.8f
                tv.background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    if (on) setColor(cPrimary) else setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
                }
            }
        }
        selectTab = { t, dir ->
            if (t != tab && t in tabs) {
                tab = t
                paintTabs()
                applyFilter()
                // Сетка «въезжает» с той стороны, куда листали.
                if (Kinetics.enabled) {
                    grid.translationX = (if (dir >= 0) 60 else -60) * d
                    grid.alpha = 0.4f
                    grid.animate().translationX(0f).alpha(1f).setDuration(220).start()
                }
            }
        }
        paintTabs()
        // Свайп влево/вправо по сетке - соседняя вкладка (прокрутка вверх-вниз не мешает).
        val swipe = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (kotlin.math.abs(dx) < 60 * d || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.4f) return false
                val i = tabs.indexOf(tab)
                val next = if (dx < 0) i + 1 else i - 1
                if (next in tabs.indices) {
                    selectTab(tabs[next], if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        grid.setOnTouchListener { _, ev ->
            swipe.onTouchEvent(ev)
            false
        }
        grid.setOnItemClickListener { _, v, p, _ ->
            val t = filtered.getOrNull(p) ?: return@setOnItemClickListener
            val on = if (t.id in selected) {
                selected.remove(t.id)
                false
            } else {
                if (selected.size >= 100) return@setOnItemClickListener
                selected.add(t.id)
                true
            }
            val avatar = v.findViewWithTag<View>("avatar")
            val check = v.findViewWithTag<View>("check")
            avatar?.animate()?.scaleX(if (on) 0.86f else 1f)?.scaleY(if (on) 0.86f else 1f)?.setDuration(220)
                ?.setInterpolator(OvershootInterpolator(2.5f))?.start()
            check?.let { c ->
                if (on) {
                    c.visibility = View.VISIBLE
                    c.scaleX = 0f
                    c.scaleY = 0f
                    c.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(3f)).start()
                } else {
                    c.animate().scaleX(0f).scaleY(0f).setDuration(160).withEndAction { c.visibility = View.INVISIBLE }.start()
                }
            }
            v.findViewWithTag<TextView>("name")?.setTextColor(if (on) cPrimary else cText)
            refreshButton()
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        // Низ: комментарий, «скрыть автора» и кнопка «Отправить (N)».
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), 0)
            visibility = View.GONE
        }
        val comment = EditText(ctx).apply {
            setHint(R.string.forward_comment)
            textSize = 17f
            maxLines = 4
            setTextColor(cText)
            setHintTextColor(cSub)
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        bottom.addView(comment)
        val hide = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            setText(R.string.forward_hide_author)
            setTextColor(cText)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        bottom.addView(hide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val send = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF7B6CF6.toInt(), 0xFF5B4BDB.toInt()),
            ).apply { cornerRadius = 24 * d }
            Kinetics.squish(this, 0.94f)
            setOnClickListener {
                // Второе быстрое нажатие (пока окно закрывается) пересылало всё повторно.
                if (selected.isEmpty() || !sheet.isShowing || !isEnabled) return@setOnClickListener
                isEnabled = false
                val chosen = targets.filter { it.id in selected }
                sheet.dismiss()
                done(Result(chosen, comment.text.toString().trim(), hide.isChecked))
            }
        }
        bottom.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(6) })
        com.stoyko.smessenger.ui.Buttons3d.apply(send)
        root.addView(bottom)
        refreshButton = {
            send.text = ctx.getString(R.string.forward_send_n, selected.size)
            if (selected.isNotEmpty() && bottom.visibility != View.VISIBLE) {
                bottom.visibility = View.VISIBLE
                bottom.alpha = 0f
                bottom.translationY = 40 * d
                bottom.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
            } else if (selected.isEmpty()) {
                bottom.visibility = View.GONE
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                query = e?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }
        })

        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
    }
}
