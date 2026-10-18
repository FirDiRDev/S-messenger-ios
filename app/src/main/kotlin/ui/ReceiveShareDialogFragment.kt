// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.Window
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LiveData
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.DialogReceiveShareBinding
import com.stoyko.smessenger.truncated
import com.stoyko.smessenger.ui.contactlist.ContactAdapter
import com.stoyko.smessenger.core.vo.Contact

private const val SHARE_TEXT_PREVIEW_LENGTH = 128

class ReceiveShareDialogFragment(
    private val contacts: LiveData<List<Contact>>,
    private val sharePreview: String,
    private val onContactSelected: (Contact) -> Unit,
    private val onDialogDismissed: () -> Unit,
    /** Выбрано несколько чатов (удержание) - отправить всем. */
    private val onManySelected: ((List<Contact>) -> Unit)? = null,
) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = ReceiveShareDialog(requireContext(), sharePreview, onContactSelected, onManySelected)
        contacts.observe(this) { dialog.setContacts(it.sortedByDescending(::contactListSorter)) }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogDismissed()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onDialogDismissed()
    }
}

/**
 * Кому отправить: нажатие - сразу в этот чат; удержание - режим выбора: отметьте одного
 * или нескольких и нажмите «Отправить».
 */
private class ReceiveShareDialog(
    ctx: Context,
    private val sharePreview: String,
    private val contactSelectedFunc: (Contact) -> Unit,
    private val manySelectedFunc: ((List<Contact>) -> Unit)?,
) : Dialog(ctx, R.style.DialogSlideAnimation) {
    private lateinit var binding: DialogReceiveShareBinding
    private val inner by lazy { ContactAdapter(layoutInflater, context) }
    private val selected = LinkedHashSet<String>()
    private var sendBtn: android.widget.TextView? = null

    /** Обёртка над списком контактов: галочка у выбранных. */
    private val wrap = object : android.widget.BaseAdapter() {
        override fun getCount() = inner.count
        override fun getItem(position: Int): Any = inner.getItem(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val d = context.resources.displayMetrics.density
            val frame = (convertView as? android.widget.FrameLayout) ?: android.widget.FrameLayout(context).apply {
                addView(android.view.View(context))
                addView(android.widget.TextView(context).apply {
                    text = "✓"
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFF00C2A8.toInt())
                    }
                }, android.widget.FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply {
                    marginEnd = (8 * d).toInt()
                })
            }
            val old = frame.getChildAt(0)
            val row = inner.getView(position, old.takeIf { it !is android.widget.Space && it.javaClass != android.view.View::class.java }, frame)
            if (row !== old) {
                frame.removeViewAt(0)
                (row.parent as? android.view.ViewGroup)?.removeView(row)
                frame.addView(row, 0)
            }
            val c = inner.getItem(position) as Contact
            val on = c.publicKey in selected
            frame.getChildAt(1).visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            frame.setBackgroundColor(if (on) 0x2600C2A8 else Color.TRANSPARENT)
            return frame
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogReceiveShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        binding.sharingText.text = String.format("%s", sharePreview.truncated(SHARE_TEXT_PREVIEW_LENGTH)) +
            if (manySelectedFunc != null) "\n\n" + context.getString(R.string.share_hold_hint) else ""

        binding.contacts.let {
            it.adapter = wrap
            it.setOnItemClickListener { _, _, position, _ ->
                val contact = wrap.getItem(position) as Contact
                if (selected.isNotEmpty()) {
                    toggle(contact)
                    return@setOnItemClickListener
                }
                contactSelectedFunc(contact)
                dismiss()
            }
            it.setOnItemLongClickListener { v, _, position, _ ->
                if (manySelectedFunc == null) return@setOnItemLongClickListener false
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                toggle(wrap.getItem(position) as Contact)
                true
            }
        }
        // Кнопка «Отправить (N)» - появляется в режиме выбора.
        val list = binding.contacts
        (list.parent as? android.widget.LinearLayout)?.let { col ->
            val btn = Btn.primary(context, "") {
                val chosen = (0 until inner.count).map { inner.getItem(it) as Contact }.filter { it.publicKey in selected }
                if (chosen.isNotEmpty()) {
                    manySelectedFunc?.invoke(chosen)
                    dismiss()
                }
            }
            btn.visibility = android.view.View.GONE
            col.addView(btn, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (48 * context.resources.displayMetrics.density).toInt()).apply {
                topMargin = (10 * context.resources.displayMetrics.density).toInt()
            })
            sendBtn = btn
        }
    }

    private fun toggle(c: Contact) {
        if (!selected.remove(c.publicKey)) selected += c.publicKey
        sendBtn?.let {
            it.visibility = if (selected.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            it.text = context.getString(R.string.share_send_n, selected.size)
        }
        wrap.notifyDataSetChanged()
    }

    fun setContacts(contacts: List<Contact>) {
        inner.contacts = contacts
        inner.notifyDataSetChanged()
        wrap.notifyDataSetChanged()
    }
}
