// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/** Комментарий для показа. */
class CommentItem(
    val uid: String,
    val senderName: String,
    val text: String,
    val time: Long,
    val own: Boolean,
    val edited: Boolean,
)

/**
 * Комментарии к посту канала: панель снизу со списком комментариев
 * и полем ввода. Удерживайте комментарий - изменить (свой) или удалить (свой; владелец
 * и модераторы канала - любой).
 */
class CommentsSheet(
    private val context: Context,
    private val postSnippet: String,
    private val canModerate: Boolean,
    private val load: () -> List<CommentItem>,
    private val onSend: (String) -> Boolean,
    private val onEdit: (CommentItem, String) -> Boolean,
    private val onDelete: (CommentItem) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val dialog = Dialog(context)
    private val list = ListView(context)
    private val empty = TextView(context)
    private var items: List<CommentItem> = emptyList()

    @Suppress("DEPRECATION")
    private val textColor = context.resources.getColor(R.color.textPrimary)

    @Suppress("DEPRECATION")
    private val secondary = context.resources.getColor(R.color.textSecondary)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = items[position]
            val box = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                addView(TextView(context).apply { textSize = 13f; paint.isFakeBoldText = true })
                addView(TextView(context).apply { textSize = 15f })
                addView(TextView(context).apply { textSize = 11f })
            }
            val name = box.getChildAt(0) as TextView
            val text = box.getChildAt(1) as TextView
            val time = box.getChildAt(2) as TextView
            name.text = c.senderName
            name.setTextColor(if (c.own) 0xFF00C2A8.toInt() else 0xFF7B6CF6.toInt())
            text.text = c.text
            text.setTextColor(textColor)
            time.setTextColor(secondary)
            time.text = (if (c.edited) context.getString(R.string.edited_mark) + " " else "") +
                DateFormat.getTimeFormat(context).format(Date(c.time))
            return box
        }
    }

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
                @Suppress("DEPRECATION")
                setColor(context.resources.getColor(R.color.colorSurface))
            }
        }
        // «Ручка» панели.
        root.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(0x55888888)
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            },
        )
        root.addView(
            TextView(context).apply {
                setText(R.string.comments)
                textSize = 18f
                paint.isFakeBoldText = true
                setTextColor(textColor)
                setPadding(dp(16), dp(10), dp(16), 0)
            },
        )
        root.addView(
            TextView(context).apply {
                text = postSnippet
                textSize = 13f
                maxLines = 2
                setTextColor(secondary)
                setPadding(dp(16), dp(2), dp(16), dp(8))
            },
        )
        empty.setText(R.string.comments_empty)
        empty.setTextColor(secondary)
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(16), dp(24), dp(16), dp(24))
        root.addView(empty)
        list.adapter = adapter
        list.divider = ColorDrawable(0x22888888)
        list.dividerHeight = 1
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(10))
        }
        val input = EditText(context).apply {
            setHint(R.string.comment_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setTextColor(textColor)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = ImageButton(context).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(0xFF5B4BDB.toInt())
            background = null
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setOnClickListener
                if (onSend(t)) {
                    input.text.clear()
                    list.postDelayed({ refresh(scrollToEnd = true) }, 400)
                } else {
                    Toast.makeText(context, R.string.edit_too_long, Toast.LENGTH_SHORT).show()
                }
            }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(inputRow)

        list.setOnItemLongClickListener { _, _, position, _ ->
            val c = items.getOrNull(position) ?: return@setOnItemLongClickListener false
            actions(c)
            true
        }

        root.fitSystemBars(top = false)
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.72f).toInt())
            w.setWindowAnimations(R.style.DialogAnimation)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        refresh(scrollToEnd = true)
        dialog.show()
    }

    fun refresh(scrollToEnd: Boolean = false) {
        items = load()
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (scrollToEnd && items.isNotEmpty()) list.setSelection(items.size - 1)
    }

    private fun actions(c: CommentItem) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (c.own) {
            labels += context.getString(R.string.edit_message)
            acts += { edit(c) }
        }
        if (c.own || canModerate) {
            labels += context.getString(R.string.delete_for_all)
            acts += {
                onDelete(c)
                list.postDelayed({ refresh() }, 300)
            }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(context)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .show()
    }

    private fun edit(c: CommentItem) {
        val field = EditText(context).apply {
            setText(c.text)
            setSelection(c.text.length)
        }
        val box = LinearLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.edit_message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = field.text.toString().trim()
                if (t.isNotEmpty()) {
                    if (onEdit(c, t)) {
                        list.postDelayed({ refresh() }, 300)
                    } else {
                        Toast.makeText(context, R.string.edit_too_long, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
