// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactlist

import androidx.lifecycle.asLiveData
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.navigation.NavigationView
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.ContactListViewItemBinding
import com.stoyko.smessenger.databinding.FragmentContactListBinding
import com.stoyko.smessenger.databinding.FriendRequestItemBinding
import com.stoyko.smessenger.databinding.NavHeaderContactListBinding
import com.stoyko.smessenger.hasPermission
import com.stoyko.smessenger.truncated
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.ui.ReceiveShareDialogFragment
import com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY
import com.stoyko.smessenger.ui.colorFromStatus
import com.stoyko.smessenger.ui.contactListSorter
import com.stoyko.smessenger.ui.friendrequest.FRIEND_REQUEST_PUBLIC_KEY
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.User
import com.stoyko.smessenger.domain.tox.ToxID
import com.stoyko.smessenger.domain.tox.ToxSaveStatus

const val ARG_ADD_CONTACT = "add_contact"
const val ARG_SHARE = "share"
/** Ссылка на профиль: отправить заявку в друзья сразу (ID - 76 знаков). */
const val ARG_AUTO_ADD = "auto_add"
/** Файлы, которыми поделились из других приложений (уже скопированы к нам). */
const val ARG_SHARE_FILES = "share_files"
const val ARG_SHARE_CAPTION = "share_caption"
private const val TAB_CHATS = 0
private const val TAB_GROUPS = 1
private const val TAB_CHANNELS = 2
private const val FOLDER_BASE = 3
private const val MAX_CONFIRM_DELETE_STRING_LENGTH = 32

private fun User.online(): Boolean = connectionStatus != ConnectionStatus.None

/** Проверка дней рождения - один раз за запуск приложения. */
private var birthdayChecked = false

