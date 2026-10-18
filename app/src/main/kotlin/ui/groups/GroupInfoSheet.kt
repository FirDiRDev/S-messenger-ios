// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import com.stoyko.smessenger.R
import com.stoyko.smessenger.domain.tox.GROUP_ROLE_FOUNDER
import com.stoyko.smessenger.domain.tox.GROUP_ROLE_MODERATOR
import com.stoyko.smessenger.domain.tox.GROUP_ROLE_OBSERVER
import com.stoyko.smessenger.domain.tox.GroupPeerInfo
import com.stoyko.smessenger.groups.GroupProfile

/**
 * «Информация» о группе/канале: аватарка, название, ID и список участников.
 * Владелец и модераторы меняют название и аватарку, удаляют участников; владелец назначает
 * администраторов.
 */
class GroupInfoSheet(
    private val fragment: Fragment,
    private val vm: GroupChatViewModel,
    private val onPickAvatar: () -> Unit,
    private val onInvite: () -> Unit,
    private val onShareId: () -> Unit,
) {
    private var dialog: BottomSheetDialog? = null
    private var rebuild: (() -> Unit)? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Название/аватарка/участники изменились - перерисовать, если окно открыто. */
    fun refresh() {
        rebuild?.invoke()
    }

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
        rebuild = null
    }

    fun show() {
        val ctx = fragment.requireContext()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val accent = ContextCompat.getColor(ctx, R.color.colorPrimary)

        val sheet = BottomSheetDialog(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(24))
        }
        val scroll = ScrollView(ctx).apply {
            setBackgroundResource(R.drawable.bg_sheet)
            addView(content)
        }

        fun build() {
            content.removeAllViews()
            val g = vm.group.value
            val admin = vm.isAdmin()
            val founder = vm.selfRole() == GROUP_ROLE_FOUNDER
            val channel = g?.isChannel == true

            // Аватарка
            val avatarSize = dp(96)
            val avatarBox = FrameLayout(ctx).apply { setBackgroundResource(R.drawable.bg_avatar_circle) }
            val avatar = ImageView(ctx)
            val bmp = GroupProfile.roundBitmap(ctx, vm.chatId, avatarSize)
            if (bmp != null) {
                avatar.setImageBitmap(bmp)
                avatarBox.addView(avatar, FrameLayout.LayoutParams(avatarSize, avatarSize))
            } else {
                avatar.setImageResource(if (channel) R.drawable.ic_channel else R.drawable.ic_group)
                avatarBox.addView(avatar, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            }
            content.addView(avatarBox, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
            if (admin) {
                avatarBox.setOnClickListener {
                    val items = mutableListOf<CharSequence>(ctx.getString(R.string.group_avatar_choose))
                    if (GroupProfile.avatarFile(ctx, vm.chatId) != null) items += ctx.getString(R.string.group_avatar_remove)
                    AlertDialog.Builder(ctx)
                        .setItems(items.toTypedArray()) { _, which ->
                            if (which == 0) {
                                onPickAvatar()
                            } else {
                                fragment.viewLifecycleOwner.lifecycleScope.launch {
                                    vm.setAvatar(null)
                                    build()
                                }
                            }
                        }
                        .show()
                }
                content.addView(
                    TextView(ctx).apply {
                        setText(R.string.group_avatar_change)
                        setTextColor(accent)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, 0)
                        setOnClickListener { avatarBox.performClick() }
                    },
                )
            }

            // Название
            val title = g?.name?.ifEmpty { null } ?: ctx.getString(R.string.group_default_name)
            content.addView(
                TextView(ctx).apply {
                    text = if (admin) "$title  ✏️" else title
                    setTextColor(primary)
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(10), dp(20), 0)
                    if (admin) setOnClickListener { askRename(title) }
                },
            )
            val kind = ctx.getString(if (channel) R.string.channel else R.string.group)
            content.addView(
                TextView(ctx).apply {
                    text = kind + " · " + ctx.getString(R.string.members_count, g?.peerCount ?: 0)
                    setTextColor(secondary)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, dp(8))
                },
            )
            // Описание («о группе»): видно всем, меняют владелец и администраторы.
            val desc = GroupProfile.description(ctx, vm.chatId)
            if (desc.isNotBlank() || admin) {
                content.addView(
                    TextView(ctx).apply {
                        text = desc.ifBlank { ctx.getString(R.string.group_description_add) }
                        setTextColor(if (desc.isBlank()) accent else primary)
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setPadding(dp(24), dp(2), dp(24), dp(12))
                        setTextIsSelectable(!admin)
                        if (admin) setOnClickListener { askDescription(desc) }
                    },
                )
            }

            // Правила и режимы группы - видны всем.
            val st = com.stoyko.smessenger.groups.GroupSettings.get(ctx, vm.chatId)
            val modes = listOfNotNull(
                if (st.slowSeconds > 0) ctx.getString(R.string.gs_slow_on, slowLabel(ctx, st.slowSeconds)) else null,
                if (st.adminMediaOnly) ctx.getString(R.string.gs_media_on) else null,
            )
            if (st.rules.isNotBlank() || modes.isNotEmpty()) {
                content.addView(
                    TextView(ctx).apply {
                        text = (if (st.rules.isNotBlank()) "📜 " + st.rules else "") +
                            (if (modes.isNotEmpty()) (if (st.rules.isNotBlank()) "\n\n" else "") + modes.joinToString("\n") else "")
                        textSize = 13f
                        setTextColor(primary)
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 14 * d
                            setColor(0x145B4BDB)
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(16), 0, dp(16), dp(10))
                    },
                )
            }

            // Кнопки - красивые объёмные клавиши без эмодзи, по две в ряд, все одного размера.
            val tiles = ArrayList<View>()
            @Suppress("UNUSED_PARAMETER")
            fun action(icon: String, label: Int, click: () -> Unit) {
                val colors = com.stoyko.smessenger.ui.Btn.PALETTE[tiles.size % com.stoyko.smessenger.ui.Btn.PALETTE.size]
                tiles += com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(label), colors) { click() }
            }
            action("➕", R.string.group_invite_short) { onInvite() }
            action("🔗", R.string.group_share_id) { onShareId() }
            action("📋", R.string.group_copy_id) {
                com.stoyko.smessenger.ui.ShareId.copy(ctx, vm.chatId)
            }
            if (admin) action("✏️", R.string.group_edit) { askEdit(title) }
            // Администраторам: медленный режим, медиа, правила.
            if (admin) action("⚙️", R.string.gs_title) { settingsDialog() }
            if (channel) {
                // Каталог товаров канала - всем подписчикам.
                action("🛍", R.string.channel_catalog_short) {
                    dismiss()
                    (fragment as? GroupChatFragment)?.openCatalog()
                }
            }
            if (admin && channel) {
                action("➕", R.string.channel_new_product_short) {
                    dismiss()
                    (fragment as? GroupChatFragment)?.newProductCard()
                }
                action("🎓", R.string.channel_tutorial_short) {
                    ChannelTutorial.show(ctx) {
                        ChannelTools.businessEditor(ctx, vm.chatId, vm.myId()) { change -> vm.pushSettings(change).also { refresh() } }
                    }
                }
                action("💼", R.string.channel_business_short) {
                    ChannelTools.businessEditor(ctx, vm.chatId, vm.myId()) { change -> vm.pushSettings(change).also { refresh() } }
                }
                action("📊", R.string.channel_stats_short) {
                    ChannelTools.statsDialog(ctx, vm.chatId, vm.messages.value.orEmpty(), vm.group.value?.peerCount ?: 0)
                }
            }
            // Больше 4 кнопок - сеткой по 3 (иначе подписи не помещаются).
            // Всегда ровная сетка: плитки одного размера, по 3 в ряд (4 - если плиток ровно 4).
            val actions = niceGrid(ctx, tiles).apply {
                setPadding(dp(16), 0, dp(16), dp(8))
            }
            content.addView(actions)
            com.stoyko.smessenger.ui.Btn.animateIn(tiles)
            // Бизнес-информация канала (сайт, телефон, адрес, часы, «Написать владельцу») - всем.
            if (channel) ChannelTools.businessBlock(ctx, vm.chatId, fragment)?.let { content.addView(it) }

            // Участники
            val header = TextView(ctx).apply {
                setText(if (channel) R.string.group_subscribers else R.string.group_members)
                setTextColor(accent)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(20), dp(14), dp(20), dp(6))
            }
            content.addView(header)
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            content.addView(list)
            val loading = ProgressBar(ctx)
            list.addView(loading, LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL })

            fun roleLabel(role: Int): String = ctx.getString(
                when (role) {
                    GROUP_ROLE_FOUNDER -> R.string.group_role_owner
                    GROUP_ROLE_MODERATOR -> R.string.group_role_admin
                    GROUP_ROLE_OBSERVER -> R.string.group_role_reader
                    else -> if (channel) R.string.group_role_subscriber else R.string.group_role_member
                },
            )

            fun row(name: String, sub: String, letter: String, click: (() -> Unit)?, key: String? = null) {
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    if (click != null) {
                        setBackgroundResource(android.R.attr.selectableItemBackground.let { attr ->
                            val tv = android.util.TypedValue()
                            ctx.theme.resolveAttribute(attr, tv, true)
                            tv.resourceId
                        })
                        setOnClickListener { click() }
                    }
                }
                if (key != null) {
                    // Участник: кружок с буквой, а если у него есть видео профиля - живое видео.
                    val box = FrameLayout(ctx)
                    r.addView(box, LinearLayout.LayoutParams(dp(40), dp(40)))
                    com.stoyko.smessenger.profile.GroupVideo.bindAvatar(box, key, name)
                } else {
                    r.addView(
                        TextView(ctx).apply {
                            text = letter
                            textSize = 17f
                            setTextColor(0xFFFFFFFF.toInt())
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_avatar_circle)
                        },
                        LinearLayout.LayoutParams(dp(40), dp(40)),
                    )
                }
                r.addView(
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), 0, 0, 0)
                        addView(TextView(ctx).apply { text = name; textSize = 16f; setTextColor(primary); maxLines = 1 })
                        addView(TextView(ctx).apply { text = sub; textSize = 13f; setTextColor(secondary); maxLines = 1 })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                list.addView(r)
            }

            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val members = runCatching { vm.members() }.getOrDefault(emptyList())
                    .sortedWith(compareBy<GroupPeerInfo> { it.role }.thenBy { it.name.lowercase() })
                if (dialog == null) return@launch
                list.removeAllViews()
                header.text = ctx.getString(if (channel) R.string.group_subscribers_n else R.string.group_members_n, members.size + 1)
                row(ctx.getString(R.string.group_you), roleLabel(vm.selfRole()), "★", null)
                if (members.isEmpty()) {
                    list.addView(
                        TextView(ctx).apply {
                            setText(R.string.group_members_empty)
                            setTextColor(secondary)
                            textSize = 14f
                            setPadding(dp(20), dp(10), dp(20), dp(10))
                        },
                    )
                }
                for (m in members) {
                    val name = m.name.ifBlank { ctx.getString(R.string.contact_default_name) }
                    val canKick = admin && m.role > vm.selfRole()
                    val canPromote = founder && m.role != GROUP_ROLE_FOUNDER
                    val click: (() -> Unit)? = if (canKick || canPromote) ({ memberMenu(m, name, canKick, canPromote) }) else null
                    row(name, roleLabel(m.role) + " · " + m.key.take(8), name.take(1).uppercase(), click, key = m.key)
                }
                com.stoyko.smessenger.ui.Kinetics.stagger((0 until list.childCount).map { list.getChildAt(it) })
            }
        }

        rebuild = { if (dialog != null) build() }
        build()
        sheet.setContentView(scroll)
        (scroll.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.setOnDismissListener {
            dialog = null
            rebuild = null
        }
        dialog = sheet
        sheet.show()
    }

    /** «Редактировать»: название, описание и фото в одном окне. */
    private fun askEdit(currentTitle: String) {
        val ctx = fragment.requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.group_edit)
            .setItems(
                arrayOf<CharSequence>(
                    ctx.getString(R.string.group_rename),
                    ctx.getString(R.string.group_description),
                    ctx.getString(R.string.group_avatar_change),
                ),
            ) { _, which ->
                when (which) {
                    0 -> askRename(currentTitle)
                    1 -> askDescription(GroupProfile.description(ctx, vm.chatId))
                    else -> onPickAvatar()
                }
            }
            .show()
    }

    private fun askDescription(current: String) {
        val ctx = fragment.requireContext()
        val field = android.widget.EditText(ctx).apply {
            setText(current)
            setSelection(text.length)
            minLines = 2
            maxLines = 6
            setHint(R.string.group_description_hint)
            filters = arrayOf(android.text.InputFilter.LengthFilter(GroupProfile.MAX_DESCRIPTION))
        }
        val box = FrameLayout(ctx).apply {
            val p = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.group_description)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val ok = vm.setDescription(field.text.toString())
                    toast(ctx, if (ok) R.string.group_description_saved else R.string.group_no_rights)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askRename(current: String) {
        val ctx = fragment.requireContext()
        val field = android.widget.EditText(ctx).apply {
            setSingleLine()
            setText(current)
            setSelection(text.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        val box = FrameLayout(ctx).apply {
            val p = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.group_rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val ok = vm.rename(name)
                    toast(ctx, if (ok) R.string.group_renamed else R.string.group_no_rights)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun memberMenu(m: GroupPeerInfo, name: String, canKick: Boolean, canPromote: Boolean) {
        val ctx = fragment.requireContext()
        val actions = ArrayList<Pair<CharSequence, () -> Unit>>()
        if (canPromote) {
            if (m.role == GROUP_ROLE_MODERATOR) {
                actions += ctx.getString(R.string.group_admin_remove) to {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        toast(ctx, if (vm.setModerator(m.peerId, false)) R.string.group_admin_removed else R.string.group_no_rights)
                        refresh()
                    }
                    Unit
                }
            } else {
                actions += ctx.getString(R.string.group_admin_make) to {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        toast(ctx, if (vm.setModerator(m.peerId, true)) R.string.group_admin_made else R.string.group_no_rights)
                        refresh()
                    }
                    Unit
                }
            }
        }
        if (canKick) {
            // Запретить писать (останется читателем) / снова разрешить.
            val muted = m.role == GROUP_ROLE_OBSERVER
            actions += ctx.getString(if (muted) R.string.gs_unmute else R.string.gs_mute) to {
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    toast(ctx, if (vm.setMuted(m.peerId, !muted)) (if (muted) R.string.gs_unmuted else R.string.gs_muted) else R.string.group_no_rights)
                    refresh()
                }
                Unit
            }
            actions += ctx.getString(R.string.group_kick) to {
                AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.group_kick_confirm, name))
                    .setPositiveButton(R.string.group_kick) { _, _ ->
                        fragment.viewLifecycleOwner.lifecycleScope.launch {
                            toast(ctx, if (vm.kick(m.peerId)) R.string.group_kicked else R.string.group_kick_failed)
                            refresh()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                Unit
            }
        }
        actions += ctx.getString(R.string.group_copy_member_key) to {
            com.stoyko.smessenger.ui.ShareId.copy(ctx, m.key)
        }
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun toast(ctx: Context, res: Int) = Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()

    private fun slowLabel(ctx: Context, s: Int): String = when {
        s <= 0 -> ctx.getString(R.string.gs_slow_off)
        s < 60 -> ctx.getString(R.string.gs_seconds, s)
        s < 3600 -> ctx.getString(R.string.gs_minutes, s / 60)
        else -> ctx.getString(R.string.gs_hours, s / 3600)
    }

    fun openSettings() = settingsDialog()

    /**
     * Настройки группы/канала для администраторов - сетка одинаковых плиток: каждая показывает
     * текущее значение и меняет его одним нажатием (переключатели) или открывает короткий выбор.
     * Изменения сразу уходят всем участникам.
     */
    private fun settingsDialog() {
        val ctx = fragment.requireContext()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val channel = vm.group.value?.isChannel == true
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(grid)
        }
        fun on(v: Boolean) = ctx.getString(if (v) R.string.gs_on else R.string.gs_off)
        fun save(change: (com.stoyko.smessenger.groups.GroupSettings.Settings) -> com.stoyko.smessenger.groups.GroupSettings.Settings) {
            if (!vm.pushSettings(change)) toast(ctx, R.string.group_no_rights)
            refresh()
        }
        lateinit var build: () -> Unit
        @Suppress("UNUSED_PARAMETER")
        fun tile(emoji: String, title: String, value: String, click: () -> Unit): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(0x1F5B4BDB)
                setStroke(dp(1), 0x335B4BDB)
            }
            isClickable = true
            // Без эмодзи: только название и состояние, на мягкой цветной подложке.
            addView(
                TextView(ctx).apply {
                    text = title
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(4) },
            )
            addView(
                TextView(ctx).apply {
                    text = value
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFF5B4BDB.toInt())
                },
            )
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                click()
            }
            com.stoyko.smessenger.ui.Kinetics.squish(this, 0.94f)
        }
        build = {
            val st = com.stoyko.smessenger.groups.GroupSettings.get(ctx, vm.chatId)
            val tiles = ArrayList<View>()
            tiles += tile("🐢", ctx.getString(R.string.gs_tile_slow), slowLabel(ctx, st.slowSeconds)) {
                val opts = com.stoyko.smessenger.groups.GroupSettings.SLOW_OPTIONS
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.gs_slow_title)
                    .setSingleChoiceItems(opts.map { slowLabel(ctx, it) as CharSequence }.toTypedArray(), opts.indexOf(st.slowSeconds).coerceAtLeast(0)) { dd, w ->
                        dd.dismiss()
                        save { it.copy(slowSeconds = opts[w]) }
                        build()
                    }
                    .show()
            }
            tiles += tile("🖼", ctx.getString(R.string.gs_tile_media), ctx.getString(if (st.adminMediaOnly) R.string.gs_media_admins else R.string.gs_media_all)) {
                save { it.copy(adminMediaOnly = !it.adminMediaOnly) }
                build()
            }
            tiles += tile("📜", ctx.getString(R.string.gs_tile_rules), if (st.rules.isBlank()) ctx.getString(R.string.gs_off) else st.rules.take(24)) {
                val rules = android.widget.EditText(ctx).apply {
                    setHint(R.string.gs_rules_hint)
                    setText(st.rules)
                    minLines = 3
                    maxLines = 8
                    filters = arrayOf(android.text.InputFilter.LengthFilter(600))
                }
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.gs_tile_rules)
                    .setView(android.widget.FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(rules) })
                    .setPositiveButton(R.string.save) { _, _ ->
                        save { it.copy(rules = rules.text.toString().trim()) }
                        build()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            // Приветствие для новых участников (видят только они).
            tiles += tile("👋", ctx.getString(R.string.welcome_menu), com.stoyko.smessenger.groups.Welcome.summary(ctx, vm.chatId)) {
                (fragment as? GroupChatFragment)?.editWelcome()
                dialog?.dismiss()
            }
            if (st.pinnedUid.isNotBlank()) {
                tiles += tile("📌", ctx.getString(R.string.gs_tile_pin), st.pinnedText.ifBlank { "…" }.take(20)) {
                    save { it.copy(pinnedUid = "", pinnedText = "") }
                    build()
                }
            }
            if (channel) {
                tiles += tile("✍️", ctx.getString(R.string.gs_tile_sign), on(st.signatures)) {
                    save { it.copy(signatures = !it.signatures) }
                    build()
                }
                tiles += tile("💬", ctx.getString(R.string.gs_tile_comments), on(st.comments)) {
                    save { it.copy(comments = !it.comments) }
                    build()
                }
                tiles += tile("💼", ctx.getString(R.string.channel_business_short), if (st.hasBusiness) ctx.getString(R.string.gs_on) else ctx.getString(R.string.gs_tile_fill)) {
                    ChannelTools.businessEditor(ctx, vm.chatId, vm.myId()) { change -> vm.pushSettings(change).also { refresh(); build() } }
                }
                tiles += tile("📊", ctx.getString(R.string.channel_stats_short), "") {
                    ChannelTools.statsDialog(ctx, vm.chatId, vm.messages.value.orEmpty(), vm.group.value?.peerCount ?: 0)
                }
                tiles += tile("🎓", ctx.getString(R.string.channel_tutorial_short), "") {
                    ChannelTutorial.show(ctx) {
                        ChannelTools.businessEditor(ctx, vm.chatId, vm.myId()) { change -> vm.pushSettings(change).also { refresh() } }
                    }
                }
            }
            grid.removeAllViews()
            grid.addView(com.stoyko.smessenger.ui.Btn.grid(ctx, tiles, cols = 3, height = 76, gap = 8))
        }
        build()
        box.addView(
            TextView(ctx).apply {
                setText(R.string.gs_tiles_hint)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
                setPadding(dp(4), dp(10), dp(4), 0)
            },
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gs_title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.close, null)
            .show()
    }
}

/** Ряды красивых кнопок по две; нечётная последняя - на всю ширину (без пустых мест). */
private fun niceGrid(ctx: android.content.Context, views: List<View>): LinearLayout {
    val d = ctx.resources.displayMetrics.density
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    views.chunked(2).forEachIndexed { r, pair ->
        val row = com.stoyko.smessenger.ui.Btn.row(ctx, pair, height = 56, gap = 10)
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (r > 0) topMargin = (10 * d).toInt()
        })
    }
    return box
}
