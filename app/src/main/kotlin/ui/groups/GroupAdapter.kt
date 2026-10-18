// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.databinding.GroupListItemBinding

/** Список групп или каналов (общий для главного экрана и экрана «Группы и каналы»). */
class GroupAdapter(private val context: Context) : BaseAdapter() {
    var items: List<GroupChat> = listOf()

    // Кэш круглых аватарок: (группа, версия изменений) -> картинка или null.
    private val avatarCache = object : LinkedHashMap<Pair<String, Int>, android.graphics.Bitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, android.graphics.Bitmap?>?) = size > 64
    }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val b = if (convertView != null) {
            GroupListItemBinding.bind(convertView)
        } else {
            GroupListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        }
        val g = items[position]
        val title0 = g.name.ifEmpty { context.getString(R.string.group_default_name) }
        val title = if (com.stoyko.smessenger.chat.ChatMute.isMuted(context, g.chatId)) "$title0 🔕" else title0
        b.name.text = if (com.stoyko.smessenger.chat.PinnedChats.isPinned(context, g.chatId)) "📌 $title" else title
        // Своя аватарка группы/канала (если владелец её поставил) - на весь кружок.
        val size = (48 * context.resources.displayMetrics.density).toInt()
        val avatar = avatarCache.getOrPut(g.chatId to com.stoyko.smessenger.groups.GroupProfile.changes.value) {
            com.stoyko.smessenger.groups.GroupProfile.roundBitmap(context, g.chatId, size)
        }
        val lp = b.icon.layoutParams
        if (avatar != null) {
            b.icon.setImageBitmap(avatar)
            lp.width = size
            lp.height = size
        } else {
            b.icon.setImageResource(if (g.isChannel) R.drawable.ic_channel else R.drawable.ic_group)
            lp.width = (26 * context.resources.displayMetrics.density).toInt()
            lp.height = lp.width
        }
        b.icon.layoutParams = lp
        val kind = context.getString(if (g.isChannel) R.string.channel else R.string.group)
        b.subtitle.text = if (g.peerCount > 0) {
            kind + " · " + context.getString(R.string.members_count, g.peerCount)
        } else {
            kind + " · " + context.getString(R.string.group_connecting)
        }
        b.unread.visibility = if (g.hasUnread) View.VISIBLE else View.INVISIBLE
        return b.root
    }
}
