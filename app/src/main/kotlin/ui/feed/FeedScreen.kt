// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.feed

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.squareup.picasso.Picasso
import java.io.File
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Markup
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.core.vo.GroupMessage
import com.stoyko.smessenger.groups.GroupProfile
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Лента новостей: свежие посты всех каналов, на которые вы подписаны, -
 * одной лентой. Фильтр по каналу сверху, нажатие - открыть канал.
 */
object FeedScreen {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    fun show(
        fragment: Fragment,
        feed: LiveData<List<GroupMessage>>,
        groups: LiveData<List<GroupChat>>,
        openChat: (String) -> Unit,
    ) {
        val ctx = fragment.requireContext()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)
        val cCard = ContextCompat.getColor(ctx, R.color.colorSurface)
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val cAccent = ContextCompat.getColor(ctx, R.color.settingsAccent)

        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        // Шапка
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_brand_gradient)
            setPadding(dp(4), dp(8), dp(12), dp(8))
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        bar.addView(
            TextView(ctx).apply {
                text = "📰 " + ctx.getString(R.string.feed_title)
                setTextColor(Color.WHITE)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        root.addView(bar)

        // Фильтр по каналам
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        val empty = TextView(ctx).apply {
            setText(R.string.feed_empty)
            setTextColor(cSub)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(32))
            visibility = View.GONE
        }
        root.addView(empty)

        var names: Map<String, GroupChat> = emptyMap()
        var all: List<GroupMessage> = emptyList()
        var filter: String? = null
        var items: List<GroupMessage> = emptyList()
        var firstShow = true

        val list = ListView(ctx).apply {
            divider = null
            dividerHeight = dp(10)
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(16))
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }

        fun titleOf(chatId: String) = names[chatId]?.name?.ifBlank { null }
            ?: ctx.getString(R.string.channel)

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = items[p].id
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val m = items[p]
                val card = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = 18 * d
                        setColor(cCard)
                    }
                    elevation = 2 * d
                    // Шапка карточки: аватарка, канал, время
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ImageView(ctx).apply { tag = "avatar" }, LinearLayout.LayoutParams(dp(38), dp(38)))
                        addView(
                            LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(10), 0, 0, 0)
                                addView(TextView(ctx).apply { tag = "chan"; textSize = 15f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
                                addView(TextView(ctx).apply { tag = "time"; textSize = 12f; setTextColor(cSub) })
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    })
                    addView(
                        ImageView(ctx).apply {
                            tag = "image"
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            adjustViewBounds = false
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(10) },
                    )
                    addView(TextView(ctx).apply {
                        tag = "body"
                        textSize = 15f
                        setTextColor(cText)
                        setPadding(0, dp(8), 0, 0)
                        maxLines = 12
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        tag = "open"
                        textSize = 13f
                        setTextColor(cAccent)
                        setPadding(0, dp(8), 0, 0)
                        setText(R.string.feed_open_channel)
                    })
                }
                val avatar = card.findViewWithTag<ImageView>("avatar")
                val bmp = GroupProfile.roundBitmap(ctx, m.chatId, dp(38))
                if (bmp != null) {
                    avatar.setImageBitmap(bmp)
                    avatar.background = null
                    avatar.setPadding(0, 0, 0, 0)
                } else {
                    avatar.setImageResource(R.drawable.ic_channel)
                    avatar.setColorFilter(Color.WHITE)
                    avatar.setBackgroundResource(R.drawable.bg_avatar_circle)
                    avatar.setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                if (bmp != null) avatar.clearColorFilter()
                card.findViewWithTag<TextView>("chan").text = titleOf(m.chatId)
                card.findViewWithTag<TextView>("time").text = DateUtils.getRelativeTimeSpanString(m.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                val image = card.findViewWithTag<ImageView>("image")
                val ext = m.fileName.substringAfterLast('.', "").lowercase()
                if (m.isFile() && m.isComplete() && ext in IMAGE_EXT && File(m.filePath).isFile) {
                    image.visibility = View.VISIBLE
                    Picasso.get().load(File(m.filePath)).resize(dp(360), dp(190)).centerCrop().onlyScaleDown().into(image)
                } else {
                    Picasso.get().cancelRequest(image)
                    image.setImageDrawable(null)
                    image.visibility = View.GONE
                }
                val body = card.findViewWithTag<TextView>("body")
                val text = when {
                    m.isFile() && ext in IMAGE_EXT -> ""
                    m.isFile() -> "📎 " + m.fileName
                    else -> Markup.body(m.message)
                }
                body.text = text
                body.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                if (firstShow && p < 8) Kinetics.springIn(card, p * 55L)
                return card
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val m = items.getOrNull(p) ?: return@setOnItemClickListener
            dialog.dismiss()
            openChat(m.chatId)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun chip(label: String, id: String?) = TextView(ctx).apply {
            text = label
            textSize = 13f
            val selected = filter == id
            setTextColor(if (selected) Color.WHITE else cText)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(if (selected) ContextCompat.getColor(ctx, R.color.colorPrimary) else ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            Kinetics.squish(this, 0.92f)
        }

        fun render() {
            items = all.filter { (filter == null || it.chatId == filter) && isPost(it) }
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            // Фильтры: «Все» + каналы, у которых есть посты.
            chips.removeAllViews()
            val ids = all.filter { isPost(it) }.map { it.chatId }.distinct()
            (listOf<String?>(null) + ids).forEach { id ->
                val label = if (id == null) ctx.getString(R.string.feed_all) else titleOf(id)
                chips.addView(
                    chip(label, id).apply {
                        setOnClickListener {
                            filter = id
                            firstShow = true
                            render()
                            list.setSelection(0)
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) },
                )
            }
            list.post { firstShow = false }
        }

        val feedObserver = Observer<List<GroupMessage>> {
            all = it
            render()
        }
        val groupsObserver = Observer<List<GroupChat>> { g ->
            names = g.associateBy { it.chatId }
            adapter.notifyDataSetChanged()
        }
        groups.observe(fragment.viewLifecycleOwner, groupsObserver)
        feed.observe(fragment.viewLifecycleOwner, feedObserver)

        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            feed.removeObserver(feedObserver)
            groups.removeObserver(groupsObserver)
        }
        dialog.show()
    }

    /** В ленте - посты; служебные (изменить/удалить) и комментарии не показываем. */
    private fun isPost(m: GroupMessage): Boolean =
        (m.isFile() || m.message.isNotBlank()) &&
            !Markup.isGroupControl(m.message) && !Markup.isAnyComment(m.message)
}
