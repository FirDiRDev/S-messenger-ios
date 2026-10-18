// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.squareup.picasso.Picasso
import java.io.File
import java.util.Date
import kotlinx.coroutines.launch
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.GroupMessage
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.databinding.FragmentGroupChatBinding
import com.stoyko.smessenger.databinding.GroupMessageItemBinding
import com.stoyko.smessenger.hasPermission
import com.stoyko.smessenger.requireStringArg
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.vmFactory

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

class GroupChatFragment : BaseFragment<FragmentGroupChatBinding>(FragmentGroupChatBinding::inflate) {
    private val vm: GroupChatViewModel by viewModels { vmFactory }
    private var pulse: ValueAnimator? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendWithCaption(listOf(uri))
    }

    private val stickerLauncher = com.stoyko.smessenger.ui.chat.StickerLauncher(
        this,
        onEmoji = { emoji -> view?.let { com.stoyko.smessenger.ui.chat.insertEmoji(binding.input, emoji) } },
        onSticker = { if (allowedToSend(media = true)) vm.sendFile(it) },
    )

    // Группа сама копирует файл перед отправкой, поэтому фото из галереи отдаём как есть.
    private val attachLauncher = com.stoyko.smessenger.ui.chat.AttachLauncher(
        this,
        copyMedia = false,
        onFiles = { uris -> sendWithCaption(uris) },
        onDocuments = { pickFile.launch(arrayOf("*/*")) },
    )

    // Новая аватарка группы/канала (владелец и модераторы).
    private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || view == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.setAvatar(uri)
            Toast.makeText(requireContext(), if (ok) R.string.group_avatar_updated else R.string.group_no_rights, Toast.LENGTH_SHORT).show()
            infoSheet?.refresh()
        }
    }

    private var infoSheet: GroupInfoSheet? = null

    /** Фото/видео/файлы с подписью, как в обычных чатах: сначала файлы, затем подпись. */
    private fun sendWithCaption(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || view == null) return
        val typed = binding.input.text?.toString().orEmpty().trim()
        if (!allowedToSend(media = true)) return
        com.stoyko.smessenger.ui.chat.SendPreview.show(requireContext(), uris, typed) { caption ->
            val spoiler = com.stoyko.smessenger.ui.chat.SendPreview.lastSpoiler
            uris.distinct().forEach { u ->
                val ctx = context ?: return@forEach
                val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull().orEmpty()
                val name = if (spoiler && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    runCatching {
                        ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull()?.let { com.stoyko.smessenger.chat.MediaSpoiler.mark(it) }
                } else null
                vm.sendFile(u, name)
            }
            context?.let { com.stoyko.smessenger.groups.GroupSettings.markSent(it, vm.chatId) }
            if (caption.isNotEmpty()) {
                vm.send(caption)
                if (view != null && caption == typed) binding.input.text.clear()
            }
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCall()
    }

    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(requireContext(), R.string.voice_permission_needed, Toast.LENGTH_LONG).show()
    }
    private val cameraPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { !it }) Toast.makeText(requireContext(), R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var groupTitle = ""

    // Живой прогресс файлов (МБ, скорость) без перестройки списка.
    private val transferTicker = object : Runnable {
        override fun run() {
            if (view == null) return
            (binding.messages.adapter as? Adapter)?.refreshTransfers()
            handler.postDelayed(this, if (com.stoyko.smessenger.domain.feature.TransferMonitor.busy) 250 else 1000)
        }
    }
    private val voiceRecorder by lazy { com.stoyko.smessenger.ui.chat.VoiceRecorder(requireContext()) }

    private inner class Adapter : BaseAdapter() {
        var items: List<GroupMessage> = listOf()
        private var newestAnimated: Long? = null

        fun submit(list: List<GroupMessage>) {
            if (newestAnimated == null) newestAnimated = list.maxOfOrNull { it.id } ?: 0L
            items = list
            notifyDataSetChanged()
        }

        // Видимые голосовые: обновляем прогресс проигрывания.
        // Ключ - сама строка списка (binding создаётся заново при каждом bind).
        private val voiceRows = HashMap<View, Pair<GroupMessageItemBinding, GroupMessage>>()

        fun refreshVoice() {
            for ((b, m) in voiceRows.values) updateVoice(b, m)
        }

        // Видимые файлы: живой прогресс загрузки.
        private val fileRows = HashMap<View, Pair<GroupMessageItemBinding, GroupMessage>>()

        fun refreshTransfers() {
            for ((b, m) in fileRows.values) updateTransfer(b, m)
        }

        private fun updateTransfer(b: GroupMessageItemBinding, m: GroupMessage) {
            val live = com.stoyko.smessenger.domain.feature.TransferMonitor.get(
                com.stoyko.smessenger.domain.feature.TransferMonitor.groupKey(m.id),
            )
            val ru = java.util.Locale.getDefault().language == "ru"
            val failed = m.progress < 0
            val done = m.isComplete()
            val active = !failed && !done
            val fraction = live?.fraction ?: if (m.fileSize > 0) (m.progress.toFloat() / m.fileSize).coerceIn(0f, 1f) else -1f
            val info = when {
                failed -> getString(R.string.group_file_failed)
                done -> android.text.format.Formatter.formatShortFileSize(requireContext(), m.fileSize)
                live != null -> com.stoyko.smessenger.domain.feature.TransferMonitor.describe(live, ru)
                else -> com.stoyko.smessenger.domain.feature.TransferMonitor.size(m.progress.coerceAtLeast(0), ru) + " / " +
                    com.stoyko.smessenger.domain.feature.TransferMonitor.size(m.fileSize, ru)
            }
            fun ring(r: com.stoyko.smessenger.ui.ProgressRingView, show: Boolean) {
                r.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    r.icon = com.stoyko.smessenger.ui.ProgressRingView.Icon.Cancel
                    r.setProgress(fraction)
                }
            }
            when {
                b.videoNoteBox.visibility == View.VISIBLE -> ring(b.videoNoteRing, active)
                b.image.visibility == View.VISIBLE -> ring(b.imageRing, active)
                b.fileRow.visibility == View.VISIBLE -> {
                    ring(b.fileRing, active)
                    b.fileInfo.text = info
                }
                b.voiceLayout.visibility == View.VISIBLE && active -> b.voiceTime.text = info
            }
        }

        private fun cancelOnTouch(v: View, m: GroupMessage) {
            v.setOnTouchListener { view, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    if (com.stoyko.smessenger.ui.chat.VoicePlayer.currentKey == m.filePath) {
                        com.stoyko.smessenger.ui.chat.VoicePlayer.stop()
                    }
                    vm.cancelTransfer(m.id)
                }
                true
            }
        }

        private fun updateVoice(b: GroupMessageItemBinding, m: GroupMessage) {
            if (b.videoNoteBox.visibility == View.VISIBLE) {
                b.videoNote.sync()
                return
            }
            val vp = com.stoyko.smessenger.ui.chat.VoicePlayer
            val fmt = com.stoyko.smessenger.ui.chat.VoiceMeta
            val key = m.filePath
            val current = key == vp.currentKey
            b.voicePlay.setImageResource(if (current && vp.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            b.voiceWave.progress = if (current) vp.progress(key) else 0f
            val total = fmt.durationMs(m.fileName) ?: 0L
            b.voiceTime.text = when {
                !m.isComplete() && !m.outgoing -> getString(R.string.voice_loading)
                current -> fmt.format(vp.positionMs(key)) + " / " + fmt.format(vp.durationMs(key).takeIf { it > 0 } ?: total)
                else -> fmt.format(total)
            } + if ((m.isComplete() || m.outgoing) && !current) "  📝" else ""
        }

        private fun bindVoice(b: GroupMessageItemBinding, m: GroupMessage, status: String) {
            voiceRows[b.root] = b to m
            val ready = m.isComplete() || m.outgoing
            // Расшифровка голосового - текстом под ним.
            val said = if (ready) com.stoyko.smessenger.chat.VoiceTranscriber.get(requireContext(), m.fileName, m.fileSize) else null
            b.text.visibility = if (ready && said == null) View.GONE else View.VISIBLE
            b.text.text = if (said != null) "📝 $said" else status
            b.voiceLayout.visibility = View.VISIBLE
            b.voiceTime.setOnClickListener { if (ready) transcribeVoice(m) }
            b.voiceTime.setOnLongClickListener { com.stoyko.smessenger.chat.VoiceTextUi.forwardLongPress(it) }
            b.voiceWave.levels = com.stoyko.smessenger.ui.chat.VoiceMeta.waveform(m.fileName)
                ?: com.stoyko.smessenger.ui.chat.VoiceMeta.fallbackWave(m.fileName)
            b.voiceWave.onSeek = { f -> com.stoyko.smessenger.ui.chat.VoicePlayer.seek(m.filePath, f) }
            b.voicePlay.setOnClickListener { playVoice(m) }
            updateVoice(b, m)
        }

        private fun unbindVoice(b: GroupMessageItemBinding) {
            voiceRows.remove(b.root)
            b.voiceLayout.visibility = View.GONE
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        /** Реакции под сообщением/постом: «❤️ 5», моя подсвечена, нажатие - поставить/снять. */
        private fun bindReactions(b: GroupMessageItemBinding, m: GroupMessage) {
            val ctx = requireContext()
            val d = resources.displayMetrics.density
            var box = b.row.findViewWithTag<android.widget.LinearLayout>("reactions")
            if (box == null) {
                box = android.widget.LinearLayout(ctx).apply {
                    tag = "reactions"
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                b.row.addView(
                    android.widget.HorizontalScrollView(ctx).apply {
                        tag = "reactions_scroll"
                        isHorizontalScrollBarEnabled = false
                        addView(box)
                    },
                    b.row.indexOfChild(b.bubbleLine) + 1,
                )
            }
            val scroll = b.row.findViewWithTag<View>("reactions_scroll")
            (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = if (m.outgoing) Gravity.END else Gravity.START
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                topMargin = (2 * d).toInt()
                // Под входящим сообщением группы - вровень с пузырём (слева аватарка автора).
                marginStart = if (!m.outgoing && !isChannel) (40 * d).toInt() else 0
            }
            box.removeAllViews()
            val list = if (m.msgUid.isBlank()) emptyList() else com.stoyko.smessenger.groups.GroupReactions.counts(ctx, vm.chatId, m.msgUid)
            scroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            for ((emoji, count, mine) in list) {
                box.addView(
                    android.widget.TextView(ctx).apply {
                        // Крупнее: эмодзи 19sp, число - жирным.
                        text = if (count > 1) android.text.SpannableString("$emoji $count").apply {
                            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), emoji.length + 1, length, 0)
                            setSpan(android.text.style.RelativeSizeSpan(0.8f), emoji.length + 1, length, 0)
                        } else emoji
                        textSize = 19f
                        gravity = Gravity.CENTER
                        minHeight = (34 * d).toInt()
                        setTextColor(if (mine) android.graphics.Color.WHITE else androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary))
                        setPadding((11 * d).toInt(), (3 * d).toInt(), (11 * d).toInt(), (3 * d).toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 17 * d
                            setColor(if (mine) 0xFF5B4BDB.toInt() else 0x225B4BDB)
                            if (!mine) setStroke((1 * d).toInt(), 0x445B4BDB)
                        }
                        setOnClickListener {
                            vm.react(m, emoji)
                            animate().scaleX(1.2f).scaleY(1.2f).setDuration(90).withEndAction {
                                animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(3f)).start()
                            }.start()
                        }
                    },
                    android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (4 * d).toInt()
                    },
                )
            }
        }
        /**
         * Аватарка автора слева от входящих сообщений группы - с живым видео профиля, если оно
         * есть (видео видят все участники, не только друзья). У серии сообщений
         * одного автора аватарка одна - у последнего, у остальных место пустое (текст ровный).
         */
        private fun bindSenderAvatar(b: GroupMessageItemBinding, m: GroupMessage, position: Int) {
            val box = b.senderAvatar
            if (m.outgoing || isChannel) {
                box.visibility = View.GONE
                com.stoyko.smessenger.profile.ProfileVideo.attach(box, null)
                return
            }
            val next = items.getOrNull(position + 1)
            val last = next == null || next.outgoing || next.senderKey != m.senderKey
            if (!last) {
                box.visibility = View.INVISIBLE
                com.stoyko.smessenger.profile.ProfileVideo.attach(box, null)
                return
            }
            box.visibility = View.VISIBLE
            com.stoyko.smessenger.profile.GroupVideo.bindAvatar(box, m.senderKey, m.senderName.ifEmpty { m.senderKey })
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView != null) {
                GroupMessageItemBinding.bind(convertView)
            } else {
                GroupMessageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            val m = items[position]
            b.row.gravity = if (m.outgoing) Gravity.END else Gravity.START
            (b.bubbleLine.layoutParams as LinearLayout.LayoutParams).gravity = if (m.outgoing) Gravity.END else Gravity.START
            bindSenderAvatar(b, m, position)
            b.bubble.setBackgroundResource(
                if (m.outgoing) R.drawable.sent_message_bubble else R.drawable.received_message_bubble,
            )
            com.stoyko.smessenger.ui.SeasonTint.bubble(b.bubble, m.outgoing)
            b.sender.visibility = if (m.outgoing) View.GONE else View.VISIBLE
            b.sender.text = m.senderName.ifEmpty { m.senderKey.take(8) }
            // Разработчик S-messenger - имя золотом и со значком 🛠.
            if (com.stoyko.smessenger.chat.DevAccount.isDevInGroup(requireContext(), m.senderKey)) {
                if (b.sender.getTag(R.id.tag_sender_color) == null) b.sender.setTag(R.id.tag_sender_color, b.sender.currentTextColor)
                b.sender.setTextColor(com.stoyko.smessenger.chat.DevAccount.GOLD)
                b.sender.text = "🛠 " + b.sender.text
                b.sender.setShadowLayer(6f, 0f, 0f, 0x66F59E0B)
            } else if (com.stoyko.smessenger.chat.DevAccount.isDesignerInGroup(requireContext(), m.senderKey)) {
                // Дизайнер S-messenger - сиреневым и со значком 🎨.
                if (b.sender.getTag(R.id.tag_sender_color) == null) b.sender.setTag(R.id.tag_sender_color, b.sender.currentTextColor)
                b.sender.setTextColor(com.stoyko.smessenger.chat.DevAccount.LILAC)
                b.sender.text = "🎨 " + b.sender.text
                b.sender.setShadowLayer(6f, 0f, 0f, 0x66A855F7)
            } else {
                (b.sender.getTag(R.id.tag_sender_color) as? Int)?.let { b.sender.setTextColor(it) }
                b.sender.setShadowLayer(0f, 0f, 0f, 0)
            }
            val timeText = DateFormat.getTimeFormat(requireContext()).format(Date(m.timestamp))
            b.time.text = if (com.stoyko.smessenger.chat.Markup.isEdited(m.message)) {
                getString(R.string.edited_mark) + " " + timeText
            } else {
                timeText
            }
            // Канал: под постом - «Комментарии: N».
            if (isChannel && com.stoyko.smessenger.groups.GroupSettings.get(requireContext(), vm.chatId).comments) {
                val n = vm.commentCount(m.msgUid)
                b.commentsButton.visibility = View.VISIBLE
                b.commentsButton.text = if (n > 0) getString(R.string.comments_count, n) else getString(R.string.comments_leave)
                (b.commentsButton.layoutParams as LinearLayout.LayoutParams).gravity =
                    if (m.outgoing) Gravity.END else Gravity.START
                b.commentsButton.setOnClickListener { openComments(m) }
            } else {
                b.commentsButton.visibility = View.GONE
            }

            b.videoNoteBox.visibility = View.GONE
            b.fileRow.visibility = View.GONE
            b.imageRing.visibility = View.GONE
            com.stoyko.smessenger.chat.KbGif.unbind(b.image)
            if (m.isFile()) {
                val isVideo = com.stoyko.smessenger.ui.chat.FileTypes.isVideo(m.fileName)
                // Фото и видео показываются картинкой (видео - кадром с ▶), а не строкой файла.
                val isImage = File(m.fileName).extension.lowercase() in IMAGE_EXT || isVideo
                val status = when {
                    m.progress < 0 -> getString(R.string.group_file_failed)
                    m.isComplete() -> android.text.format.Formatter.formatShortFileSize(requireContext(), m.fileSize)
                    else -> getString(R.string.group_file_receiving, (m.progress * 100 / m.fileSize.coerceAtLeast(1)).toInt())
                }
                val voice = com.stoyko.smessenger.ui.chat.VoiceMeta.isVoice(m.fileName)
                val note = com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(m.fileName)
                val ready = m.isComplete() || (m.outgoing && m.progress >= 0)
                if (voice) {
                    bindVoice(b, m, status)
                } else if (note) {
                    // «Кружок»: круг без пузыря, нажатие - играть прямо в круге.
                    voiceRows[b.root] = b to m
                    b.voiceLayout.visibility = View.GONE
                    b.text.visibility = View.GONE
                    b.videoNoteBox.visibility = View.VISIBLE
                    val uri = if (ready) android.net.Uri.fromFile(File(m.filePath)) else null
                    b.videoNote.bind(m.filePath, uri, com.stoyko.smessenger.ui.chat.VoiceMeta.durationMs(m.fileName) ?: 0L, ready)
                    b.videoNote.setOnTouchListener { v, e ->
                        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && ready) {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            playVoice(m)
                        }
                        true
                    }
                    cancelOnTouch(b.videoNoteRing, m)
                } else {
                    unbindVoice(b)
                    b.text.visibility = View.GONE
                    if (!(isImage && ready)) {
                        b.fileRow.visibility = View.VISIBLE
                        b.fileName.text = "📎 " + com.stoyko.smessenger.chat.MediaSpoiler.clean(m.fileName)
                        b.fileInfo.text = status
                        cancelOnTouch(b.fileRing, m)
                    }
                }
                b.videoPlay.visibility = View.GONE
                b.image.tag = null
                b.image.setOnClickListener(null)
                b.image.isClickable = false
                if (isImage && ready) {
                    b.image.visibility = View.VISIBLE
                    if (isVideo) {
                        Picasso.get().cancelRequest(b.image)
                        b.videoPlay.visibility = View.VISIBLE
                        com.stoyko.smessenger.ui.chat.FileTypes.loadThumb(
                            requireContext(),
                            android.net.Uri.fromFile(File(m.filePath)).toString(),
                            b.image,
                            600,
                        )
                    } else if (com.stoyko.smessenger.chat.MediaSpoiler.isHidden(m.fileName, m.filePath) ||
                        com.stoyko.smessenger.chat.KbGif.isKbGif(m.fileName) ||
                        !com.stoyko.smessenger.chat.KbGif.bind(b.image, m.filePath, m.fileName, 600)
                    ) {
                        Picasso.get().load(File(m.filePath)).resize(600, 0).onlyScaleDown().into(b.image)
                    }
                    cancelOnTouch(b.imageRing, m)
                } else {
                    b.image.visibility = View.GONE
                }
                com.stoyko.smessenger.ui.Safe.run("spoiler") {
                    com.stoyko.smessenger.chat.MediaSpoiler.bind(b.image, if (isImage && ready) m.fileName else null, m.filePath)
                }
                fileRows[b.root] = b to m
                updateTransfer(b, m)
            } else {
                unbindVoice(b)
                fileRows.remove(b.root)
                b.image.visibility = View.GONE
                b.videoPlay.visibility = View.GONE
                b.text.visibility = View.VISIBLE
                // Оформление + файлы и музыка прямо в тексте - кнопками-ссылками (несколько треков - плейлист).
                val ctxText = requireContext()
                val richText: () -> CharSequence = {
                    com.stoyko.smessenger.chat.InlineFiles.decorate(
                        ctxText,
                        com.stoyko.smessenger.chat.Markup.render(m.message, 0xCCFFFFFF.toInt()),
                    ) { r ->
                        com.stoyko.smessenger.chat.InlineFiles.findGroup(vm.allMessages(), r, m.senderKey)?.let { f ->
                            com.stoyko.smessenger.chat.InlineFiles.targetGroup(f) { openInline(f) }
                        }
                    }
                }
                b.text.text = richText()
                com.stoyko.smessenger.chat.RichSpans.attach(b.text, richText)
                // Ссылка YouTube - обложка с ▶, видео смотрится внутри мессенджера (как в личных чатах).
                val yt = com.stoyko.smessenger.media.YouTube.videoId(m.message)
                if (yt != null && com.stoyko.smessenger.ui.chat.ChatLook(requireContext()).linkPreviews) {
                    b.image.visibility = View.VISIBLE
                    b.videoPlay.visibility = View.VISIBLE
                    Picasso.get().load(com.stoyko.smessenger.media.YouTube.thumbnail(yt)).resize(600, 0).onlyScaleDown().into(b.image)
                    b.image.setOnClickListener { com.stoyko.smessenger.media.YouTube.play(it.context, yt) }
                } else {
                    b.videoPlay.visibility = View.GONE
                    b.image.setOnClickListener(null)
                    b.image.isClickable = false
                }
            }

            // Стикеры и 1-3 эмодзи - крупно и без пузыря.
            val sticker = m.isFile() && com.stoyko.smessenger.ui.chat.isStickerName(m.fileName) &&
                (m.isComplete() || m.outgoing)
            val jumbo = !m.isFile() && com.stoyko.smessenger.chat.Markup.parseReply(m.message).first == null &&
                com.stoyko.smessenger.ui.chat.isJumboEmoji(m.message)
            b.text.textSize = if (jumbo) 56f else com.stoyko.smessenger.ui.UiPrefs.messageTextSize
            b.image.scaleType = if (sticker) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            val bare = sticker || jumbo || (m.isFile() && com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(m.fileName))
            if (bare) b.bubble.background = null
            // В канале имя автора сверху не пишем (пост - от канала); подпись - внизу, если включена.
            b.sender.visibility = if (m.outgoing || bare || isChannel) View.GONE else View.VISIBLE
            com.stoyko.smessenger.ui.Safe.run("extras") { ChannelTools.bindExtras(b.bubble, b.text, m, vm.chatId, isChannel, this@GroupChatFragment) }
            // Кнопки внутри сообщения - под текстом и оформлением поста.
            com.stoyko.smessenger.ui.Safe.run("buttons") {
                com.stoyko.smessenger.chat.MsgButtons.bind(
                    b.bubble,
                    b.bubble.findViewWithTag<View>(ChannelTools.EXTRAS) ?: b.text,
                    if (m.isFile()) "" else m.message,
                    false,
                    buttonHost,
                )
            }
            // Опрос - под вопросом.
            com.stoyko.smessenger.ui.Safe.run("poll") { com.stoyko.smessenger.chat.Polls.bind(b.bubble, b.text, if (m.isFile()) "" else m.message, pollHost) }
            com.stoyko.smessenger.ui.Safe.run("html") { com.stoyko.smessenger.chat.HtmlBubbles.bind(b.bubble, b.text, if (m.isFile()) "" else m.message, buttonHost, autoPlay = position == items.lastIndex && com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.LIVE)) }
            if (isChannel && m.msgUid.isNotBlank()) {
                // Пост показан - учитываем просмотр (один раз), под постом - «👁 N».
                com.stoyko.smessenger.groups.ChannelStats.seen(requireContext(), vm.chatId, m.msgUid, vm.selfKey())
                val n = com.stoyko.smessenger.groups.ChannelStats.views(requireContext(), vm.chatId, m.msgUid)
                if (n > 0) b.time.text = "👁 " + com.stoyko.smessenger.groups.ChannelStats.short(n) + "  " + b.time.text
            }
            b.time.setTextColor(
                if (bare) androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary) else 0x99FFFFFF.toInt(),
            )
            if (sticker) {
                b.text.visibility = View.GONE
                b.image.visibility = View.VISIBLE
                val px = (150 * resources.displayMetrics.density).toInt()
                if (!com.stoyko.smessenger.chat.KbGif.bind(b.image, m.filePath, m.fileName, (200 * resources.displayMetrics.density).toInt())) {
                    Picasso.get().load(File(m.filePath)).resize(px, px).centerInside().into(b.image)
                }
            }

            bindReactions(b, m)

            val newest = newestAnimated
            if (newest != null && m.id > newest) {
                newestAnimated = m.id
                if (com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.MESSAGES)) com.stoyko.smessenger.ui.Safe.run("appear") { com.stoyko.smessenger.ui.anim.MessageAppear.play(b.root, m.outgoing, parent) }
            } else {
                com.stoyko.smessenger.ui.Safe.run("appear") { com.stoyko.smessenger.ui.anim.MessageAppear.reset(b.root) }
            }
            return b.root
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        vm.setChat(requireStringArg(GROUP_CHAT_ID))
        // Черновик группы/канала: недописанное сообщение возвращается при открытии.
        com.stoyko.smessenger.ui.BackgroundSaver.draft(requireContext(), "g_" + vm.chatId).takeIf { it.isNotEmpty() }?.let {
            if (input.text.isNullOrEmpty()) {
                input.setText(it)
                input.setSelection(it.length)
            }
        }
        com.stoyko.smessenger.ui.BackgroundSaver.saveDraft(requireContext(), "g_" + vm.chatId, "")
        // Сезонное оформление групп и каналов: снег или листопад на фоне сообщений (под ними).
        val seasonKind = com.stoyko.smessenger.ui.Seasonal.current(requireContext())
        if (seasonKind != com.stoyko.smessenger.ui.Seasonal.Kind.NONE) {
            val base = messages.background ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            messages.background = android.graphics.drawable.LayerDrawable(
                arrayOf(base, com.stoyko.smessenger.ui.Seasonal.SeasonDrawable(requireContext(), seasonKind, header = false, garland = false)),
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            root.updatePadding(bottom = insets.bottom)
            compat
        }
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        toolbar.inflateMenu(R.menu.group_chat_menu)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId) }
        // Закреплённое сообщение - панель под шапкой (видят все; закрепляют администраторы).
        pinnedBar = android.widget.TextView(requireContext()).apply {
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary))
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorSurface))
            elevation = 3 * d
            visibility = View.GONE
            setOnClickListener { jumpToPinned() }
            setOnLongClickListener {
                if (vm.isAdmin()) {
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.gs_unpin_confirm)
                        .setPositiveButton(R.string.gs_unpin) { _, _ -> vm.pushSettings { s -> s.copy(pinnedUid = "", pinnedText = "") } }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                true
            }
        }
        (root as ViewGroup).addView(pinnedBar, (root as ViewGroup).indexOfChild(callBar) + 1)
        renderPinned()
        com.stoyko.smessenger.groups.GroupSettings.listen(settingsListener)
        com.stoyko.smessenger.groups.Welcome.listen(welcomeListener)
        // Выделите слово в поле ввода - над ним меню оформления на «жидком стекле».
        com.stoyko.smessenger.chat.SelectionMenu.install(input)
        // Открыли из списка - фон разворачивается кругом из точки нажатия.
        if (savedInstanceState == null) com.stoyko.smessenger.ui.anim.Hero.reveal(view, arguments)
        com.stoyko.smessenger.chat.Polls.listen(reactionsListener)

        val adapter = Adapter()
        messages.adapter = adapter
        // «Желейный» скролл сообщений.
        com.stoyko.smessenger.ui.anim.JellyScroll.attach(messages)
        voiceListener = { adapter.refreshVoice() }
        // Реакции участников пришли - перерисовываем.
        com.stoyko.smessenger.groups.GroupReactions.listen(reactionsListener)
        // Пришло видео профиля участника - аватарки в ленте оживают.
        com.stoyko.smessenger.profile.GroupVideo.listen(reactionsListener)
        com.stoyko.smessenger.groups.ChannelStats.listen(reactionsListener)
        com.stoyko.smessenger.chat.VoiceTranscriber.listen(reactionsListener)
        ChannelTools.contactCheck = { pk -> vm.isContact(pk) }
        com.stoyko.smessenger.ui.chat.VoicePlayer.onUpdate = voiceListener
        vm.messages.observe(viewLifecycleOwner) {
            adapter.submit(it)
            commentsSheet?.refresh()
        }
        messages.setOnItemClickListener { _, row, position, _ ->
            val m = adapter.items[position]
            // Фото/видео под спойлером: первое нажатие только открывает пелену.
            if (m.isFile() && com.stoyko.smessenger.chat.MediaSpoiler.reveal(row?.findViewById(R.id.image), m.fileName, m.filePath)) return@setOnItemClickListener
            if (m.isFile() && m.isComplete()) {
                openFile(m)
            } else if (!m.isFile()) {
                // Ссылки на серверы VPN в сообщении - предложить открыть.
                val links = com.stoyko.smessenger.vpn.VpnShare.linksIn(com.stoyko.smessenger.chat.Markup.body(m.message))
                if (links.isNotEmpty()) com.stoyko.smessenger.vpn.VpnShare.offer(requireContext(), links)
            }
        }

        // Свайп влево по сообщению - ответить (если можно писать).
        messages.canSwipe = { pos -> inputBar.visibility == View.VISIBLE && adapter.items.getOrNull(pos) != null }
        messages.onSwipeReply = { pos ->
            adapter.items.getOrNull(pos)?.let { m ->
                val quote = if (m.isFile()) groupQuote(m) else m.message
                replyTo = quote
                replyText.text = com.stoyko.smessenger.chat.Markup.snippet(quote)
                replyBar.visibility = View.VISIBLE
                input.requestFocus()
            }
        }
        vm.scheduledChanges.observe(viewLifecycleOwner) {
            val n = vm.scheduledCount()
            scheduledBar.visibility = if (n > 0) View.VISIBLE else View.GONE
            scheduledBar.text = getString(R.string.scheduled_count, n)
        }
        scheduledBar.setOnClickListener {
            com.stoyko.smessenger.chat.ScheduledMessages.showList(requireContext(), vm.scheduled, vm.scheduledTarget())
        }
        // Удерживайте «Отправить» - отложенная отправка.
        send.setOnLongClickListener {
            val text = input.text.toString()
            // Канал, администратор: конструктор поста открывается и с пустым полем (карточка товара).
            // Пустое поле: в канале - конструктор поста (у админа), в группе - меню (кнопки, приветствие).
            if (text.isBlank() && (editing != null || (isChannel && !vm.isAdmin()))) return@setOnLongClickListener false
            // Канал, администратор: конструктор поста (товар/услуга, кнопка, без звука, отложить).
            if (isChannel && vm.isAdmin() && editing == null) {
                val quoted = replyTo
                val body = if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, text) else text
                fun clearInput() {
                    if (view == null) return
                    input.text.clear()
                    replyTo = null
                    replyBar.visibility = View.GONE
                }
                ChannelTools.composer(
                    requireContext(), vm.chatId, body,
                    publish = { post ->
                        vm.send(post)
                        clearInput()
                    },
                    schedule = { post ->
                        com.stoyko.smessenger.chat.ScheduledMessages.pickTime(requireContext()) { at ->
                            vm.schedule(post, at)
                            clearInput()
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.scheduled_added, com.stoyko.smessenger.chat.ScheduledMessages.label(requireContext(), at)),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                return@setOnLongClickListener true
            }
            groupSendOptions(text)
            true
        }

        // Нажатие на шапку - «Информация» (участники, название, аватарка).
        toolbar.setOnClickListener { openInfo() }
        vm.profileChanges.observe(viewLifecycleOwner) {
            updateToolbarAvatar()
            infoSheet?.refresh()
        }
        vm.group.observe(viewLifecycleOwner) { g ->
            if (g == null) return@observe
            infoSheet?.refresh()
            toolbar.title = g.name.ifEmpty { getString(R.string.group_default_name) }
            groupTitle = toolbar.title?.toString().orEmpty()
            val kind = getString(if (g.isChannel) R.string.channel else R.string.group)
            toolbar.subtitle = kind + " · " + getString(R.string.members_count, g.peerCount)
            val wasChannel = isChannel
            isChannel = g.isChannel
            if (wasChannel != isChannel) adapter.notifyDataSetChanged()
            inputBar.visibility = if (g.canPost()) View.VISIBLE else View.GONE
            readonly.visibility = if (g.canPost()) View.GONE else View.VISIBLE
            // Каталог и карточки товаров - только в каналах (карточки - у администраторов).
            toolbar.menu.findItem(R.id.group_catalog)?.isVisible = g.isChannel
            toolbar.menu.findItem(R.id.group_new_product)?.isVisible = g.isChannel && vm.isAdmin()
            // Только что созданный канал - обучение «Как вести канал» (один раз).
            if (g.isChannel && ChannelTutorial.consumePending(requireContext(), vm.chatId)) {
                view?.postDelayed({
                    if (view == null) return@postDelayed
                    ChannelTutorial.show(requireContext()) {
                        ChannelTools.businessEditor(requireContext(), vm.chatId, vm.myId()) { change -> vm.pushSettings(change) }
                    }
                }, 500)
            }
        }

        vm.activeCall.observe(viewLifecycleOwner) { active ->
            val inCall = active == vm.chatId
            callBar.visibility = if (inCall) View.VISIBLE else View.GONE
            if (inCall) startPulse() else stopPulse()
        }
        vm.micMuted.observe(viewLifecycleOwner) { muted ->
            callMute.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        callMute.setOnClickListener { vm.toggleMute() }
        vm.speakerOn.observe(viewLifecycleOwner) { on ->
            callSpeaker.setImageResource(if (on) R.drawable.ic_speakerphone else R.drawable.ic_speakerphone_off)
        }
        callSpeaker.setOnClickListener { vm.toggleSpeaker() }
        callPeople.setOnClickListener { showParticipants() }
        callLeave.setOnClickListener { vm.leaveCall() }

        com.stoyko.smessenger.ui.Kinetics.squish(send, 0.86f)
        if (com.stoyko.smessenger.ui.chat.ChatLook(requireContext()).typingAnimation) {
            com.stoyko.smessenger.ui.chat.TypingFx(input).attach()
        }
        send.setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val edited = editing
            if (edited != null) {
                // Изменение поста/сообщения у всех.
                if (!vm.editMessage(edited, text.trim())) {
                    Toast.makeText(requireContext(), R.string.edit_too_long, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                editing = null
                input.text.clear()
                replyBar.visibility = View.GONE
                return@setOnClickListener
            }
            val quoted = replyTo
            if (!allowedToSend(media = false)) return@setOnClickListener
            vm.send(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, text) else text)
            com.stoyko.smessenger.groups.GroupSettings.markSent(requireContext(), vm.chatId)
            input.text.clear()
            androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, input).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            input.clearFocus()
            replyTo = null
            replyBar.visibility = View.GONE
            it.animate().scaleX(0.7f).scaleY(0.7f).rotation(-20f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
        }
        attach.setOnClickListener { attachLauncher.show() }
        replyClose.setOnClickListener {
            replyTo = null
            if (editing != null) {
                editing = null
                input.text.clear()
            }
            replyBar.visibility = View.GONE
        }
        // Долгое нажатие на сообщение: ответить / скопировать.
        messages.setOnItemLongClickListener { _, _, position, _ ->
            val m = adapter.items.getOrNull(position) ?: return@setOnItemLongClickListener false
            val canPost = inputBar.visibility == View.VISIBLE
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            // Администратор: закрепить сообщение для всех.
            if (vm.isAdmin() && m.msgUid.isNotBlank()) {
                val pinned = com.stoyko.smessenger.groups.GroupSettings.get(requireContext(), vm.chatId).pinnedUid == m.msgUid
                actions += (if (pinned) "📌 " + getString(R.string.gs_unpin) else "📌 " + getString(R.string.gs_pin)) to {
                    val snippet = if (m.isFile()) "📎 " + m.fileName else com.stoyko.smessenger.chat.Markup.snippet(m.message)
                    vm.pushSettings { s -> if (pinned) s.copy(pinnedUid = "", pinnedText = "") else s.copy(pinnedUid = m.msgUid, pinnedText = snippet) }
                    Unit
                }
            }
            // Реакции - на любое сообщение и пост (их видят все участники).
            if (m.msgUid.isNotBlank()) {
                actions += ("😊 " + getString(R.string.react)) to {
                    com.stoyko.smessenger.chat.showReactionPicker(
                        requireContext(),
                        com.stoyko.smessenger.groups.GroupReactions.mine(requireContext(), vm.chatId, m.msgUid),
                    ) { e -> if (e != null) vm.react(m, e) }
                }
            }
            val own = m.outgoing
            if (own && !m.isFile() && com.stoyko.smessenger.chat.HtmlBubbles.isTemplate(m.message)) {
                // Живое сообщение - меняем его параметры в редакторе.
                actions += getString(R.string.edit_message) to {
                    com.stoyko.smessenger.chat.HtmlBubbles.editSent(requireContext(), m.message) { newRaw ->
                        if (view != null && !vm.editMessage(m, newRaw, raw = true)) {
                            Toast.makeText(requireContext(), R.string.edit_too_long, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else if (own && !m.isFile()) {
                actions += getString(R.string.edit_message) to {
                    editing = m
                    replyTo = null
                    val body = com.stoyko.smessenger.chat.Markup.body(m.message)
                    replyText.text = "✏️ " + getString(R.string.editing) + ": " + com.stoyko.smessenger.chat.Markup.snippet(m.message)
                    replyBar.visibility = View.VISIBLE
                    input.setText(body)
                    input.setSelection(body.length)
                    input.requestFocus()
                }
            }
            if (own || vm.isAdmin()) {
                actions += getString(R.string.delete_for_all) to {
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.delete_message)
                        .setPositiveButton(R.string.delete_for_all) { _, _ -> vm.deleteForAll(m) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            if (isChannel) actions += getString(R.string.comments) to { openComments(m) }
            // Голосовое - расшифровать в текст.
            if (m.isFile() && com.stoyko.smessenger.ui.chat.VoiceMeta.isVoice(m.fileName) && (m.isComplete() || m.outgoing)) {
                actions += getString(R.string.voice_text_menu) to { transcribeVoice(m) }
            }
            if (m.isFile()) {
                if (actions.isEmpty()) return@setOnItemLongClickListener false
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                    .show()
                return@setOnItemLongClickListener true
            }
            if (canPost) {
                actions += getString(R.string.reply) to {
                    replyTo = m.message
                    replyText.text = com.stoyko.smessenger.chat.Markup.snippet(m.message)
                    replyBar.visibility = View.VISIBLE
                    input.requestFocus()
                }
            }
            actions += getString(R.string.copy_atox) to {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", com.stoyko.smessenger.chat.Markup.body(m.message)))
                Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
            }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .show()
            true
        }

        com.stoyko.smessenger.ui.applyPrivateKeyboard(input)
        com.stoyko.smessenger.ui.applySendByEnter(input, send)
        // GIF и стикеры с клавиатуры - сразу в группу/канал, играют по кругу в ленте.
        com.stoyko.smessenger.chat.KbGif.attach(input) { uri -> if (view != null && allowedToSend(media = true)) vm.sendFile(uri) }
        stickers.setOnClickListener { stickerLauncher.show() }

        // «/» в пустом поле - галерея живых сообщений (отправка одним нажатием).
        com.stoyko.smessenger.chat.HtmlBubbles.attachSlash(input) { out ->
            if (view != null) vm.send(out)
        }

        // Пустое поле - микрофон (удерживать для записи), есть текст - «Отправить».
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                send.visibility = if (hasText) View.VISIBLE else View.GONE
                mic.visibility = if (hasText) View.GONE else View.VISIBLE
            }
        })
        // Микрофон ↔ камера («кружок»).
        com.stoyko.smessenger.ui.chat.setupRecordButton(
            mic,
            input,
            voiceRecorder,
            getString(R.string.voice_recording_hint),
            ensureMic = {
                if (requireContext().hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    true
                } else {
                    voicePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    false
                }
            },
            ensureCamera = {
                val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    .filterNot { requireContext().hasPermission(it) }
                if (need.isEmpty()) {
                    true
                } else {
                    cameraPermissions.launch(need.toTypedArray())
                    false
                }
            },
            onVoice = { file -> sendOwn(file) },
            onVideoNote = { file -> sendOwn(file) },
        )
    }

    // ---------------- закреп, медленный режим, поиск ----------------

    private var pinnedBar: android.widget.TextView? = null
    private val settingsListener: () -> Unit = {
        if (view != null) {
            renderPinned()
            // Подписи, комментарии, бизнес-информация - перерисовываем ленту.
            (binding.messages.adapter as? Adapter)?.notifyDataSetChanged()
        }
    }

    private fun renderPinned() {
        val bar = pinnedBar ?: return
        val st = com.stoyko.smessenger.groups.GroupSettings.get(requireContext(), vm.chatId)
        val show = st.pinnedUid.isNotBlank()
        if (show) bar.text = "📌 " + st.pinnedText.ifBlank { getString(R.string.gs_pinned) }
        if (show && bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = -bar.height.toFloat().coerceAtLeast(40f)
            bar.animate().alpha(1f).translationY(0f).setDuration(240).start()
        } else if (!show) {
            bar.visibility = View.GONE
        }
    }

    /**
     * Удержание «Отправить» в группе: отложить, сообщение с кнопками
     * или (у администраторов) приветствие для новых участников.
     */
    private fun groupSendOptions(text: String) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (text.isNotBlank()) opts += getString(R.string.group_send_schedule) to { scheduleTyped(text) }
        if (text.isNotBlank()) opts += getString(R.string.send_spoiler) to {
            val quoted = replyTo
            val body = com.stoyko.smessenger.chat.MediaSpoiler.wrapText(text)
            vm.send(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, body) else body)
            clearTyped()
        }
        opts += getString(R.string.send_rich) to {
            com.stoyko.smessenger.chat.RichEditor.show(requireContext(), text) { out ->
                if (view == null) return@show
                val quoted = replyTo
                vm.send(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, out) else out)
                clearTyped()
            }
        }
        opts += getString(R.string.send_files_inline) to {
            com.stoyko.smessenger.chat.InlineFiles.compose(
                requireContext(),
                text.trim(),
                pick = { cb ->
                    inlinePickResult = cb
                    inlinePickLauncher.launch(arrayOf("*/*"))
                },
            ) { body, files ->
                if (view == null) return@compose
                val ctx = requireContext()
                files.forEach { u -> vm.sendFile(u, com.stoyko.smessenger.chat.InlineFiles.describe(ctx, u)?.name) }
                vm.send(body)
                clearTyped()
            }
        }
        opts += getString(R.string.send_html) to {
            com.stoyko.smessenger.chat.HtmlBubbles.editor(requireContext(), text.trim()) { out ->
                if (view == null) return@editor
                vm.send(out)
                clearTyped()
            }
        }
        opts += getString(R.string.send_poll) to {
            com.stoyko.smessenger.chat.Polls.editor(requireContext(), text.trim()) { out ->
                if (view == null) return@editor
                vm.send(out)
                clearTyped()
            }
        }
        opts += getString(R.string.send_with_buttons) to {
            val quoted = replyTo
            com.stoyko.smessenger.chat.MsgButtons.editor(requireContext(), text.trim(), games = false, shop = false) { out ->
                if (view == null) return@editor
                vm.send(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, out) else out)
                clearTyped()
            }
        }
        if (vm.isAdmin()) {
            opts += getString(R.string.welcome_menu) to { editWelcome(text.trim()) }
        }
        AlertDialog.Builder(requireContext())
            .setItems(opts.map { it.first as CharSequence }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    /** Приветствие для новых участников (администратор): изменить, выключить, посмотреть. */
    fun editWelcome(initial: String = "") {
        if (view == null) return
        val ctx = requireContext()
        com.stoyko.smessenger.groups.Welcome.editor(ctx, vm.chatId, initial) { w ->
            if (view == null) return@editor
            if (!vm.pushWelcome(w)) {
                Toast.makeText(ctx, R.string.welcome_too_long, Toast.LENGTH_LONG).show()
                return@editor
            }
            if (initial.isNotEmpty()) clearTyped()
            if (w != null) {
                Toast.makeText(ctx, R.string.welcome_saved, Toast.LENGTH_SHORT).show()
                com.stoyko.smessenger.groups.Welcome.show(ctx, vm.chatId, groupTitle, buttonHost, preview = true)
            } else {
                Toast.makeText(ctx, R.string.welcome_turned_off, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Новый участник: показать приветствие (один раз на версию). */
    private fun maybeShowWelcome() {
        val ctx = context ?: return
        if (view == null) return
        if (!com.stoyko.smessenger.groups.Welcome.shouldShow(ctx, vm.chatId, vm.isAdmin())) return
        com.stoyko.smessenger.groups.Welcome.show(ctx, vm.chatId, groupTitle, buttonHost)
    }

    private val welcomeListener: () -> Unit = { maybeShowWelcome() }

    private fun clearTyped() {
        if (view == null) return
        binding.input.text.clear()
        replyTo = null
        binding.replyBar.visibility = View.GONE
    }

    /** Отложенная отправка набранного текста (как раньше по удержанию «Отправить»). */
    private fun scheduleTyped(text: String) {
        com.stoyko.smessenger.chat.ScheduledMessages.pickTime(requireContext()) { at ->
            val quoted = replyTo
            vm.schedule(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, text) else text, at)
            clearTyped()
            Toast.makeText(
                requireContext(),
                getString(R.string.scheduled_added, com.stoyko.smessenger.chat.ScheduledMessages.label(requireContext(), at)),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Кнопки внутри сообщений группы/канала: быстрый ответ - в этот чат, «Каталог» - товары канала. */
    private val buttonHost by lazy {
        com.stoyko.smessenger.chat.MsgButtons.Host(
            send = { text -> if (view != null) vm.send(text) },
            catalog = { if (isChannel) openCatalog() },
        )
    }

    /** «Текст с файлами»: выбор файлов для вставки в текст. */
    private var inlinePickResult: ((List<android.net.Uri>) -> Unit)? = null
    private val inlinePickLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { files ->
            for (file in files) {
                runCatching {
                    activity?.contentResolver?.takePersistableUriPermission(file, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            inlinePickResult?.invoke(files)
            inlinePickResult = null
        }

    /** Опросы: голос уходит служебным сообщением в группу; мой ключ - ключ участника группы. */
    private val pollHost: com.stoyko.smessenger.chat.Polls.Host
        get() = com.stoyko.smessenger.chat.Polls.Host(
            send = { text -> if (view != null) vm.send(text) },
            // Ключ берём каждый раз: до подключения к группе он ещё пустой.
            myKey = vm.selfKey(),
        )

    /** Новая карточка товара (из каталога и меню): конструктор поста сразу в режиме «Товар». */
    fun newProductCard() {
        if (view == null) return
        ChannelTools.composer(
            requireContext(), vm.chatId, "",
            publish = { post -> vm.send(post) },
            schedule = { post ->
                com.stoyko.smessenger.chat.ScheduledMessages.pickTime(requireContext()) { at -> vm.schedule(post, at) }
            },
            startProduct = true,
        )
    }

    /** Каталог товаров канала. */
    fun openCatalog() {
        if (view == null) return
        ChannelTools.catalogDialog(
            requireContext(), vm.messages.value.orEmpty(),
            jump = { m -> jumpToUid(m.msgUid) },
            onAdd = if (vm.isAdmin()) ({ newProductCard() }) else null,
            onOrder = { p -> ChannelTools.orderFromCatalog(this, vm.chatId, p) },
        )
    }

    /** Перейти к сообщению по uid (из каталога товаров, поиска и т.п.). */
    fun jumpToUid(uid: String) {
        if (view == null) return
        val a = binding.messages.adapter as? Adapter ?: return
        val i = a.items.indexOfFirst { it.msgUid == uid }
        if (i >= 0) binding.messages.smoothScrollToPositionFromTop(i, 120, 260) else Toast.makeText(requireContext(), R.string.gs_pinned_not_loaded, Toast.LENGTH_SHORT).show()
    }

    private fun jumpToPinned() {
        val uid = com.stoyko.smessenger.groups.GroupSettings.get(requireContext(), vm.chatId).pinnedUid
        val a = binding.messages.adapter as? Adapter ?: return
        val i = a.items.indexOfFirst { it.msgUid == uid }
        if (i >= 0) binding.messages.smoothScrollToPositionFromTop(i, 120, 260) else Toast.makeText(requireContext(), R.string.gs_pinned_not_loaded, Toast.LENGTH_SHORT).show()
    }

    /** Медленный режим и «медиа только от администраторов». */
    private fun allowedToSend(media: Boolean): Boolean {
        val ctx = context ?: return false
        val admin = vm.isAdmin()
        val st = com.stoyko.smessenger.groups.GroupSettings.get(ctx, vm.chatId)
        if (media && st.adminMediaOnly && !admin) {
            Toast.makeText(ctx, R.string.gs_media_blocked, Toast.LENGTH_LONG).show()
            return false
        }
        val wait = com.stoyko.smessenger.groups.GroupSettings.waitSeconds(ctx, vm.chatId, admin)
        if (wait > 0) {
            Toast.makeText(ctx, getString(R.string.gs_wait, wait), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** Поиск по сообщениям: результаты списком, нажатие - перейти к сообщению. */
    private fun searchDialog() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val field = android.widget.EditText(ctx).apply {
            setHint(R.string.gs_search_hint)
            setSingleLine()
        }
        val results = android.widget.ListView(ctx)
        var found: List<GroupMessage> = emptyList()
        results.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = found.size
            override fun getItem(p: Int) = found[p]
            override fun getItemId(p: Int) = found[p].id
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val m = found[p]
                v.findViewById<android.widget.TextView>(android.R.id.text1).text =
                    if (m.isFile()) "📎 " + m.fileName else com.stoyko.smessenger.chat.Markup.snippet(m.message)
                v.findViewById<android.widget.TextView>(android.R.id.text2).text =
                    m.senderName.ifBlank { m.senderKey.take(8) } + " · " + android.text.format.DateFormat.format("dd.MM HH:mm", m.timestamp)
                return v
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (6 * d).toInt(), (18 * d).toInt(), 0)
            addView(field)
            addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * d).toInt()))
        }
        val dlg = AlertDialog.Builder(ctx).setTitle(R.string.gs_search).setView(box).setNegativeButton(R.string.close, null).show()
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    found = vm.search(q)
                    (results.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                }
            }
        })
        results.setOnItemClickListener { _, _, p, _ ->
            val m = found.getOrNull(p) ?: return@setOnItemClickListener
            dlg.dismiss()
            val a = binding.messages.adapter as? Adapter ?: return@setOnItemClickListener
            val i = a.items.indexOfFirst { it.id == m.id }
            if (i >= 0) binding.messages.smoothScrollToPositionFromTop(i, 120, 260)
        }
    }

    private fun onMenu(id: Int): Boolean {
        when (id) {
            R.id.group_call -> {
                if (requireContext().hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    startCall()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            R.id.group_copy_id -> {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ID", vm.chatId))
                Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
            }
            R.id.group_share_id -> startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, getString(R.string.group_share_text, vm.chatId))
                    },
                    getString(R.string.group_share_id),
                ),
            )
            R.id.group_info -> openInfo()
            R.id.group_search -> searchDialog()
            R.id.group_catalog -> openCatalog()
            R.id.group_new_product -> if (vm.isAdmin()) newProductCard() else Toast.makeText(requireContext(), R.string.group_no_rights, Toast.LENGTH_SHORT).show()
            R.id.group_settings -> if (vm.isAdmin()) {
                openInfo()
                infoSheet?.openSettings()
            } else {
                Toast.makeText(requireContext(), R.string.group_no_rights, Toast.LENGTH_SHORT).show()
            }
            R.id.group_invite -> inviteContact()
            // Уведомления группы/канала: на 1 час … навсегда (как у обычных чатов).
            R.id.group_mute -> com.stoyko.smessenger.chat.ChatMute.showDialog(requireContext(), vm.chatId) {
                val st = com.stoyko.smessenger.chat.ChatMute.status(requireContext(), vm.chatId)
                Toast.makeText(requireContext(), st ?: getString(R.string.mute_off_done), Toast.LENGTH_SHORT).show()
            }
            R.id.group_leave -> AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.group_leave_confirm, binding.toolbar.title))
                .setPositiveButton(R.string.group_leave) { _, _ ->
                    vm.leave()
                    findNavController().popBackStack()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return true
    }

    private fun openInfo() {
        if (view == null) return
        val sheet = infoSheet ?: GroupInfoSheet(
            this,
            vm,
            onPickAvatar = {
                pickAvatar.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onInvite = { inviteContact() },
            onShareId = { onMenu(R.id.group_share_id) },
        ).also { infoSheet = it }
        if (!sheet.isShowing) sheet.show()
    }

    /** Аватарка группы слева от названия в шапке. */
    private fun updateToolbarAvatar() {
        if (view == null) return
        val size = (36 * resources.displayMetrics.density).toInt()
        val bmp = com.stoyko.smessenger.groups.GroupProfile.roundBitmap(requireContext(), vm.chatId, size)
        binding.toolbar.logo = bmp?.let {
            android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.BitmapDrawable(resources, it),
                0,
                0,
                (10 * resources.displayMetrics.density).toInt(),
                0,
            )
        }
    }

    /** Список тех, кого слышно в звонке: можно отключить звук отдельного участника. */
    private fun showParticipants() {
        val list = vm.participantsNow()
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), R.string.group_call_nobody, Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.map { it.name }.toTypedArray()
        val muted = list.map { it.muted }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.group_call_mute_people)
            .setMultiChoiceItems(names, muted) { _, which, _ -> vm.togglePeerMute(list[which].key) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startCall() {
        if (!vm.joinCall()) {
            Toast.makeText(requireContext(), R.string.call_mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun inviteContact() {
        viewLifecycleOwner.lifecycleScope.launch {
            val contacts = vm.contacts()
            if (view == null) return@launch
            if (contacts.isEmpty()) {
                Toast.makeText(requireContext(), R.string.group_no_contacts, Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = contacts.map { it.name.ifEmpty { it.publicKey.take(8) } }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.group_invite_contact)
                .setItems(names) { _, which ->
                    vm.invite(PublicKey(contacts[which].publicKey))
                    Toast.makeText(requireContext(), R.string.group_invited, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun sendOwn(file: File) {
        if (!isAdded) return
        if (!allowedToSend(media = true)) return
        com.stoyko.smessenger.groups.GroupSettings.markSent(requireContext(), vm.chatId)
        val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        vm.sendFile(uri)
    }

    private fun groupQuote(m: GroupMessage): String = when {
        com.stoyko.smessenger.ui.chat.VoiceMeta.isVoice(m.fileName) -> "🎤 " + getString(R.string.voice_message)
        com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(m.fileName) -> "⭕ " + getString(R.string.video_message)
        File(m.fileName).extension.lowercase() in IMAGE_EXT -> "🖼 " + getString(R.string.photo)
        else -> "📎 " + m.fileName
    }

    private fun transcribeVoice(m: GroupMessage) {
        com.stoyko.smessenger.chat.VoiceTextUi.run(
            requireContext(), File(m.filePath), m.fileName, m.fileSize,
            requestMic = { voicePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            alive = { view != null },
        )
    }

    private fun playVoice(m: GroupMessage) {
        if (!m.isComplete() && !m.outgoing) return
        openFile(m)
    }

    /** Файл из текста сообщения: трек - в проигрыватель, остальное - как обычный файл. */
    private fun openInline(m: GroupMessage) {
        if (view == null) return
        if (!(m.outgoing || m.isComplete())) {
            Toast.makeText(requireContext(), "⬇ " + (if (m.fileSize > 0) "${m.progress * 100 / m.fileSize}%" else ""), Toast.LENGTH_SHORT).show()
            return
        }
        if (com.stoyko.smessenger.media.MusicPlayer.isMusic(m.fileName) && m.filePath.isNotBlank()) {
            com.stoyko.smessenger.media.MusicPlayer.play(requireContext(), android.net.Uri.fromFile(java.io.File(m.filePath)), m.fileName)
        } else {
            openFile(m)
        }
    }

    private fun openFile(m: GroupMessage) {
        try {
            val file = File(m.filePath)
            val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            if (com.stoyko.smessenger.ui.chat.VoicePlayer.isVoice(m.fileName)) {
                com.stoyko.smessenger.ui.chat.VoicePlayer.toggle(requireContext(), m.filePath, uri, groupTitle)
                return
            }
            if (com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(m.fileName)) {
                com.stoyko.smessenger.ui.chat.VoicePlayer.toggle(requireContext(), m.filePath, uri, groupTitle, video = true)
                return
            }
            if (com.stoyko.smessenger.ui.chat.isStickerName(m.fileName)) {
                if (!m.outgoing) com.stoyko.smessenger.ui.chat.StickerLauncher.offerSave(requireContext(), file)
                return
            }
            if (file.extension.lowercase() in IMAGE_EXT) {
                com.stoyko.smessenger.ui.chat.MediaViewer.show(requireContext(), uri, m.fileName)
                return
            }
            // Видео - встроенный плеер, APK - установка, остальное - «Открыть с помощью».
            when {
                com.stoyko.smessenger.ui.chat.FileTypes.isVideo(m.fileName) ->
                    com.stoyko.smessenger.ui.chat.VideoPlayerDialog(requireContext(), uri, m.fileName).show()
                com.stoyko.smessenger.ui.chat.FileTypes.isApk(m.fileName) ->
                    com.stoyko.smessenger.ui.chat.FileTypes.install(requireContext(), uri, m.fileName)
                else -> {
                    // TXT/JSON с серверами VPN - предложить встроенный VPN.
                    val ctx = requireContext()
                    com.stoyko.smessenger.vpn.VpnShare.checkFile(ctx, uri, m.fileName) {
                        com.stoyko.smessenger.ui.chat.FileTypes.openWith(ctx, uri, m.fileName)
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.mimetype_handler_not_found, m.fileName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { binding.callPulse.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
    }

    override fun onResume() {
        super.onResume()
        vm.onVisible(true)
        // Новый участник - карточка приветствия (только у него, один раз на версию).
        binding.root.postDelayed({ maybeShowWelcome() }, 700)
        com.stoyko.smessenger.media.MiniPlayer.setAnchor(binding.toolbar)
        handler.removeCallbacks(transferTicker)
        handler.post(transferTicker)
        // Свайп вправо - назад, с резиновой оттяжкой (не из поля ввода и не из шапки).
        com.stoyko.smessenger.ui.anim.SwipeBack.enable(
            binding.root,
            { if (view != null) findNavController().popBackStack() },
            binding.inputBar, binding.toolbar, binding.callBar,
        )
    }

    override fun onPause() {
        com.stoyko.smessenger.ui.anim.SwipeBack.disable(binding.root)
        voiceRecorder.cancel()
        // Голосовое/«кружок» продолжает играть в других чатах (мини-плеер).
        handler.removeCallbacks(transferTicker)
        com.stoyko.smessenger.media.MiniPlayer.setAnchor(null)
        vm.onVisible(false)
        // Сворачивание / уход в фон - черновик сохраняется (кроме режима изменения сообщения).
        if (editing == null) com.stoyko.smessenger.ui.BackgroundSaver.saveDraft(requireContext(), "g_" + vm.chatId, binding.input.text?.toString().orEmpty())
        super.onPause()
    }

    private var voiceListener: (() -> Unit)? = null
    private var replyTo: String? = null
    private var editing: GroupMessage? = null
    private var isChannel = false
    private var commentsSheet: CommentsSheet? = null

    /** Комментарии к посту канала. */
    private fun openComments(post: GroupMessage) {
        val selfKey = vm.selfKey()
        fun items() = vm.commentsFor(post.msgUid).map { c ->
            CommentItem(
                c.msgUid,
                c.senderName.ifEmpty { c.senderKey.take(8) },
                com.stoyko.smessenger.chat.Markup.parseComment(c.message)?.second.orEmpty(),
                c.timestamp,
                c.outgoing || (selfKey.isNotEmpty() && c.senderKey == selfKey),
                com.stoyko.smessenger.chat.Markup.isEdited(c.message),
            )
        }
        val snippet = if (post.isFile()) groupQuote(post) else com.stoyko.smessenger.chat.Markup.snippet(post.message)
        val sheet = CommentsSheet(
            requireContext(),
            snippet,
            vm.isAdmin(),
            load = { items() },
            onSend = { vm.sendComment(post.msgUid, it) },
            onEdit = { c, t ->
                val target = vm.commentsFor(post.msgUid).firstOrNull { it.msgUid == c.uid }
                target != null && vm.editMessage(target, t)
            },
            onDelete = { c -> vm.commentsFor(post.msgUid).firstOrNull { it.msgUid == c.uid }?.let { vm.deleteForAll(it) } },
        )
        commentsSheet = sheet
        sheet.show()
    }

    private val reactionsListener: () -> Unit = {
        if (view != null) (binding.messages.adapter as? Adapter)?.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        com.stoyko.smessenger.chat.HtmlBubbles.clear()
        com.stoyko.smessenger.groups.GroupReactions.unlisten(reactionsListener)
        com.stoyko.smessenger.profile.GroupVideo.unlisten(reactionsListener)
        com.stoyko.smessenger.groups.ChannelStats.unlisten(reactionsListener)
        com.stoyko.smessenger.chat.VoiceTranscriber.unlisten(reactionsListener)
        ChannelTools.contactCheck = { false }
        com.stoyko.smessenger.groups.GroupSettings.unlisten(settingsListener)
        com.stoyko.smessenger.groups.Welcome.unlisten(welcomeListener)
        com.stoyko.smessenger.chat.Polls.unlisten(reactionsListener)
        pinnedBar = null
        if (com.stoyko.smessenger.ui.chat.VoicePlayer.onUpdate === voiceListener) {
            com.stoyko.smessenger.ui.chat.VoicePlayer.onUpdate = null
        }
        voiceListener = null
        infoSheet?.dismiss()
        infoSheet = null
        handler.removeCallbacks(transferTicker)
        stopPulse()
        super.onDestroyView()
    }
}
