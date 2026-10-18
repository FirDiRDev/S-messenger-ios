// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactlist

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.DateFormat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.ContactListViewItemBinding
import com.stoyko.smessenger.databinding.FriendRequestItemBinding
import com.stoyko.smessenger.ui.AvatarImageView
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FriendRequest

enum class ContactListItemType {
    FriendRequest,
    Contact,
}

class ContactAdapter(private val inflater: LayoutInflater, private val context: Context) : BaseAdapter() {
    var friendRequests: List<FriendRequest> = listOf()
    var contacts: List<Contact> = listOf()

    /** Выбранные чаты (режим выбора - удалить или очистить сразу несколько). */
    var selected: Set<String> = emptySet()

    override fun getCount(): Int = friendRequests.size + contacts.size
    override fun getItem(position: Int): Any = when {
        position < friendRequests.size -> friendRequests[position]
        else -> contacts[position - friendRequests.size]
    }

    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = when {
        position < friendRequests.size -> ContactListItemType.FriendRequest.ordinal
        else -> ContactListItemType.Contact.ordinal
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (ContactListItemType.entries[getItemViewType(position)]) {
            ContactListItemType.FriendRequest -> {
                val view: View
                val vh: FriendRequestViewHolder

                if (convertView == null) {
                    view = inflater.inflate(R.layout.friend_request_item, parent, false)
                    vh = FriendRequestViewHolder(FriendRequestItemBinding.bind(view))
                    view.tag = vh
                } else {
                    view = convertView
                    vh = view.tag as FriendRequestViewHolder
                }

                friendRequests[position].run {
                    // Ник и аватарка из карточки запроса (RequestCard), а не 76-символьный ID.
                    val card = com.stoyko.smessenger.chat.RequestCard.parse(message)
                    val who = com.stoyko.smessenger.chat.RequestCard.title(card, publicKey)
                    vh.publicKey.text = "👋 $who"
                    vh.message.text = card.text.trim().ifBlank { com.stoyko.smessenger.ai.t("Хочет добавить вас в контакты", "Wants to add you as a contact") }
                    vh.badge.text = com.stoyko.smessenger.ai.t("Запрос", "Request")
                    val px = (48 * context.resources.displayMetrics.density).toInt()
                    val bmp = card.bitmap()?.let { com.stoyko.smessenger.chat.RequestCard.circle(it, px) }
                        ?: com.stoyko.smessenger.ui.AvatarFactory.create(context.resources, card.name.ifBlank { who }, publicKey, com.stoyko.smessenger.ui.Px(px))
                    vh.avatar.setImageBitmap(bmp)
                }

                view
            }
            ContactListItemType.Contact -> {
                val view: View
                val vh: ContactViewHolder

                if (convertView == null) {
                    view = inflater.inflate(R.layout.contact_list_view_item, parent, false)
                    vh = ContactViewHolder(ContactListViewItemBinding.bind(view))
                    view.tag = vh
                } else {
                    view = convertView
                    vh = view.tag as ContactViewHolder
                }

                contacts[position - friendRequests.size].run {
                    name = name.ifEmpty { context.getString(R.string.contact_default_name) }

                    val shortId = publicKey.take(8)
                    vh.publicKey.text = String.format("%s %s", shortId.take(4), shortId.takeLast(4))
                    // 📌 - закреплённый чат.
                    val shown = if (com.stoyko.smessenger.chat.ChatMute.isMuted(context, publicKey)) "$name 🔕" else name
                    vh.name.text = if (com.stoyko.smessenger.chat.PinnedChats.isPinned(context, publicKey)) "📌 $shown" else shown
                    if (publicKey in selected) vh.name.text = "✅ " + vh.name.text
                    // Подсветка выбранного - поверх строки (фон с эффектом нажатия не трогаем).
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        view.foreground = if (publicKey in selected) android.graphics.drawable.ColorDrawable(0x335B4BDB) else null
                    }
                    vh.lastMessage.text = if (lastMessage != 0L) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(lastMessage)
                    } else {
                        context.getText(R.string.never)
                    }
                    // Что делает собеседник: «записывает аудио», «отправляет фото»… - со значком, цветом акцента.
                    val act = if (connectionStatus != com.stoyko.smessenger.core.vo.ConnectionStatus.None) {
                        com.stoyko.smessenger.chat.PeerActivity.of(publicKey)
                    } else {
                        null
                    }
                    val accent = ContextCompat.getColor(context, R.color.colorAccent)
                    when {
                        act != null -> {
                            vh.statusMessage.text = when (act) {
                                com.stoyko.smessenger.chat.PeerActivity.Kind.VOICE -> "🎤 " + context.getString(R.string.activity_voice)
                                com.stoyko.smessenger.chat.PeerActivity.Kind.CIRCLE -> "📹 " + context.getString(R.string.activity_circle)
                                com.stoyko.smessenger.chat.PeerActivity.Kind.PHOTO -> "🖼 " + context.getString(R.string.activity_photo)
                                com.stoyko.smessenger.chat.PeerActivity.Kind.VIDEO -> "🎬 " + context.getString(R.string.activity_video)
                                else -> "📎 " + context.getString(R.string.activity_file)
                            }
                            vh.statusMessage.setTextColor(accent)
                        }
                        typing -> {
                            vh.statusMessage.text = "✍️ " + context.getString(R.string.contact_typing)
                            vh.statusMessage.setTextColor(accent)
                        }
                        draftMessage.isNotEmpty() -> {
                            vh.statusMessage.text = context.getString(R.string.draft_message, draftMessage)
                            vh.statusMessage.setTextColor(
                                ContextCompat.getColor(context, R.color.colorAccent),
                            )
                        }
                        else -> {
                            vh.statusMessage.text = statusMessage
                            vh.statusMessage.setTextColor(vh.lastMessage.currentTextColor)
                        }
                    }
                    vh.avatarImageView.setFrom(this)
                    vh.unreadIndicator.visibility = if (hasUnreadMessages) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }

                view
            }
        }

    private class FriendRequestViewHolder(row: FriendRequestItemBinding) {
        val publicKey: TextView = row.publicKey
        val message: TextView = row.message
        val badge: TextView = row.badge
        val avatar: android.widget.ImageView = row.avatar
    }

    private class ContactViewHolder(row: ContactListViewItemBinding) {
        val name: TextView = row.name
        val publicKey: TextView = row.publicKey
        val statusMessage: TextView = row.statusMessage
        val lastMessage: TextView = row.lastMessage
        val avatarImageView: AvatarImageView = row.avatarImageView
        val unreadIndicator: ImageView = row.unreadIndicator
    }
}