class ContactListFragment :
    BaseFragment<FragmentContactListBinding>(FragmentContactListBinding::inflate),
    NavigationView.OnNavigationItemSelectedListener {

    private val viewModel: ContactListViewModel by viewModels { vmFactory }

    /** Левое окно планшета (список чатов всегда на экране). */
    private val isSide: Boolean get() = com.stoyko.smessenger.ui.TwoPane.isSide(this)

    /** В режиме планшета этот список - «под заглушкой» справа: знакомство и экскурсию показывает левый. */
    private val isHiddenMain: Boolean
        get() = !isSide && context?.let { com.stoyko.smessenger.ui.TwoPane.active(it) } == true

    /**
     * Навигация. Левое окно планшета открывает экраны в правом: сначала правое окно
     * возвращается к списку (закрывается предыдущий чат), затем открывается нужный экран.
     */
    private fun nav(): androidx.navigation.NavController {
        if (!isSide) return androidx.navigation.fragment.NavHostFragment.findNavController(this)
        val main = com.stoyko.smessenger.ui.TwoPane.mainNav(requireActivity())
            ?: return androidx.navigation.fragment.NavHostFragment.findNavController(this)
        if (main.currentDestination?.id != R.id.contactListFragment) main.popBackStack(R.id.contactListFragment, false)
        return main
    }

    private var navHeader: NavHeaderContactListBinding? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    private var backupFileNameHint = "profile.smsave"

    private var passwordDialog: AlertDialog? = null

    // Истории: фото или видео из галереи.
    private val storyPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) publishStoryFromGallery(uri)
    }
    private val storyCameraPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) recordStory()
    }

    private var exportWithMedia = false
    private val exportToxSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest ->
            if (dest == null) return@registerForActivityResult
            // Окно с процентами: снимок базы, запись, затем проверка записанного файла.
            val progress = com.stoyko.smessenger.profile.BackupProgress.show(requireContext(), R.string.smsave_progress_export)
            val app = requireContext().applicationContext
            viewModel.saveToxBackupTo(dest, exportWithMedia, progress) { message ->
                progress.dismiss()
                val a = activity
                if (a != null && !a.isFinishing && isAdded) {
                    AlertDialog.Builder(a)
                        .setTitle(R.string.backup_full_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                }
            }
        }

    /**
     * Экспорт: анимированное меню - полный перенос (.smsave) или архив для чтения (.zip)
     * с выбором, что выгрузить (переписка, медиа, контакты, группы, подарки, скины, монеты…).
     */
    private fun askExport() {
        com.stoyko.smessenger.profile.ExportCenter.show(
            requireContext(),
            onFull = { media ->
                exportWithMedia = media
                exportToxSaveLauncher.launch(backupFileNameHint)
            },
            onData = { cats ->
                dataExportCats = cats
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                dataExportLauncher.launch("S-messenger_export_$stamp.zip")
            },
        )
    }

    private var dataExportCats: Set<com.stoyko.smessenger.profile.DataExport.Cat> = emptySet()
    private val dataExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { dest ->
            if (dest == null) return@registerForActivityResult
            val ctx = requireContext()
            val app = ctx.applicationContext
            val cats = dataExportCats
            val progress = com.stoyko.smessenger.profile.BackupProgress.show(ctx, R.string.data_export_title)
            Thread({
                val msg: String = try {
                    val resolver = app.contentResolver
                    val pfd = runCatching { resolver.openFileDescriptor(dest, "wt") }.getOrNull() ?: resolver.openFileDescriptor(dest, "w")
                        ?: throw java.io.FileNotFoundException(dest.toString())
                    val res = pfd.use { fd ->
                        java.io.FileOutputStream(fd.fileDescriptor).use { out ->
                            val r = com.stoyko.smessenger.profile.DataExport.export(app, out, cats, progress)
                            out.flush()
                            runCatching { fd.fileDescriptor.sync() }
                            r
                        }
                    }
                    val bad = resolver.openInputStream(dest)?.use { com.stoyko.smessenger.profile.DataExport.verify(it, res.bytes, progress) } ?: "read"
                    if (bad != null) {
                        app.getString(R.string.data_export_bad, bad)
                    } else {
                        app.getString(R.string.data_export_done, res.chats, res.messages, res.files, android.text.format.Formatter.formatShortFileSize(app, res.bytes)) +
                            if (res.skipped.isEmpty()) "" else "\n\n" + app.getString(R.string.data_export_skipped, res.skipped.size)
                    }
                } catch (e: Throwable) {
                    app.getString(R.string.data_export_bad, e.message ?: e.javaClass.simpleName)
                }
                progress.dismiss()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val a = activity
                    if (a != null && !a.isFinishing && isAdded) {
                        AlertDialog.Builder(a).setTitle(R.string.data_export_title).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
                    } else {
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }, "data-export").start()
        }

    private var vpnShield: android.widget.ImageView? = null
    private var vpnShieldAnim: android.animation.Animator? = null
    private val vpnShieldListener: () -> Unit = { renderVpnShield() }

    /**
     * Щит VPN справа в шапке «S-messenger»: одно нажатие - включить последний выбранный сервер
     * (или выключить), долгое - открыть экран VPN. Цвет показывает состояние: серый - выключен,
     * жёлтый (пульсирует) - подключается, зелёный - включён, красный - ошибка.
     */
    private fun setupVpnShield(toolbar: androidx.appcompat.widget.Toolbar) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val iv = toolbar.findViewById<android.widget.ImageView>(R.id.vpnShield) ?: android.widget.ImageView(ctx).apply {
            id = R.id.vpnShield
            setImageResource(R.drawable.ic_shield)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                null,
                android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()),
            )
            contentDescription = getString(R.string.vpn_shield)
            toolbar.addView(
                this,
                androidx.appcompat.widget.Toolbar.LayoutParams((44 * d).toInt(), (44 * d).toInt(), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                    .apply { marginEnd = (6 * d).toInt() },
            )
        }
        vpnShield = iv
        // Лупа рядом со щитом: поиск чатов (и скрытых чатов по PIN).
        if (toolbar.findViewById<View>(R.id.toolbarSearch) == null) {
            toolbar.addView(
                android.widget.ImageView(ctx).apply {
                    id = R.id.toolbarSearch
                    setImageResource(R.drawable.ic_search)
                    setColorFilter(android.graphics.Color.WHITE)
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    contentDescription = getString(R.string.search_chats)
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                        null,
                        android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()),
                    )
                    setOnClickListener { openGlobalSearch() }
                    com.stoyko.smessenger.ui.Kinetics.squish(this, 0.8f)
                },
                androidx.appcompat.widget.Toolbar.LayoutParams((44 * d).toInt(), (44 * d).toInt(), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL),
            )
        }
        com.stoyko.smessenger.ui.Kinetics.squish(iv, 0.8f)
        iv.setOnClickListener { toggleVpn() }
        iv.setOnLongClickListener {
            com.stoyko.smessenger.vpn.VpnScreen(ctx).show()
            true
        }
        com.stoyko.smessenger.vpn.VpnState.listen(vpnShieldListener)
        renderVpnShield()
    }

    private fun toggleVpn() {
        val ctx = context ?: return
        val state = com.stoyko.smessenger.vpn.VpnState.status
        val store = com.stoyko.smessenger.vpn.VpnStore(ctx)
        if (state == com.stoyko.smessenger.vpn.VpnState.Status.On || state == com.stoyko.smessenger.vpn.VpnState.Status.Connecting) {
            com.stoyko.smessenger.vpn.SmVpnService.stop(ctx)
            Toast.makeText(ctx, R.string.vpn_shield_off, Toast.LENGTH_SHORT).show()
            return
        }
        if (!store.ready() || (store.mode != com.stoyko.smessenger.vpn.VpnStore.MODE_WARP && !com.stoyko.smessenger.vpn.XrayCore.supported)) {
            // Серверов ещё нет - открываем экран VPN, там можно вставить ссылку или подписку.
            com.stoyko.smessenger.vpn.VpnScreen(ctx).show()
            return
        }
        com.stoyko.smessenger.vpn.ForeignVpn.confirmThen(ctx) {
            if (!isAdded) return@confirmThen
            val ask = com.stoyko.smessenger.vpn.SmVpnService.prepareIntent(ctx)
            if (ask != null) {
                @Suppress("DEPRECATION")
                runCatching { requireActivity().startActivityForResult(ask, com.stoyko.smessenger.vpn.VpnScreen.REQUEST_VPN) }
                return@confirmThen
            }
            com.stoyko.smessenger.vpn.SmVpnService.start(ctx)
            Toast.makeText(ctx, getString(R.string.vpn_shield_on, store.targetName(ctx)), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderVpnShield() {
        val iv = vpnShield ?: return
        val st = com.stoyko.smessenger.vpn.VpnState.status
        val color = when (st) {
            com.stoyko.smessenger.vpn.VpnState.Status.On -> 0xFF4ADE80.toInt()
            com.stoyko.smessenger.vpn.VpnState.Status.Connecting -> 0xFFFBBF24.toInt()
            com.stoyko.smessenger.vpn.VpnState.Status.Error -> 0xFFF87171.toInt()
            com.stoyko.smessenger.vpn.VpnState.Status.Off -> 0xB3FFFFFF.toInt()
        }
        iv.setColorFilter(color)
        vpnShieldAnim?.cancel()
        vpnShieldAnim = null
        iv.alpha = 1f
        if (st == com.stoyko.smessenger.vpn.VpnState.Status.Connecting) {
            // Подключение - щит мягко «дышит» (только пока идёт подключение).
            vpnShieldAnim = android.animation.ObjectAnimator.ofFloat(iv, View.ALPHA, 1f, 0.35f).apply {
                duration = 600
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else if (st == com.stoyko.smessenger.vpn.VpnState.Status.On) {
            iv.animate().scaleX(1.25f).scaleY(1.25f).setDuration(140).withEndAction {
                iv.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
        }
        iv.contentDescription = getString(R.string.vpn_shield) + ": " + when (st) {
            com.stoyko.smessenger.vpn.VpnState.Status.On -> getString(R.string.vpn_shield_state_on)
            com.stoyko.smessenger.vpn.VpnState.Status.Connecting -> getString(R.string.vpn_shield_state_connecting)
            com.stoyko.smessenger.vpn.VpnState.Status.Error -> getString(R.string.vpn_shield_state_error)
            com.stoyko.smessenger.vpn.VpnState.Status.Off -> getString(R.string.vpn_shield_state_off)
        }
    }

    /**
     * «Включить VPN» из «Развлечений»: серверов нет - включаем обход DPI (он работает без
     * сервера и настроек), чтобы YouTube и Pinterest открылись сразу.
     */
    private fun vpnForFun() {
        val ctx = context ?: return
        val store = com.stoyko.smessenger.vpn.VpnStore(ctx)
        if (!store.ready() && store.mode == com.stoyko.smessenger.vpn.VpnStore.MODE_SERVER &&
            com.stoyko.smessenger.vpn.DpiEngine.available(ctx)
        ) {
            store.mode = com.stoyko.smessenger.vpn.VpnStore.MODE_DPI
            Toast.makeText(ctx, R.string.fun_dpi_auto, Toast.LENGTH_LONG).show()
        }
        if (!com.stoyko.smessenger.vpn.VpnState.running) toggleVpn()
    }

    /**
     * Поделиться контактом: у получателя в чате появится карточка с именем и ID и кнопками
     * «Добавить» / «Отклонить». Нужен полный ID (76 символов) - он приходит сам, когда
     * контакт бывает в сети.
     */
    private fun shareContactCard(c: Contact) {
        val ctx = context ?: return
        val full = com.stoyko.smessenger.profile.ContactIds.get(ctx, c.publicKey)
        if (full == null) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.contact_full_id_unknown_title)
                .setMessage(R.string.contact_full_id_unknown)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val others = viewModel.contacts.value.orEmpty().filter { it.publicKey != c.publicKey && com.stoyko.smessenger.chat.HiddenChats.visible(ctx, it.publicKey) }
        val targets = others.map {
            com.stoyko.smessenger.ui.chat.ForwardSheet.Target(
                it.publicKey,
                it.name.ifEmpty { getString(R.string.contact_default_name) },
                com.stoyko.smessenger.ui.chat.ForwardSheet.Target.Kind.CONTACT,
            )
        }
        if (targets.isEmpty()) {
            Toast.makeText(ctx, R.string.card_no_recipients, Toast.LENGTH_SHORT).show()
            return
        }
        val card = com.stoyko.smessenger.chat.Cards.contactMessage(full, c.name)
        com.stoyko.smessenger.ui.chat.ForwardSheet.show(ctx, targets) { r ->
            viewModel.sendCard(r.targets.map { it.id }, card, r.comment)
            Toast.makeText(ctx, getString(R.string.card_sent, r.targets.size), Toast.LENGTH_SHORT).show()
        }
    }

    /** Эмодзи-статус в шапке бокового меню, рядом с аватаром. */
    private fun showDrawerEmoji(play: Boolean) {
        val iv = navHeader?.drawerEmojiStatus ?: return
        val ctx = context ?: return
        val e = com.stoyko.smessenger.profile.ExtProfile.fromJson(
            ctx.getSharedPreferences("profile_card", android.content.Context.MODE_PRIVATE).getString("mine", null),
        )?.emojiStatus?.trim().orEmpty()
        if (e.isEmpty()) {
            com.stoyko.smessenger.chat.AnimatedEmoji.stop(iv)
            return
        }
        if (!play && iv.visibility == View.VISIBLE && iv.contentDescription == e) return
        iv.contentDescription = e
        if (iv.drawable == null || iv.getTag(R.id.tag_emoji_bound) != e) {
            // Пока живой эмодзи грузится - обычный.
            iv.setTag(R.id.tag_emoji_bound, e)
            val size = (40 * resources.displayMetrics.density).toInt()
            com.stoyko.smessenger.chat.AnimatedEmoji.drawable(ctx, e, size) { d ->
                if (iv.getTag(R.id.tag_emoji_bound) != e) return@drawable
                iv.setImageDrawable(d)
                iv.visibility = View.VISIBLE
                iv.scaleX = 0.3f
                iv.scaleY = 0.3f
                iv.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
            }
        } else if (play) {
            iv.visibility = View.VISIBLE
            (iv.drawable as? android.graphics.drawable.Animatable)?.let {
                it.stop()
                it.start()
            }
        }
        iv.setOnClickListener {
            (iv.drawable as? android.graphics.drawable.Animatable)?.let { a -> a.stop(); a.start() }
            iv.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
                iv.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        navHeader = NavHeaderContactListBinding.bind(binding.navView.getHeaderView(0))
        // «Эффект воды» при нажатии на пункты меню (можно выключить в Персонализации).
        if (com.stoyko.smessenger.ui.WaterRipple.isOn(requireContext())) {
            runCatching { binding.navView.itemBackground = com.stoyko.smessenger.ui.WaterRipple.drawable(requireContext()) }
        }
        navHeader?.themeToggle?.let { btn ->
            // одна кнопка «солнце/луна» и плавная смена темы.
            com.stoyko.smessenger.ui.ThemeSwitcher.bindIcon(btn, requireActivity())
            btn.setOnClickListener { com.stoyko.smessenger.ui.ThemeSwitcher.toggle(requireActivity(), btn) }
        }
        return v
    }

    /** Была ли связь при прошлом обновлении (null - ещё не знаем). */
    private var wasOnline: Boolean? = null
    private var connectFrame = 0

    /** «Подключение» с крутящимся значком и бегущими точками. */
    private val connectingAnim: Runnable = object : Runnable {
        override fun run() {
            if (view == null) return
            val spin = charArrayOf('◐', '◓', '◑', '◒')[connectFrame % 4]
            val dots = ".".repeat(connectFrame / 3 % 4)
            binding.toolbar.subtitle = "$spin " + getString(R.string.connecting).trimEnd('…', '.') + dots
            connectFrame++
            binding.toolbar.postDelayed(this, 130)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        // Сезонное оформление (осень / Новый год): цвета шапки, листья или снег, гирлянда.
        com.stoyko.smessenger.ui.Seasonal.decorateHeader(toolbar)
        com.stoyko.smessenger.ui.Seasonal.decorateHeader(tabs, garland = false)
        if (isSide) layoutSidePane()
        com.stoyko.smessenger.ui.Seasonal.accent(requireContext())?.let { fabAddContact.backgroundTintList = android.content.res.ColorStateList.valueOf(it[1]) }
        // Знакомство с приложением - один раз после регистрации/обновления.
        if (savedInstanceState == null && viewModel.isToxRunning() && !isHiddenMain) {
            view.post {
                if (this@ContactListFragment.view != null) {
                    val ctx = requireContext()
                    if (com.stoyko.smessenger.ui.Onboarding.shouldShow(ctx)) {
                        // Знакомство → экскурсия по экрану (что куда нажимать) → проверка обновлений.
                        com.stoyko.smessenger.ui.Onboarding.showIfNeeded(ctx) {
                            if (this@ContactListFragment.view != null) {
                                com.stoyko.smessenger.ui.Tour.startMain(this@ContactListFragment) {
                                    com.stoyko.smessenger.update.Updater.autoCheck(ctx)
                                }
                            }
                        }
                    } else {
                        // Проверка обновлений в фоне при каждом входе (сеть - не чаще раза в час).
                        com.stoyko.smessenger.update.Updater.autoCheck(ctx)
                        // Один раз: знакомство со встроенным VPN (как включить для всего телефона).
                        activity?.let { com.stoyko.smessenger.vpn.VpnIntro.showOnceWhenFree(it) }
                    }
                }
            }
        }
        if (!viewModel.isToxRunning()) {
            // Профиль ещё открывается (в фоне) - показываем индикатор, экран построится после.
            loadingProgress.visibility = View.VISIBLE
            return@run
        }
        loadingProgress.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!requireContext().hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(left = insets.left)
            navView.updatePadding(left = insets.left)
            contactList.updatePadding(bottom = insets.bottom)
            compat
        }

        toolbar.title = getText(R.string.app_name)
        // Зимой - буквы-карамельки с лампочками, осенью - золотые с листиками.
        com.stoyko.smessenger.ui.SeasonTitle.apply(toolbar)
        setupVpnShield(toolbar)
        com.stoyko.smessenger.transport.ui.ChannelBadge.attach(toolbar) // текущий канал: 🌐 Tox / 🧅 Tor / 📶 Wi-Fi / 🔵 Bluetooth

        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user == null) return@observe

            backupFileNameHint = user.name + ".smsave"

            navHeader!!.apply {
                profileName.text = user.name
                com.stoyko.smessenger.profile.SelfAvatar.show(
                    profilePhoto,
                    user.name,
                    user.publicKey,
                    (64 * resources.displayMetrics.density).toInt(),
                )
                // Видео-аватарка - живая и в боковом меню (как в профиле).
                com.stoyko.smessenger.profile.ProfileVideo.attachOver(profilePhoto, com.stoyko.smessenger.profile.ProfileVideo.mine(requireContext()))
                profileStatusMessage.text = user.statusMessage
                showDrawerEmoji(play = false)

                if (user.online()) {
                    statusIndicator.setColorFilter(colorFromStatus(requireContext(), user.status))
                } else {
                    statusIndicator.setColorFilter(ContextCompat.getColor(requireContext(), R.color.statusOffline))
                }
            }

            // Вместо «Доступен/Доступна»: пока нет связи - живое «Подключение…» с крутящимся
            // значком; связь появилась - на миг «✓ Подключено», дальше только «S-messenger».
            val online = user.online()
            if (online != wasOnline) {
                toolbar.removeCallbacks(connectingAnim)
                if (online) {
                    if (wasOnline == false) {
                        toolbar.subtitle = getString(R.string.connected_ok)
                        toolbar.postDelayed({ if (view != null && wasOnline == true) binding.toolbar.subtitle = null }, 1600)
                    } else {
                        toolbar.subtitle = null
                    }
                } else {
                    toolbar.post(connectingAnim)
                }
                wasOnline = online
            }
        }

        navView.setNavigationItemSelectedListener(this@ContactListFragment)

        // Заголовки списков (до назначения адаптеров): истории и «Архив».
        setupHeaders()
        setupTabs()

        // Кнопка «+»: выпрыгивает при открытии экрана, по нажатию - действие текущей вкладки.
        com.stoyko.smessenger.ui.Kinetics.squish(fabAddContact, 0.88f)
        fabAddContact.scaleX = 0f
        fabAddContact.scaleY = 0f
        fabAddContact.animate().scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        // «+»: выезжает меню - контакт, группа, канал, вход по ID, Избранное, мой ID.
        fabAddContact.setOnClickListener { showPlusMenu(it) }

        val contactAdapter = ContactAdapter(layoutInflater, requireContext())
        contactList.adapter = contactAdapter
        contactAdapterRef = contactAdapter
        // Собеседник начал/закончил запись аудио или отправку файла - обновляем строку в списке.
        com.stoyko.smessenger.chat.PeerActivity.listen(listActivityListener)
        com.stoyko.smessenger.profile.ProfileVideo.listen(drawerVideoListener)
        registerForContextMenu(contactList)

        viewModel.friendRequests.observe(viewLifecycleOwner) { friendRequests ->
            allFriendRequests = friendRequests
            showTab(currentTab)
        }

        viewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            allContacts = contacts.sortedByDescending(::contactListSorter)
            showTab(currentTab)
            refreshStories()
            // День рождения (свой - 500 монет, друзей - предложить поздравить) - раз за запуск.
            if (!birthdayChecked && viewModel.isToxRunning()) {
                birthdayChecked = true
                view.postDelayed({
                    val act = activity ?: return@postDelayed
                    if (this@ContactListFragment.view == null) return@postDelayed
                    runCatching {
                        com.stoyko.smessenger.chat.Birthday.check(
                            act,
                            allContacts.map { it.publicKey to (com.stoyko.smessenger.profile.ContactAlias.get(act, it.publicKey) ?: it.name) },
                            onChat = { pk -> if (this@ContactListFragment.view != null) openChat(pk) },
                            onGift = { pk -> if (this@ContactListFragment.view != null) allContacts.firstOrNull { it.publicKey == pk }?.let { openProfile(it) } },
                        )
                    }
                }, 2500)
            }
        }
        viewModel.storyChanges.observe(viewLifecycleOwner) { refreshStories() }

        // Свайпы по чату: влево - архив, вправо - закрепить.
        contactList.canSwipe = { pos -> contactList.getItemAtPosition(pos) is Contact }
        contactList.labels = { pos ->
            val c = contactList.getItemAtPosition(pos) as? Contact
            val ctx = requireContext()
            val archived = c != null && com.stoyko.smessenger.chat.Archive.isArchived(ctx, c.publicKey)
            val pinned = c != null && com.stoyko.smessenger.chat.PinnedChats.isPinned(ctx, c.publicKey)
            arrayOf(
                "🗄", getString(if (archived) R.string.archive_remove else R.string.archive_add),
                "📌", getString(if (pinned) R.string.unpin_chat else R.string.pin_chat),
            )
        }
        contactList.onSwipe = { pos, left ->
            (contactList.getItemAtPosition(pos) as? Contact)?.let { c ->
                val ctx = requireContext()
                if (left) {
                    val was = com.stoyko.smessenger.chat.Archive.isArchived(ctx, c.publicKey)
                    com.stoyko.smessenger.chat.Archive.set(ctx, c.publicKey, !was)
                    Toast.makeText(ctx, if (was) R.string.unarchived else R.string.archived, Toast.LENGTH_SHORT).show()
                } else {
                    val now = com.stoyko.smessenger.chat.PinnedChats.toggle(ctx, c.publicKey)
                    Toast.makeText(ctx, if (now) R.string.chat_pinned else R.string.chat_unpinned, Toast.LENGTH_SHORT).show()
                }
                contactList.postDelayed({ if (view != null) showTab(currentTab) }, 360)
            }
        }
        contactList.setOnItemClickListener { _, row, position, _ ->
            // Режим выбора: касание отмечает/снимает чат.
            if (selectedChats.isNotEmpty() && contactList.adapter.getItemViewType(position) == ContactListItemType.Contact.ordinal) {
                toggleSelected((contactList.getItemAtPosition(position) as Contact).publicKey)
                return@setOnItemClickListener
            }
            when (contactList.adapter.getItemViewType(position)) {
                ContactListItemType.FriendRequest.ordinal -> {
                    openFriendRequest(contactList.getItemAtPosition(position) as FriendRequest)
                }
                ContactListItemType.Contact.ordinal -> {
                    // Аватарка и имя «перелетают» в шапку чата.
                    openChatHero(row, (contactList.getItemAtPosition(position) as Contact).publicKey)
                }
            }
        }
        // Peek & Pop: удержание строки чата - предпросмотр (без анимаций - прежнее меню).
        fun peekable(pos: Int) = selectedChats.isEmpty() &&
            pos in 0 until contactList.adapter.count &&
            contactList.adapter.getItemViewType(pos) == ContactListItemType.Contact.ordinal
        com.stoyko.smessenger.ui.anim.PeekPop.installPress(contactList) { peekable(it) }
        contactList.setOnItemLongClickListener { _, row, position, _ ->
            // Предпросмотр - всегда (без анимаций - просто окно); раньше при выключенных анимациях
            // и на слабых телефонах показывались только действия.
            if (!peekable(position)) return@setOnItemLongClickListener false
            com.stoyko.smessenger.ui.anim.PeekPop.resetPress(contactList)
            peekChat(row, contactList.getItemAtPosition(position) as Contact)
            true
        }

        val toggle = ActionBarDrawerToggle(
            requireActivity(),
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close,
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        // Меню открылось - эмодзи-статус рядом с аватаром «оживает» (проигрывается пару раз,
        // а не бесконечно - бережём батарею).
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            var played = false
            override fun onDrawerOpened(drawerView: View) = showDrawerEmoji(play = true)

            // Пункты меню «въезжают» один за другим, когда меню начинает открываться.
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (played || slideOffset <= 0f || slideOffset > 0.5f) return
                played = true
                // Меню всегда открывается с начала - с фото профиля.
                (navView.getChildAt(0) as? ViewGroup)?.let { if (it.canScrollVertically(-1)) it.scrollBy(0, -100_000) }
                if (!com.stoyko.smessenger.ui.Kinetics.enabled || com.stoyko.smessenger.ui.EnergySaver.on) return
                val list = navView.getChildAt(0) as? ViewGroup ?: return
                val dx = -36 * resources.displayMetrics.density
                for (i in 0 until list.childCount) {
                    val item = list.getChildAt(i)
                    item.animate().cancel()
                    item.alpha = 0f
                    item.translationX = dx
                    item.animate().alpha(1f).translationX(0f).setStartDelay(25L * i.coerceAtMost(14))
                        .setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                played = false
                (navView.getChildAt(0) as? ViewGroup)?.let { if (it.canScrollVertically(-1)) it.scrollBy(0, -100_000) }
            }
        })

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else if (archiveMode) {
                setArchiveMode(false)
            } else if (isSide && com.stoyko.smessenger.ui.TwoPane.mainNav(requireActivity())
                    ?.let { it.currentDestination?.id != R.id.contactListFragment && it.popBackStack() } == true
            ) {
                // Планшет: «назад» закрывает экран в правом окне (чат, настройки).
            } else {
                activity?.finish()
            }
        }

        // Вернулись из чата, открытого в архиве, - остаёмся в архиве.
        if (archiveMode) {
            toolbar.title = getString(R.string.archive)
            tabs.visibility = View.GONE
        }

        arguments?.getString(ARG_ADD_CONTACT)?.let { toxId ->
            arguments?.remove(ARG_ADD_CONTACT)
            val id = ToxID(toxId)
            val pk = id.toPublicKey()
            if (viewModel.contactAdded(pk)) {
                openChat(pk.string())
            } else {
                nav().navigate(R.id.addContactFragment, bundleOf("toxId" to toxId))
            }
        }

        arguments?.getString(ARG_AUTO_ADD)?.let { toxId ->
            arguments?.remove(ARG_AUTO_ADD)
            com.stoyko.smessenger.ui.Safe.run("auto-add") {
                val id = ToxID(toxId)
                val pk = id.toPublicKey()
                when {
                    viewModel.isSelf(pk) -> Toast.makeText(requireContext(), R.string.profile_link_self, Toast.LENGTH_LONG).show()
                    viewModel.contactAdded(pk) -> openChat(pk.string())
                    else -> {
                        viewModel.autoAddContact(toxId, getString(R.string.add_contact_message_default))
                        Toast.makeText(requireContext(), R.string.profile_link_request_sent, Toast.LENGTH_LONG).show()
                        view?.postDelayed({ if (isAdded && view != null) openChat(pk.string()) }, 600)
                    }
                }
            }
        }

        offerDeveloperContact()

        arguments?.getStringArrayList(ARG_SHARE_FILES)?.let { files ->
            arguments?.remove(ARG_SHARE_FILES)
            showMultiShare(files)
        }
        arguments?.getString(ARG_SHARE)?.let { share ->
            ReceiveShareDialogFragment(
                viewModel.contacts,
                share,
                onContactSelected = {
                    viewModel.onShareText(share, it)
                    openChat(it)
                },
                onDialogDismissed = {
                    arguments?.remove(ARG_SHARE)
                },
                onManySelected = { chosen ->
                    chosen.forEach { c -> viewModel.onShareText(share, c) }
                    Toast.makeText(requireContext(), getString(R.string.share_sent_to, chosen.size), Toast.LENGTH_LONG).show()
                },
            ).show(childFragmentManager, null)
        }
    }

    private var currentTab = TAB_CHATS
    private var allGroups: List<com.stoyko.smessenger.core.vo.GroupChat> = emptyList()
    private var groupAdapter: com.stoyko.smessenger.ui.groups.GroupAdapter? = null

    // Все чаты и заявки; на вкладке папки показываются только её чаты.
    private var allContacts: List<Contact> = emptyList()
    private var allFriendRequests: List<FriendRequest> = emptyList()
    private var contactAdapterRef: ContactAdapter? = null
    private val listActivityListener: () -> Unit = { if (view != null) contactAdapterRef?.notifyDataSetChanged() }
    private val drawerVideoListener: () -> Unit = {
        // Экран мог уже закрыться (событие пришло после onDestroyView) - тогда ничего не делаем.
        val c = context
        if (c != null && view != null) {
            navHeader?.let { h -> com.stoyko.smessenger.profile.ProfileVideo.attachOver(h.profilePhoto, com.stoyko.smessenger.profile.ProfileVideo.mine(c)) }
        }
    }
    private var folders: List<com.stoyko.smessenger.chat.ChatFolder> = emptyList()

    private fun folderFor(tab: Int) = folders.getOrNull(tab - FOLDER_BASE)

    private fun applyContacts() {
        val a = contactAdapterRef ?: return
        val folder = folderFor(currentTab)
        val archived = com.stoyko.smessenger.chat.Archive.ids(requireContext())
        val base = if (folder == null) allContacts else allContacts.filter { it.publicKey in folder.members }
        a.friendRequests = if (folder == null && !archiveMode) allFriendRequests else emptyList()
        // Скрытые чаты (PIN в поиске) в списке не показываются.
        val hidden = com.stoyko.smessenger.chat.HiddenChats.ids(requireContext())
        // Закреплённые чаты - сверху (без ограничения количества).
        a.contacts = com.stoyko.smessenger.chat.PinnedChats.sort(
            requireContext(),
            base.filter { (it.publicKey in archived) == archiveMode && it.publicKey !in hidden },
        ) { it.publicKey }
        a.notifyDataSetChanged()
        updateHeaders()
    }

    // ---------- Архив и истории (заголовки списков) ----------

    private var archiveMode = false
    private var chatsHeader: HeaderBox? = null
    private var storiesStrip: com.stoyko.smessenger.stories.StoriesStrip? = null
    private var chatsArchiveRow: ArchiveRow? = null
    private var groupsArchiveRow: ArchiveRow? = null
    private var aiRow: com.stoyko.smessenger.ai.AiRow? = null

    private fun setupHeaders() = binding.run {
        val ctx = requireContext()
        val header = HeaderBox(ctx)
        val strip = com.stoyko.smessenger.stories.StoriesStrip(ctx)
        strip.onMine = { onMyStory() }
        strip.onMineLong = { createStory() }
        strip.onOwner = { index -> openStories(index) }
        header.addView(strip)
        val row = ArchiveRow(ctx)
        row.setOnClickListener { setArchiveMode(!archiveMode) }
        header.addView(row)
        // S-AI - встроенный помощник, всегда в списке чатов и всегда «в сети».
        val ai = com.stoyko.smessenger.ai.AiRow(ctx)
        ai.setOnClickListener {
            com.stoyko.smessenger.ai.AiChatDialog(ctx) { aiRow?.refresh() }.show()
        }
        header.addView(ai)
        aiRow = ai
        // «Избранное» - встроенный чат для себя: заметки и пересланное.
        val saved = com.stoyko.smessenger.chat.SavedRow(ctx)
        saved.setOnClickListener { com.stoyko.smessenger.chat.SavedMessages.show(ctx) { saved.refresh() } }
        header.addView(saved)
        contactList.addHeaderView(header, null, false)
        chatsHeader = header
        storiesStrip = strip
        chatsArchiveRow = row

        val groupsRow = ArchiveRow(ctx)
        groupsRow.setOnClickListener { setArchiveMode(!archiveMode) }
        val groupsHeader = HeaderBox(ctx)
        groupsHeader.addView(groupsRow)
        groupListView.addHeaderView(groupsHeader, null, false)
        groupsArchiveRow = groupsRow

        // Удержание группы/канала: в архив/из архива, выйти.
        // Peek & Pop и для групп/каналов: удержание - предпросмотр последних сообщений.
        com.stoyko.smessenger.ui.anim.PeekPop.installPress(groupListView) { pos ->
            pos in 0 until groupListView.count && groupListView.getItemAtPosition(pos) is com.stoyko.smessenger.core.vo.GroupChat
        }
        groupListView.setOnItemLongClickListener { _, row, position, _ ->
            val g = groupListView.getItemAtPosition(position) as? com.stoyko.smessenger.core.vo.GroupChat
                ?: return@setOnItemLongClickListener false
            com.stoyko.smessenger.ui.anim.PeekPop.resetPress(groupListView)
            peekGroup(row, g)
            true
        }
    }

    private fun setArchiveMode(on: Boolean) {
        val b = _bindingOrNull() ?: return
        archiveMode = on
        b.toolbar.title = getString(if (on) R.string.archive else R.string.app_name)
        b.tabs.visibility = if (on) View.GONE else View.VISIBLE
        showTab(currentTab)
        val shown = if (b.contactList.visibility == View.VISIBLE) b.contactList else b.groupListView
        shown.translationX = (if (on) 1 else -1) * shown.width * 0.3f
        shown.alpha = 0f
        shown.animate().translationX(0f).alpha(1f).setDuration(220).start()
    }

    private fun updateHeaders() {
        val ctx = context ?: return
        val archived = com.stoyko.smessenger.chat.Archive.ids(ctx)
        val chatsTab = currentTab == TAB_CHATS
        // Скрытые чаты не выдают себя и счётчиком архива.
        val hiddenIds = com.stoyko.smessenger.chat.HiddenChats.ids(ctx)
        val contactsArchived = allContacts.filter { it.publicKey in archived && it.publicKey !in hiddenIds }
        chatsArchiveRow?.let {
            it.visibility = if (chatsTab || archiveMode) View.VISIBLE else View.GONE
            it.bind(contactsArchived.size, contactsArchived.count { c -> c.hasUnreadMessages }, archiveMode)
        }
        storiesStrip?.visibility = if (chatsTab && !archiveMode) View.VISIBLE else View.GONE
        aiRow?.let {
            it.visibility = if (chatsTab && !archiveMode) View.VISIBLE else View.GONE
            it.refresh()
        }
        val channels = currentTab == TAB_CHANNELS
        val groupsArchived = allGroups.filter { it.chatId in archived && it.isChannel == channels }
        groupsArchiveRow?.bind(groupsArchived.size, groupsArchived.count { it.hasUnread }, archiveMode)
    }

    private fun groupActions(g: com.stoyko.smessenger.core.vo.GroupChat) {
        val ctx = requireContext()
        val archived = com.stoyko.smessenger.chat.Archive.isArchived(ctx, g.chatId)
        val pinned = com.stoyko.smessenger.chat.PinnedChats.isPinned(ctx, g.chatId)
        val items = arrayOf(
            getString(if (archived) R.string.archive_remove else R.string.archive_add),
            getString(R.string.group_leave),
            getString(if (pinned) R.string.unpin_chat else R.string.pin_chat),
        )
        AlertDialog.Builder(ctx)
            .setTitle(g.name.ifEmpty { getString(R.string.group_default_name) })
            .setItems(items) { _, which ->
                if (which == 2) {
                    com.stoyko.smessenger.chat.PinnedChats.toggle(ctx, g.chatId)
                    Toast.makeText(ctx, if (pinned) R.string.chat_unpinned else R.string.chat_pinned, Toast.LENGTH_SHORT).show()
                    showTab(currentTab)
                } else if (which == 0) {
                    com.stoyko.smessenger.chat.Archive.set(ctx, g.chatId, !archived)
                    Toast.makeText(ctx, if (archived) R.string.unarchived else R.string.archived, Toast.LENGTH_SHORT).show()
                    showTab(currentTab)
                } else {
                    AlertDialog.Builder(ctx)
                        .setMessage(getString(R.string.group_leave_confirm, g.name))
                        .setPositiveButton(R.string.group_leave) { _, _ -> viewModel.leaveGroup(g.chatId) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    // ---------- Истории ----------

    private fun refreshStories() {
        val strip = storiesStrip ?: return
        val st = viewModel.stories
        val size = (64 * resources.displayMetrics.density).toInt()
        val me = viewModel.user.value
        val myAvatar = com.stoyko.smessenger.profile.SelfAvatar.currentFile(requireContext())?.absolutePath
        strip.bind(me?.name.orEmpty(), SmallAvatars.load(myAvatar, size), st.mine(), storyOwners())
        updateHeaders()
    }

    private fun storyOwners(): List<com.stoyko.smessenger.stories.StoryOwner> {
        val size = (64 * resources.displayMetrics.density).toInt()
        val others = viewModel.stories.others()
        return others.mapNotNull { (pk, list) ->
            val c = allContacts.firstOrNull { it.publicKey == pk } ?: return@mapNotNull null
            com.stoyko.smessenger.stories.StoryOwner(
                pk,
                c.name.ifEmpty { getString(R.string.contact_default_name) },
                SmallAvatars.load(c.avatarUri, size),
                list,
                list.any { !it.viewed },
            )
        }.sortedByDescending { it.unviewed }
    }

    private fun openStories(index: Int) {
        val owners = storyOwners()
        if (owners.isEmpty()) return
        com.stoyko.smessenger.stories.StoryViewer(
            requireContext(),
            owners,
            index,
            onViewed = { viewModel.stories.markViewed(it.id) },
            onDelete = null,
            onAddMore = null,
        ).show()
    }

    private fun onMyStory() {
        val mine = viewModel.stories.mine()
        if (mine.isEmpty()) {
            createStory()
            return
        }
        val size = (64 * resources.displayMetrics.density).toInt()
        val me = viewModel.user.value
        val avatar = SmallAvatars.load(com.stoyko.smessenger.profile.SelfAvatar.currentFile(requireContext())?.absolutePath, size)
        com.stoyko.smessenger.stories.StoryViewer(
            requireContext(),
            listOf(com.stoyko.smessenger.stories.StoryOwner(com.stoyko.smessenger.stories.StoryMeta.ME, me?.name.orEmpty(), avatar, mine, false)),
            0,
            onViewed = {},
            onDelete = { viewModel.stories.delete(it.id) },
            onAddMore = { createStory() },
        ).show()
    }

    /** Новая история: снять видео (до 15 с) или выбрать фото/видео из галереи. */
    private fun createStory() {
        if (viewModel.stories.mine().size >= com.stoyko.smessenger.stories.StoryMeta.MAX_MINE) {
            Toast.makeText(requireContext(), R.string.story_limit, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.story_new)
            .setItems(arrayOf("🎥  " + getString(R.string.story_record_video), "🖼  " + getString(R.string.story_from_gallery))) { _, which ->
                if (which == 0) {
                    val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        .filterNot { requireContext().hasPermission(it) }
                    if (need.isEmpty()) recordStory() else storyCameraPermissions.launch(need.toTypedArray())
                } else {
                    storyPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun recordStory() {
        if (!isAdded) return
        com.stoyko.smessenger.media.CaptureDialog(requireContext(), story = true) { file ->
            val dur = com.stoyko.smessenger.ui.chat.VoiceMeta.durationMs(file.name) ?: 0L
            val preview = runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(file.absolutePath)
                    mmr.getFrameAtTime(0)
                } finally {
                    runCatching { mmr.release() }
                }
            }.getOrNull()
            if (!isAdded) return@CaptureDialog
            com.stoyko.smessenger.stories.StoryComposer.show(requireContext(), preview, true, dur) { caption ->
                uploadStory { progress ->
                    progress(0.3f)
                    if (viewModel.stories.publish(file, true, dur, caption) { done, total -> progress(0.3f + 0.7f * done / total.coerceAtLeast(1)) }) 1 else 0
                }
            }
        }.show()
    }

    /**
     * Загрузка истории с плашкой снизу: полоска плавно ползёт (копирование файла и отправка
     * каждому контакту), в конце - «История загружена успешно».
     * [work] возвращает 1 - готово, 0 - уже 3 истории, -1 - видео длиннее минуты.
     */
    private fun uploadStory(work: suspend ((Float) -> Unit) -> Int) {
        val ctx = context ?: return
        val bar = com.stoyko.smessenger.ui.UploadBar.show(ctx, getString(R.string.story_uploading))
        val app = ctx.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    work { p -> bar?.set(p) }
                } catch (e: Exception) {
                    com.stoyko.smessenger.domain.Diagnostics.record("загрузка истории", e, fatal = false)
                    0
                }
            }
            val msg = app.getString(
                when (result) {
                    1 -> R.string.story_uploaded
                    -1 -> R.string.story_too_long
                    else -> R.string.story_limit
                },
            )
            if (bar == null) {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            } else if (result == 1) {
                bar.done(msg)
            } else {
                bar.fail(msg)
            }
        }
    }

    private fun publishStoryFromGallery(uri: android.net.Uri) {
        val ctx = requireContext()
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val video = type.startsWith("video/")
        viewLifecycleOwner.lifecycleScope.launch {
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val preview = com.stoyko.smessenger.stories.StoryComposer.previewOf(ctx, uri, video)
                var dur = 0L
                if (video) {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(ctx, uri)
                        dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                    } finally {
                        runCatching { mmr.release() }
                    }
                }
                preview to dur
            }
            if (!isAdded) return@launch
            val (preview, dur) = info
            if (dur > com.stoyko.smessenger.stories.StoryMeta.MAX_VIDEO_MS) {
                Toast.makeText(ctx, R.string.story_too_long, Toast.LENGTH_LONG).show()
                return@launch
            }
            com.stoyko.smessenger.stories.StoryComposer.show(ctx, preview, video, dur) { caption ->
                val app = ctx.applicationContext
                uploadStory { progress ->
                    val dir = java.io.File(app.filesDir, "outgoing/story").apply { mkdirs() }
                    val ext = if (video) "mp4" else if (type.contains("png")) "png" else "jpg"
                    val out = java.io.File(dir, "pick_${System.currentTimeMillis()}.$ext")
                    val total = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var copied = 0L
                            var lastShown = 0f
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val p = 0.5f * copied / total
                                    if (p - lastShown > 0.03f) {
                                        lastShown = p
                                        progress(p)
                                    }
                                }
                            }
                        }
                    }
                    progress(0.5f)
                    if (viewModel.stories.publish(out, video, dur, caption) { done, all -> progress(0.5f + 0.5f * done / all.coerceAtLeast(1)) }) 1 else 0
                }
            }
        }
    }

    /** Вкладки: Чаты / Группы / Каналы + папки пользователя. */
    private fun rebuildTabs(): Unit = binding.run {
        folders = com.stoyko.smessenger.chat.Folders.list(requireContext())
        val keep = currentTab
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText(R.string.tab_chats))
        tabs.addTab(tabs.newTab().setText(R.string.tab_groups))
        tabs.addTab(tabs.newTab().setText(R.string.tab_channels))
        folders.forEach { tabs.addTab(tabs.newTab().setText(it.name)) }
        // Растянуть вкладки можно только в FIXED; в SCROLLABLE - по левому краю (иначе предупреждение в журнале).
        // Через GRAVITY_CENTER (допустим в обоих режимах): иначе при смене режима Material пишет
        // предупреждение «MODE_SCROLLABLE + GRAVITY_FILL is not supported» в журнал.
        tabs.tabGravity = com.google.android.material.tabs.TabLayout.GRAVITY_CENTER
        if (folders.isEmpty()) {
            tabs.tabMode = com.google.android.material.tabs.TabLayout.MODE_FIXED
            tabs.tabGravity = com.google.android.material.tabs.TabLayout.GRAVITY_FILL
        } else {
            // Сначала режим, потом выравнивание: GRAVITY_START в режиме FIXED даёт предупреждение.
            tabs.tabMode = com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE
            tabs.tabGravity = com.google.android.material.tabs.TabLayout.GRAVITY_START
        }
        val target = if (keep < tabs.tabCount) keep else TAB_CHATS
        tabs.getTabAt(target)?.select()
        showTab(target)
        // Удержание вкладки папки - изменить или удалить её.
        for (i in FOLDER_BASE until tabs.tabCount) {
            tabs.getTabAt(i)?.view?.setOnLongClickListener {
                com.stoyko.smessenger.chat.Folders.actions(requireContext(), allContacts, i - FOLDER_BASE) {
                    if (view != null) rebuildTabs()
                }
                true
            }
        }
    }

    fun openFolders() {
        com.stoyko.smessenger.chat.Folders.manage(requireContext(), allContacts) { if (view != null) rebuildTabs() }
    }

    private fun setupTabs() = binding.run {
        rebuildTabs()
        // Плавный переключатель «Чаты / Группы / Каналы»: выбранная вкладка - светлая «пилюля»,
        // которая упруго перетекает к другой вкладке (тянется и догоняет), а не полоска снизу.
        run {
            val d = resources.displayMetrics.density
            val pill = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(android.graphics.Color.WHITE)
            }
            tabs.setSelectedTabIndicator(android.graphics.drawable.InsetDrawable(pill, (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt()))
            tabs.setSelectedTabIndicatorColor(0x38FFFFFF)
            tabs.setSelectedTabIndicatorGravity(com.google.android.material.tabs.TabLayout.INDICATOR_GRAVITY_STRETCH)
            tabs.isTabIndicatorFullWidth = true
            tabs.tabIndicatorAnimationMode = com.google.android.material.tabs.TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
            tabs.tabRippleColor = null
            tabs.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), (4 * d).toInt())
        }
        val adapter = com.stoyko.smessenger.ui.groups.GroupAdapter(requireContext())
        groupAdapter = adapter
        groupListView.adapter = adapter
        // Владелец сменил аватарку/название группы - перерисовываем список.
        com.stoyko.smessenger.groups.GroupProfile.changes.asLiveData().observe(viewLifecycleOwner) {
            groupAdapter?.notifyDataSetChanged()
        }
        groupListView.setOnItemClickListener { _, _, position, _ ->
            // Позиция учитывает строку «Архив» сверху.
            val g = groupListView.getItemAtPosition(position) as? com.stoyko.smessenger.core.vo.GroupChat
                ?: return@setOnItemClickListener
            openGroup(g.chatId, groupListView.getChildAt(position - groupListView.firstVisiblePosition))
        }
        viewModel.groups.observe(viewLifecycleOwner) {
            allGroups = it
            showTab(currentTab)
        }
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                // Нажатие на вкладку: полоску ведёт сама TabLayout; запоминаем, где она окажется.
                if (!swipeSelecting) {
                    indicatorAnim?.cancel()
                    indicatorPos = tab.position.toFloat()
                }
                // Подпись выбранной вкладки чуть «подпрыгивает».
                tab.view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                tab.view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        tabs.getTabAt(currentTab)?.select()
        setupSwipe()
    }

    /** Свайп влево/вправо (в обе стороны, по всей области) переключает вкладки. */
    private fun setupSwipe() = binding.run {
        swipeTabs.canSwipe = { dir -> !archiveMode && (currentTab + dir) in 0 until tabs.tabCount }
        // «Пилюля» под вкладками едет за пальцем непрерывно (позиция - дробное число вкладки).
        swipeTabs.onDrag = { f ->
            indicatorAnim?.cancel()
            setIndicator(currentTab + f)
        }
        // Не долистали - «пилюля» плавно возвращается, а не прыгает.
        swipeTabs.onCancel = { animateIndicator(currentTab) }
        swipeTabs.onSwipe = { dir ->
            val target = (currentTab + dir).coerceIn(0, tabs.tabCount - 1)
            if (target != currentTab) {
                swipeDirection = dir
                // Вкладку выбираем без встроенной анимации полоски: она начиналась бы со старой
                // вкладки (рывок назад). «Пилюля» доезжает с того места, где её оставил палец.
                swipeSelecting = true
                tabs.selectTab(tabs.getTabAt(target), false)
                swipeSelecting = false
                animateIndicator(target)
            } else {
                animateIndicator(currentTab)
            }
        }
    }

    /** Текущее (дробное) положение «пилюли»; -1 - ещё не двигали. */
    private var indicatorPos = -1f
    private var indicatorAnim: android.animation.ValueAnimator? = null
    private var swipeSelecting = false

    private fun setIndicator(pos: Float) {
        val b = _bindingOrNull() ?: return
        val max = (b.tabs.tabCount - 1).coerceAtLeast(0)
        val p = pos.coerceIn(0f, max.toFloat())
        val i = kotlin.math.floor(p).toInt().coerceAtMost(max)
        b.tabs.setScrollPosition(i, p - i, false)
        indicatorPos = p
    }

    /** Плавно довести «пилюлю» до вкладки [to] (замедление в конце, без отскока). */
    private fun animateIndicator(to: Int) {
        indicatorAnim?.cancel()
        val from = if (indicatorPos >= 0f) indicatorPos else currentTab.toFloat()
        val dist = kotlin.math.abs(to - from)
        if (dist < 0.001f) {
            setIndicator(to.toFloat())
            return
        }
        indicatorAnim = android.animation.ValueAnimator.ofFloat(from, to.toFloat()).apply {
            duration = (170 + 140 * dist).toLong().coerceAtMost(340)
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { setIndicator(it.animatedValue as Float) }
            start()
        }
    }

    /** 1 - пришли свайпом влево (новая вкладка въезжает справа), -1 - вправо, 0 - по нажатию на вкладку. */
    private var swipeDirection = 0

    private fun showTab(tab: Int) {
        val b = _bindingOrNull() ?: return
        val changed = tab != currentTab
        val previousTab = currentTab
        currentTab = tab
        val chats = tab == TAB_CHATS || tab >= FOLDER_BASE
        applyContacts()
        b.contactList.visibility = if (chats) View.VISIBLE else View.GONE
        b.groupListView.visibility = if (chats) View.GONE else View.VISIBLE
        b.noContactsCallToAction.translationX = 0f
        b.noContactsCallToAction.alpha = 1f
        val archived = com.stoyko.smessenger.chat.Archive.ids(requireContext())
        val items = when (tab) {
            TAB_GROUPS -> allGroups.filter { !it.isChannel && (it.chatId in archived) == archiveMode }
            TAB_CHANNELS -> allGroups.filter { it.isChannel && (it.chatId in archived) == archiveMode }
            else -> emptyList()
        }
        groupAdapter?.items = com.stoyko.smessenger.chat.PinnedChats.sort(requireContext(), items) { it.chatId }
        groupAdapter?.notifyDataSetChanged()
        if (changed) {
            val shown = if (chats) b.contactList else b.groupListView
            if (!chats) b.groupListView.scheduleLayoutAnimation()
            val dir = if (swipeDirection != 0) swipeDirection else if (tab > previousTab) 1 else -1
            swipeDirection = 0
            shown.translationX = dir * shown.width.coerceAtLeast(1) * 0.5f
            shown.alpha = 0f
            shown.animate().translationX(0f).alpha(1f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        updateHeaders()
        b.noContactsCallToAction.visibility = when {
            chats -> if (b.contactList.adapter?.isEmpty != false) View.VISIBLE else View.GONE
            else -> if (items.isEmpty()) View.VISIBLE else View.GONE
        }
        b.noContactsCallToAction.text = getString(
            when {
                archiveMode -> R.string.archive_empty
                tab == TAB_GROUPS -> R.string.no_groups_tab
                tab == TAB_CHANNELS -> R.string.no_channels_tab
                tab >= FOLDER_BASE -> R.string.folder_empty
                else -> R.string.no_contacts_call_to_action
            },
        )
    }

    private fun _bindingOrNull() = if (view == null) null else binding

    // ---------------- меню «+» ----------------

    private var plusPopup: android.widget.PopupWindow? = null

    /**
     * Меню «+»: карточка плавно выезжает из кнопки, пункты появляются по очереди.
     * Сюда перенесены «Новый контакт», «Создать группу», «Создать канал», «Избранное» и «Скопировать мой ID».
     */
    private fun showPlusMenu(anchor: View) {
        plusPopup?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        class Item(val emoji: String, val title: Int, val action: () -> Unit)
        val items = listOf(
            Item("👤", R.string.plus_new_contact) { nav().navigate(R.id.action_contactListFragment_to_addContactFragment) },
            Item("👥", R.string.create_group) {
                askText(R.string.create_group, R.string.group_name_hint) { groupAction { viewModel.createGroup(it, false) } }
            },
            Item("📢", R.string.create_channel) {
                askText(R.string.create_channel, R.string.group_name_hint) { groupAction { viewModel.createGroup(it, true) } }
            },
            Item("🔗", R.string.join_by_id) {
                askText(R.string.join_by_id, R.string.group_id_hint) { groupAction { viewModel.joinGroup(it) } }
            },
            Item("⭐", R.string.saved_messages) { com.stoyko.smessenger.chat.SavedMessages.show(ctx) },
            Item("📋", R.string.plus_copy_my_id) { copyMyId() },
        )
        val surface = ContextCompat.getColor(ctx, R.color.colorSurface)
        val textColor = ContextCompat.getColor(ctx, R.color.textPrimary)
        val ripple = android.util.TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(surface)
            }
            elevation = 12 * d
            setPadding(0, dp(8), 0, dp(8))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        lateinit var popup: android.widget.PopupWindow
        val rows = items.map { item ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(13), dp(22), dp(13))
                if (ripple != 0) setBackgroundResource(ripple)
                addView(
                    android.widget.TextView(ctx).apply {
                        text = item.emoji
                        textSize = 20f
                    },
                    LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                addView(
                    android.widget.TextView(ctx).apply {
                        setText(item.title)
                        textSize = 15.5f
                        setTextColor(textColor)
                    },
                )
                setOnClickListener {
                    popup.dismiss()
                    item.action()
                }
            }.also { card.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        val width = dp(260)
        popup = android.widget.PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 12 * d
        }
        plusPopup = popup
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width - width
        val y = loc[1] - card.measuredHeight - dp(10)
        // «+» поворачивается в «×», пока меню открыто.
        anchor.animate().rotation(45f).setDuration(220).start()
        popup.setOnDismissListener {
            anchor.animate().rotation(0f).setDuration(220).start()
            plusPopup = null
        }
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x.coerceAtLeast(dp(8)), y.coerceAtLeast(dp(8)))
        if (com.stoyko.smessenger.ui.Kinetics.enabled) {
            // Карточка выезжает из кнопки (правый нижний угол), пункты - по очереди справа.
            card.pivotX = width.toFloat()
            card.pivotY = card.measuredHeight.toFloat()
            card.scaleX = 0.4f
            card.scaleY = 0.4f
            card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
            rows.forEachIndexed { i, row ->
                row.alpha = 0f
                row.translationX = dp(28).toFloat()
                row.animate().alpha(1f).translationX(0f).setStartDelay(60L + i * 35L).setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
    }

    /** «Скопировать мой ID» (76 символов) - теперь в меню «+», а не в профиле. */
    private fun copyMyId() {
        val ctx = context ?: return
        val id = runCatching { viewModel.toxIdString }.getOrNull() ?: return
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.tox_id), id))
        Toast.makeText(ctx, getString(R.string.tox_id_copied_full, id.length), Toast.LENGTH_SHORT).show()
    }

    /** Создать группу/канал или войти по ID - из вкладки «Группы»/«Каналы». */
    private fun showGroupActions(isChannel: Boolean) {
        val items = arrayOf(
            getString(if (isChannel) R.string.create_channel else R.string.create_group),
            getString(R.string.join_by_id),
        )
        AlertDialog.Builder(requireContext())
            .setItems(items) { _, which ->
                if (which == 0) {
                    askText(if (isChannel) R.string.create_channel else R.string.create_group, R.string.group_name_hint) {
                        groupAction { viewModel.createGroup(it, isChannel) }
                    }
                } else {
                    askText(R.string.join_by_id, R.string.group_id_hint) { groupAction { viewModel.joinGroup(it) } }
                }
            }
            .show()
    }

    private fun askText(title: Int, hint: Int, onOk: (String) -> Unit) {
        val edit = EditText(requireContext()).apply {
            this.hint = getString(hint)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun groupAction(block: suspend () -> String?) {
        if (!viewModel.isToxRunning()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var error: String? = null
            val chatId = try {
                block()
            } catch (e: Exception) {
                com.stoyko.smessenger.domain.Diagnostics.record("группа/канал", e, fatal = false)
                error = e.message
                null
            }
            if (view == null) return@launch
            if (chatId == null) {
                val msg = getString(R.string.group_join_failed) + (error?.let { "\n$it" } ?: "")
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } else {
                nav().navigate(
                    R.id.groupChatFragment,
                    bundleOf(com.stoyko.smessenger.ui.groups.GROUP_CHAT_ID to chatId),
                )
            }
        }
    }

    override fun onDestroyView() {
        // Выбор чатов не переживает пересоздание экрана (иначе касание чата «выбирало», а не открывало).
        selectedChats.clear()
        selectionBar = null
        // Новый экран начнёт показ статуса связи заново.
        wasOnline = null
        com.stoyko.smessenger.chat.PeerActivity.unlisten(listActivityListener)
        com.stoyko.smessenger.profile.ProfileVideo.unlisten(drawerVideoListener)
        _bindingOrNull()?.toolbar?.removeCallbacks(connectingAnim)
        com.stoyko.smessenger.vpn.VpnState.unlisten(vpnShieldListener)
        vpnShield = null
        groupAdapter = null
        navHeader = null
        chatsHeader = null
        storiesStrip = null
        chatsArchiveRow = null
        groupsArchiveRow = null
        aiRow = null
        super.onDestroyView()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)

        val inflater: MenuInflater = requireActivity().menuInflater
        val info = menuInfo as AdapterView.AdapterContextMenuInfo

        when (binding.contactList.adapter.getItemViewType(info.position)) {
            ContactListItemType.FriendRequest.ordinal -> {
                val f = FriendRequestItemBinding.bind(info.targetView)
                menu.setHeaderTitle(f.publicKey.text)
                inflater.inflate(R.menu.friend_request_context_menu, menu)
            }
            ContactListItemType.Contact.ordinal -> {
                val c = ContactListViewItemBinding.bind(info.targetView)
                menu.setHeaderTitle(c.name.text)
                inflater.inflate(R.menu.contact_list_context_menu, menu)
                val contact = binding.contactList.adapter.getItem(info.position) as Contact
                if (com.stoyko.smessenger.chat.Archive.isArchived(requireContext(), contact.publicKey)) {
                    menu.findItem(R.id.archive)?.setTitle(R.string.archive_remove)
                }
                if (com.stoyko.smessenger.chat.PinnedChats.isPinned(requireContext(), contact.publicKey)) {
                    menu.findItem(R.id.pin_chat)?.setTitle(R.string.unpin_chat)
                }
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterView.AdapterContextMenuInfo

        return when (info.targetView.id) {
            R.id.friendRequestItem -> {
                val friendRequest = binding.contactList.adapter.getItem(info.position) as FriendRequest
                when (item.itemId) {
                    R.id.accept -> {
                        viewModel.acceptFriendRequest(friendRequest)
                    }
                    R.id.reject -> {
                        viewModel.rejectFriendRequest(friendRequest)
                    }
                }
                true
            }
            R.id.contactListItem -> {
                when (item.itemId) {
                    R.id.profile -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        openProfile(contact)
                    }
                    R.id.copy_full_id -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        val full = com.stoyko.smessenger.profile.ContactIds.get(requireContext(), contact.publicKey)
                        if (full != null) {
                            com.stoyko.smessenger.ui.ShareId.copy(requireContext(), full, R.string.contact_full_id_copied)
                        } else {
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.contact_full_id_unknown_title)
                                .setMessage(R.string.contact_full_id_unknown)
                                .setPositiveButton(R.string.contact_copy_key) { _, _ ->
                                    com.stoyko.smessenger.ui.ShareId.copy(requireContext(), contact.publicKey)
                                }
                                .setNegativeButton(R.string.close, null)
                                .show()
                        }
                    }
                    R.id.share_contact -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        shareContactCard(contact)
                    }
                    R.id.pin_chat -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        val now = com.stoyko.smessenger.chat.PinnedChats.toggle(requireContext(), contact.publicKey)
                        Toast.makeText(requireContext(), if (now) R.string.chat_pinned else R.string.chat_unpinned, Toast.LENGTH_SHORT).show()
                        showTab(currentTab)
                    }
                    R.id.mute_chat -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        com.stoyko.smessenger.chat.ChatMute.showDialog(requireContext(), contact.publicKey) { showTab(currentTab) }
                    }
                    R.id.hide_chat -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        askHidePin(contact)
                    }
                    R.id.archive -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        val was = com.stoyko.smessenger.chat.Archive.isArchived(requireContext(), contact.publicKey)
                        com.stoyko.smessenger.chat.Archive.set(requireContext(), contact.publicKey, !was)
                        Toast.makeText(requireContext(), if (was) R.string.unarchived else R.string.archived, Toast.LENGTH_SHORT).show()
                        showTab(currentTab)
                    }
                    R.id.select_chats -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        toggleSelected(contact.publicKey)
                    }
                    R.id.delete -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact

                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete_contact)
                            .setMessage(
                                getString(
                                    R.string.contact_list_delete_contact_confirm,
                                    contact.name.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                                ),
                            )
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewModel.deleteContact(PublicKey(contact.publicKey))
                            }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.drawer_profile -> {
                nav().navigate(R.id.action_contactListFragment_to_userProfileFragment)
            }
            R.id.add_contact -> nav().navigate(R.id.action_contactListFragment_to_addContactFragment)
            R.id.new_group -> askText(R.string.create_group, R.string.group_name_hint) {
                groupAction { viewModel.createGroup(it, false) }
            }
            R.id.new_channel -> askText(R.string.create_channel, R.string.group_name_hint) {
                groupAction {
                    viewModel.createGroup(it, true).also { id ->
                        // Новый канал: при первом открытии - обучение «Как вести канал».
                        context?.let { c -> com.stoyko.smessenger.ui.groups.ChannelTutorial.markPending(c, id) }
                    }
                }
            }
            R.id.folders -> openFolders()
            R.id.browser -> com.stoyko.smessenger.browser.Browser.open(requireContext())
            // «Развлечения»: YouTube Shorts, YouTube и Pinterest - с подсказкой включить VPN.
            R.id.entertainment -> com.stoyko.smessenger.ui.Entertainment.show(requireContext()) { vpnForFun() }
            // Схема метро Москвы с маршрутами - работает без интернета.
            R.id.metro_map -> com.stoyko.smessenger.ui.metro.MetroScreen.show(requireContext())
            R.id.law_ukrf -> com.stoyko.smessenger.ui.law.LawScreen.show(requireContext())
            R.id.write_dev -> (activity as? com.stoyko.smessenger.MainActivity)?.writeDeveloper()
            // «Игры»: Змейка, Шахматы с друзьями на монеты, ежедневная награда.
            R.id.games -> com.stoyko.smessenger.games.GamesHub.show(requireContext())
            // Файловый менеджер: память телефона, SD-карта, загрузки, файлы мессенджера.
            R.id.file_manager -> com.stoyko.smessenger.ui.files.FileManager.show(requireContext())
            R.id.restart_app -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.restart_app)
                .setMessage(R.string.restart_app_hint)
                .setPositiveButton(R.string.restart_app) { _, _ ->
                    activity?.let { com.stoyko.smessenger.CrashGuard.restartApp(it) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            // Общий поиск: текст, ссылки, названия, файлы по расширению и PIN скрытого чата.
            R.id.search_chats -> openGlobalSearch()
            R.id.saved_messages -> com.stoyko.smessenger.chat.SavedMessages.show(requireContext())
            R.id.news_feed -> com.stoyko.smessenger.ui.feed.FeedScreen.show(this, viewModel.feed, viewModel.groups) { chatId ->
                if (view != null) {
                    nav().navigate(
                        R.id.groupChatFragment,
                        bundleOf(com.stoyko.smessenger.ui.groups.GROUP_CHAT_ID to chatId),
                    )
                }
            }
            R.id.scan_qr -> nav().navigate(R.id.addContactFragment, androidx.core.os.bundleOf("scan" to true))
            R.id.proxy -> com.stoyko.smessenger.settings.Proxies.show(
                requireContext(),
                com.stoyko.smessenger.settings.Settings(requireContext()),
            ) {}
            R.id.invite -> inviteFriends()
            R.id.groups -> nav().navigate(R.id.action_contactListFragment_to_groupListFragment)
            R.id.settings -> nav().navigate(R.id.action_contactListFragment_to_settingsFragment)
            R.id.export_tox_save -> askExport()
            R.id.quit_tox -> {
                if (!viewModel.quittingNeedsConfirmation()) {
                    viewModel.quitTox()
                    activity?.finishAffinity()
                    return false
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.quit_confirm)
                    .setPositiveButton(R.string.quit) { _, _ ->
                        viewModel.quitTox()
                        activity?.finishAffinity()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return false
    }

    override fun onResume() {
        // Имена перелёта снимаем: строку списка потом займёт другой чат.
        heroViews.forEach {
            it.transitionName = null
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
            it.translationY = 0f
            it.visibility = View.VISIBLE
        }
        heroViews = emptyList()
        super.onResume()
        // Экскурсию запросили из Настроек.
        if (!isHiddenMain && com.stoyko.smessenger.ui.Tour.takePending(requireContext())) {
            view?.postDelayed({ if (view != null) com.stoyko.smessenger.ui.Tour.startMain(this) }, 350)
        }
        _bindingOrNull()?.let {
            com.stoyko.smessenger.media.MiniPlayer.setAnchor(if (it.tabs.visibility == View.VISIBLE) it.tabs else it.toolbar)
        }
        // Истории могли истечь, пока экран был скрыт.
        if (viewModel.isToxRunning()) {
            viewModel.stories.purge()
            refreshStories()
        }
    }

    override fun onPause() {
        plusPopup?.dismiss()
        com.stoyko.smessenger.media.MiniPlayer.setAnchor(null)
        super.onPause()
    }

    // Профиль открывается в фоновом потоке: на слабых телефонах расшифровка и запуск ядра
    // занимают секунды - раньше это делалось в главном потоке («тёмный экран», зависание).
    private var loading = false

    override fun onStart() {
        super.onStart()
        if (viewModel.isToxRunning() || loading) return
        loading = true
        _bindingOrNull()?.loadingProgress?.visibility = View.VISIBLE
        viewModel.tryLoadToxAsync(null) { status ->
            loading = false
            if (!isAdded) return@tryLoadToxAsync
            _bindingOrNull()?.loadingProgress?.visibility = View.GONE
            onLoadStatus(status)
        }
    }

    /** Экран строится заново, когда профиль открыт (до этого список пуст). */
    private fun reloadSelf() {
        if (!isAdded || isStateSaved) {
            view?.post { if (isAdded && !isStateSaved) reloadSelf() }
            return
        }
        parentFragmentManager.beginTransaction().detach(this).commitAllowingStateLoss()
        parentFragmentManager.beginTransaction().attach(this).commitAllowingStateLoss()
    }

    private fun onLoadStatus(status: ToxSaveStatus) {
        when (status) {
            ToxSaveStatus.BadProxyHost, ToxSaveStatus.BadProxyPort,
            ToxSaveStatus.BadProxyType, ToxSaveStatus.ProxyNotFound,
            -> {
                Toast.makeText(requireContext(), getString(R.string.warn_proxy_broken), Toast.LENGTH_LONG).show()
                nav().navigate(R.id.action_contactListFragment_to_settingsFragment)
            }
            ToxSaveStatus.SaveNotFound ->
                nav().navigate(R.id.action_contactListFragment_to_profileFragment)
            ToxSaveStatus.Encrypted -> askPassword()
            ToxSaveStatus.Ok -> reloadSelf()
            else -> {
                // Раньше здесь было исключение и приложение падало.
                Toast.makeText(
                    requireContext(),
                    getString(R.string.import_tox_save_failed, status.name),
                    Toast.LENGTH_LONG,
                ).show()
                nav().navigate(R.id.action_contactListFragment_to_profileFragment)
            }
        }
    }

    private fun askPassword() {
        view?.visibility = View.INVISIBLE
        if (passwordDialog != null) return
        val passwordEdit = EditText(requireContext()).apply {
            hint = getString(R.string.password)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            transformationMethod = PasswordTransformationMethod()
        }
        val passwordLayout = LinearLayout(requireContext()).apply {
            val attr = context.obtainStyledAttributes(intArrayOf(android.R.attr.dialogPreferredPadding))
            val padding = attr.getDimensionPixelSize(0, 0)
            attr.recycle()
            setPadding(padding, 0, padding, 0)
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(passwordEdit)
        }
        var unlocking = false
        passwordDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.unlock_profile))
            .setView(passwordLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = passwordEdit.text.toString()
                unlocking = true
                loading = true
                _bindingOrNull()?.loadingProgress?.visibility = View.VISIBLE
                view?.visibility = View.VISIBLE
                viewModel.tryLoadToxAsync(password) { status ->
                    loading = false
                    if (!isAdded) return@tryLoadToxAsync
                    _bindingOrNull()?.loadingProgress?.visibility = View.GONE
                    if (status == ToxSaveStatus.Ok) {
                        reloadSelf()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.incorrect_password), Toast.LENGTH_LONG).show()
                        askPassword()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                passwordDialog = null
                if (!unlocking && !viewModel.isToxRunning()) {
                    activity?.finishAffinity()
                }
            }
            .show()
    }

    /**
     * Пригласить друзей: сразу APK мессенджера (установочный файл этого приложения) + полный ID
     * и короткая инструкция, как установить и добавить вас в контакты.
     */
    /**
     * «Пригласить друзей»: APK, файл-инструкция и подробный текст - что это за мессенджер,
     * кто разработчик, чем он лучше, насколько анонимен, его возможности (VPN, обход DPI,
     * узлы без серверов, ИИ) и как добавить вас. Многие приложения при отправке файлов
     * теряют текст, поэтому текст можно отправить и отдельно, и скопировать.
     */
    private fun inviteFriends() {
        val ctx = requireContext()
        val text = getString(R.string.invite_pitch, viewModel.toxIdString) + "\n\n" + getString(R.string.profile_link_one_tap, com.stoyko.smessenger.ui.ProfileLink.webLink(viewModel.toxIdString))
        val options = arrayOf<CharSequence>(
            getString(R.string.invite_all),
            getString(R.string.invite_text_only),
            getString(R.string.invite_copy_text),
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.invite_friends)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Текст - ещё и в буфер обмена: если приложение его потеряет, его можно вставить.
                        com.stoyko.smessenger.ui.ShareId.copy(ctx, text, R.string.invite_text_copied)
                        com.stoyko.smessenger.ui.ShareId.send(ctx, text, withApk = true, chooserTitle = getString(R.string.invite_friends))
                    }
                    1 -> com.stoyko.smessenger.ui.ShareId.send(ctx, text, withApk = false, chooserTitle = getString(R.string.invite_friends))
                    else -> com.stoyko.smessenger.ui.ShareId.copy(ctx, text, R.string.invite_text_copied)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Скрыть чат: PIN (4-8 цифр) дважды. Открыть - «Поиск», ввести PIN. */
    private fun askHidePin(contact: Contact) {
        com.stoyko.smessenger.chat.HiddenChats.askHide(requireContext(), contact.publicKey) {
            if (view != null) showTab(currentTab)
        }
    }

    private fun openGlobalSearch() = GlobalSearch.show(
        requireContext(),
        openChat = { pk -> openChat(pk) },
        openGroup = { id ->
            nav().navigate(R.id.groupChatFragment, bundleOf(com.stoyko.smessenger.ui.groups.GROUP_CHAT_ID to id))
        },
    )

    /** Поиск по чатам; если ввести PIN скрытого чата - он появится в результатах. */
    @Suppress("unused")
    private fun searchChats() {
        val ctx = requireContext()
        val field = android.widget.EditText(ctx).apply {
            // Короткая подсказка в поле (длинная уезжала за край строки), пояснение - под полем.
            setHint(R.string.search_chats_hint_short)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val fieldHelp = android.widget.TextView(ctx).apply {
            setText(R.string.search_chats_hint)
            textSize = 12f
            alpha = 0.7f
            setPadding((4 * resources.displayMetrics.density).toInt(), 0, 0, (6 * resources.displayMetrics.density).toInt())
        }
        val list = android.widget.ListView(ctx)
        val results = ArrayList<Contact>()
        val adapter = android.widget.ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
            addView(fieldHelp)
            addView(list, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt()))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.search_chats)
            .setView(box)
            .setNegativeButton(R.string.close, null)
            .create()
        fun refresh(q: String) {
            results.clear()
            val hidden = com.stoyko.smessenger.chat.HiddenChats.ids(ctx)
            val unlocked = com.stoyko.smessenger.chat.HiddenChats.unlock(ctx, q.trim()).toSet()
            allContacts.forEach { c ->
                val name = c.name.ifEmpty { getString(R.string.contact_default_name) }
                val visible = c.publicKey !in hidden || c.publicKey in unlocked
                val matches = c.publicKey in unlocked || (q.isNotBlank() && name.contains(q.trim(), ignoreCase = true))
                if (visible && matches) results.add(c)
            }
            adapter.clear()
            adapter.addAll(results.map { c ->
                (if (c.publicKey in unlocked) "🔓 " else "") + c.name.ifEmpty { getString(R.string.contact_default_name) }
            })
            adapter.notifyDataSetChanged()
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) = refresh(e?.toString().orEmpty())
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val c = results.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            if (com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, c.publicKey)) {
                AlertDialog.Builder(ctx)
                    .setMessage(R.string.hidden_chat_open)
                    .setPositiveButton(R.string.open) { _, _ -> openChat(c) }
                    .setNeutralButton(R.string.hidden_chat_unhide) { _, _ ->
                        com.stoyko.smessenger.chat.HiddenChats.unhide(ctx, c.publicKey)
                        showTab(currentTab)
                    }
                    .show()
            } else {
                openChat(c)
            }
        }
        dialog.show()
    }

    /**
     * «Поделиться» извне: превью + подпись, затем выбор получателей - можно
     * отметить сразу несколько чатов. Один чат - откроется он; несколько - файлы уйдут всем.
     */
    private fun showMultiShare(files: ArrayList<String>) {
        val ctx = requireContext()
        // Один и тот же файл дважды в списке «Поделиться» - отправляется один раз.
        val uris = files.distinct().map { android.net.Uri.parse(it) }
        com.stoyko.smessenger.ui.chat.SendPreview.show(ctx, uris) { caption ->
            if (view == null) return@show
            val contacts = (viewModel.contacts.value ?: allContacts)
                .filter { !com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, it.publicKey) }
                .sortedByDescending { it.lastMessage }
            if (contacts.isEmpty()) {
                Toast.makeText(ctx, R.string.share_no_contacts, Toast.LENGTH_LONG).show()
                return@show
            }
            val names = contacts.map { it.name.ifEmpty { getString(R.string.contact_default_name) } }.toTypedArray<CharSequence>()
            val checked = BooleanArray(contacts.size)
            AlertDialog.Builder(ctx)
                .setTitle(R.string.share_pick_chats)
                .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.send) { _, _ ->
                    val chosen = contacts.filterIndexed { i, _ -> checked[i] }
                    when {
                        chosen.isEmpty() -> Toast.makeText(ctx, R.string.share_pick_none, Toast.LENGTH_SHORT).show()
                        chosen.size == 1 -> nav().navigate(
                            R.id.action_contactListFragment_to_chatFragment,
                            bundleOf(CONTACT_PUBLIC_KEY to chosen[0].publicKey, ARG_SHARE_FILES to files, ARG_SHARE_CAPTION to caption),
                        )
                        else -> {
                            viewModel.shareFilesTo(uris, caption, chosen)
                            Toast.makeText(ctx, getString(R.string.share_sent_to, chosen.size), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Предложение добавить разработчика в контакты - не больше двух раз (на 2-м и 6-м запуске
     * экрана чатов) и только если его ещё нет в контактах.
     */
    private fun offerDeveloperContact() {
        val ctx = context ?: return
        val p = ctx.getSharedPreferences("dev_offer", android.content.Context.MODE_PRIVATE)
        val shown = p.getInt("shown", 0)
        if (shown >= 2 || p.getBoolean("never", false)) return
        val opens = p.getInt("opens", 0) + 1
        p.edit().putInt("opens", opens).apply()
        if (opens != 2 && opens != 6) return
        val devPk = runCatching { com.stoyko.smessenger.domain.tox.ToxID(com.stoyko.smessenger.DEVELOPER_ID).toPublicKey() }.getOrNull() ?: return
        if (viewModel.contactAdded(devPk)) return
        p.edit().putInt("shown", shown + 1).apply()
        view?.postDelayed({
            if (view == null) return@postDelayed
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_offer_title)
                .setMessage(R.string.dev_offer_text)
                .setPositiveButton(R.string.dev_offer_add) { _, _ -> (activity as? com.stoyko.smessenger.MainActivity)?.writeDeveloper() }
                .setNeutralButton(R.string.dev_offer_never) { _, _ -> p.edit().putBoolean("never", true).apply() }
                .setNegativeButton(R.string.battery_prompt_later, null)
                .show()
        }, 2500)
    }

    // ---------- выбор нескольких чатов ----------

    private val selectedChats = LinkedHashSet<String>()
    private var selectionBar: LinearLayout? = null

    private fun selAdapter(): ContactAdapter? = if (view == null) null else contactAdapterRef

    private fun toggleSelected(pk: String) {
        if (!selectedChats.remove(pk)) selectedChats += pk
        selAdapter()?.let {
            it.selected = selectedChats.toSet()
            it.notifyDataSetChanged()
        }
        renderSelectionBar()
    }

    private fun clearSelection() {
        selectedChats.clear()
        selAdapter()?.let {
            it.selected = emptySet()
            it.notifyDataSetChanged()
        }
        renderSelectionBar()
    }

    private fun renderSelectionBar() {
        if (view == null) return
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val host = binding.drawerLayout.getChildAt(0) as? ViewGroup ?: return
        if (selectedChats.isEmpty()) {
            selectionBar?.let { bar -> bar.animate().translationY(dp(90).toFloat()).alpha(0f).setDuration(180).withEndAction { host.removeView(bar) }.start() }
            selectionBar = null
            return
        }
        val bar = selectionBar ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(ContextCompat.getColor(ctx, R.color.colorSurface))
            }
            elevation = 12 * d
            host.addView(
                this,
                androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.BOTTOM
                    setMargins(dp(12), 0, dp(12), dp(18))
                },
            )
            translationY = dp(90).toFloat()
            animate().translationY(0f).setDuration(220).start()
            selectionBar = this
        }
        bar.removeAllViews()
        bar.addView(
            TextView(ctx).apply {
                text = getString(R.string.selected_count_chats, selectedChats.size)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                setPadding(dp(6), 0, dp(6), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val tiles = listOf(
            com.stoyko.smessenger.ui.Btn.tile(ctx, "☑️", getString(R.string.select_all_short)) {
                (selAdapter()?.contacts ?: emptyList()).forEach { selectedChats += it.publicKey }
                selAdapter()?.let { it.selected = selectedChats.toSet(); it.notifyDataSetChanged() }
                renderSelectionBar()
            },
            com.stoyko.smessenger.ui.Btn.tile(ctx, "🧹", getString(R.string.clear_history_short)) { massClear() },
            com.stoyko.smessenger.ui.Btn.tile(ctx, "🗑", getString(R.string.delete), 0xFFE5484D.toInt()) { massDelete() },
            com.stoyko.smessenger.ui.Btn.tile(ctx, "✕", getString(android.R.string.cancel)) { clearSelection() },
        )
        tiles.forEach { bar.addView(it, LinearLayout.LayoutParams(dp(62), dp(56)).apply { marginStart = dp(4) }) }
    }

    private fun massDelete() {
        val list = selectedChats.toList()
        if (list.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.mass_delete_title)
            .setMessage(getString(R.string.mass_delete_text, list.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                list.forEach { runCatching { viewModel.deleteContact(PublicKey(it)) } }
                Toast.makeText(requireContext(), getString(R.string.mass_deleted, list.size), Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun massClear() {
        val list = selectedChats.toList()
        if (list.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.mass_clear_title)
            .setMessage(getString(R.string.mass_clear_text, list.size))
            .setPositiveButton(R.string.clear_history_short) { _, _ ->
                list.forEach { runCatching { viewModel.clearHistory(PublicKey(it)) } }
                Toast.makeText(requireContext(), getString(R.string.mass_cleared, list.size), Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Планшет: экран справа появляется мягко (без «проезда» через весь экран). */
    private fun paneOptions(): androidx.navigation.NavOptions? = if (!isSide) null else androidx.navigation.NavOptions.Builder()
        .setEnterAnim(R.anim.pane_enter).setExitAnim(R.anim.pane_exit)
        .setPopEnterAnim(R.anim.pane_enter).setPopExitAnim(R.anim.pane_exit)
        .build()

    private fun openChat(contact: Contact) = openChat(contact.publicKey)
    private fun openChat(pk: String) {
        // Снимок списка - он будет виден под чатом при свайпе «назад».
        view?.let { com.stoyko.smessenger.ui.anim.Hero.snapshot(it) }
        nav().navigate(
            R.id.action_contactListFragment_to_chatFragment,
            bundleOf(CONTACT_PUBLIC_KEY to pk),
            paneOptions(),
        )
    }

    private var heroViews: List<View> = emptyList()

    /**
     * Открыть чат с «перелётом»: аватарка и имя строки летят в шапку чата, фон чата
     * разворачивается из точки нажатия. В режиме планшета и без анимаций - как обычно.
     */
    private fun openChatHero(row: View?, pk: String) {
        val ctx = context ?: return
        val avatar = row?.findViewById<View>(R.id.avatarImageView)
        val name = row?.findViewById<View>(R.id.name)
        if (isSide || row == null || avatar == null || name == null || !com.stoyko.smessenger.ui.anim.PeekPop.motionOk(ctx) ||
            !com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.SCREENS)
        ) {
            openChat(pk)
            return
        }
        view?.let { com.stoyko.smessenger.ui.anim.Hero.snapshot(it) }
        heroViews.forEach { it.transitionName = null }
        avatar.transitionName = com.stoyko.smessenger.ui.anim.Hero.AVATAR
        name.transitionName = com.stoyko.smessenger.ui.anim.Hero.NAME
        heroViews = listOf(avatar, name)
        val loc = IntArray(2)
        avatar.getLocationOnScreen(loc)
        val args = bundleOf(CONTACT_PUBLIC_KEY to pk)
        com.stoyko.smessenger.ui.anim.Hero.args(args, (loc[0] + avatar.width / 2).toFloat(), (loc[1] + avatar.height / 2).toFloat())
        val options = androidx.navigation.NavOptions.Builder()
            .setExitAnim(R.anim.nav_exit)
            .setPopEnterAnim(R.anim.nav_pop_enter)
            .setPopExitAnim(R.anim.nav_pop_exit)
            .build()
        val extras = androidx.navigation.fragment.FragmentNavigatorExtras(
            avatar to com.stoyko.smessenger.ui.anim.Hero.AVATAR,
            name to com.stoyko.smessenger.ui.anim.Hero.NAME,
        )
        runCatching { nav().navigate(R.id.action_contactListFragment_to_chatFragment, args, options, extras) }
            .onFailure { openChat(pk) }
    }

    /** Открыть группу/канал: фон разворачивается кругом из точки нажатия, список - снимком для свайпа назад. */
    private fun openGroup(chatId: String, row: View?) {
        view?.let { com.stoyko.smessenger.ui.anim.Hero.snapshot(it) }
        val args = bundleOf(com.stoyko.smessenger.ui.groups.GROUP_CHAT_ID to chatId)
        if (row != null && !isSide && com.stoyko.smessenger.ui.anim.PeekPop.motionOk(requireContext()) && com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.SCREENS)) {
            val loc = IntArray(2)
            row.getLocationOnScreen(loc)
            com.stoyko.smessenger.ui.anim.Hero.args(args, (loc[0] + row.height / 2).toFloat(), (loc[1] + row.height / 2).toFloat())
        }
        nav().navigate(R.id.groupChatFragment, args)
    }

    /** Peek & Pop группы/канала: последние сообщения и действия «домино». */
    private fun peekGroup(row: View, g: com.stoyko.smessenger.core.vo.GroupChat) {
        val ctx = requireContext()
        val app = ctx.applicationContext as com.stoyko.smessenger.App
        val avatar = row.findViewById<android.widget.ImageView>(R.id.avatarImageView)?.drawable?.constantState?.newDrawable()?.mutate()
            ?: com.stoyko.smessenger.groups.GroupProfile.roundBitmap(ctx, g.chatId, (40 * resources.displayMetrics.density).toInt())
                ?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        val name = com.stoyko.smessenger.groups.GroupProfile.name(ctx, g.chatId) ?: g.name.ifBlank { getString(R.string.group_default_name) }
        Thread {
            val lines = runCatching {
                app.component.groupRepository().recentTexts(g.chatId, 0L, 60)
                    .filter {
                        !it.isFile() && !com.stoyko.smessenger.chat.Markup.isGroupControl(it.message) &&
                            !com.stoyko.smessenger.chat.Markup.isAnyComment(it.message) && !com.stoyko.smessenger.chat.Polls.isVote(it.message)
                    }
                    .sortedBy { it.timestamp }
                    .takeLast(8)
                    .map { m ->
                        val who = if (m.outgoing || g.isChannel) "" else m.senderName.ifBlank { "…" } + ": "
                        (who + com.stoyko.smessenger.chat.RichText.plain(com.stoyko.smessenger.chat.Markup.body(m.message)) as CharSequence) to m.outgoing
                    }
            }.getOrDefault(emptyList())
            row.post {
                if (view == null) return@post
                val pinned = com.stoyko.smessenger.chat.PinnedChats.isPinned(ctx, g.chatId)
                val muted = com.stoyko.smessenger.chat.ChatMute.isMuted(ctx, g.chatId)
                val archived = com.stoyko.smessenger.chat.Archive.isArchived(ctx, g.chatId)
                val actions = listOf(
                    com.stoyko.smessenger.ui.anim.PeekPop.Action(getString(R.string.peek_open)) { openGroup(g.chatId, row) },
                    com.stoyko.smessenger.ui.anim.PeekPop.Action("📌 " + getString(if (pinned) R.string.unpin_chat else R.string.pin_chat)) {
                        com.stoyko.smessenger.chat.PinnedChats.toggle(ctx, g.chatId)
                        if (view != null) showTab(currentTab)
                    },
                    com.stoyko.smessenger.ui.anim.PeekPop.Action(getString(if (muted) R.string.peek_unmute else R.string.peek_mute)) {
                        if (muted) {
                            com.stoyko.smessenger.chat.ChatMute.unmute(ctx, g.chatId)
                            if (view != null) showTab(currentTab)
                        } else {
                            com.stoyko.smessenger.chat.ChatMute.showDialog(ctx, g.chatId) { if (view != null) showTab(currentTab) }
                        }
                    },
                    com.stoyko.smessenger.ui.anim.PeekPop.Action("🗂 " + getString(if (archived) R.string.archive_remove else R.string.archive_add)) {
                        com.stoyko.smessenger.chat.Archive.set(ctx, g.chatId, !archived)
                        Toast.makeText(ctx, if (archived) R.string.unarchived else R.string.archived, Toast.LENGTH_SHORT).show()
                        if (view != null) showTab(currentTab)
                    },
                    com.stoyko.smessenger.ui.anim.PeekPop.Action("🚪 " + getString(R.string.group_leave), danger = true) {
                        if (view != null) {
                            AlertDialog.Builder(ctx)
                                .setMessage(getString(R.string.group_leave_confirm, g.name))
                                .setPositiveButton(R.string.group_leave) { _, _ -> viewModel.leaveGroup(g.chatId) }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    },
                )
                com.stoyko.smessenger.ui.anim.PeekPop.show(ctx, requireActivity().window.decorView, avatar, name, lines, actions)
            }
        }.start()
    }

    /** Peek & Pop: предпросмотр последних сообщений чата и действия «домино». */
    private fun peekChat(row: View, c: Contact) {
        val ctx = requireContext()
        val avatar = row.findViewById<android.widget.ImageView>(R.id.avatarImageView)?.drawable?.constantState?.newDrawable()?.mutate()
        val name = c.name.ifBlank { c.publicKey.take(8) }
        val app = ctx.applicationContext as com.stoyko.smessenger.App
        Thread {
            val lines = runCatching {
                app.component.messageRepository().getNow(c.publicKey)
                    .filter { it.type == com.stoyko.smessenger.core.vo.MessageType.Normal && !com.stoyko.smessenger.chat.Markup.isHidden(it.message) }
                    .takeLast(8)
                    .map { m ->
                        val text = com.stoyko.smessenger.chat.Cards.preview(ctx, m.message)
                            ?: com.stoyko.smessenger.chat.RichText.plain(com.stoyko.smessenger.chat.Markup.body(m.message))
                        (text as CharSequence) to (m.sender == com.stoyko.smessenger.core.vo.Sender.Sent)
                    }
            }.getOrDefault(emptyList())
            row.post {
                if (view == null) return@post
                val pinned = com.stoyko.smessenger.chat.PinnedChats.isPinned(ctx, c.publicKey)
                val muted = com.stoyko.smessenger.chat.ChatMute.isMuted(ctx, c.publicKey)
                val archived = com.stoyko.smessenger.chat.Archive.isArchived(ctx, c.publicKey)
                val actions = ArrayList<com.stoyko.smessenger.ui.anim.PeekPop.Action>()
                actions += com.stoyko.smessenger.ui.anim.PeekPop.Action(getString(R.string.peek_open)) { openChatHero(row, c.publicKey) }
                if (c.hasUnreadMessages) {
                    actions += com.stoyko.smessenger.ui.anim.PeekPop.Action(getString(R.string.peek_mark_read)) {
                        Thread { runCatching { app.component.contactRepository().setHasUnreadMessages(c.publicKey, false) } }.start()
                    }
                }
                actions += com.stoyko.smessenger.ui.anim.PeekPop.Action("📌 " + getString(if (pinned) R.string.unpin_chat else R.string.pin_chat)) {
                    com.stoyko.smessenger.chat.PinnedChats.toggle(ctx, c.publicKey)
                    if (view != null) showTab(currentTab)
                }
                actions += com.stoyko.smessenger.ui.anim.PeekPop.Action(getString(if (muted) R.string.peek_unmute else R.string.peek_mute)) {
                    if (muted) {
                        com.stoyko.smessenger.chat.ChatMute.unmute(ctx, c.publicKey)
                        if (view != null) showTab(currentTab)
                    } else {
                        com.stoyko.smessenger.chat.ChatMute.showDialog(ctx, c.publicKey) { if (view != null) showTab(currentTab) }
                    }
                }
                actions += com.stoyko.smessenger.ui.anim.PeekPop.Action("🗂 " + getString(if (archived) R.string.archive_remove else R.string.archive_add)) {
                    com.stoyko.smessenger.chat.Archive.set(ctx, c.publicKey, !archived)
                    Toast.makeText(ctx, if (archived) R.string.unarchived else R.string.archived, Toast.LENGTH_SHORT).show()
                    if (view != null) showTab(currentTab)
                }
                // Скрыть чат PIN-кодом - прямо из меню удержания (раньше только из меню чата).
                if (com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, c.publicKey)) {
                    actions += com.stoyko.smessenger.ui.anim.PeekPop.Action("🔓 " + com.stoyko.smessenger.ai.t("Не скрывать чат", "Unhide chat")) {
                        com.stoyko.smessenger.chat.HiddenChats.unhide(ctx, c.publicKey)
                        if (view != null) showTab(currentTab)
                    }
                } else {
                    actions += com.stoyko.smessenger.ui.anim.PeekPop.Action("🔒 " + com.stoyko.smessenger.ai.t("Скрыть чат (PIN)", "Hide chat (PIN)")) {
                        if (view != null) askHidePin(c)
                    }
                }
                actions += com.stoyko.smessenger.ui.anim.PeekPop.Action(getString(R.string.peek_profile)) { openProfile(c) }
                actions += com.stoyko.smessenger.ui.anim.PeekPop.Action("🗑 " + getString(R.string.delete_contact), danger = true) {
                    if (view == null) return@Action
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.delete_contact)
                        .setMessage(getString(R.string.contact_list_delete_contact_confirm, c.name.ifBlank { c.publicKey.take(8) }.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH)))
                        .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteContact(PublicKey(c.publicKey)) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                com.stoyko.smessenger.ui.anim.PeekPop.show(ctx, requireActivity().window.decorView, avatar, name, lines, actions)
            }
        }.start()
    }

    private fun openFriendRequest(friendRequest: FriendRequest) = nav().navigate(
        R.id.action_contactListFragment_to_friendRequestFragment,
        bundleOf(FRIEND_REQUEST_PUBLIC_KEY to friendRequest.publicKey),
        paneOptions(),
    )

    private fun openProfile(contact: Contact) = nav().navigate(
        R.id.action_contactListFragment_to_contactProfileFragment,
        bundleOf(CONTACT_PUBLIC_KEY to contact.publicKey),
        paneOptions(),
    )

    /**
     * Левое окно планшета: вкладки «Чаты / Группы / Каналы» - внизу окна, а шапка - той же
     * высоты, что и шапка открытого справа чата. Раньше левая шапка (панель + вкладки) была
     * выше правой, и окна стояли «не вровень».
     */
    private fun layoutSidePane() {
        val b = if (view == null) return else binding
        val tabs = b.tabs
        val bar = tabs.parent as? ViewGroup ?: return
        val coord = b.swipeTabs.parent as? androidx.coordinatorlayout.widget.CoordinatorLayout ?: return
        if (tabs.parent === coord) return
        bar.removeView(tabs)
        val d = resources.displayMetrics.density
        coord.addView(
            tabs,
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = android.view.Gravity.BOTTOM },
        )
        tabs.elevation = 8 * d
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(tabs) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, nav.bottom)
            insets
        }
        tabs.post {
            val h = tabs.height.takeIf { it > 0 } ?: (56 * d).toInt()
            listOf<View>(b.contactList, b.groupListView).forEach { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, (96 * d).toInt() + h) }
            b.fabAddContact.translationY = -h.toFloat()
        }
    }
}
