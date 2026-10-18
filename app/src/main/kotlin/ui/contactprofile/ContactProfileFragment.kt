// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactprofile

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentContactProfileBinding
import com.stoyko.smessenger.requireStringArg
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.PublicKey

class ContactProfileFragment : BaseFragment<FragmentContactProfileBinding>(FragmentContactProfileBinding::inflate) {
    private val viewModel: ContactProfileViewModel by viewModels { vmFactory }

    private var online = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        // Сезонный снег / листья - за аватаркой, без гирлянды поверх шапки.
        com.stoyko.smessenger.ui.SeasonTint.noGarland(appBar)
        com.stoyko.smessenger.ui.Seasonal.decorateBackground(profileHeader)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            appBar.updatePadding(left = insets.left, right = insets.right, top = insets.top)
            content.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            profileHeaderInner.updatePadding(top = insets.top + (56 * resources.displayMetrics.density).toInt())
            compat
        }

        // Шапка: при прокрутке аватар уменьшается и тает, а сверху проявляется
        // «стеклянная» панель с именем.
        appBar.tintColor = 0xD95B4BDB.toInt()
        appBar.fallbackColor = 0xFF5B4BDB.toInt()
        appBar.edgeTop = false
        appBar.edgeBottom = true
        appBar.blurSource = scroll
        appBar.glassAlpha = 0f
        scroll.setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView, _, y, _, _ ->
            val range = (profileHeader.height - appBar.height).coerceAtLeast(1)
            val f = (y.toFloat() / range).coerceIn(0f, 1f)
            appBar.glassAlpha = ((f - 0.6f) / 0.4f).coerceIn(0f, 1f)
            toolbar.title = if (f > 0.85f) headerMainText.text else ""
            toolbar.subtitle = if (f > 0.85f) headerStatus.text else ""
            val scale = 1f - 0.35f * f
            com.stoyko.smessenger.profile.ProfileVideo.outer(avatarImageView).scaleX = scale
            com.stoyko.smessenger.profile.ProfileVideo.outer(avatarImageView).scaleY = scale
            com.stoyko.smessenger.profile.ProfileVideo.outer(avatarImageView).translationY = y * 0.45f
            com.stoyko.smessenger.profile.ProfileVideo.outer(avatarImageView).alpha = 1f - f
            headerMainText.alpha = 1f - f * 1.3f
            headerStatus.alpha = 1f - f * 1.5f
        }
        avatarImageView.alpha = 0f
        avatarImageView.scaleX = 0.6f
        avatarImageView.scaleY = 0.6f
        avatarImageView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        viewModel.publicKey = PublicKey(requireStringArg(CONTACT_PUBLIC_KEY))
        val pk = requireStringArg(CONTACT_PUBLIC_KEY)
        // Значок разработчика - по открытому ключу контакта (подделать нельзя): крупная
        // золотая плашка прямо над витриной подарков.
        val isDesigner = com.stoyko.smessenger.chat.DevAccount.isDesigner(pk)
        val isTester = !isDesigner && com.stoyko.smessenger.chat.DevAccount.isTester(pk)
        if (com.stoyko.smessenger.chat.DevAccount.isDev(pk) || isDesigner || isTester) {
            val giftsParent = giftsBox.parent as? android.view.ViewGroup
            if (giftsParent != null && giftsParent.findViewWithTag<View>("dev_big_badge") == null) {
                val m = (16 * resources.displayMetrics.density).toInt()
                giftsParent.addView(
                    com.stoyko.smessenger.chat.DevAccount.bigBadge(requireContext(), designer = isDesigner, tester = isTester),
                    giftsParent.indexOfChild(giftsBox),
                    android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(m, m / 2, m, m / 2)
                    },
                )
            }
            // У разработчика под плашкой - красивая кнопка его канала.
            if (com.stoyko.smessenger.chat.DevAccount.isDev(pk) && giftsParent != null && giftsParent.findViewWithTag<View>("dev_extras") == null) {
                val m = (16 * resources.displayMetrics.density).toInt()
                giftsParent.addView(
                    com.stoyko.smessenger.chat.DevAccount.devExtras(requireContext(), mine = false),
                    giftsParent.indexOfChild(giftsBox),
                    android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(m, m / 2, m, m / 2)
                    },
                )
            }
            headerMainText.setTextColor(if (isDesigner) com.stoyko.smessenger.chat.DevAccount.LILAC else com.stoyko.smessenger.chat.DevAccount.GOLD)
        }

        // «Написать»: если пришли из этого чата - просто возвращаемся в него.
        actionMessage.setOnClickListener {
            val nav = findNavController()
            if (nav.previousBackStackEntry?.destination?.id == R.id.chatFragment) {
                nav.popBackStack()
            } else {
                nav.navigate(
                    R.id.action_contactProfileFragment_to_chatFragment,
                    androidx.core.os.bundleOf(CONTACT_PUBLIC_KEY to pk),
                )
            }
        }
        actionCall.setOnClickListener {
            if (!online) {
                android.widget.Toast.makeText(requireContext(), R.string.call_contact_offline, android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            findNavController().navigate(
                R.id.action_contactProfileFragment_to_callFragment,
                androidx.core.os.bundleOf(CONTACT_PUBLIC_KEY to pk),
            )
        }
        actionVideo.setOnClickListener {
            if (!online) {
                android.widget.Toast.makeText(requireContext(), R.string.call_contact_offline, android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            findNavController().navigate(
                R.id.action_contactProfileFragment_to_callFragment,
                androidx.core.os.bundleOf(CONTACT_PUBLIC_KEY to pk, com.stoyko.smessenger.ui.call.ARG_VIDEO to true),
            )
        }
        // «Поделиться»: полный ID контакта (76 символов) - чтобы другой друг мог его добавить.
        actionShare.setOnClickListener { shareContactId(pk) }
        // Подарок: выбрать живой эмодзи, написать записку.
        actionGift.setOnClickListener {
            com.stoyko.smessenger.chat.Gifts.pick(requireContext(), contactDisplayName()) { gift, note ->
                if (view == null) return@pick
                // Друг не в сети: подарок встаёт в очередь (как обычное сообщение) и уйдёт сам,
                // как только он появится; в чате карточка видна сразу.
                if (!online) {
                    val app = requireActivity().application as com.stoyko.smessenger.App
                    val queued = runCatching {
                        val text = com.stoyko.smessenger.chat.Gifts.message(gift, note, com.stoyko.smessenger.chat.Gifts.mySign(requireContext()))
                        app.component.chatManager().sendMessage(com.stoyko.smessenger.core.vo.PublicKey(pk), text)
                        true
                    }.getOrDefault(false)
                    if (queued) {
                        com.stoyko.smessenger.chat.Gifts.sendAway(actionGift, gift)
                        android.widget.Toast.makeText(requireContext(), getString(R.string.gift_queued, gift.name()), android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(requireContext(), R.string.gift_offline, android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@pick
                }
                val text = com.stoyko.smessenger.chat.Gifts.send(viewModel.toxCore, pk, gift, note, com.stoyko.smessenger.chat.Gifts.mySign(requireContext()))
                if (text != null) {
                    // Подарок появляется и в чате - карточкой с анимацией (у друга - с кнопками «Принять» / «Отклонить»).
                    runCatching {
                        val repo = (requireActivity().application as com.stoyko.smessenger.App).component.messageRepository()
                        val msg = com.stoyko.smessenger.core.vo.Message(
                            pk,
                            text,
                            com.stoyko.smessenger.core.vo.Sender.Sent,
                            com.stoyko.smessenger.core.vo.MessageType.Normal,
                            Int.MIN_VALUE,
                            System.currentTimeMillis(),
                        )
                        Thread { runCatching { repo.add(msg) } }.start()
                    }
                    // Подарок улетает к другу; в его профиле он появится, только когда тот примет.
                    com.stoyko.smessenger.chat.Gifts.sendAway(actionGift, gift)
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.gift_sent, gift.name()),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.widget.Toast.makeText(requireContext(), R.string.gift_offline, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        fun copy(text: CharSequence) {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("tox", text))
            android.widget.Toast.makeText(requireContext(), R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
        }
        contactPublicKeyRow.setOnClickListener { idMenu(pk) }
        contactStatusMessageRow.setOnClickListener { copy(contactStatusMessage.text) }
        // Код безопасности: нажатие - живая проверка сквозного шифрования с анимацией.
        e2eCheckButton.setOnClickListener {
            if (!viewModel.toxCore.started) return@setOnClickListener
            com.stoyko.smessenger.chat.E2eCheck.show(
                requireContext(),
                viewLifecycleOwner.lifecycleScope,
                viewModel.toxCore,
                pk,
                headerMainText.text?.toString().orEmpty().ifBlank { getString(R.string.contact_default_name) },
                viewModel.contact.value?.connectionStatus ?: ConnectionStatus.None,
            )
        }
        // Нажатие на имя: «Переименовать» (своё имя для контакта) или «Скопировать».
        contactNameRow.setOnClickListener {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setItems(arrayOf<CharSequence>(getString(R.string.contact_rename), getString(R.string.copy))) { _, which ->
                    if (which == 1) {
                        copy(contactName.text)
                        return@setItems
                    }
                    val field = android.widget.EditText(ctx).apply {
                        setText(contactName.text)
                        setSelection(text.length)
                        setSingleLine()
                    }
                    val box = android.widget.FrameLayout(ctx).apply {
                        val p = (20 * resources.displayMetrics.density).toInt()
                        setPadding(p, p / 2, p, 0)
                        addView(field)
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.contact_rename)
                        .setMessage(R.string.contact_rename_hint)
                        .setView(box)
                        .setPositiveButton(R.string.save) { _, _ -> viewModel.rename(field.text.toString()) }
                        .setNeutralButton(R.string.contact_rename_reset) { _, _ -> viewModel.rename("") }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .show()
        }

        // Общие медиа, файлы, голосовые и ссылки.
        val shared = SharedMediaView(requireContext())
        sharedMediaBox.addView(shared)
        viewLifecycleOwner.lifecycleScope.launch {
            val data = runCatching { viewModel.shared() }.getOrNull() ?: return@launch
            if (view != null) shared.bind(data)
        }

        listOf(actionMessage, actionCall, actionVideo, actionShare, actionGift).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 30f
            v.animate().alpha(1f).translationY(0f).setStartDelay(80L * i).setDuration(300).start()
        }
        // Полный ID может прийти позже (когда контакт появится в сети) - обновляем строку сразу.
        val idPrefs = requireContext().getSharedPreferences("contact_full_ids", android.content.Context.MODE_PRIVATE)
        val idListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key.equals(pk, ignoreCase = true)) view.post { showId(pk) }
        }
        idPrefs.registerOnSharedPreferenceChangeListener(idListener)
        viewLifecycleOwner.lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    idPrefs.unregisterOnSharedPreferenceChangeListener(idListener)
                }
            },
        )
        viewModel.contact.observe(viewLifecycleOwner) { contact ->
            online = contact.connectionStatus != ConnectionStatus.None
            contact.name = contact.name.ifEmpty { getString(R.string.contact_default_name) }

            headerMainText.text = contact.name
            // Подарки, которые получил собеседник (из его карточки профиля).
            val giftLine = com.stoyko.smessenger.profile.ProfileCard.peer(requireContext(), contact.publicKey)?.gifts.orEmpty()
            if (giftsBox.tag != giftLine) {
                giftsBox.tag = giftLine
                com.stoyko.smessenger.chat.Gifts.fillSection(giftsBox, emptyList(), giftLine)
            }
            // Монеты и рекорды собеседника + «Все подарки» и «Подарить монеты».
            bindPeerStats(giftsBox, contact.publicKey)
            // Живой эмодзи-статус собеседника рядом с именем.
            com.stoyko.smessenger.chat.AnimatedEmoji.attachStatus(
                headerMainText,
                com.stoyko.smessenger.profile.ProfileCard.peer(requireContext(), contact.publicKey)?.emojiStatus,
                28,
            )
            avatarImageView.setFrom(contact)
            // Видео профиля друга - живая аватарка (без звука, по кругу).
            com.stoyko.smessenger.profile.ProfileVideo.attachOver(
                avatarImageView,
                com.stoyko.smessenger.profile.ProfileVideo.peer(requireContext(), contact.publicKey),
            )
            // Фото на весь экран (с увеличением) и все фото профиля - листаются свайпом.
            val pk = contact.publicKey
            val avatar = contact.avatarUri
            avatarImageView.setOnClickListener {
                val ctx = requireContext()
                val items = ArrayList<Pair<android.net.Uri, String>>()
                if (avatar.isNotEmpty()) items += android.net.Uri.parse(avatar) to "avatar.jpg"
                com.stoyko.smessenger.profile.ProfilePhotos.peer(ctx, pk).forEach { items += android.net.Uri.fromFile(it) to it.name }
                if (items.isNotEmpty()) com.stoyko.smessenger.ui.chat.MediaViewer.showList(ctx, items, 0)
            }
            val extra = com.stoyko.smessenger.profile.ProfilePhotos.peer(requireContext(), pk).size
            avatarImageView.contentDescription = if (extra > 0) getString(R.string.photos_count, extra + 1) else null

            showId(contact.publicKey)
            securityCode.text = viewModel.securityCode()
            contactName.text = contact.name
            contactStatusMessage.text = contact.statusMessage
            contactStatusMessageRow.visibility = if (contact.statusMessage.isBlank()) View.GONE else View.VISIBLE
            // Расширенный профиль собеседника (о себе, город, день рождения...).
            (contactStatusMessageRow.parent as? android.view.ViewGroup)?.let { parent ->
                parent.findViewWithTag<View>("ext_profile")?.let { parent.removeView(it) }
                val p = com.stoyko.smessenger.profile.ProfileCard.peer(requireContext(), contact.publicKey)
                if (p != null && !p.isEmpty) {
                    val box = android.widget.LinearLayout(requireContext()).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        tag = "ext_profile"
                    }
                    listOf(
                        R.string.profile_username to p.username.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
                        R.string.profile_bio to p.bio,
                        R.string.profile_city to p.city,
                        R.string.profile_birthday to p.birthday,
                        R.string.profile_link to p.link,
                    ).filter { it.second.isNotBlank() }.forEach { (t, v) ->
                        box.addView(com.stoyko.smessenger.ui.userprofile.infoRow(requireContext(), getString(t), v))
                    }
                    parent.addView(box, parent.indexOfChild(contactStatusMessageRow) + 1)
                }
            }
            contactConnectionStatus.text = when (contact.connectionStatus) {
                ConnectionStatus.None -> getText(R.string.atox_offline)
                ConnectionStatus.TCP -> getText(R.string.atox_connected_with_tcp)
                ConnectionStatus.UDP -> getText(R.string.atox_connected_with_udp)
            }
            // Точка «в сети» больше не лежит на аватарке (налезала на фото) - она стоит перед строкой статуса.
            onlineDot.visibility = View.GONE
            run {
                val dd = resources.displayMetrics.density
                val dot = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (online) 0xFF22C55E.toInt() else 0x99FFFFFF.toInt())
                    setStroke((1.5f * dd).toInt(), 0x66FFFFFF)
                    setSize((10 * dd).toInt(), (10 * dd).toInt())
                }
                headerStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                headerStatus.compoundDrawablePadding = (7 * dd).toInt()
                headerStatus.gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val lastSeen = if (online) null else com.stoyko.smessenger.tox.Presence.lastSeenText(requireContext(), contact.publicKey)
            headerStatus.text = if (online) {
                getString(R.string.profile_online)
            } else if (lastSeen != null) {
                // Точное «был(а) в сети …» (запоминается, когда контакт уходит из сети).
                lastSeen
            } else if (contact.lastMessage > 0) {
                getString(
                    R.string.profile_last_seen,
                    android.text.format.DateUtils.getRelativeTimeSpanString(contact.lastMessage).toString(),
                )
            } else {
                getString(R.string.profile_offline)
            }
        }
    }

    /** Полный ID (76), если он известен; иначе показываем ключ (64) и подсказку. */
    private fun showId(pk: String) {
        if (view == null) return
        val b = binding
        val full = com.stoyko.smessenger.profile.ContactIds.get(requireContext(), pk)
        b.contactPublicKey.text = full ?: pk
        b.contactIdLabel.setText(if (full != null) R.string.contact_full_id else R.string.contact_key_only)
    }

    private fun bindPeerStats(anchor: View, pk: String) {
        val ctx = context ?: return
        val parent = anchor.parent as? android.view.ViewGroup ?: return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val card = com.stoyko.smessenger.profile.ProfileCard.peer(ctx, pk)
        val box = parent.findViewWithTag<android.widget.LinearLayout>("peer_stats") ?: android.widget.LinearLayout(ctx).apply {
            tag = "peer_stats"
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            parent.addView(this, parent.indexOfChild(anchor) + 1)
        }
        // Строка монет и рекордов обновляется на месте; кнопки создаются один раз -
        // раньше весь блок пересобирался при каждом обновлении контакта (в том числе
        // прямо под пальцем), и нажатие попадало не в ту кнопку.
        val coins = card?.coins ?: -1
        val g = card?.games.orEmpty()
        val snake = Regex("s:(\\d+):(\\d+)").find(g)
        val chess = Regex("c:(\\d+)").find(g)
        val durak = Regex("d:(\\d+)").find(g)
        val statsText = if (coins < 0) "" else resources.getQuantityString(R.plurals.coins_count, coins, coins) +
            (snake?.let { "  ·  🐍 " + getString(R.string.peer_snake, it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: "") +
            (chess?.let { "  ·  ♞ " + getString(R.string.peer_chess, it.groupValues[1].toInt()) } ?: "") +
            (durak?.let { "  ·  🃏 " + getString(R.string.peer_durak, it.groupValues[1].toInt()) } ?: "")
        // Монеты и достижения - ровными плитками (пересобираются только при изменении).
        val old = box.findViewWithTag<View>("peer_stats_row")
        // Ключ - и строка рекордов целиком: новые плитки («Три в ряд», «Бег по рельсам») тоже обновляются.
        val statsKey = statsText + "|" + g
        if (old?.getTag(R.id.tag_btn_norm) != statsKey) {
            old?.let { box.removeView(it) }
            if (statsText.isNotEmpty()) {
                val snap = com.stoyko.smessenger.games.stats.GameStats.parse(g, coins)
                val ids = snap.showcase
                val chips: View = if (ids != null) {
                    // 4.7.0 у друга: монеты + его витрина (что он сам выбрал) и «📊 Статистика игр».
                    val coinsItem = com.stoyko.smessenger.ui.StatChips.Item("🪙", com.stoyko.smessenger.games.stats.GameStats.compact(coins), getString(R.string.stat_coins))
                    com.stoyko.smessenger.games.stats.GameStatsSheet.profileBlock(ctx, snap, ids, contactDisplayName(), lead = coinsItem)
                } else {
                    // Старая версия у друга: прежние плитки + та же кнопка статистики (что известно).
                    android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        addView(com.stoyko.smessenger.ui.StatChips.build(ctx, com.stoyko.smessenger.ui.StatChips.fromStats(ctx, coins, g)))
                        addView(
                            com.stoyko.smessenger.ui.Btn.nice(ctx, getString(R.string.gamesb_stats_button), intArrayOf(0xFF818CF8.toInt(), 0xFF4F46E5.toInt())) {
                                com.stoyko.smessenger.games.stats.GameStatsSheet.show(ctx, snap, contactDisplayName())
                            },
                            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) },
                        )
                    }
                }
                chips.tag = "peer_stats_row"
                chips.setTag(R.id.tag_btn_norm, statsKey)
                box.addView(chips, 0)
                if (old != null) {
                    chips.alpha = 0.3f
                    chips.animate().alpha(1f).setDuration(300).start()
                }
            }
        }
        if (box.findViewWithTag<View>("peer_stats_btns") == null) {
            box.addView(
                com.stoyko.smessenger.ui.Btn.row(
                    ctx,
                    listOf(
                        com.stoyko.smessenger.ui.Btn.nice(ctx, getString(R.string.peer_all_gifts), com.stoyko.smessenger.ui.Btn.PALETTE[6]) { showAllPeerGifts(pk) },
                        com.stoyko.smessenger.ui.Btn.nice(ctx, getString(R.string.peer_give_coins), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) { giveCoins(pk) },
                    ),
                    height = 48,
                    gap = 10,
                ).apply { tag = "peer_stats_btns" },
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
            )
            // Свежие монеты и рекорды: просим друга прислать карточку сейчас, а пока экран
            // открыт - обновляем строку, как только карточка пришла.
            com.stoyko.smessenger.games.GameNet.requestStats(pk)
            val cardPrefs = ctx.getSharedPreferences("profile_card", android.content.Context.MODE_PRIVATE)
            val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key.equals("peer_$pk", ignoreCase = true)) box.post { if (view != null) bindPeerStats(anchor, pk) }
            }
            cardPrefs.registerOnSharedPreferenceChangeListener(l)
            viewLifecycleOwner.lifecycle.addObserver(
                object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                        cardPrefs.unregisterOnSharedPreferenceChangeListener(l)
                    }
                },
            )
        }
    }

    /** Все подарки друга (запрос по сети; друг должен быть в сети и не скрывать подарки). */
    private fun showAllPeerGifts(pk: String) {
        val ctx = context ?: return
        if (!online) {
            android.widget.Toast.makeText(ctx, R.string.peer_gifts_offline, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val wait = android.widget.Toast.makeText(ctx, R.string.peer_gifts_loading, android.widget.Toast.LENGTH_SHORT)
        wait.show()
        com.stoyko.smessenger.games.GameNet.requestGifts(pk) { list ->
            val c = context ?: return@requestGifts
            val line = "G:" + list.flatMap { (id, n) -> List(n.coerceAtMost(20)) { id } }.joinToString(",") { id ->
                id.toString() + "|" + (com.stoyko.smessenger.chat.GiftCatalog.byId(id)?.emoji ?: "") + "|"
            }
            val box = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.VERTICAL }
            com.stoyko.smessenger.chat.Gifts.fillSection(box, emptyList(), line, limit = 500)
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(c)
            sheet.setContentView(android.widget.ScrollView(c).apply {
                setBackgroundResource(R.drawable.bg_sheet)
                addView(box)
            })
            sheet.show()
        }
    }

    /**
     * Подарить другу свои монеты: готовые суммы или своя сумма от 1 до 500 за раз.
     * Разработчик (см. DevAccount) может вписать любую сумму.
     */
    private fun giveCoins(pk: String) {
        val ctx = context ?: return
        val have = com.stoyko.smessenger.chat.Coins.balance(ctx)
        val dev = com.stoyko.smessenger.chat.DevAccount.isMe(ctx)
        val max = if (dev) Int.MAX_VALUE else minOf(have, com.stoyko.smessenger.chat.CoinGift.MAX_PER_TRANSFER)
        if (max <= 0) {
            android.widget.Toast.makeText(ctx, R.string.peer_give_none, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        fun send(n: Int) {
            if (n <= 0 || n > max) {
                android.widget.Toast.makeText(ctx, getString(R.string.peer_give_range, max), android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            if (com.stoyko.smessenger.chat.Coins.giveTo(ctx, pk, n)) {
                com.stoyko.smessenger.games.CoinSound.jackpot(n)
                android.widget.Toast.makeText(ctx, getString(R.string.peer_give_done, n), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val options = intArrayOf(10, 50, 100, 250, 500).filter { it <= max }
        val labels = options.map { "🪙 $it" } + getString(R.string.peer_give_custom)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.peer_give_title, have))
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < options.size) {
                    send(options[i])
                    return@setItems
                }
                // Своя сумма.
                val input = android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = if (dev) getString(R.string.peer_give_custom_dev) else getString(R.string.peer_give_custom_hint, max)
                    filters = arrayOf(android.text.InputFilter.LengthFilter(if (dev) 9 else 3))
                }
                val pad = (20 * resources.displayMetrics.density).toInt()
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.peer_give_custom)
                    .setView(android.widget.FrameLayout(ctx).apply {
                        setPadding(pad, pad / 2, pad, 0)
                        addView(input)
                    })
                    .setPositiveButton(R.string.peer_give_send) { _, _ -> send(input.text.toString().toIntOrNull() ?: 0) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun contactDisplayName(): String =
        (if (view != null) binding.headerMainText.text?.toString() else null).orEmpty().ifBlank { getString(R.string.contact_default_name) }

    private fun idMenu(pk: String) {
        val ctx = requireContext()
        val full = com.stoyko.smessenger.profile.ContactIds.get(ctx, pk)
        if (full == null) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.contact_full_id_unknown_title)
                .setMessage(R.string.contact_full_id_unknown)
                .setPositiveButton(R.string.contact_copy_key) { _, _ -> com.stoyko.smessenger.ui.ShareId.copy(ctx, pk) }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        val items = arrayOf<CharSequence>(
            getString(R.string.contact_copy_full_id),
            getString(R.string.contact_share_id),
            getString(R.string.contact_show_qr),
            getString(R.string.contact_copy_key),
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.contact_full_id)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> com.stoyko.smessenger.ui.ShareId.copy(ctx, full, R.string.contact_full_id_copied)
                    1 -> shareContactId(pk)
                    2 -> com.stoyko.smessenger.ui.ShareId.showQr(ctx, contactDisplayName(), full)
                    else -> com.stoyko.smessenger.ui.ShareId.copy(ctx, pk)
                }
            }
            .show()
    }

    private fun shareContactId(pk: String) {
        val ctx = requireContext()
        val full = com.stoyko.smessenger.profile.ContactIds.get(ctx, pk)
        if (full == null) {
            idMenu(pk)
            return
        }
        val name = contactDisplayName()
        val text = getString(R.string.contact_share_text, name, full)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.contact_share_id)
            .setItems(
                arrayOf<CharSequence>(getString(R.string.contact_share_text_only), getString(R.string.contact_share_with_apk)),
            ) { _, which ->
                com.stoyko.smessenger.ui.ShareId.copy(ctx, full, R.string.contact_full_id_copied)
                com.stoyko.smessenger.ui.ShareId.send(ctx, text, withApk = which == 1, chooserTitle = getString(R.string.share))
            }
            .show()
    }
}
