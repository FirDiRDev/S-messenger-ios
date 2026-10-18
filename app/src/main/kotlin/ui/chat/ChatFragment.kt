// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import com.stoyko.smessenger.chat.PeerActivity.Kind as ActKind
import androidx.appcompat.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.google.android.material.math.MathUtils.lerp
import java.io.File
import java.net.URLConnection
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentChatBinding
import com.stoyko.smessenger.hasPermission
import com.stoyko.smessenger.requireStringArg
import com.stoyko.smessenger.truncated
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.isStarted
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.isComplete
import com.stoyko.smessenger.core.vo.isRejected
import com.stoyko.smessenger.domain.feature.CallState

private const val TAG = "ChatFragment"
const val CONTACT_PUBLIC_KEY = "publicKey"
const val FOCUS_ON_MESSAGE_BOX = "focusOnMessageBox"
private const val MAX_CONFIRM_DELETE_STRING_LENGTH = 20

class OpenMultiplePersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

class ChatFragment : BaseFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {
    /** Открыт из списка с «перелётом» аватарки (см. ui/anim/Hero). */
    private val hero: Boolean get() = arguments?.getBoolean(com.stoyko.smessenger.ui.anim.Hero.ARG_HERO) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hero && savedInstanceState == null) {
            sharedElementEnterTransition = com.stoyko.smessenger.ui.anim.Hero.transition()
            // Обратный перелёт выключен: строки списка переиспользуются, и аватарка «прилетала»
            // в чужую строку - после этого список «плыл». Назад - обычная анимация экрана.
            sharedElementReturnTransition = null
            // Ждём имя в шапке (не дольше 400 мс), чтобы перелёт шёл в готовое место.
            postponeEnterTransition(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
    private val viewModel: ChatViewModel by viewModels { vmFactory }

    private lateinit var contactPubKey: String
    private var contactName = ""
    private var selectedFt: Int = Int.MIN_VALUE
    private var fts: List<FileTransfer> = listOf()

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { dest ->
            if (dest == null) return@registerForActivityResult
            viewModel.backupHistory(contactPubKey, dest)
        }

    private val exportFtLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest ->
        if (dest == null) return@registerForActivityResult
        viewModel.exportFt(selectedFt, dest)
    }

    private val voiceRecorder by lazy { VoiceRecorder(requireContext()) }
    private val micPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    // «Кружок»: нужны камера и микрофон.
    private val cameraPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { !it }) {
            Toast.makeText(requireContext(), R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Живой прогресс передач (МБ, скорость) - без перестройки всего списка.
    private val transferTicker = object : Runnable {
        override fun run() {
            val b = if (view == null) return else binding
            (b.messages.adapter as? ChatAdapter)?.refreshTransfers()
            handler.postDelayed(this, if (com.stoyko.smessenger.domain.feature.TransferMonitor.busy) 250 else 1000)
        }
    }

    private val attachFilesLauncher =
        registerForActivityResult(OpenMultiplePersistableDocuments()) { files ->
            viewModel.setActiveChat(PublicKey(contactPubKey))
            if (!viewModel.contactOnline && files.isNotEmpty()) {
                // Не в сети: копия в папку приложения, чтобы файл точно ушёл при подключении.
                val ctx = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val copies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        files.mapNotNull { AttachLauncher.copyToOutgoing(ctx, it, optimize = false) }
                    }
                    sendWithCaption(copies)
                }
                return@registerForActivityResult
            }
            for (file in files) {
                try {
                    activity?.contentResolver?.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.w(TAG, "No persistable permission for $file")
                }
            }
            sendWithCaption(files)
        }

    /** «Текст с файлами»: выбор файлов для вставки в текст (результат - в [inlinePickResult]). */
    private var inlinePickResult: ((List<android.net.Uri>) -> Unit)? = null
    private val inlinePickLauncher =
        registerForActivityResult(OpenMultiplePersistableDocuments()) { files ->
            for (file in files) {
                runCatching { activity?.contentResolver?.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            inlinePickResult?.invoke(files)
            inlinePickResult = null
        }

    /** Отправить текст с файлами: сначала файлы (обычной передачей), затем текст с метками. */
    private fun sendInline(text: String, files: List<android.net.Uri>) {
        viewModel.setActiveChat(PublicKey(contactPubKey))
        stickToBottom()
        files.forEach { viewModel.createFt(it) }
        viewModel.send(text, MessageType.Normal)
    }

    /**
     * Превью выбранного + подпись. Текст, уже набранный в поле, подставляется
     * в подпись. Подпись уходит вместе с файлом и показывается под ним.
     */
    private fun sendWithCaption(uris: List<android.net.Uri>) {
        if (view == null || uris.isEmpty()) return
        val typed = binding.outgoingMessage.text.toString().trim()
        SendPreview.show(requireContext(), uris, typed) { caption0 ->
            if (view == null) return@show
            viewModel.setActiveChat(PublicKey(contactPubKey))
            // Ответ фото/видео/файлом: цитата уходит в подписи первого файла и видна под ним.
            val caption = withReply(caption0)
            // Подпись - вместе с первым файлом (как у альбома), а не отдельным сообщением.
            val spoiler = SendPreview.lastSpoiler
            uris.distinct().forEachIndexed { i, uri ->
                stickToBottom()
                val media = runCatching { requireContext().contentResolver.getType(uri) }.getOrNull().orEmpty().let { it.startsWith("image/") || it.startsWith("video/") }
                if (i == 0 && caption.isNotEmpty()) viewModel.createFtWithCaption(uri, caption, spoiler && media) else viewModel.createFt(uri, spoiler && media)
            }
            if (caption0.isNotEmpty() && caption0 == typed) binding.outgoingMessage.text.clear()
        }
    }

    // «Скрепка»: Галерея / Камера / Файл.
    private val attachLauncher = AttachLauncher(
        this,
        copyMedia = true,
        onFiles = { uris -> sendWithCaption(uris) },
        onDocuments = { attachFilesLauncher.launch(arrayOf("*/*")) },
    )

    // Стикеры: эмодзи уходят обычным сообщением (показываются крупно), свои стикеры - картинкой.
    private val stickerLauncher = StickerLauncher(
        this,
        onEmoji = { emoji ->
            view?.let {
                insertEmoji(binding.outgoingMessage, emoji)
                // Сразу обратно к сообщению: курсор в поле, клавиатура открыта.
                binding.outgoingMessage.requestFocus()
                binding.outgoingMessage.postDelayed({
                    if (view != null) {
                        WindowInsetsControllerCompat(requireActivity().window, binding.outgoingMessage)
                            .show(WindowInsetsCompat.Type.ime())
                    }
                }, 150)
            }
        },
        onSticker = { uri ->
            viewModel.setActiveChat(PublicKey(contactPubKey))
            stickToBottom()
            sendFileReplying(uri)
        },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        contactPubKey = requireStringArg(CONTACT_PUBLIC_KEY)
        // Скрытый чат открывается только по PIN - откуда бы его ни открыли (уведомление,
        // «следующий непрочитанный», профиль, ярлык). Пока PIN не введён, переписка не видна.
        if (!com.stoyko.smessenger.chat.HiddenChats.canOpen(requireContext(), contactPubKey)) {
            view.visibility = View.INVISIBLE
            com.stoyko.smessenger.chat.HiddenChats.gate(
                requireContext(), contactPubKey,
                ok = { if (this@ChatFragment.view != null) view.visibility = View.VISIBLE },
                denied = { if (isAdded) runCatching { findNavController().popBackStack() } },
            )
        }
        viewModel.setActiveChat(PublicKey(contactPubKey))
        if (hero) {
            avatarImageView.transitionName = com.stoyko.smessenger.ui.anim.Hero.AVATAR
            title.transitionName = com.stoyko.smessenger.ui.anim.Hero.NAME
            // Фон чата разворачивается кругом из точки нажатия.
            if (savedInstanceState == null) com.stoyko.smessenger.ui.anim.Hero.reveal(view, arguments)
        }
        // Файлы, которыми поделились из другого приложения, - отправить в этот чат.
        arguments?.getStringArrayList(com.stoyko.smessenger.ui.contactlist.ARG_SHARE_FILES)?.let { files ->
            arguments?.remove(com.stoyko.smessenger.ui.contactlist.ARG_SHARE_FILES)
            val caption = arguments?.getString(com.stoyko.smessenger.ui.contactlist.ARG_SHARE_CAPTION).orEmpty()
            arguments?.remove(com.stoyko.smessenger.ui.contactlist.ARG_SHARE_CAPTION)
            stickToBottom()
            files.distinct().forEachIndexed { i, f ->
                val uri = android.net.Uri.parse(f)
                if (i == 0 && caption.isNotBlank()) viewModel.createFtWithCaption(uri, caption) else viewModel.createFt(uri)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            appBarLayout.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            bottomBar.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            val pad = (8 * resources.displayMetrics.density).toInt()
            messages.updatePadding(left = insets.left + pad, right = insets.right + pad)
            compat
        }

        // «Стекло»: сообщения прокручиваются под шапкой и панелью ввода и видны сквозь них размытыми.
        setupGlass()
        guardJob = viewLifecycleOwner.lifecycleScope.launch {
            com.stoyko.smessenger.chat.ScreenGuard.changes.collect { applyGuard() }
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                var startBottom = 0
                var endBottom = 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    val pos = IntArray(2)
                    outgoingMessage.getLocationInWindow(pos)
                    startBottom = pos[1]
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    val pos = IntArray(2)
                    outgoingMessage.getLocationInWindow(pos)
                    endBottom = pos[1]
                    val offset = (startBottom - endBottom).toFloat()
                    messages.translationY = offset
                    bottomPanel.translationY = offset

                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val animation = runningAnimations[0]
                    val offset = lerp((startBottom - endBottom).toFloat(), 0f, animation.interpolatedFraction)
                    messages.translationY = offset
                    bottomPanel.translationY = offset
                    return insets
                }
            },
        )

        // Значок канала связи на аватарке убран: он налезал на аватар. Текущий канал виден в шапке списка чатов.
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        toolbar.inflateMenu(R.menu.chat_options_menu)
        // Скрытый чат: пункт «Вернуть в обычные чаты» (раньше скрыть можно было, а вернуть - нет).
        if (com.stoyko.smessenger.chat.HiddenChats.isHidden(requireContext(), contactPubKey)) {
            toolbar.menu.add(0, UNHIDE_ID, 900, R.string.hide_chat_unhide)
        } else {
            toolbar.menu.add(0, HIDE_ID, 900, R.string.hide_chat)
        }
        // Запрет скриншотов и пересылки (общий для обоих собеседников).
        toolbar.menu.add(0, GUARD_ID, 850, R.string.chatfx_guard_menu).isCheckable = true
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == GUARD_ID) {
                toggleGuard()
                return@setOnMenuItemClickListener true
            }
            if (item.itemId == HIDE_ID) {
                com.stoyko.smessenger.chat.HiddenChats.askHide(requireContext(), contactPubKey) {
                    // Чат скрыт - уходим из него, чтобы он не остался открытым на экране.
                    if (isAdded) runCatching { findNavController().popBackStack() }
                }
                return@setOnMenuItemClickListener true
            }
            if (item.itemId == UNHIDE_ID) {
                com.stoyko.smessenger.chat.HiddenChats.unhide(requireContext(), contactPubKey)
                toolbar.menu.removeItem(UNHIDE_ID)
                Toast.makeText(requireContext(), R.string.hide_chat_unhidden, Toast.LENGTH_LONG).show()
                return@setOnMenuItemClickListener true
            }
            when (item.itemId) {
                R.id.chat_wallpaper -> {
                    val opts = arrayOf<CharSequence>(
                        getString(R.string.wallpaper_photo),
                        getString(R.string.wallpaper_video),
                        getString(R.string.wallpaper_remove),
                    )
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.chat_wallpaper)
                        .setItems(opts) { _, which ->
                            when (which) {
                                0 -> wallpaperPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                                1 -> wallpaperPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                                )
                                else -> {
                                    ChatWallpaper.clear(requireContext(), contactPubKey)
                                    applyWallpaper()
                                }
                            }
                        }
                        .show()
                    true
                }
                R.id.backup_history -> {
                    exportBackupLauncher.launch(
                        "backup-atox-messages_${contactPubKey}_${
                            SimpleDateFormat(
                                """yyyy-MM-dd'T'HH-mm-ss""",
                                Locale.getDefault(),
                            ).format(Date())
                        }.json",
                    )
                    true
                }
                R.id.search -> {
                    openSearch()
                    true
                }
                R.id.e2e_check -> {
                    openE2eCheck()
                    true
                }
                R.id.auto_delete -> {
                    showAutoDeleteDialog()
                    true
                }
                R.id.mute_chat -> {
                    com.stoyko.smessenger.chat.ChatMute.showDialog(requireContext(), contactPubKey) {
                        com.stoyko.smessenger.chat.ChatMute.status(requireContext(), contactPubKey)?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        } ?: Toast.makeText(requireContext(), R.string.mute_off_done, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.scheduled -> {
                    if (viewModel.scheduledCount() == 0) {
                        Toast.makeText(requireContext(), R.string.scheduled_none, Toast.LENGTH_SHORT).show()
                    } else {
                        com.stoyko.smessenger.chat.ScheduledMessages.showList(
                            requireContext(),
                            viewModel.scheduled,
                            viewModel.scheduledTarget(),
                        )
                    }
                    true
                }
                R.id.clear_history -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.clear_history)
                        .setMessage(getString(R.string.clear_history_confirm, contactName))
                        .setPositiveButton(R.string.clear_history) { _, _ ->
                            Toast.makeText(requireContext(), R.string.clear_history_cleared, Toast.LENGTH_LONG).show()
                            viewModel.clearHistory()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
                R.id.live_help -> {
                    com.stoyko.smessenger.chat.HtmlBubbles.showHelp(requireContext())
                    true
                }
                R.id.video_call -> {
                    navigateToCallScreen(video = true)
                    true
                }
                R.id.call -> {
                    if (!viewModel.callingNeedsConfirmation()) {
                        navigateToCallScreen()
                        return@setOnMenuItemClickListener true
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.call_confirm)
                        .setPositiveButton(R.string.call) { _, _ ->
                            navigateToCallScreen()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }
        }

        contactHeader.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            findNavController().navigate(
                R.id.action_chatFragment_to_contactProfileFragment,
                bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
            )
        }

        viewModel.autoDeleteChanges.observe(viewLifecycleOwner) { updateSubtitle() }

        viewModel.contact.observe(viewLifecycleOwner) {
            if (it == null) {
                Log.e(TAG, "Contact $contactPubKey does not exist, leaving chat")
                findNavController().popBackStack()
                return@observe
            }
            it.name = it.name.ifEmpty { getString(R.string.contact_default_name) }

            contactName = it.name
            updateReactionBadges(it)
            ongoingCall.info.text = getString(R.string.in_call_with, contactName)
            viewModel.contactOnline = it.connectionStatus != ConnectionStatus.None

            // Без лишних перерисовок: раньше «печатает…» каждый раз перерисовывало шапку и аватар,
            // и вся верхняя часть экрана (вместе со строкой состояния) мигала.
            if (title.text.toString() != contactName) title.text = contactName
            if (hero) title.post { if (view != null) startPostponedEnterTransition() }
            com.stoyko.smessenger.chat.AnimatedEmoji.attachStatus(
                title,
                com.stoyko.smessenger.profile.ProfileCard.peer(requireContext(), it.publicKey)?.emojiStatus,
                20,
            )
            fitTitle()
            peerTyping = it.typing
            peerOnline = it.connectionStatus != ConnectionStatus.None
            refreshStatus()

            val avatarKey = it.avatarUri + "|" + it.name + "|" + it.connectionStatus + "|" + com.stoyko.smessenger.profile.ProfileVideo.peer(requireContext(), it.publicKey)?.name
            if (avatarKey != lastAvatarKey) {
                lastAvatarKey = avatarKey
                avatarImageView.setFrom(it)
                // Видео профиля собеседника - живая аватарка в шапке чата.
                com.stoyko.smessenger.profile.ProfileVideo.attachOver(
                    avatarImageView,
                    com.stoyko.smessenger.profile.ProfileVideo.peer(requireContext(), it.publicKey),
                )
            }
            markReadIfVisible()

            if (it.draftMessage.isNotEmpty() && outgoingMessage.text.isEmpty()) {
                outgoingMessage.setText(it.draftMessage)
                viewModel.clearDraft()
            }

            updateActions()
        }

        viewModel.callState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CallAvailability.Unavailable -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = false
                    toolbar.menu.findItem(R.id.video_call).isEnabled = false
                }
                CallAvailability.Available -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                    toolbar.menu.findItem(R.id.video_call).isEnabled = true
                }
                CallAvailability.Active -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.ongoing_call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                }
                null -> {}
            }
        }

        fun renderOngoing(it: CallState?) {
            if (it is CallState.InCall && it.publicKey.string() == contactPubKey) {
                ongoingCall.container.visibility = View.VISIBLE
                // Таймер - только когда собеседник ответил (до этого идёт «Вызов…»).
                val since = viewModel.callActiveSince
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && since > 0) {
                    ongoingCall.duration.visibility = View.VISIBLE
                    ongoingCall.duration.base = since
                    ongoingCall.duration.isCountDown = false
                    ongoingCall.duration.start()
                } else {
                    ongoingCall.duration.visibility = View.GONE
                }
            } else {
                ongoingCall.container.visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ongoingCall.duration.stop()
                }
            }
        }
        viewModel.ongoingCall.observe(viewLifecycleOwner) { renderOngoing(it) }
        viewModel.callPhase.observe(viewLifecycleOwner) { renderOngoing(viewModel.ongoingCall.value) }

        ongoingCall.endCall.setOnClickListener { viewModel.onEndCall() }
        ongoingCall.info.setOnClickListener { navigateToCallScreen() }

        val adapter = ChatAdapter(layoutInflater, resources)
        messages.adapter = adapter
        voiceListener = { adapter.refreshVoice() }
        VoicePlayer.onUpdate = voiceListener
        adapter.reactionsFor = { viewModel.reactionLine(it) }
        adapter.reactionParts = { viewModel.reactionParts(it) }
        // Карточки: «Добавить» контакт / «Вступить» в группу.
        adapter.onCard = { m, accept ->
            if (com.stoyko.smessenger.chat.Gifts.isGift(m.message)) {
                viewModel.respondGift(m.message, accept)
                if (accept) view?.let { v -> com.stoyko.smessenger.chat.Gifts.parse(m.message)?.let { o -> com.stoyko.smessenger.chat.Gifts.celebrate(v, o.gift) } }
            } else if (accept) {
                acceptCard(m)
            }
        }
        adapter.onCoinCard = { m, accept, from ->
            val c = requireContext()
            com.stoyko.smessenger.chat.CoinGift.respond(c, contactPubKey, m.message, accept)
            if (accept) {
                com.stoyko.smessenger.chat.CoinGift.parse(m.message)?.let { o ->
                    (view as? android.view.ViewGroup)?.let { v -> com.stoyko.smessenger.chat.CoinGift.celebrate(v, from, o.amount) }
                }
            }
        }
        com.stoyko.smessenger.chat.Gifts.listen(captionListener)
        // «Записывает аудио» / «отправляет фото» - у собеседника и от собеседника.
        com.stoyko.smessenger.chat.PeerActivity.listen(activityListener)
        voiceRecorder.onState = { rec ->
            viewModel.setActivity(if (rec) com.stoyko.smessenger.chat.PeerActivity.Kind.VOICE else com.stoyko.smessenger.chat.PeerActivity.Kind.NONE)
        }
        com.stoyko.smessenger.chat.Cards.listen(captionListener)
        // Подпись к файлу пришла (или сохранена) - перерисовываем пузыри.
        com.stoyko.smessenger.chat.Captions.listen(captionListener)
        com.stoyko.smessenger.chat.VoiceTranscriber.listen(captionListener)
        adapter.onFtAction = { action, ft -> onFtAction(action, ft) }
        // Кнопки внутри сообщений: быстрый ответ уходит в этот чат, игра - приглашение собеседнику.
        adapter.buttonHost = com.stoyko.smessenger.chat.MsgButtons.Host(
            send = { text ->
                if (view != null) {
                    viewModel.setActiveChat(PublicKey(contactPubKey))
                    stickToBottom()
                    viewModel.send(text, MessageType.Normal)
                }
            },
            peer = contactPubKey,
        )
        adapter.pollHost = com.stoyko.smessenger.chat.Polls.Host(
            send = { text -> if (view != null) viewModel.send(text, MessageType.Normal) },
            myKey = runCatching { viewModel.tox.publicKey.string() }.getOrDefault("me"),
            nameOf = { key -> if (key == contactPubKey) viewModel.contact.value?.name.orEmpty().ifBlank { key.take(8) } else key.take(8) },
        )
        com.stoyko.smessenger.chat.Polls.listen(captionListener)
        viewModel.extrasChanges.observe(viewLifecycleOwner) {
            updateReadIndex(adapter)
            keepScrollPosition { adapter.notifyDataSetChanged() }
            updatePinned()
        }

        // Свайп влево по сообщению - ответить.
        messages.canSwipe = { pos ->
            val m = adapter.messages.getOrNull(pos)
            m != null && !com.stoyko.smessenger.chat.AutoDelete.isMarker(m.message)
        }
        messages.onSwipeReply = { pos -> adapter.messages.getOrNull(pos)?.let { startReply(quoteFor(it)) } }
        adapter.onReplyTap = { m -> if (view != null && selected.isEmpty()) jumpToQuoted(m) }
        // Нажатие на реакции под сообщением - выбрать другую или убрать свою.
        adapter.onReactionsTap = { m ->
            if (view != null && selected.isEmpty()) {
                com.stoyko.smessenger.chat.showReactionPicker(requireContext(), viewModel.myReaction(m)) { viewModel.react(m, it) }
            }
        }
        // Двойное касание - быстрая реакция ❤️ (повторно - снять), с «сердечком» и разлётом искр.
        messages.onDoubleTap = { pos, x, y ->
            adapter.messages.getOrNull(pos)?.let { m ->
                if (m.type == MessageType.Normal || m.type == MessageType.Action || m.type == MessageType.FileTransfer) {
                    val quick = com.stoyko.smessenger.chat.QuickReaction.get(requireContext())
                    val mine = viewModel.myReaction(m)
                    viewModel.react(m, quick)
                    if (!com.stoyko.smessenger.chat.ChatExtras.has(mine, quick)) com.stoyko.smessenger.chat.QuickReaction.play(messages, x, y, quick)
                }
            }
        }

        // Отложенные сообщения.
        viewModel.scheduledChanges.observe(viewLifecycleOwner) { updateScheduledBar() }
        scheduledBar.setOnClickListener {
            com.stoyko.smessenger.chat.ScheduledMessages.showList(requireContext(), viewModel.scheduled, viewModel.scheduledTarget())
        }
        // Закрепы: нажатие - перейти к показанному и переключиться на предыдущий,
        // удержание - список всех закреплённых.
        pinnedBar.setOnClickListener {
            val pins = viewModel.pins()
            if (pins.isEmpty()) return@setOnClickListener
            pinIndex = pinIndex.coerceIn(0, pins.size - 1)
            scrollToKey(pins[pinIndex].first)
            pinIndex = if (pinIndex == 0) pins.size - 1 else pinIndex - 1
            updatePinned(keepIndex = true)
        }
        pinnedBar.setOnLongClickListener {
            showPinList()
            true
        }
        pinnedClose.setOnClickListener {
            val pins = viewModel.pins()
            if (pins.isEmpty()) return@setOnClickListener
            val current = pins[pinIndex.coerceIn(0, pins.size - 1)]
            val items = if (pins.size > 1) {
                arrayOf<CharSequence>(getString(R.string.unpin_this), getString(R.string.unpin_all), getString(R.string.pins_list))
            } else {
                arrayOf<CharSequence>(getString(R.string.unpin_this))
            }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.unpin)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> viewModel.unpin(current.first)
                        1 -> viewModel.pin(null)
                        2 -> showPinList()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        setupMusicBar()
        replyClose.setOnClickListener { cancelReply() }
        searchClose.setOnClickListener { closeSearch() }
        searchInput.doAfterTextChanged { runSearch(it?.toString().orEmpty()) }
        searchUp.setOnClickListener {
            if (searchMatches.isNotEmpty()) {
                searchIndex = (searchIndex - 1).coerceAtLeast(0)
                showSearchPosition()
            }
        }
        searchDown.setOnClickListener {
            if (searchMatches.isNotEmpty()) {
                searchIndex = (searchIndex + 1).coerceAtMost(searchMatches.lastIndex)
                showSearchPosition()
            }
        }
        registerForContextMenu(messages)

        // Фон чата (Настройки → Персонализация) и стрелка «вниз» с числом новых сообщений.
        val look = ChatLook(requireContext())
        // Анимация набора и удаления: новые буквы мягко «всплывают», стёртые - испаряются частицами.
        if (look.typingAnimation) TypingFx(outgoingMessage).attach()
        look.backgroundDrawable()?.let { view.background = it }
        applyWallpaper()
        // Музыка: очередь из всех треков этого чата (⏭ листает и включает следующий).
        com.stoyko.smessenger.media.MusicPlayer.queueProvider = provider@{
            if (view == null) return@provider emptyList()
            val a = binding.messages.adapter as? ChatAdapter ?: return@provider emptyList()
            a.messages.mapNotNull { m ->
                if (m.type != MessageType.FileTransfer) return@mapNotNull null
                val ft = a.fileTransfers.firstOrNull { it.id == m.correlationId } ?: return@mapNotNull null
                if (!com.stoyko.smessenger.media.MusicPlayer.isMusic(ft.fileName) || VoicePlayer.isVoice(ft.fileName)) return@mapNotNull null
                if (!(ft.outgoing || ft.isComplete())) return@mapNotNull null
                playableUri(ft) to ft.fileName
            }
        }
        // Автопроигрывание: после голосового сразу следующее голосовое/кружок в этом чате.
        VoicePlayer.nextProvider = provider@{ key ->
            if (!look.autoplayVoices || view == null) return@provider null
            val list = adapter.messages
            val idx = list.indexOfFirst { m ->
                m.type == MessageType.FileTransfer && adapter.fileTransfers.firstOrNull { it.id == m.correlationId }?.voiceKey() == key
            }
            if (idx < 0) return@provider null
            for (i in idx + 1 until list.size) {
                val m = list[i]
                if (m.type != MessageType.FileTransfer) continue
                val ft = adapter.fileTransfers.firstOrNull { it.id == m.correlationId } ?: continue
                val voice = VoicePlayer.isVoice(ft.fileName)
                val note = com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(ft.fileName)
                if (!voice && !note) continue
                if (!(ft.outgoing || ft.isComplete())) return@provider null
                return@provider Triple(ft.voiceKey(), playableUri(ft), note)
            }
            null
        }
        // «Желейный» скролл (пузыри тянутся от скорости и колеблются при остановке) + прежний слушатель.
        com.stoyko.smessenger.ui.anim.JellyScroll.attach(messages, object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView?, state: Int) {
                scrollThumb?.onScrollState(state)
                // Человек сам листает - больше не тянем его вниз.
                if (state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) stickToBottomUntil = 0L
                if (state == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) hideDatePill()
            }

            override fun onScroll(v: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                if (messages.isPressed || scrolledByUser) showDatePill(first)
                scrollThumb?.onScroll(first, visible, total)
                val atBottom = total == 0 || first + visible >= total - 1
                if (atBottom) unseenBelow = 0
                // Кнопка «вниз» - когда пролистали вверх больше чем на полтора экрана (или есть новые).
                val below = total - (first + visible)
                val far = below > (visible * 1.5f).toInt().coerceAtLeast(3)
                showScrollDown(!atBottom && (far || unseenBelow > 0) && look.scrollDownButton)
            }
        })

        // Быстрая прокрутка: свой ползунок на всю высоту чата (системный работал только с середины
        // экрана и показывал большую голубую дату).
        messages.isFastScrollEnabled = false
        messages.isVerticalScrollBarEnabled = false
        scrollThumb = ChatScrollThumb(requireContext(), messages).also { t ->
            t.attach(view as android.view.ViewGroup)
            t.onDrag = { pos -> showDatePill(pos) }
        }
        // Потянуть вверх в самом низу чата - перейти к следующему непрочитанному чату.
        setupPullToNextChat()
        scrollDown.setOnClickListener {
            unseenBelow = 0
            scrollToBottom(smooth = true)
        }
        restoredScroll = false
        viewModel.messages.observe(viewLifecycleOwner) {
            // Открыли чат - сразу на последнем сообщении (и ещё раз, когда догрузятся
            // картинки и высота строк уточнится), если человек сам не начал листать.
            if (!restoredScroll && it.isNotEmpty()) {
                restoredScroll = true
                stickToBottom()
                messages.post { if (view != null) scrollToBottom(smooth = false) }
                messages.postDelayed({
                    if (view != null && android.os.SystemClock.uptimeMillis() < stickToBottomUntil) scrollToBottom(smooth = false)
                }, 350)
            }
            if (lastMessageCount in 1 until it.size && scrollDown.visibility == View.VISIBLE) {
                unseenBelow += it.size - lastMessageCount
            }
            lastMessageCount = it.size
            // Плавность: база присылает тот же список повторно (например, пока идёт передача
            // файла) - если ничего не изменилось, список не пересобираем вовсе.
            if (it == adapter.messages) return@observe
            val grewAtEnd = adapter.messages.isNotEmpty() && it.size > adapter.messages.size &&
                it.lastOrNull()?.id != adapter.messages.lastOrNull()?.id
            adapter.messages = it
            updateReadIndex(adapter)
            adapter.notifyDataSetChanged()
            markReadIfVisible()
            // Только что отправили сообщение/файл - плавно вниз (даже если
            // в этот момент играет музыка или чат был пролистан вверх).
            if (android.os.SystemClock.uptimeMillis() < stickToBottomUntil) {
                messages.post { if (view != null) scrollToBottom(smooth = true) }
            }
            // Плавное появление нового сообщения внизу: выезжает снизу и проявляется.
            if (grewAtEnd) {
                messages.post {
                    if (view == null) return@post
                    val last = messages.lastVisiblePosition
                    if (last != adapter.count - 1) return@post
                    val row = messages.getChildAt(last - messages.firstVisiblePosition) ?: return@post
                    row.alpha = 0f
                    row.translationY = 18 * resources.displayMetrics.density
                    row.animate().alpha(1f).translationY(0f).setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            }
        }

        viewModel.fileTransfers.observe(viewLifecycleOwner) {
            val old = fts
            fts = it
            adapter.fileTransfers = it
            // Пока файл грузится, приходит много обновлений процента. Раньше каждое пересобирало
            // весь список - чат «дёргался». Теперь, если поменялся только процент, обновляем
            // видимые строки на месте (и не чаще 4 раз в секунду).
            if (onlyProgressChanged(old, it)) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastProgressRefresh < 250) {
                    messages.removeCallbacks(refreshVisibleRows)
                    messages.postDelayed(refreshVisibleRows, 250)
                } else {
                    lastProgressRefresh = now
                    refreshVisibleRows.run()
                }
            } else {
                keepScrollPosition { adapter.notifyDataSetChanged() }
            }
        }

        // Режим выбора: касание в любом месте сообщения (текст, фото, файл, голосовое)
        // отмечает его; кружки-галочки рисует список.
        messages.selectionActive = { selected.isNotEmpty() }
        messages.isPositionSelected = { pos -> adapter.messages.getOrNull(pos)?.id?.let { it in selected } == true }
        messages.onSelectTap = { pos ->
            adapter.messages.getOrNull(pos)?.let {
                toggleSelected(it)
                if (it.id in selected) messages.animateCheck(pos)
            }
        }
        // Удержание сообщения - одно меню: реакции + действия (в режиме выбора - отметить).
        messages.setOnItemLongClickListener { _, view, position, _ ->
            val m = adapter.messages.getOrNull(position) ?: return@setOnItemLongClickListener false
            if (selected.isNotEmpty()) {
                toggleSelected(m)
            } else {
                showMessageMenu(view, position)
            }
            true
        }
        messages.setOnItemClickListener { _, view, position, _ ->
            // Режим выбора: касание отмечает/снимает сообщение.
            if (selected.isNotEmpty()) {
                adapter.messages.getOrNull(position)?.let { toggleSelected(it) }
                return@setOnItemClickListener
            }
            // Нажатие на сообщение-ответ - переход к исходному сообщению.
            val tapped = adapter.messages.getOrNull(position)
            if (tapped != null && tapped.type != MessageType.FileTransfer && jumpToQuoted(tapped)) return@setOnItemClickListener
            // Нажатие на сообщение - сразу панель реакций (удержание - полное меню).
            if (tapped != null && tapped.type == MessageType.Normal &&
                !com.stoyko.smessenger.chat.Markup.isHidden(tapped.message)
            ) {
                // Ссылки на серверы VPN - предложить встроенный VPN или другое приложение.
                val links = com.stoyko.smessenger.vpn.VpnShare.linksIn(com.stoyko.smessenger.chat.Markup.body(tapped.message))
                if (links.isNotEmpty()) {
                    com.stoyko.smessenger.vpn.VpnShare.offer(requireContext(), links)
                    return@setOnItemClickListener
                }
                showQuickReactions(view, tapped)
                return@setOnItemClickListener
            }
            when (view.id) {
                R.id.accept -> viewModel.acceptFt(adapter.messages[position].correlationId)
                R.id.reject, R.id.cancel -> viewModel.rejectFt(adapter.messages[position].correlationId)
                R.id.voicePlay, R.id.fileTransfer -> {
                    val id = adapter.messages[position].correlationId
                    val ft = adapter.fileTransfers.find { it.id == id } ?: return@setOnItemClickListener
                    // Голосовые и «кружки» проигрываем прямо в чате - и свои, и полученные.
                    if (VoicePlayer.isVoice(ft.fileName)) {
                        onFtAction(FtAction.PlayVoice, ft)
                        return@setOnItemClickListener
                    }
                    if (com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(ft.fileName)) {
                        onFtAction(FtAction.PlayVideoNote, ft)
                        return@setOnItemClickListener
                    }
                    if (view.id == R.id.voicePlay) return@setOnItemClickListener
                    if (isStickerName(ft.fileName)) {
                        // Нажатие на стикер - «салют» из маленьких копий и его собственная анимация
                        // (сохранить к себе - удержанием, пункт «В мои стикеры»).
                        val box = (view.findViewById<View>(R.id.stickerAnim)?.parent as? View)
                        val emoji = com.stoyko.smessenger.chat.AnimatedEmoji.stickerCode(ft.fileName)
                            ?.let { com.stoyko.smessenger.chat.EmojiMotion.fromCode(it) }.orEmpty()
                        if (box != null && box.isShown) {
                            com.stoyko.smessenger.chat.EmojiMotion.burst(box, emoji.ifEmpty { "✨" })
                        } else if (!ft.outgoing && ft.isComplete()) {
                            ft.destination.toUri().path?.let { StickerLauncher.offerSave(requireContext(), File(it)) }
                        }
                        return@setOnItemClickListener
                    }
                    // Спойлер: первое нажатие только открывает пелену.
                    if (com.stoyko.smessenger.chat.MediaSpoiler.reveal(view.findViewById(R.id.imagePreview), ft.fileName, ft.destination)) {
                        return@setOnItemClickListener
                    }
                    // Фото - в своём просмотрщике, без выбора приложения.
                    if ((ft.outgoing || ft.isComplete()) && isImageName(ft.fileName)) {
                        MediaViewer.show(requireContext(), playableUri(ft), ft.fileName)
                        return@setOnItemClickListener
                    }
                    // Видео - во встроенном плеере, APK - установка, архивы и документы - «Открыть с помощью».
                    // Работает и для своих отправленных файлов.
                    val openable = ft.isComplete() || (ft.outgoing && !ft.isRejected())
                    if (!openable) return@setOnItemClickListener
                    WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
                    FileTypes.open(requireContext(), ft.destination, ft.fileName)
                }
            }
        }

        registerForContextMenu(send)
        // Выделите слово в поле ввода - над ним меню оформления на «жидком стекле».
        com.stoyko.smessenger.chat.SelectionMenu.install(outgoingMessage)
        com.stoyko.smessenger.ui.Kinetics.squish(send, 0.86f)
        send.setOnClickListener {
            // Кнопка «улетает» и возвращается - отклик на отправку.
            it.animate().scaleX(0.7f).scaleY(0.7f).rotation(-20f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
            send(MessageType.Normal)
        }

        attach.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            attachLauncher.show()
        }

        // Микрофон ↔ камера: нажатие переключает, удержание записывает.
        setupRecordButton(
            mic,
            outgoingMessage,
            voiceRecorder,
            getString(R.string.voice_recording_hint),
            ensureMic = {
                if (requireContext().hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                    true
                } else {
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    false
                }
            },
            ensureCamera = {
                val need = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                    .filterNot { requireContext().hasPermission(it) }
                if (need.isEmpty()) {
                    true
                } else {
                    cameraPermissions.launch(need.toTypedArray())
                    false
                }
            },
            onVoice = { file -> sendOwnFile(file) },
            onVideoNote = { file -> sendOwnFile(file) },
        )

        com.stoyko.smessenger.ui.applyPrivateKeyboard(outgoingMessage)
        com.stoyko.smessenger.ui.applySendByEnter(outgoingMessage, send)
        // GIF и стикеры с клавиатуры - сразу в чат, играют по кругу, как живые эмодзи.
        com.stoyko.smessenger.chat.KbGif.attach(outgoingMessage) { uri ->
            if (view != null) {
                viewModel.setActiveChat(PublicKey(contactPubKey))
                stickToBottom()
                sendFileReplying(uri)
            }
        }

        stickers.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            stickerLauncher.show()
        }

        // «/» в пустом поле - галерея живых сообщений (отправка одним нажатием).
        com.stoyko.smessenger.chat.HtmlBubbles.attachSlash(outgoingMessage) { out ->
            if (view == null) return@attachSlash
            viewModel.setActiveChat(PublicKey(contactPubKey))
            stickToBottom()
            viewModel.send(out, MessageType.Normal)
        }

        outgoingMessage.doAfterTextChanged {
            viewModel.setTyping(outgoingMessage.text.isNotEmpty())
            updateActions()
        }

        updateActions()

        if (arguments?.getBoolean(FOCUS_ON_MESSAGE_BOX) == true) {
            outgoingMessage.requestFocus()
        }
    }

    private var voiceListener: (() -> Unit)? = null
    private var lastSubtitle = ""
    private var peerTyping = false
    private var peerOnline = false

    /** Что делает собеседник: «записывает аудио», «отправляет фото»… (null - ничего). */
    private var peerAct: com.stoyko.smessenger.chat.PeerActivity.Kind? = null
    private val activityListener: () -> Unit = { if (view != null) refreshStatus() }

    /** Строка под именем: действие собеседника, «печатает», «в сети» или «не в сети». */
    private fun refreshStatus() {
        if (view == null || !::contactPubKey.isInitialized) return
        val act = if (peerOnline) com.stoyko.smessenger.chat.PeerActivity.of(contactPubKey) else null
        val newSubtitle = when {
            act == ActKind.VOICE -> getString(R.string.activity_voice)
            act == ActKind.CIRCLE -> getString(R.string.activity_circle)
            act == ActKind.PHOTO -> getString(R.string.activity_photo)
            act == ActKind.VIDEO -> getString(R.string.activity_video)
            act == ActKind.FILE -> getString(R.string.activity_file)
            peerTyping -> getString(R.string.contact_typing)
            peerOnline -> getString(R.string.status_online)
            // «был(а) в сети 5 мин. назад» - если знаем, когда собеседник ушёл из сети.
            else -> com.stoyko.smessenger.tox.Presence.lastSeenText(requireContext(), contactPubKey)
                ?: getString(R.string.status_offline_waiting)
        }.lowercase(Locale.getDefault())
        val effective = act ?: if (peerTyping) ActKind.NONE else null
        if (newSubtitle != lastSubtitle || effective != peerAct) {
            lastSubtitle = newSubtitle
            peerAct = effective
            updateSubtitle()
        }
    }

    /**
     * Значок, который «живёт» в строке статуса: рука с ручкой пишет (качается и двигается),
     * скрепка подпрыгивает, микрофон пульсирует, плёнка крутится.
     */
    private class LiveIconSpan(private val icon: String, private val mode: Int, private val t: Float) : android.text.style.ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            fm?.let {
                val m = paint.fontMetricsInt
                it.top = m.top
                it.ascent = m.ascent
                it.descent = m.descent
                it.bottom = m.bottom
            }
            return (paint.measureText(icon) * 1.25f).toInt()
        }

        override fun draw(c: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
            val w = paint.measureText(icon)
            val cx = x + w / 2
            val cy = (top + bottom) / 2f
            c.save()
            when (mode) {
                // Пишет: рука ходит вправо-влево и покачивается, как при письме.
                0 -> {
                    c.translate(kotlin.math.sin(t * 7f) * w * 0.12f, kotlin.math.abs(kotlin.math.sin(t * 14f)) * -w * 0.06f)
                    c.rotate(kotlin.math.sin(t * 14f) * 12f, cx, cy)
                }
                // Микрофон «дышит».
                1 -> {
                    val sc = 1f + 0.14f * kotlin.math.abs(kotlin.math.sin(t * 5f))
                    c.scale(sc, sc, cx, cy)
                }
                // Скрепка, фото: подпрыгивают.
                2 -> c.translate(0f, -kotlin.math.abs(kotlin.math.sin(t * 6f)) * w * 0.18f)
                // Видео: покачивается.
                else -> c.rotate(kotlin.math.sin(t * 5f) * 15f, cx, cy)
            }
            c.drawText(icon, x, y.toFloat(), paint)
            c.restore()
        }
    }

    /**
     * Имя собеседника в шапке - целиком («Наталья Мячина», а не «Наталья…»): шрифт плавно
     * уменьшается, пока имя не влезет; совсем длинное имя бежит строкой.
     */
    private fun fitTitle() {
        val t = if (view == null) return else binding.title
        val run = Runnable {
            if (view == null) return@Runnable
            val box = t.parent as? View ?: return@Runnable
            val avail = box.width - t.compoundPaddingLeft - t.compoundPaddingRight
            if (avail <= 0) return@Runnable
            val name = t.text?.toString().orEmpty()
            val p = android.text.TextPaint(t.paint)
            val sp = resources.displayMetrics.scaledDensityCompat()
            var size = 19f
            while (size > 13f) {
                p.textSize = size * sp
                if (p.measureText(name) <= avail) break
                size -= 0.5f
            }
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
            p.textSize = size * sp
            if (p.measureText(name) > avail) {
                t.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                t.marqueeRepeatLimit = -1
                t.isSelected = true
            } else {
                t.ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        t.post(run)
        // Эмодзи-статус подгружается позже и забирает место - пересчитываем ещё раз.
        t.postDelayed(run, 900)
    }

    private fun android.util.DisplayMetrics.scaledDensityCompat(): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, this)

    /** Подпись под именем: статус + «⏱ 1 д», если включено автоудаление. */
    private fun updateSubtitle() {
        val b = if (view == null) return else binding
        val ttl = viewModel.autoDeleteSeconds()
        val shield = if (com.stoyko.smessenger.chat.ScreenGuard.isOn(requireContext(), contactPubKey)) "🛡 " else ""
        b.subtitle.text = shield + if (ttl > 0) {
            "$lastSubtitle  ·  ⏱ " + com.stoyko.smessenger.chat.AutoDelete.label(requireContext(), ttl)
        } else {
            lastSubtitle
        }
        // «печатает» и другие действия: живой значок и три точки волной.
        val live = peerAct != null
        b.subtitle.removeCallbacks(typingWave)
        if (live && com.stoyko.smessenger.ui.Kinetics.enabled) b.subtitle.post(typingWave)
    }

    private val typingStart = android.os.SystemClock.uptimeMillis()
    private val typingWave: Runnable = object : Runnable {
        override fun run() {
            val b = if (view == null) return else binding
            if (peerAct == null) return
            val base = lastSubtitle.trimEnd('.', '…', ' ')
            val t = (android.os.SystemClock.uptimeMillis() - typingStart) / 1000.0
                val (icon, mode) = when (peerAct) {
                ActKind.VOICE -> "🎤" to 1
                ActKind.CIRCLE -> "📹" to 3
                ActKind.PHOTO -> "🖼" to 2
                ActKind.VIDEO -> "🎬" to 3
                ActKind.FILE -> "📎" to 2
                else -> "✍️" to 0
            }
            val sb = android.text.SpannableStringBuilder("$icon $base ")
            sb.setSpan(LiveIconSpan(icon, mode, t.toFloat()), 0, icon.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val color = b.subtitle.currentTextColor
            for (i in 0..2) {
                val k = ((kotlin.math.sin(t * 6.0 - i * 0.9) + 1) / 2).toFloat()
                val startIdx = sb.length
                sb.append("•")
                sb.setSpan(
                    android.text.style.ForegroundColorSpan((color and 0x00FFFFFF) or ((70 + 185 * k).toInt() shl 24)),
                    startIdx, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            b.subtitle.text = sb
            b.subtitle.postDelayed(this, 70)
        }
    }

    private fun showAutoDeleteDialog() {
        val options = com.stoyko.smessenger.chat.AUTO_DELETE_OPTIONS
        val labels = options.map { com.stoyko.smessenger.chat.AutoDelete.label(requireContext(), it) }.toTypedArray()
        val current = options.indexOf(viewModel.autoDeleteSeconds()).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.autodelete)
            .setSingleChoiceItems(labels, current) { d, which ->
                viewModel.setAutoDelete(options[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        Toast.makeText(requireContext(), R.string.autodelete_hint, Toast.LENGTH_LONG).show()
    }

    private fun acceptCard(m: Message) {
        val ctx = context ?: return
        val cards = com.stoyko.smessenger.chat.Cards
        val contact = cards.parseContact(m.message)
        if (contact != null) {
            viewModel.addContactFromCard(contact.id, getString(R.string.card_hello))
            Toast.makeText(ctx, getString(R.string.card_added_toast, contact.name.ifBlank { contact.id.take(8) }), Toast.LENGTH_LONG).show()
            return
        }
        if (cards.isInvite(m.message) || cards.isMeta(m.message)) {
            viewModel.acceptInvite(m.message) { chatId ->
                activity?.runOnUiThread {
                    if (chatId == null) {
                        // Не вышло (пригласивший не в сети) - кнопки возвращаются, можно попробовать позже.
                        cards.clearState(ctx, cards.keyFor(m.message, m.timestamp))
                        (binding.messages.adapter as? ChatAdapter)?.notifyDataSetChanged()
                        Toast.makeText(ctx, R.string.card_join_failed, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, R.string.card_joined, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val captionListener: () -> Unit = {
        if (view != null) (binding.messages.adapter as? ChatAdapter)?.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        com.stoyko.smessenger.chat.HtmlBubbles.clear()
        guardJob?.cancel()
        guardJob = null
        com.stoyko.smessenger.chat.ScreenGuard.setActive(activity, null)
        com.stoyko.smessenger.chat.Captions.unlisten(captionListener)
        com.stoyko.smessenger.chat.Polls.unlisten(captionListener)
        com.stoyko.smessenger.chat.VoiceTranscriber.unlisten(captionListener)
        com.stoyko.smessenger.chat.Gifts.unlisten(captionListener)
        com.stoyko.smessenger.chat.PeerActivity.unlisten(activityListener)
        voiceRecorder.onState = null
        com.stoyko.smessenger.chat.Cards.unlisten(captionListener)
        handler.removeCallbacks(transferTicker)
        com.stoyko.smessenger.media.MusicPlayer.queueProvider = null
        if (VoicePlayer.onUpdate === voiceListener) VoicePlayer.onUpdate = null
        voiceListener = null
        VoicePlayer.nextProvider = null
        // Запоминаем место чтения.
        runCatching {
            val list = binding.messages
            val a = list.adapter as? ChatAdapter
            val first = list.firstVisiblePosition
            val atBottom = list.lastVisiblePosition >= (a?.count ?: 0) - 1
            if (a != null && !atBottom && first in a.messages.indices) {
                scrollMemory[contactPubKey] = a.messages[first].id to (list.getChildAt(0)?.top ?: 0)
            } else {
                scrollMemory.remove(contactPubKey)
            }
        }
        ChatWallpaper.release()
        datePill = null
        com.stoyko.smessenger.media.MusicPlayer.removeListener(musicListener)
        musicBar = null
        musicTitle = null
        musicToggle = null
        scrollThumb = null
        scrollDownShown = false
        lastAvatarKey = ""
        lastSubtitle = ""
        selected.clear()
        selectionBar = null
        selectionCount = null
        super.onDestroyView()
    }

    /** Адрес файла, который можно открыть: свои файлы - через FileProvider. */
    private fun playableUri(ft: FileTransfer): android.net.Uri {
        val u = ft.destination.toUri()
        return if (u.scheme == "file") {
            FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", File(u.path!!))
        } else {
            u
        }
    }

    private fun isImageName(name: String) =
        URLConnection.guessContentTypeFromName(name)?.startsWith("image/") == true

    override fun onPause() {
        com.stoyko.smessenger.ui.anim.SwipeBack.disable(binding.root)
        voiceRecorder.cancel()
        // Голосовое/«кружок» продолжает играть в других чатах (мини-плеер).
        handler.removeCallbacks(transferTicker)
        handler.removeCallbacks(presenceTicker)
        com.stoyko.smessenger.media.MiniPlayer.setAnchor(null)
        viewModel.setDraft(binding.outgoingMessage.text.toString())
        viewModel.setActiveChat(PublicKey(""))
        super.onPause()
    }

    override fun onResume() = binding.run {
        viewModel.setActiveChat(PublicKey(contactPubKey))
        viewModel.setTyping(outgoingMessage.text.isNotEmpty())
        super.onResume()
        com.stoyko.smessenger.media.MiniPlayer.setAnchor(appBarLayout)
        handler.removeCallbacks(transferTicker)
        handler.post(transferTicker)
        // «был(а) в сети N мин. назад» не застывает: пересчитываем при возврате и раз в минуту.
        handler.removeCallbacks(presenceTicker)
        handler.post(presenceTicker)
        markReadIfVisible()
        refreshOtherUnread()
        // Свайп вправо - назад, с резиновой оттяжкой (не из поля ввода и не из шапки).
        com.stoyko.smessenger.ui.anim.SwipeBack.enable(root, { if (view != null) findNavController().popBackStack() }, bottomPanel, appBarLayout)
    }

    private val presenceTicker: Runnable = object : Runnable {
        override fun run() {
            if (view == null) return
            refreshStatus()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) = binding.run {
        super.onCreateContextMenu(menu, v, menuInfo)
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
        when (v.id) {
            R.id.messages -> {
                val info = menuInfo as AdapterView.AdapterContextMenuInfo
                prepareMessageMenu(menu, info.position)
            }
            R.id.send -> requireActivity().menuInflater.inflate(R.menu.chat_send_long_press_menu, menu)
        }
        Unit
    }

    /** Пункты меню сообщения (общие для системного меню и для меню с реакциями). */
    private fun prepareMessageMenu(menu: android.view.Menu, position: Int): Boolean {
        val inflater = requireActivity().menuInflater
        val message = binding.messages.adapter.getItem(position) as? Message ?: return false
        when (message.type) {
            MessageType.Action, MessageType.Normal -> {
                inflater.inflate(R.menu.chat_message_context_menu, menu)
                menu.findItem(R.id.edit)?.isVisible = viewModel.canEdit(message)
            }
            MessageType.FileTransfer -> {
                inflater.inflate(R.menu.ft_message_context_menu, menu)
                val ft = fts.find { it.id == message.correlationId } ?: return true
                if (!ft.isComplete() || ft.outgoing || !ft.destination.startsWith("file://")) {
                    menu.findItem(R.id.export)?.isVisible = false
                }
                val have = ft.isComplete() || ft.outgoing
                // Голосовое - «Расшифровать» (текст покажется под ним).
                menu.findItem(R.id.transcribe)?.isVisible = have && VoicePlayer.isVoice(ft.fileName)
                // «Сохранить в Галерею/Музыку/Загрузки» - для любого готового файла.
                menu.findItem(R.id.save_public)?.let {
                    it.isVisible = have
                    it.setTitle(FileTypes.saveTargetLabel(ft.fileName))
                }
                // Стикер или картинку собеседника - в свои стикеры.
                val img = isStickerName(ft.fileName) || FileTypes.ext(ft.fileName) in setOf("png", "webp", "jpg", "jpeg", "gif")
                menu.findItem(R.id.add_sticker)?.isVisible = have && img && ft.destination.startsWith("file://")
                // Не дошёл / не докачался - «Повторить загрузку» (с места обрыва).
                if (!ft.isComplete() && (ft.outgoing || ft.isStarted() || ft.isRejected())) {
                    menu.add(0, R.id.retry_ft, 0, R.string.ft_retry)
                }
            }
        }
        // Запрет скриншотов и пересылки: ни переслать, ни скопировать, ни сохранить (у обоих).
        if (com.stoyko.smessenger.chat.ScreenGuard.isOn(requireContext(), contactPubKey)) {
            listOf(R.id.forward, R.id.copy, R.id.export, R.id.save_public, R.id.add_sticker).forEach { menu.findItem(it)?.isVisible = false }
        }
        return true
    }

    // Позиция сообщения, для которого открыто меню с реакциями (у его пунктов нет menuInfo).
    private var popupPosition = -1

    private fun menuInfoOf(item: MenuItem): AdapterView.AdapterContextMenuInfo =
        item.menuInfo as? AdapterView.AdapterContextMenuInfo ?: AdapterView.AdapterContextMenuInfo(null, popupPosition, 0)

    /**
     * Удержание сообщения: одно меню - сверху реакции, ниже действия (ответить, переслать, выбрать,
     * сохранить, закрепить...). Плавно раскрывается от сообщения и так же сворачивается.
     */
    private fun showMessageMenu(anchor: View, position: Int) {
        val ctx = requireContext()
        val message = binding.messages.adapter.getItem(position) as? Message ?: return
        val holder = android.widget.PopupMenu(ctx, anchor)
        if (!prepareMessageMenu(holder.menu, position)) return
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val root = android.widget.FrameLayout(ctx).apply { setBackgroundColor(0x66000000) }
        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val popup = android.widget.PopupWindow(root, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT, true)
        popup.isClippingEnabled = false
        var closing = false
        fun close(after: (() -> Unit)? = null) {
            if (closing) return
            closing = true
            column.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(140).start()
            root.animate().alpha(0f).setDuration(160).withEndAction {
                runCatching { popup.dismiss() }
                after?.invoke()
            }.start()
        }
        root.setOnClickListener { close() }

        // Реакции - на всё: текст, фото, видео, музыку, голосовые, файлы, ссылки.
        val reactable = (message.type == MessageType.Normal || message.type == MessageType.FileTransfer || message.type == MessageType.Action) &&
            !com.stoyko.smessenger.chat.Markup.isHidden(message.message) &&
            !com.stoyko.smessenger.chat.AutoDelete.isMarker(message.message)
        if (reactable) {
            val mine = viewModel.myReaction(message)
            val bar = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(px(8), px(6), px(8), px(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 26 * d
                    setColor(ContextCompat.getColor(ctx, R.color.colorSurface))
                }
                elevation = 10 * d
            }
            // Больше реакций прокруткой; «✖» - убрать свои, «➕» - все эмодзи.
            val quickList = com.stoyko.smessenger.chat.ReactionPicker.quick(ctx) +
                (if (com.stoyko.smessenger.chat.ChatExtras.split(mine).isNotEmpty()) listOf("✖") else emptyList()) + "➕"
            quickList.forEachIndexed { i, e ->
                bar.addView(
                    android.widget.TextView(ctx).apply {
                        text = e
                        textSize = if (e == "➕" || e == "✖") 19f else 23f
                        if (e == "✖") setTextColor(0xFFE5484D.toInt())
                        if (e == "➕") com.stoyko.smessenger.chat.ReactionPicker.stylePlus(this)
                        gravity = android.view.Gravity.CENTER
                        setPadding(px(5), px(2), px(5), px(2))
                        if (com.stoyko.smessenger.chat.ChatExtras.has(mine, e)) {
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(0x335B4BDB)
                            }
                        }
                        scaleX = 0f
                        scaleY = 0f
                        animate().scaleX(1f).scaleY(1f).setStartDelay(60L + i * 22L).setDuration(220)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                        setOnClickListener {
                            close {
                                when (e) {
                                    "➕" -> com.stoyko.smessenger.chat.showReactionPicker(ctx, mine) { viewModel.react(message, it) }
                                    "✖" -> viewModel.react(message, null)
                                    else -> viewModel.react(message, e)
                                }
                            }
                        }
                    },
                )
            }
            // Полоса шире экрана - листается пальцем.
            bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val maxW = resources.displayMetrics.widthPixels - px(24)
            val barScroll = android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                background = bar.background
                elevation = bar.elevation
                bar.background = null
                bar.elevation = 0f
                addView(bar)
            }
            column.addView(barScroll, android.widget.LinearLayout.LayoutParams(minOf(bar.measuredWidth, maxW), android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) })
        }

        val card = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, px(6), 0, px(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(ContextCompat.getColor(ctx, R.color.colorSurface))
            }
            elevation = 10 * d
        }
        val icons = mapOf(
            R.id.reply to "↩", R.id.forward to "➦", R.id.select to "☑", R.id.edit to "✎", R.id.copy to "⧉",
            R.id.pin to "📌", R.id.delete to "🗑", R.id.export to "💾", R.id.save_public to "⬇", R.id.add_sticker to "⭐",
        )
        val menu = holder.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible || item.itemId == R.id.react) continue
            val danger = item.itemId == R.id.delete
            card.addView(
                android.widget.TextView(ctx).apply {
                    text = (icons[item.itemId]?.let { "$it   " } ?: "") + item.title
                    textSize = 16f
                    setTextColor(if (danger) 0xFFE5484D.toInt() else ContextCompat.getColor(ctx, R.color.textPrimary))
                    setPadding(px(18), px(12), px(28), px(12))
                    val ripple = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        close {
                            popupPosition = position
                            onContextItemSelected(item)
                        }
                    }
                },
                android.widget.LinearLayout.LayoutParams(px(240), android.view.ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        column.addView(card, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

        // Позиция: у сообщения (над ним, если снизу мало места), своё - справа, чужое - слева.
        column.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val outgoing = message.sender == com.stoyko.smessenger.core.vo.Sender.Sent
        val x = if (outgoing) screenW - column.measuredWidth - px(12) else px(12)
        val below = loc[1] + anchor.height + px(4)
        val y = if (below + column.measuredHeight < screenH - px(48)) below else (loc[1] - column.measuredHeight - px(4)).coerceAtLeast(px(48))
        column.x = x.toFloat()
        column.y = y.toFloat()
        column.pivotX = if (outgoing) column.measuredWidth.toFloat() else 0f
        column.pivotY = if (y < loc[1]) column.measuredHeight.toFloat() else 0f
        column.scaleX = 0.6f
        column.scaleY = 0.6f
        column.alpha = 0f
        root.alpha = 0f
        popup.showAtLocation(binding.root, android.view.Gravity.NO_GRAVITY, 0, 0)
        root.animate().alpha(1f).setDuration(160).start()
        column.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean = binding.run {
        return when (item.itemId) {
            R.id.copy -> {
                val info = menuInfoOf(item)
                val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
                val message = messages.adapter.getItem(info.position) as Message
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(getText(R.string.message), com.stoyko.smessenger.chat.Markup.body(message.message)),
                )

                Toast.makeText(requireContext(), getText(R.string.copied), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.delete -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message

                val builder = AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete_message)
                    .setMessage(
                        getString(
                            R.string.delete_message_confirm,
                            com.stoyko.smessenger.chat.Markup.snippet(message.message).truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                        ),
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                if (message.sender == com.stoyko.smessenger.core.vo.Sender.Sent) {
                    // Своё сообщение: удалить у меня или у всех.
                    builder.setPositiveButton(R.string.delete_for_all) { _, _ -> viewModel.deleteForAll(message) }
                    builder.setNeutralButton(R.string.delete_for_me) { _, _ -> viewModel.delete(message) }
                } else {
                    builder.setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(message) }
                }
                builder.show()
                true
            }
            R.id.send_rich -> {
                val typed = outgoingMessage.text.toString()
                com.stoyko.smessenger.chat.RichEditor.show(requireContext(), typed) { out ->
                    if (view == null) return@show
                    viewModel.setActiveChat(PublicKey(contactPubKey))
                    stickToBottom()
                    viewModel.send(out, MessageType.Normal)
                    if (typed.isNotEmpty()) outgoingMessage.text.clear()
                }
                true
            }
            R.id.send_files_inline -> {
                val typed = outgoingMessage.text.toString().trim()
                com.stoyko.smessenger.chat.InlineFiles.compose(
                    requireContext(),
                    typed,
                    pick = { cb ->
                        inlinePickResult = cb
                        inlinePickLauncher.launch(arrayOf("*/*"))
                    },
                ) { text, files ->
                    if (view == null) return@compose
                    sendInline(text, files)
                    if (typed.isNotEmpty()) outgoingMessage.text.clear()
                }
                true
            }
            R.id.send_html -> {
                val typed = outgoingMessage.text.toString().trim()
                com.stoyko.smessenger.chat.HtmlBubbles.editor(requireContext(), typed) { out ->
                    if (view == null) return@editor
                    viewModel.setActiveChat(PublicKey(contactPubKey))
                    stickToBottom()
                    viewModel.send(out, MessageType.Normal)
                    if (typed.isNotEmpty()) outgoingMessage.text.clear()
                }
                true
            }
            R.id.send_poll -> {
                val typed = outgoingMessage.text.toString().trim()
                com.stoyko.smessenger.chat.Polls.editor(requireContext(), typed) { out ->
                    if (view == null) return@editor
                    viewModel.setActiveChat(PublicKey(contactPubKey))
                    stickToBottom()
                    viewModel.send(out, MessageType.Normal)
                    if (typed.isNotEmpty()) outgoingMessage.text.clear()
                }
                true
            }
            R.id.send_buttons -> {
                // Сообщение с кнопками: текст из поля ввода становится текстом сообщения.
                val typed = outgoingMessage.text.toString().trim()
                com.stoyko.smessenger.chat.MsgButtons.editor(requireContext(), typed, games = true, shop = false) { out ->
                    if (view == null) return@editor
                    viewModel.setActiveChat(PublicKey(contactPubKey))
                    stickToBottom()
                    viewModel.send(out, MessageType.Normal)
                    if (typed.isNotEmpty()) outgoingMessage.text.clear()
                }
                true
            }
            R.id.send_action -> {
                send(MessageType.Action)
                true
            }
            R.id.send_spoiler -> {
                val typed = outgoingMessage.text.toString()
                if (typed.isBlank()) return true
                outgoingMessage.setText(com.stoyko.smessenger.chat.MediaSpoiler.wrapText(typed))
                send(MessageType.Normal)
                true
            }
            R.id.select -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                toggleSelected(message)
                true
            }
            R.id.forward -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                val ft = if (message.type == MessageType.FileTransfer) {
                    (messages.adapter as? ChatAdapter)?.fileTransfers?.firstOrNull { it.id == message.correlationId }
                } else {
                    null
                }
                forwardItems(listOf(message to ft))
                true
            }
            R.id.send_scheduled -> {
                val text = outgoingMessage.text.toString()
                if (text.isBlank()) return true
                com.stoyko.smessenger.chat.ScheduledMessages.pickTime(requireContext()) { at ->
                    val quoted = replyTo
                    viewModel.schedule(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, text) else text, at)
                    if (view != null) {
                        binding.outgoingMessage.text.clear()
                        cancelReply()
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.scheduled_added, com.stoyko.smessenger.chat.ScheduledMessages.label(requireContext(), at)),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                true
            }
            R.id.reply -> {
                val info = menuInfoOf(item)
                startReply(quoteFor(messages.adapter.getItem(info.position) as Message))
                true
            }
            R.id.retry_ft -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                fts.find { it.id == message.correlationId }?.let { retryFt(it) }
                true
            }
            R.id.edit -> {
                val info = menuInfoOf(item)
                startEdit(messages.adapter.getItem(info.position) as Message)
                true
            }
            R.id.react -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                com.stoyko.smessenger.chat.showReactionPicker(requireContext(), viewModel.myReaction(message)) {
                    viewModel.react(message, it)
                }
                true
            }
            R.id.pin -> {
                val info = menuInfoOf(item)
                val m = messages.adapter.getItem(info.position) as Message
                // Закрепить можно сколько угодно сообщений и медиа; повторно - открепить.
                if (viewModel.isPinned(m)) {
                    viewModel.unpin(com.stoyko.smessenger.chat.Markup.key(m.message))
                    Toast.makeText(requireContext(), R.string.unpinned, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.pin(m)
                    pinIndex = Int.MAX_VALUE
                    Toast.makeText(requireContext(), R.string.pinned_done, Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.transcribe -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                val ft = fts.find { it.id == message.correlationId } ?: return true
                transcribe(ft)
                true
            }
            R.id.save_public -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                val ft = fts.find { it.id == message.correlationId } ?: return true
                runCatching {
                    FileTypes.saveToPublic(requireContext(), FileTypes.shareUri(requireContext(), ft.destination), ft.fileName)
                }
                true
            }
            R.id.add_sticker -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                val ft = fts.find { it.id == message.correlationId } ?: return true
                val path = android.net.Uri.parse(ft.destination).path
                val added = path?.let { StickerStore.addFile(requireContext(), File(it)) }
                Toast.makeText(
                    requireContext(),
                    if (added != null) R.string.sticker_added else R.string.sticker_add_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
            R.id.export -> {
                val info = menuInfoOf(item)
                val message = messages.adapter.getItem(info.position) as Message
                selectedFt = message.correlationId
                exportFtLauncher.launch(message.message)
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun send(type: MessageType) = binding.run {
        val edited = editing
        if (edited != null) {
            val newText = outgoingMessage.text.toString().trim()
            if (newText.isNotEmpty()) {
                if (!viewModel.edit(edited, newText)) {
                    Toast.makeText(requireContext(), R.string.edit_too_long, Toast.LENGTH_LONG).show()
                    return@run
                }
            }
            outgoingMessage.text.clear()
            cancelReply()
            return@run
        }
        viewModel.clearDraft()
        val text = outgoingMessage.text.toString()
        val quoted = replyTo
        stickToBottomUntil = android.os.SystemClock.uptimeMillis() + 2500
        viewModel.send(if (quoted != null) com.stoyko.smessenger.chat.Markup.reply(quoted, text) else text, type)
        scrollToBottom(smooth = true)
        outgoingMessage.text.clear()
        cancelReply()
        // После отправки клавиатура прячется сама.
        WindowInsetsControllerCompat(requireActivity().window, outgoingMessage).hide(WindowInsetsCompat.Type.ime())
        outgoingMessage.clearFocus()
    }

    // ---------- Ответ, закреп, поиск ----------

    private var replyTo: String? = null

    // Сообщение, которое сейчас изменяем (: текст в поле ввода, «Редактирование» сверху).
    private var editing: Message? = null

    private fun startEdit(m: Message) {
        // Живое сообщение (галерея «/») - меняем его параметры в редакторе.
        if (com.stoyko.smessenger.chat.HtmlBubbles.isTemplate(m.message)) {
            com.stoyko.smessenger.chat.HtmlBubbles.editSent(requireContext(), m.message) { newRaw ->
                if (view == null) return@editSent
                if (!viewModel.edit(m, newRaw, raw = true)) {
                    Toast.makeText(requireContext(), R.string.edit_too_long, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val b = if (view == null) return else binding
        startReply(m.message)
        replyTo = null
        editing = m
        b.replyLabel.setText(R.string.editing)
        val body = com.stoyko.smessenger.chat.Markup.body(m.message)
        b.outgoingMessage.setText(body)
        b.outgoingMessage.setSelection(body.length)
    }

    private fun startReply(text: String) {
        val b = if (view == null) return else binding
        editing = null
        b.replyLabel.setText(R.string.reply_to)
        replyTo = text
        b.replyText.text = com.stoyko.smessenger.chat.Markup.snippet(text)
        b.replyBar.visibility = View.VISIBLE
        b.replyBar.alpha = 0f
        b.replyBar.translationY = 20f
        b.replyBar.animate().alpha(1f).translationY(0f).setDuration(160).start()
        b.outgoingMessage.requestFocus()
        androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, b.outgoingMessage)
            .show(WindowInsetsCompat.Type.ime())
    }

    private fun cancelReply() {
        replyTo = null
        if (editing != null) {
            editing = null
            if (view != null) binding.outgoingMessage.text.clear()
        }
        if (view != null) binding.replyBar.visibility = View.GONE
    }

    /** Прокрутить к сообщению с ключом key и коротко подсветить его. */
    private fun scrollToKey(key: String) {
        val b = if (view == null) return else binding
        val adapter = b.messages.adapter as? ChatAdapter ?: return
        val index = adapter.messages.indexOfLast { com.stoyko.smessenger.chat.Markup.key(it.message) == key }
        if (index < 0) {
            Toast.makeText(requireContext(), R.string.message_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        scrollToIndex(index)
    }

    /**
     * Сообщение - ответ? Перейти к исходному (подсветить). true - это был ответ.
     * Исходное ищем по цитате: точное совпадение, затем по началу (исходное могли изменить).
     */
    private fun jumpToQuoted(tapped: Message): Boolean {
        val quote = com.stoyko.smessenger.chat.Markup.parseReply(tapped.message).first ?: return false
        if (view == null) return false
        val list = (binding.messages.adapter as? ChatAdapter)?.messages ?: return false
        fun snip(m: Message) = if (m.type == MessageType.FileTransfer) com.stoyko.smessenger.chat.Markup.snippet(quoteFor(m)) else com.stoyko.smessenger.chat.Markup.snippet(m.message)
        val before = list.indexOfFirst { it.id == tapped.id }.let { if (it < 0) list.size else it }
        // Исходное - выше ответа; сначала точное совпадение, потом по началу текста.
        var idx = (before - 1 downTo 0).firstOrNull { snip(list[it]) == quote } ?: -1
        if (idx < 0) {
            val head = quote.removeSuffix("…").take(24)
            if (head.length >= 4) idx = (before - 1 downTo 0).firstOrNull { snip(list[it]).startsWith(head) } ?: -1
        }
        if (idx >= 0) scrollToIndex(idx) else Toast.makeText(requireContext(), R.string.message_not_found, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun scrollToIndex(index: Int) {
        val b = if (view == null) return else binding
        // Исходное - по центру экрана, плавно; затем мягкая подсветка пузыря.
        val offset = (b.messages.height / 3).coerceAtLeast(0)
        b.messages.smoothScrollToPositionFromTop(index, offset, 280)
        b.messages.postDelayed({
            if (view == null) return@postDelayed
            val child = b.messages.getChildAt(index - b.messages.firstVisiblePosition) ?: run {
                b.messages.setSelectionFromTop(index, offset)
                return@postDelayed
            }
            val flash = android.graphics.drawable.ColorDrawable(0x557B6CF6)
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                child.foreground = flash
                android.animation.ObjectAnimator.ofInt(flash, "alpha", 0, 255, 255, 0).apply {
                    duration = 1100
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (child.foreground === flash) child.foreground = null
                        }
                    })
                    start()
                }
            } else {
                child.animate().alpha(0.35f).setDuration(150).withEndAction { child.animate().alpha(1f).setDuration(350).start() }.start()
            }
        }, 330)
    }

    /** Какой закреп показан в полоске (по умолчанию - последний закреплённый). */
    private var pinIndex = Int.MAX_VALUE

    private fun updatePinned(keepIndex: Boolean = false) {
        val b = if (view == null) return else binding
        val pins = viewModel.pins()
        b.pinnedBar.visibility = if (pins.isNotEmpty()) View.VISIBLE else View.GONE
        if (pins.isEmpty()) return
        if (!keepIndex && pinIndex >= pins.size) pinIndex = pins.size - 1
        pinIndex = pinIndex.coerceIn(0, pins.size - 1)
        val p = pins[pinIndex]
        b.pinnedText.text = if (pins.size > 1) "${pinIndex + 1}/${pins.size} · ${p.second}" else p.second
    }

    /** Список всех закреплённых сообщений - нажмите, чтобы перейти. */
    private fun showPinList() {
        val pins = viewModel.pins()
        if (pins.isEmpty()) return
        val rev = pins.asReversed()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pins_count, pins.size))
            .setItems(rev.map { "📌 " + it.second.ifBlank { "…" } }.toTypedArray<CharSequence>()) { _, which ->
                scrollToKey(rev[which].first)
                pinIndex = pins.size - 1 - which
                updatePinned(keepIndex = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Музыка прямо в чате (под закрепом): ⏯ ⏭ и название трека ----------

    private var musicBar: android.widget.LinearLayout? = null
    private var musicTitle: android.widget.TextView? = null
    private var musicToggle: android.widget.ImageButton? = null
    private val musicListener: () -> Unit = { updateMusicBar() }

    private fun setupMusicBar() {
        val b = binding
        val parent = b.pinnedBar.parent as? android.view.ViewGroup ?: return
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val bar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurface))
            setPadding((12 * d).toInt(), 0, 0, 0)
            visibility = View.GONE
        }
        val accent = ContextCompat.getColor(ctx, R.color.colorPrimary)
        fun btn(icon: Int, click: () -> Unit) = android.widget.ImageButton(ctx).apply {
            setImageResource(icon)
            setColorFilter(accent)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { click() }
        }
        val toggle = btn(R.drawable.ic_pause) { com.stoyko.smessenger.media.MusicPlayer.toggle() }
        val title = android.widget.TextView(ctx).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
            setPadding((6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
        }
        bar.addView(toggle, android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
        bar.addView(title, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(
            btn(R.drawable.ic_skip_next) { com.stoyko.smessenger.media.MusicPlayer.next() }.apply { contentDescription = getString(R.string.music_next) },
            android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()),
        )
        bar.addView(
            btn(R.drawable.ic_close) { com.stoyko.smessenger.media.MusicPlayer.stop() },
            android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()),
        )
        // Нажатие на название - большой плеер.
        title.setOnClickListener {
            com.stoyko.smessenger.media.MusicPlayer.current?.let { (uri, name) -> AudioPlayerDialog(ctx, uri, name).show() }
        }
        parent.addView(bar, parent.indexOfChild(b.pinnedBar) + 1)
        musicBar = bar
        musicTitle = title
        musicToggle = toggle
        com.stoyko.smessenger.media.MusicPlayer.addListener(musicListener)
        updateMusicBar()
    }

    private fun updateMusicBar() {
        val bar = musicBar ?: return
        val cur = com.stoyko.smessenger.media.MusicPlayer.current
        if (cur == null) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        musicTitle?.text = "🎵 " + cur.second
        musicToggle?.setImageResource(if (com.stoyko.smessenger.media.MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private var searchMatches: List<Int> = emptyList()
    private var searchIndex = -1

    private fun openSearch() {
        val b = if (view == null) return else binding
        b.searchBar.visibility = View.VISIBLE
        b.searchInput.requestFocus()
        androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, b.searchInput)
            .show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        val b = if (view == null) return else binding
        b.searchInput.text.clear()
        b.searchBar.visibility = View.GONE
        (b.messages.adapter as? ChatAdapter)?.let {
            it.highlight = null
            it.notifyDataSetChanged()
        }
        androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, b.searchInput)
            .hide(WindowInsetsCompat.Type.ime())
    }

    private fun runSearch(query: String) {
        val b = if (view == null) return else binding
        val adapter = b.messages.adapter as? ChatAdapter ?: return
        val q = query.trim()
        adapter.highlight = q.ifEmpty { null }
        searchMatches = if (q.isEmpty()) {
            emptyList()
        } else {
            adapter.messages.indices.filter {
                com.stoyko.smessenger.chat.Markup.body(adapter.messages[it].message).contains(q, ignoreCase = true)
            }
        }
        adapter.notifyDataSetChanged()
        searchIndex = searchMatches.lastIndex
        showSearchPosition()
    }

    private fun showSearchPosition() {
        val b = if (view == null) return else binding
        if (searchMatches.isEmpty()) {
            b.searchCount.text = if (b.searchInput.text.isNullOrBlank()) "" else getString(R.string.search_none)
            return
        }
        b.searchCount.text = "${searchIndex + 1}/${searchMatches.size}"
        scrollToIndex(searchMatches[searchIndex])
    }

    private fun updateActions() = binding.run {
        val wasSend = send.visibility == View.VISIBLE
        val nowSend = outgoingMessage.text.isNotEmpty()
        send.visibility = if (nowSend) View.VISIBLE else View.GONE
        // «Скрепка» видна всегда: можно набрать подпись и прикрепить фото или видео.
        attach.visibility = View.VISIBLE
        mic.visibility = if (send.isVisible) View.GONE else View.VISIBLE
        // Микрофон ↔ «Отправить»: кнопка появляется с поворотом и пружиной.
        if (wasSend != nowSend && com.stoyko.smessenger.ui.Kinetics.enabled) {
            val shown = if (nowSend) send else mic
            shown.animate().cancel()
            shown.scaleX = 0.3f
            shown.scaleY = 0.3f
            shown.rotation = if (nowSend) -90f else 90f
            shown.alpha = 0f
            shown.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f).setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        // Собеседник не в сети - голосовые и файлы всё равно можно отправить: они уйдут при подключении.
        mic.isEnabled = true
        mic.alpha = 1f
        attach.isEnabled = true
        attach.setColorFilter(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
    }

    /** Свой файл (голосовое, «кружок») - отправить. */
    private fun sendOwnFile(file: File) {
        if (!isAdded) return
        val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        viewModel.setActiveChat(PublicKey(contactPubKey))
        stickToBottom()
        sendFileReplying(uri)
    }

    /**
     * Ответ не только текстом: если открыта плашка «Ответ», цитата добавляется к подписи файла
     * (фото, видео, голосовое, «кружок», стикер, документ) и плашка закрывается.
     */
    private fun withReply(caption: String): String {
        val quoted = replyTo ?: return caption
        if (editing != null) return caption
        cancelReply()
        return com.stoyko.smessenger.chat.Markup.reply(quoted, caption)
    }

    private fun sendFileReplying(uri: android.net.Uri) {
        val caption = withReply("")
        if (caption.isNotEmpty()) viewModel.createFtWithCaption(uri, caption) else viewModel.createFt(uri)
    }

    /** Расшифровка голосового: окно с ходом работы, затем текст (он же - под голосовым в чате). */
    private fun transcribe(ft: com.stoyko.smessenger.core.vo.FileTransfer) {
        val ctx = requireContext()
        val run = { file: File? ->
            com.stoyko.smessenger.chat.VoiceTextUi.run(
                ctx, file, ft.fileName, ft.fileSize,
                requestMic = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                alive = { view != null },
            )
        }
        val path = android.net.Uri.parse(ft.destination).path
        val local = path?.let { File(it) }?.takeIf { it.exists() }
        if (local != null || com.stoyko.smessenger.chat.VoiceTranscriber.get(ctx, ft.fileName, ft.fileSize) != null) {
            run(local)
            return
        }
        // Своё голосовое из content:// - копия во временный файл (в фоне, у каждого голосового своя).
        val app = ctx.applicationContext
        Thread({
            val tmp = runCatching {
                val f = File(app.cacheDir, "voice_tx_${ft.id}.m4a")
                app.contentResolver.openInputStream(android.net.Uri.parse(ft.destination))!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                f
            }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (view != null) run(tmp) }
        }, "voice-copy").start()
    }

    /** Цитата для ответа: для файлов - понятная подпись вместо имени файла. */
    private fun quoteFor(m: Message): String {
        if (m.type != MessageType.FileTransfer) return m.message
        val name = m.message
        return when {
            VoicePlayer.isVoice(name) -> "🎤 " + getString(R.string.voice_message)
            com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(name) -> "⭕ " + getString(R.string.video_message)
            isImageName(name) -> "🖼 " + getString(R.string.photo)
            else -> "📎 $name"
        }
    }

    private var badgeSig = ""

    /** Аватарки для значков у реакций: собеседник (фото или буква) и я. */
    private fun updateReactionBadges(c: com.stoyko.smessenger.core.vo.Contact) {
        val sig = c.avatarUri + "|" + c.name
        if (sig == badgeSig) return
        badgeSig = sig
        val ctx = context?.applicationContext ?: return
        val px = (32 * resources.displayMetrics.density).toInt()
        val youName = getString(R.string.durak_you)
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            fun decode(path: String?): android.graphics.Bitmap? = path?.let { p ->
                runCatching {
                    val f = java.io.File(p)
                    if (!f.isFile) return@runCatching null
                    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeFile(f.path, o)?.let { android.graphics.Bitmap.createScaledBitmap(it, px, px, true) }
                }.getOrNull()
            }
            val peer = decode(c.avatarUri.takeIf { it.startsWith("file:") }?.let { android.net.Uri.parse(it).path })
                ?: com.stoyko.smessenger.ui.AvatarFactory.create(ctx.resources, c.name, c.publicKey, com.stoyko.smessenger.ui.Px(px))
            val mine = decode(com.stoyko.smessenger.profile.SelfAvatar.currentFile(ctx)?.path)
                ?: com.stoyko.smessenger.ui.AvatarFactory.create(ctx.resources, youName, "self", com.stoyko.smessenger.ui.Px(px))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val a = (if (view != null) binding.messages.adapter as? ChatAdapter else null) ?: run {
                    badgeSig = ""
                    return@withContext
                }
                a.peerBadge = peer
                a.myBadge = mine
                if (view != null) a.notifyDataSetChanged()
            }
        }
    }

    private fun retryFt(ft: FileTransfer) {
        viewModel.retryFt(ft)
        Toast.makeText(requireContext(), if (ft.outgoing) R.string.ft_retry_sending else R.string.ft_retry_asked, Toast.LENGTH_SHORT).show()
    }

    private fun onFtAction(action: FtAction, ft: FileTransfer) {
        when (action) {
            FtAction.PlayVoice -> if (ft.outgoing || ft.isComplete()) {
                VoicePlayer.toggle(requireContext(), ft.voiceKey(), playableUri(ft), contactName)
            }
            FtAction.PlayVideoNote -> if (ft.outgoing || ft.isComplete()) {
                VoicePlayer.toggle(requireContext(), ft.voiceKey(), playableUri(ft), contactName, video = true)
            }
            FtAction.Cancel -> {
                if (VoicePlayer.currentKey == ft.voiceKey()) VoicePlayer.stop()
                viewModel.rejectFt(ft.id)
            }
            FtAction.Transcribe -> if (ft.outgoing || ft.isComplete()) transcribe(ft)
            FtAction.Accept -> viewModel.acceptFt(ft.id)
            FtAction.Retry -> retryFt(ft)
            FtAction.Reject -> viewModel.rejectFt(ft.id)
        }
    }

    /** До какого нашего сообщения собеседник дочитал (✓✓). */
    private fun updateReadIndex(adapter: ChatAdapter) {
        val key = viewModel.readKey()
        adapter.readIndex = if (key == null) {
            -1
        } else {
            adapter.messages.indexOfLast {
                it.sender == com.stoyko.smessenger.core.vo.Sender.Sent && com.stoyko.smessenger.chat.Markup.key(it.message) == key
            }
        }
    }

    /** Чат открыт на экране - сообщаем собеседнику, что его сообщения прочитаны. */
    /** Выбор до 10 собеседников. */
    /**
     * Пересылка: сетка получателей (Избранное, контакты, группы и каналы),
     * поиск, комментарий и «Скрыть автора».
     */
    private fun forwardItems(items: List<Pair<Message, com.stoyko.smessenger.core.vo.FileTransfer?>>) {
        if (items.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val vctx = requireContext()
            val contacts = viewModel.contactsSnapshot().filter { com.stoyko.smessenger.chat.HiddenChats.visible(vctx, it.publicKey) }.sortedByDescending { it.lastMessage }
            val groups = runCatching { viewModel.groupsSnapshot() }.getOrDefault(emptyList()).filter { it.canPost() }
            if (view == null) return@launch
            val ctx = requireContext()
            val avatarPx = (56 * resources.displayMetrics.density).toInt()
            val targets = ArrayList<ForwardSheet.Target>()
            targets += ForwardSheet.Target(SAVED_TARGET, getString(R.string.saved_messages), ForwardSheet.Target.Kind.SAVED)
            contacts.forEach { c ->
                targets += ForwardSheet.Target(
                    c.publicKey,
                    c.name.ifEmpty { getString(R.string.contact_default_name) },
                    ForwardSheet.Target.Kind.CONTACT,
                )
            }
            groups.forEach { g ->
                targets += ForwardSheet.Target(
                    "g:" + g.chatId,
                    g.name.ifEmpty { getString(R.string.group_default_name) },
                    if (g.isChannel) ForwardSheet.Target.Kind.CHANNEL else ForwardSheet.Target.Kind.GROUP,
                    com.stoyko.smessenger.groups.GroupProfile.roundBitmap(ctx, g.chatId, avatarPx),
                )
            }
            ForwardSheet.show(ctx, targets) { result ->
                val prefix = if (result.hideAuthor) "" else getString(R.string.forwarded_prefix)
                val ids = result.targets.map { it.id }
                // Файлы проверяются и готовятся в фоне: уходят под настоящим именем, а пустой,
                // недокачанный или удалённый файл не уходит - вместо «пустышки» будет понятная ошибка.
                viewModel.forwardAll(
                    items,
                    toSaved = SAVED_TARGET in ids,
                    contacts = ids.filter { it != SAVED_TARGET && !it.startsWith("g:") },
                    groups = ids.filter { it.startsWith("g:") }.map { it.removePrefix("g:") },
                    prefix = prefix,
                    comment = result.comment,
                )
                Toast.makeText(ctx, getString(R.string.forwarded_count, ids.size), Toast.LENGTH_SHORT).show()
                clearSelection()
            }
        }
    }

    private val selected = LinkedHashSet<Long>()
    private var selectionBar: android.widget.LinearLayout? = null
    private var selectionCount: android.widget.TextView? = null

    private fun chatAdapter() = if (view == null) null else binding.messages.adapter as? ChatAdapter

    private fun toggleSelected(m: Message) {
        if (!selected.add(m.id)) selected.remove(m.id)
        if (selected.size > 100) {
            selected.remove(m.id)
            Toast.makeText(requireContext(), R.string.select_max, Toast.LENGTH_SHORT).show()
        }
        val a = chatAdapter() ?: return
        a.selectedIds = selected.toSet()
        a.notifyDataSetChanged()
        if (selected.isEmpty()) hideSelectionBar() else showSelectionBar()
    }

    private fun clearSelection() {
        selected.clear()
        chatAdapter()?.let {
            it.selectedIds = emptySet()
            it.notifyDataSetChanged()
        }
        hideSelectionBar()
    }

    private fun selectedItems(): List<Pair<Message, FileTransfer?>> {
        val a = chatAdapter() ?: return emptyList()
        return a.messages.filter { it.id in selected }.map { m ->
            m to if (m.type == MessageType.FileTransfer) a.fileTransfers.firstOrNull { it.id == m.correlationId } else null
        }
    }

    private fun showSelectionBar() {
        val b = if (view == null) return else binding
        val d = resources.displayMetrics.density
        val bar = selectionBar ?: android.widget.LinearLayout(requireContext()).also { bar ->
            bar.orientation = android.widget.LinearLayout.HORIZONTAL
            bar.gravity = android.view.Gravity.CENTER_VERTICAL
            bar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
            bar.elevation = 20 * d
            bar.isClickable = true
            fun btn(label: String, click: () -> Unit) = android.widget.TextView(requireContext()).apply {
                text = label
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
                setOnClickListener {
                    it.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                    click()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            bar.addView(btn("✕") { clearSelection() }, lp)
            val count = android.widget.TextView(requireContext()).apply {
                textSize = 17f
                setTextColor(android.graphics.Color.WHITE)
                paint.isFakeBoldText = true
            }
            selectionCount = count
            bar.addView(count, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(btn("📋") { copySelected() }, lp)
            bar.addView(btn("🗑") { deleteSelected() }, lp)
            bar.addView(btn("➦") { forwardSelected() }, lp)
            (b.root as android.view.ViewGroup).addView(
                bar,
                android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, b.toolbar.height.coerceAtLeast((56 * d).toInt())),
            )
            selectionBar = bar
            bar.y = b.toolbar.y
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(150).start()
        }
        bar.visibility = View.VISIBLE
        selectionCount?.text = getString(R.string.selected_count, selected.size)
    }

    private fun hideSelectionBar() {
        val bar = selectionBar ?: return
        selectionBar = null
        selectionCount = null
        bar.animate().alpha(0f).setDuration(120).withEndAction { (bar.parent as? android.view.ViewGroup)?.removeView(bar) }.start()
    }

    private fun copySelected() {
        if (guardBlocks()) return
        val text = selectedItems().filter { it.second == null }.joinToString("\n") { com.stoyko.smessenger.chat.Markup.body(it.first.message) }
        if (text.isNotBlank()) {
            requireContext().getSystemService<android.content.ClipboardManager>()
                ?.setPrimaryClip(android.content.ClipData.newPlainText("messages", text))
            Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
        }
        clearSelection()
    }

    private fun deleteSelected() {
        val items = selectedItems()
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.delete_selected, items.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                items.forEach { viewModel.delete(it.first) }
                clearSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun forwardSelected() {
        if (guardBlocks()) return
        forwardItems(selectedItems())
    }

    // ---------- Запрет скриншотов и пересылки ----------

    private var guardJob: kotlinx.coroutines.Job? = null

    /** Флаг окна, значок 🛡 в шапке и пункт меню - по текущему состоянию защиты. */
    private fun applyGuard() {
        val b = if (view == null) return else binding
        val on = com.stoyko.smessenger.chat.ScreenGuard.isOn(requireContext(), contactPubKey)
        com.stoyko.smessenger.chat.ScreenGuard.setActive(activity, if (on) contactPubKey else null)
        b.toolbar.menu.findItem(GUARD_ID)?.isChecked = on
        updateSubtitle()
    }

    private fun toggleGuard() {
        val ctx = requireContext()
        val on = !com.stoyko.smessenger.chat.ScreenGuard.isOn(ctx, contactPubKey)
        AlertDialog.Builder(ctx)
            .setTitle(if (on) R.string.chatfx_guard_on_title else R.string.chatfx_guard_off_title)
            .setMessage(if (on) R.string.chatfx_guard_on_text else R.string.chatfx_guard_off_text)
            .setPositiveButton(if (on) R.string.chatfx_guard_enable else R.string.chatfx_guard_disable) { _, _ ->
                viewModel.setScreenGuard(on)
                Toast.makeText(ctx, if (on) R.string.chatfx_guard_you_on else R.string.chatfx_guard_you_off, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Защищённый чат: переслать/скопировать нельзя (подсказка). */
    private fun guardBlocks(): Boolean {
        if (!com.stoyko.smessenger.chat.ScreenGuard.isOn(requireContext(), contactPubKey)) return false
        Toast.makeText(requireContext(), R.string.chatfx_guard_blocked, Toast.LENGTH_SHORT).show()
        return true
    }

    /** Быстрые реакции над сообщением + «⋯» (полное меню: ответить, переслать, изменить...). */
    private fun showQuickReactions(anchor: View, message: Message) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val mine = viewModel.myReaction(message)
        val bar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(ContextCompat.getColor(ctx, R.color.colorSurface))
            }
            elevation = 12 * d
        }
        val popup = android.widget.PopupWindow(bar, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 12 * d
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        val items = com.stoyko.smessenger.chat.ReactionPicker.quick(ctx) +
            (if (com.stoyko.smessenger.chat.ChatExtras.split(mine).isNotEmpty()) listOf("✖") else emptyList()) + listOf("➕", "⋯")
        items.forEachIndexed { i, e ->
            val tv = android.widget.TextView(ctx).apply {
                text = e
                textSize = if (e == "⋯" || e == "➕" || e == "✖") 20f else 24f
                if (e == "✖") setTextColor(0xFFE5484D.toInt())
                if (e == "➕") com.stoyko.smessenger.chat.ReactionPicker.stylePlus(this)
                gravity = android.view.Gravity.CENTER
                setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
                if (com.stoyko.smessenger.chat.ChatExtras.has(mine, e)) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x335B4BDB)
                    }
                }
                // Появление «волной».
                scaleX = 0f
                scaleY = 0f
                animate().scaleX(1f).scaleY(1f).setStartDelay(i * 25L).setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                setOnClickListener {
                    popup.dismiss()
                    when (e) {
                        "⋯" -> binding.messages.showContextMenuForChild(anchor)
                        "➕" -> com.stoyko.smessenger.chat.showReactionPicker(ctx, mine) { viewModel.react(message, it) }
                        "✖" -> viewModel.react(message, null)
                        else -> viewModel.react(message, e)
                    }
                }
            }
            bar.addView(tv)
        }
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // Полоса шире экрана - листается пальцем.
        val barW = minOf(bar.measuredWidth, resources.displayMetrics.widthPixels - (16 * d).toInt())
        if (barW < bar.measuredWidth) {
            bar.elevation = 0f
            val holder = android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                background = bar.background
                bar.background = null
                elevation = 12 * d
                addView(bar)
            }
            popup.contentView = holder
            popup.width = barW
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = ((resources.displayMetrics.widthPixels - barW) / 2).coerceAtLeast(0)
        val y = (loc[1] - bar.measuredHeight - (6 * d).toInt()).coerceAtLeast((40 * d).toInt())
        popup.animationStyle = android.R.style.Animation_Dialog
        popup.showAtLocation(anchor, android.view.Gravity.TOP or android.view.Gravity.START, x, y)
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Свои обои этого чата (картинка или видео). */
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChatWallpaper.set(app, contactPubKey, uri) }
            if (view == null) return@launch
            if (ok) applyWallpaper() else Toast.makeText(requireContext(), R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyWallpaper() {
        val b = if (view == null) return else binding
        ChatWallpaper.apply(requireContext(), contactPubKey, b.chatWallpaper, b.chatWallpaperVideo)
        // Сезонное оформление: листья или снег на фоне чата (под сообщениями) и в шапке.
        if (com.stoyko.smessenger.ui.Seasonal.current(requireContext()) != com.stoyko.smessenger.ui.Seasonal.Kind.NONE) {
            if (b.chatWallpaper.visibility != View.VISIBLE) {
                b.chatWallpaper.setImageDrawable(null)
                b.chatWallpaper.visibility = View.VISIBLE
            }
            // Шапка и фон чата - одно поле частиц: листья/снег летят из шапки в чат без обрыва.
            com.stoyko.smessenger.ui.Seasonal.decorateForeground(b.chatWallpaper, shared = true, scene = true)
            com.stoyko.smessenger.ui.Seasonal.decorateForeground(b.appBarLayout, shared = true)
        } else {
            com.stoyko.smessenger.ui.Seasonal.decorateForeground(b.chatWallpaper)
            com.stoyko.smessenger.ui.Seasonal.decorateForeground(b.appBarLayout)
        }
    }

    // ---------- Дата при прокрутке: маленькая «таблетка» по центру сверху ----------

    private var datePill: android.widget.TextView? = null
    private var scrolledByUser = false
    private val dayFmt = SimpleDateFormat("d MMMM", Locale.getDefault())
    private val dayYearFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    private val hidePill = Runnable {
        datePill?.animate()?.alpha(0f)?.translationY(-8 * resources.displayMetrics.density)?.setDuration(220)?.start()
    }

    private fun showDatePill(first: Int) {
        val b = if (view == null) return else binding
        val a = b.messages.adapter as? ChatAdapter ?: return
        val m = a.messages.getOrNull(first) ?: return
        if (m.timestamp <= 0) return
        val d = resources.displayMetrics.density
        val pill = datePill ?: android.widget.TextView(requireContext()).also { p ->
            p.textSize = 11f
            p.setTextColor(android.graphics.Color.WHITE)
            p.setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            p.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0x991E1B3A.toInt())
            }
            p.alpha = 0f
            (b.root as android.view.ViewGroup).addView(
                p,
                android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            datePill = p
        }
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = m.timestamp }
        val text = when {
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) -> getString(R.string.today)
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> dayFmt.format(java.util.Date(m.timestamp))
            else -> dayYearFmt.format(java.util.Date(m.timestamp))
        }
        if (pill.text != text) pill.text = text
        pill.post {
            pill.x = (b.root.width - pill.width) / 2f
            pill.y = b.toolbar.y + b.toolbar.height + 8 * d + (b.toolbar.parent as? View)?.y.let { it ?: 0f }
        }
        b.root.removeCallbacks(hidePill)
        if (pill.alpha < 1f) pill.animate().alpha(1f).translationY(0f).setDuration(150).start()
        b.root.postDelayed(hidePill, 1400)
    }

    private fun hideDatePill() {
        if (view == null) return
        binding.root.removeCallbacks(hidePill)
        binding.root.postDelayed(hidePill, 900)
        scrolledByUser = false
    }

    private var lastAvatarKey = ""
    private var restoredScroll = false

    // ---------- «Потяните вверх - следующий непрочитанный чат» ----------

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPullToNextChat() {
        val b = binding
        val d = resources.displayMetrics.density
        val threshold = 110 * d
        var startY = -1f
        var pulling = false
        var armed = false
        var hint: android.widget.TextView? = null
        fun hideHint() {
            val h = hint ?: return
            hint = null
            h.animate().alpha(0f).translationY(40 * d).setDuration(160).withEndAction { (h.parent as? android.view.ViewGroup)?.removeView(h) }.start()
        }
        b.messages.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    pulling = false
                    armed = false
                    scrolledByUser = true
                    refreshOtherUnread()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - e.rawY
                    val atBottom = !b.messages.canScrollVertically(1)
                    // Подсказка - только если есть другие непрочитанные чаты (иначе она мешала).
                    if (!pulling && atBottom && dy > 24 * d && selected.isEmpty() && otherUnread) {
                        pulling = true
                        hint = android.widget.TextView(requireContext()).apply {
                            setText(R.string.pull_next_chat)
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 14f
                            gravity = android.view.Gravity.CENTER
                            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = 22 * d
                                setColor(0xE65B4BDB.toInt())
                            }
                            alpha = 0f
                            translationY = 40 * d
                        }.also { h ->
                            (b.root as android.view.ViewGroup).addView(h, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                            h.post {
                                h.x = (b.root.width - h.width) / 2f
                                h.y = b.bottomPanel.y - h.height - 12 * d
                            }
                            h.animate().alpha(1f).translationY(0f).setDuration(200).start()
                        }
                    }
                    if (pulling) {
                        val progress = (dy / threshold).coerceIn(0f, 1.2f)
                        hint?.scaleX = 0.9f + 0.1f * progress.coerceAtMost(1f)
                        hint?.scaleY = 0.9f + 0.1f * progress.coerceAtMost(1f)
                        if (!armed && progress >= 1f) {
                            armed = true
                            b.messages.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            hint?.setText(R.string.pull_next_chat_release)
                        } else if (armed && progress < 1f) {
                            armed = false
                            hint?.setText(R.string.pull_next_chat)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling && armed) openNextUnreadChat()
                    pulling = false
                    armed = false
                    hideHint()
                }
            }
            false
        }
    }

    private var otherUnread = false

    private fun refreshOtherUnread() {
        viewLifecycleOwner.lifecycleScope.launch {
            otherUnread = runCatching {
                val c = requireContext()
                viewModel.contactsSnapshot().any { it.hasUnreadMessages && it.publicKey != contactPubKey && !com.stoyko.smessenger.chat.HiddenChats.isHidden(c, it.publicKey) }
            }.getOrDefault(false)
        }
    }

    private fun openNextUnreadChat() {
        viewLifecycleOwner.lifecycleScope.launch {
            val next = viewModel.contactsSnapshot()
                // Скрытый чат «следующим непрочитанным» не открывается (раньше - открывался без PIN).
                .filter { it.hasUnreadMessages && it.publicKey != contactPubKey && !com.stoyko.smessenger.chat.HiddenChats.isHidden(requireContext(), it.publicKey) }
                .maxByOrNull { it.lastMessage }
            if (view == null) return@launch
            // Непрочитанных больше нет - просто ничего не делаем (без лишнего уведомления).
            if (next == null) return@launch
            binding.messages.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            findNavController().navigate(
                R.id.chatFragment,
                bundleOf(CONTACT_PUBLIC_KEY to next.publicKey),
                androidx.navigation.navOptions {
                    popUpTo(R.id.chatFragment) { inclusive = true }
                    anim {
                        enter = R.anim.slide_in_bottom
                        exit = R.anim.fade_out
                    }
                },
            )
        }
    }

    private var unseenBelow = 0
    private var lastMessageCount = 0
    private var scrollThumb: ChatScrollThumb? = null

    /** «Проверка шифрования»: ключи, канал, живой тест туда-обратно, код безопасности. */
    private fun openE2eCheck() {
        if (!viewModel.tox.started) {
            Toast.makeText(requireContext(), R.string.e2e_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val c = viewModel.contact.value
        com.stoyko.smessenger.chat.E2eCheck.show(
            requireContext(),
            viewLifecycleOwner.lifecycleScope,
            viewModel.tox,
            contactPubKey,
            contactName.ifBlank { getString(R.string.contact_default_name) },
            c?.connectionStatus ?: com.stoyko.smessenger.core.vo.ConnectionStatus.None,
        )
    }
    private var stickToBottomUntil = 0L
    private var lastProgressRefresh = 0L
    private var scrollDownShown = false

    private fun stickToBottom() {
        stickToBottomUntil = android.os.SystemClock.uptimeMillis() + 2500
    }

    /** Вниз чата: далеко - сначала прыжок поближе, потом плавно, и точно до конца. */
    private fun scrollToBottom(smooth: Boolean) {
        val b = if (view == null) return else binding
        val list = b.messages
        val last = (list.adapter?.count ?: 0) - 1
        if (last < 0) return
        if (!smooth) {
            list.setSelection(last)
            return
        }
        if (last - list.lastVisiblePosition > 15) list.setSelection(last - 8)
        list.post {
            if (view == null) return@post
            list.smoothScrollToPositionFromTop(last, 0, 220)
            // Плавная прокрутка в ListView иногда не доходит до конца (разная высота строк).
            list.postDelayed({ if (view != null && list.lastVisiblePosition < last) list.setSelection(last) }, 320)
        }
    }

    /** Обновить уже показанные строки без пересборки списка (нет «прыжков»). */
    private val refreshVisibleRows = Runnable {
        val b = if (view == null) return@Runnable else binding
        lastProgressRefresh = android.os.SystemClock.uptimeMillis()
        // Обновляем только строки с файлами (проценты), остальные строки - и анимированные
        // эмодзи у поля ввода - не трогаем, чтобы ничего не дёргалось.
        (b.messages.adapter as? ChatAdapter)?.refreshTransfers()
    }

    private fun onlyProgressChanged(
        old: List<FileTransfer>?,
        new: List<FileTransfer>,
    ): Boolean {
        if (old == null || old.size != new.size) return false
        for (i in new.indices) {
            val a = old[i]
            val b = new[i]
            if (a.id != b.id || a.destination != b.destination || a.fileName != b.fileName) return false
            // Завершение/начало передачи меняет вид строки - нужна полная перерисовка.
            if (a.isComplete() != b.isComplete() || a.isStarted() != b.isStarted() || a.isRejected() != b.isRejected()) return false
        }
        return true
    }

    /** Перерисовать список, сохранив место, где читает человек (если он не в самом низу). */
    private fun keepScrollPosition(block: () -> Unit) {
        val b = if (view == null) return block() else binding
        val list = b.messages
        val count = list.adapter?.count ?: 0
        val atBottom = count == 0 || list.lastVisiblePosition >= count - 1
        if (atBottom) return block()
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        block()
        list.setSelectionFromTop(first, top)
    }

    private fun showScrollDown(show: Boolean) {
        val b = if (view == null) return else binding
        val badge = b.scrollDownBadge
        badge.text = if (unseenBelow > 99) "99+" else unseenBelow.toString()
        badge.visibility = if (unseenBelow > 0) View.VISIBLE else View.GONE
        val btn = b.scrollDown
        // Состояние храним отдельно: раньше кнопка проверяла прозрачность во время анимации
        // и то «залипала», то мигала при каждом сдвиге списка.
        if (show == scrollDownShown) return
        scrollDownShown = show
        btn.animate().cancel()
        if (show) {
            btn.visibility = View.VISIBLE
            btn.scaleX = 0.5f
            btn.scaleY = 0.5f
            btn.alpha = 0f
            btn.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
        } else {
            btn.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(120).withEndAction {
                if (view != null && !scrollDownShown) btn.visibility = View.GONE
            }.start()
        }
    }

    private fun markReadIfVisible() {
        if (!isResumed || view == null) return
        val adapter = binding.messages.adapter as? ChatAdapter ?: return
        val last = adapter.messages.lastOrNull { it.sender == com.stoyko.smessenger.core.vo.Sender.Received } ?: return
        viewModel.markRead(last)
    }

    private fun updateScheduledBar() {
        val b = if (view == null) return else binding
        val n = viewModel.scheduledCount()
        b.scheduledBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        b.scheduledBar.text = getString(R.string.scheduled_count, n)
    }

    private fun setupGlass() = binding.run {
        val surface = ContextCompat.getColor(requireContext(), R.color.colorSurface)
        topGlass.blurSource = messages
        topGlass.tintColor = 0xB35B4BDB.toInt()
        topGlass.fallbackColor = 0xF25B4BDB.toInt()
        topGlass.edgeTop = false
        topGlass.edgeBottom = true
        bottomPanel.blurSource = messages
        bottomPanel.tintColor = (surface and 0x00FFFFFF) or 0xC0000000.toInt()
        bottomPanel.fallbackColor = (surface and 0x00FFFFFF) or 0xF5000000.toInt()
        // Список сообщений не заезжает под панели при прокрутке до конца.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val top = appBarLayout.height
            val bottom = bottomPanel.height
            val oldBottom = messages.paddingBottom
            if (messages.paddingTop != top || messages.paddingBottom != bottom) {
                // Были ли мы у последнего сообщения - до смены отступа.
                val count = messages.count
                val atEnd = count == 0 || messages.lastVisiblePosition >= count - 2
                messages.updatePadding(top = top, bottom = bottom)
                // Клавиатура открылась: переписка поднимается вместе с полем ввода.
                // Клавиатура закрылась: остаёмся на том же месте (у последнего сообщения), без прыжков вверх.
                val delta = bottom - oldBottom
                if (delta != 0 && oldBottom > 0) {
                    messages.post {
                        if (view != null) {
                            val last = messages.count - 1
                            when {
                                atEnd && last >= 0 -> messages.setSelection(last)
                                delta > 0 -> messages.scrollListBy(delta)
                            }
                        }
                    }
                }
            }
        }
        appBarLayout.addOnLayoutChangeListener(relayout)
        bottomPanel.addOnLayoutChangeListener(relayout)
    }

    private fun navigateToCallScreen(video: Boolean = false) {
        view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
        findNavController().navigate(
            R.id.action_chatFragment_to_callFragment,
            bundleOf(CONTACT_PUBLIC_KEY to contactPubKey, com.stoyko.smessenger.ui.call.ARG_VIDEO to video),
        )
    }
}

private const val SAVED_TARGET = "__saved__"
private const val UNHIDE_ID = 0x5A11
private const val GUARD_ID = 0x5A12
private const val HIDE_ID = 0x5A13

/** Где пользователь читал каждый чат (до закрытия приложения). */
private val scrollMemory = HashMap<String, Pair<Long, Int>>()
