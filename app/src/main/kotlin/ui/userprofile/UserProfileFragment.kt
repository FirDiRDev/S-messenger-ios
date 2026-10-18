// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.userprofile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.stoyko.smessenger.hasPermission
import io.nayuki.qrcodegen.QrCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentUserProfileBinding
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.ui.Dp
import com.stoyko.smessenger.ui.Px
import com.stoyko.smessenger.ui.StatusDialog
import com.stoyko.smessenger.ui.colorFromStatus
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.UserStatus
import com.stoyko.smessenger.domain.tox.ToxID

private const val TOX_MAX_NAME_LENGTH = 128
private const val TOX_MAX_STATUS_MESSAGE_LENGTH = 1007

private const val QR_CODE_TO_SCREEN_RATIO = 0.5f
private val qrCodePadding = Dp(16f)
private val qrCodeSharedImageSize = Px(1024)
private val qrCodeSharedImagePadding = Px(200)

class UserProfileFragment : BaseFragment<FragmentUserProfileBinding>(FragmentUserProfileBinding::inflate) {
    private val vm: UserProfileViewModel by viewModels { vmFactory }
    private lateinit var currentStatus: UserStatus
    private var currentName = ""

    private val pickAvatar = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) applyAvatar(uri) }

    private var pendingCameraFile: File? = null
    private val takeAvatar = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val f = pendingCameraFile
        pendingCameraFile = null
        if (ok && f != null) {
            applyAvatar(FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", f))
        }
    }
    /** Видео профиля (до 10 с): выбор из галереи, длинное - выбор фрагмента. */
    private val pickProfileVideo = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { onProfileVideoPicked(it) } }

    private val pickExtraPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) addExtraPhoto(uri) }

    private fun addExtraPhoto(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.stoyko.smessenger.profile.ProfilePhotos.add(ctx, uri) }
            if (view == null) return@launch
            Toast.makeText(
                requireContext(),
                if (f != null) R.string.photos_added else R.string.photos_add_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (f != null) vm.broadcastCard()
            updatePhotosButton()
        }
    }

    /** Все мои фото: главное (аватар) и дополнительные - на весь экран, свайпом. */
    private fun openGallery(start: Int = 0) {
        val ctx = requireContext()
        val items = ArrayList<Pair<Uri, String>>()
        com.stoyko.smessenger.profile.SelfAvatar.currentFile(ctx)?.let { items += Uri.fromFile(it) to "avatar.jpg" }
        com.stoyko.smessenger.profile.ProfilePhotos.mine(ctx).forEach { items += Uri.fromFile(it) to it.name }
        if (items.isEmpty()) {
            showAvatarOptions()
            return
        }
        com.stoyko.smessenger.ui.chat.MediaViewer.showList(ctx, items, start.coerceIn(0, items.size - 1))
    }

    /** «Мои фото»: превью, «+ Добавить», удержание - сделать главным / удалить. */
    private fun photosSheet() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        root.addView(android.widget.TextView(ctx).apply {
            setText(R.string.photos_title)
            textSize = 19f
            paint.isFakeBoldText = true
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary))
        })
        root.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.photos_hint, com.stoyko.smessenger.profile.ProfilePhotos.MAX + 1)
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary))
            setPadding(0, dp(4), 0, dp(10))
        })
        val grid = android.widget.GridLayout(ctx).apply { columnCount = 3 }
        val size = (resources.displayMetrics.widthPixels - dp(32) - dp(16)) / 3
        fun tile(build: (android.widget.FrameLayout) -> Unit, click: () -> Unit, long: (() -> Unit)? = null) {
            val f = android.widget.FrameLayout(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0x1F5B4BDB)
                }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                setOnClickListener { click() }
                long?.let { l -> setOnLongClickListener { l(); true } }
            }
            build(f)
            com.stoyko.smessenger.ui.Kinetics.squish(f, 0.94f)
            grid.addView(f, android.widget.GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        val avatar = com.stoyko.smessenger.profile.SelfAvatar.currentFile(ctx)
        val extras = com.stoyko.smessenger.profile.ProfilePhotos.mine(ctx)
        var index = 0
        if (avatar != null) {
            tile({ f ->
                f.addView(android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    com.squareup.picasso.Picasso.get().load(avatar).resize(size, size).centerCrop().into(this)
                })
                f.addView(android.widget.TextView(ctx).apply {
                    setText(R.string.photos_main)
                    textSize = 11f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8 * d; setColor(0xAA5B4BDB.toInt()) }
                }, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
            }, { sheet.dismiss(); openGallery(0) }, { sheet.dismiss(); showAvatarOptions() })
            index++
        }
        extras.forEach { file ->
            val at = index
            tile({ f ->
                f.addView(android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    com.squareup.picasso.Picasso.get().load(file).resize(size, size).centerCrop().into(this)
                })
            }, { sheet.dismiss(); openGallery(at) }) {
                AlertDialog.Builder(ctx)
                    .setItems(arrayOf<CharSequence>(getString(R.string.photos_make_main), getString(R.string.delete))) { _, which ->
                        sheet.dismiss()
                        if (which == 0) {
                            applyAvatar(FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", file))
                        } else {
                            com.stoyko.smessenger.profile.ProfilePhotos.remove(ctx, file)
                            vm.broadcastCard()
                            updatePhotosButton()
                        }
                    }
                    .show()
            }
            index++
        }
        if (extras.size < com.stoyko.smessenger.profile.ProfilePhotos.MAX) {
            tile({ f ->
                f.addView(android.widget.TextView(ctx).apply {
                    text = "＋\n" + getString(R.string.photos_add)
                    gravity = android.view.Gravity.CENTER
                    textSize = 14f
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.settingsAccent))
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
            }, {
                sheet.dismiss()
                if (avatar == null) {
                    showAvatarOptions()
                } else {
                    pickExtraPhoto.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                }
            })
        }
        root.addView(grid)
        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sheet.show()
    }

    private var photosButton: View? = null

    private fun updatePhotosButton() {
        val ctx = context ?: return
        val n = com.stoyko.smessenger.profile.ProfilePhotos.mine(ctx).size +
            (if (com.stoyko.smessenger.profile.SelfAvatar.currentFile(ctx) != null) 1 else 0)
        photosButton?.let { com.stoyko.smessenger.ui.Btn.setTileLabel(it, getString(R.string.profile_tile_photos, n)) }
    }

    /**
     * Все действия профиля - кнопками одного вида: большая объёмная «Изменить профиль»
     * и сетка плиток 3×3 (фото, имя, статус, «в сети», эмодзи-статус, о себе, ID, QR, поделиться).
     */
    private fun buildActions() {
        val b = if (view == null) return else binding
        val ctx = requireContext()
        val host = b.needsHacks
        if (host.findViewWithTag<View>("profile_actions") != null) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val Btn = com.stoyko.smessenger.ui.Btn
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            tag = "profile_actions"
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        // «Изменить профиль» - объёмная клавиша с ровными углами: при нажатии «проваливается».
        val edit = Btn.key3d(ctx, getString(R.string.profile_edit_about)) { openEditor() }
        box.addView(edit, android.widget.LinearLayout.LayoutParams(-1, dp(58)))
        val photos = Btn.tile(ctx, "🖼", getString(R.string.profile_tile_photos, 0)) { photosSheet() }
        photosButton = photos
        val tiles = listOf(
            photos,
            Btn.tile(ctx, "👤", getString(R.string.profile_tile_name)) { b.profileOptions.profileChangeNickname.performClick() },
            Btn.tile(ctx, "💬", getString(R.string.profile_tile_status)) { b.profileOptions.profileChangeStatusText.performClick() },
            Btn.tile(ctx, "🟢", getString(R.string.profile_tile_presence)) { b.profileOptions.profileChangeStatus.performClick() },
            Btn.tile(ctx, "😀", getString(R.string.profile_tile_emoji)) {
                pickEmojiStatus { e ->
                    vm.setExtProfile(vm.profileCard.mine.copy(emojiStatus = e))
                    if (view != null) fillExtra(binding.userStatusMessage.text.toString())
                }
            },
            // «О себе» - теперь в «Изменить профиль»; «Скопировать ID» - в меню «+» на главном экране.
            // Вместо них - галерея всех подаренных подарков.
            giftsTile(ctx),
            Btn.tile(ctx, "🔳", getString(R.string.profile_tile_qr)) { createQrCodeDialog().show() },
            Btn.tile(ctx, "📤", getString(R.string.profile_tile_share)) { b.profileShareId.performClick() },
            Btn.tile(ctx, "🎞", getString(R.string.pvideo_tile)) { profileVideoMenu() },
            // Мини-игра: монеты 🪙 на подарки; и магазин - подарок себе за монеты.
            Btn.tile(ctx, "🐍", getString(R.string.snake_title)) {
                com.stoyko.smessenger.games.SnakeGame.show(ctx) { buySelfGift() }
            },
            // Рядом со Змейкой - магазин монет (паки, эксклюзивы, скины), тем же видом плитки.
            Btn.tile(ctx, "🛒", getString(R.string.profile_tile_shop)) { com.stoyko.smessenger.chat.CoinShop.show(ctx) },
            Btn.tile(ctx, "🛍", getString(R.string.gift_shop_self_tile)) { buySelfGift() },
        )
        box.addView(
            Btn.grid(ctx, tiles, cols = 3, height = 76),
            android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        host.addView(box, 0)
        updatePhotosButton()
        Btn.animateIn(listOf<View>(edit) + tiles)
    }

    /** Плитка «Мои подарки»: открывает галерею, которая плавно выходит из самой плитки. */
    private fun giftsTile(ctx: android.content.Context): View {
        lateinit var tile: View
        tile = com.stoyko.smessenger.ui.Btn.tile(ctx, "🎁", getString(R.string.profile_tile_gifts)) { openGiftGallery(tile) }
        return tile
    }

    private fun openGiftGallery(anchor: View) {
        com.stoyko.smessenger.chat.GiftGallery.open(anchor) {
            // Витрину поменяли - профиль обновится через слушатель подарков; друзьям - новая карточка.
            vm.broadcastCard()
        }
    }

    private val cameraPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera() }

    private fun applyAvatar(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.setAvatar(uri)
            if (view == null) return@launch
            Toast.makeText(
                requireContext(),
                if (ok) R.string.profile_photo_updated else R.string.profile_photo_failed,
                Toast.LENGTH_SHORT,
            ).show()
            refreshAvatar()
        }
    }

    private fun launchCamera() {
        val dir = File(requireContext().filesDir, "outgoing/camera").apply { mkdirs() }
        val f = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = f
        try {
            takeAvatar.launch(FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", f))
        } catch (e: Exception) {
            pendingCameraFile = null
            Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- видео профиля ----------------

    private fun profileVideoMenu() {
        val ctx = requireContext()
        if (com.stoyko.smessenger.profile.ProfileVideo.mine(ctx) == null) {
            runCatching { pickProfileVideo.launch("video/*") }
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.pvideo_tile)
            .setItems(arrayOf(getString(R.string.pvideo_change), getString(R.string.pvideo_remove))) { _, which ->
                if (which == 0) {
                    runCatching { pickProfileVideo.launch("video/*") }
                } else {
                    com.stoyko.smessenger.profile.ProfileVideo.remove(ctx)
                    refreshAvatar()
                    vm.broadcastCard()
                }
            }
            .show()
    }

    /** Видео выбрано: короткое - сразу; длинное - выбрать фрагмент 10 с ползунком с живым просмотром. */
    private fun onProfileVideoPicked(uri: android.net.Uri) {
        val ctx = requireContext()
        val dur = com.stoyko.smessenger.profile.ProfileVideo.durationMs(ctx, uri)
        val window = com.stoyko.smessenger.profile.ProfileVideo.MAX_MS
        if (dur <= window + 500) {
            applyProfileVideo(uri, 0L)
            return
        }
        val d = resources.displayMetrics.density
        var start = 0L
        val video = android.widget.VideoView(ctx)
        val label = android.widget.TextView(ctx).apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        fun fmt(ms: Long) = String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60)
        fun updateLabel() {
            label.text = getString(R.string.pvideo_range, fmt(start), fmt(start + window))
        }
        val seek = android.widget.SeekBar(ctx).apply {
            max = ((dur - window) / 100).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    start = p * 100L
                    updateLabel()
                    if (fromUser) runCatching { video.seekTo(start.toInt()) }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    runCatching {
                        video.seekTo(start.toInt())
                        video.start()
                    }
                }
            })
        }
        updateLabel()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p / 2, p, 0)
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.pvideo_pick_hint)
                textSize = 13f
                setPadding(0, 0, 0, (8 * d).toInt())
            })
            addView(video, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (260 * d).toInt()))
            addView(label)
            addView(seek)
        }
        // Просмотр выбранного фрагмента по кругу, без звука.
        val loop = object : Runnable {
            override fun run() {
                if (!video.isAttachedToWindow) return
                runCatching { if (video.currentPosition >= start + window) video.seekTo(start.toInt()) }
                video.postDelayed(this, 200)
            }
        }
        video.setVideoURI(uri)
        video.setOnPreparedListener { mp ->
            mp.setVolume(0f, 0f)
            mp.isLooping = true
            video.start()
            video.post(loop)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.pvideo_pick_title)
            .setView(box)
            .setPositiveButton(R.string.pvideo_done) { _, _ ->
                runCatching { video.stopPlayback() }
                applyProfileVideo(uri, start)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> runCatching { video.stopPlayback() } }
            .show()
    }

    private fun applyProfileVideo(uri: android.net.Uri, startMs: Long) {
        val app = requireContext().applicationContext
        android.widget.Toast.makeText(app, R.string.pvideo_processing, android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val f = com.stoyko.smessenger.profile.ProfileVideo.set(app, uri, startMs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (f == null) {
                    android.widget.Toast.makeText(app, R.string.pvideo_failed, android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(app, R.string.pvideo_ok, android.widget.Toast.LENGTH_SHORT).show()
                    if (view != null) refreshAvatar()
                    vm.broadcastCard()
                }
            }
        }.start()
    }

    /** Купить подарок себе за монеты - он сразу появится в «Моих подарках». */
    private fun buySelfGift() {
        val ctx = requireContext()
        com.stoyko.smessenger.chat.Gifts.pick(ctx, getString(R.string.gift_from_me), self = true) { gift, note ->
            com.stoyko.smessenger.chat.Gifts.giveSelf(ctx, gift, note)
            view?.let { v -> com.stoyko.smessenger.chat.Gifts.celebrate(v, gift) }
            vm.broadcastCard()
        }
    }

    private fun refreshAvatar() {
        val b = if (view == null) return else binding
        val size = (116 * resources.displayMetrics.density).toInt()
        com.stoyko.smessenger.profile.SelfAvatar.show(b.profileAvatar, currentName, vm.publicKey.string(), size)
        // Моё видео профиля - поверх аватарки, живое.
        com.stoyko.smessenger.profile.ProfileVideo.attachOver(b.profileAvatar, com.stoyko.smessenger.profile.ProfileVideo.mine(requireContext()))
    }

    private fun showAvatarOptions() {
        val hasPhoto = vm.selfAvatar.current() != null
        val hasVideo = com.stoyko.smessenger.profile.ProfileVideo.mine(requireContext()) != null
        val items = mutableListOf(getString(R.string.profile_photo_pick), getString(R.string.profile_photo_camera))
        if (hasPhoto) items.add(getString(R.string.profile_photo_remove))
        // Видео-аватарка (до 10 с, как живая аватарка): выбрать / убрать.
        val videoPick = items.size
        items.add("🎞 " + getString(R.string.pvideo_set_avatar))
        val videoRemove = if (hasVideo) items.size.also { items.add(getString(R.string.pvideo_remove)) } else -1
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_photo)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    videoPick -> runCatching { pickProfileVideo.launch("video/*") }
                    videoRemove -> {
                        com.stoyko.smessenger.profile.ProfileVideo.remove(requireContext())
                        refreshAvatar()
                        vm.broadcastCard()
                    }
                    0 -> pickAvatar.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                    1 -> if (requireContext().hasPermission(android.Manifest.permission.CAMERA)) {
                        launchCamera()
                    } else {
                        cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }
                    2 -> {
                        vm.removeAvatar()
                        refreshAvatar()
                    }
                }
            }
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        // Снег / листья в шапке профиля - ЗА аватаркой (раньше гирлянда и снег рисовались
        // поверх фото). Гирлянду на панели профиля не вешаем.
        com.stoyko.smessenger.ui.Seasonal.decorateBackground(appbar)
        com.stoyko.smessenger.ui.SeasonTint.noGarland(appbar)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            profileCollapsingToolbar.updatePadding(left = insets.left, right = insets.right)
            profileToolbar.updatePadding(top = insets.top)
            mainSection.updatePadding(left = insets.left, right = insets.right)
            compat
        }

        profileToolbar.apply {
            setNavigationOnClickListener {
                activity?.onBackPressed()
            }
        }

        vm.user.observe(viewLifecycleOwner) { user ->
            currentStatus = user.status

            userName.text = user.name
            if (currentName != user.name) {
                currentName = user.name
                refreshAvatar()
            }
            userStatusMessage.text = user.statusMessage
            userStatus.setColorFilter(colorFromStatus(requireContext(), user.status))
            fillExtra(user.statusMessage)
        }

        // Полный ID (76 символов): выделяется целиком, есть кнопка «Скопировать».
        userToxId.text = vm.toxId.string()
        userToxIdCopy.setOnClickListener { copyToxId() }
        userToxId.setOnClickListener { copyToxId() }
        // «Изменить профиль»: всё в одном окне (имя, о себе, ник, статус…).
        profileOptions.profileEditAbout.setOnClickListener { openEditor() }
        // Фото, имя, статус и статус сети - теперь всё в одной кнопке «Изменить профиль».
        profileOptions.profileChangeNickname.visibility = View.GONE
        profileOptions.profileChangeStatusText.visibility = View.GONE
        profileOptions.profileChangeAvatar.visibility = View.GONE
        profileOptions.profileChangeStatus.visibility = View.GONE
        listOf(profileOptions.profileEditAbout, profileOptions.profileChangeAvatar, profileOptions.profileChangeStatus)
            .forEach { com.stoyko.smessenger.ui.Kinetics.squish(it, 0.96f) }

        refreshAvatar()
        // Из Настроек «Изменить профиль» - сразу открываем редактор (один раз).
        if (arguments?.getBoolean("edit") == true) {
            arguments?.putBoolean("edit", false)
            view.postDelayed({ if (this@UserProfileFragment.view != null) openEditor() }, 350)
        }
        // Аватар «выпрыгивает» при открытии и уменьшается при прокрутке.
        profileAvatarBox.scaleX = 0.6f
        profileAvatarBox.scaleY = 0.6f
        profileAvatarBox.alpha = 0f
        profileAvatarBox.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        appbar.addOnOffsetChangedListener(
            com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener { bar, offset ->
                val f = (-offset.toFloat() / bar.totalScrollRange.coerceAtLeast(1)).coerceIn(0f, 1f)
                val sc = 1f - 0.4f * f
                profileAvatarBox.scaleX = sc
                profileAvatarBox.scaleY = sc
                profileAvatarBox.alpha = 1f - f * 1.2f
            },
        )
        // Нажатие на фото - все фото на весь экран; удержание - «Мои фото».
        profileAvatarBox.setOnClickListener { openGallery(0) }
        profileAvatarBox.setOnLongClickListener {
            photosSheet()
            true
        }
        buildActions()
        // Подарок приняли в чате - раздел «Подарки» обновляется сам.
        com.stoyko.smessenger.chat.Gifts.listen(giftsListener)
        com.stoyko.smessenger.chat.Coins.listen(coinsListener)
        // Все действия - кнопками вверху: старые пункты списка больше не нужны.
        profileOptions.root.visibility = View.GONE
        userToxIdCopy.visibility = View.GONE
        // QR - отдельной плиткой; значок в карточке ID больше не нужен.
        showQr.visibility = View.GONE
        profileOptions.profileChangeAvatar.setOnClickListener { showAvatarOptions() }

        // TODO(robinlinden): This should open a nice dialog where you show the QR and have both share and copy buttons.
        profileShareId.setOnClickListener {
            // Ссылка на профиль: у получателя по нажатию откроется S-messenger и уйдёт заявка в друзья.
            com.stoyko.smessenger.ui.ProfileLink.share(requireContext(), vm.toxId.string(), currentName)
        }
        registerForContextMenu(profileShareId)

        showQr.setOnClickListener {
            createQrCodeDialog().show()
        }

        profileOptions.profileChangeNickname.setOnClickListener {
            val nameEdit = EditText(requireContext()).apply {
                text.append(binding.userName.text)
                filters = arrayOf(InputFilter.LengthFilter(TOX_MAX_NAME_LENGTH))
                setSingleLine()
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.name)
                .setView(nameEdit)
                .setPositiveButton(R.string.update) { _, _ ->
                    vm.setName(nameEdit.text.toString())
                    vm.broadcastCard()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        profileOptions.profileChangeStatusText.setOnClickListener {
            val statusMessageEdit =
                EditText(requireContext()).apply {
                    text.append(binding.userStatusMessage.text)
                    filters = arrayOf(InputFilter.LengthFilter(TOX_MAX_STATUS_MESSAGE_LENGTH))
                }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.status_message)
                .setView(statusMessageEdit)
                .setPositiveButton(R.string.update) { _, _ ->
                    vm.setStatusMessage(statusMessageEdit.text.toString())
                    vm.broadcastCard()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        profileOptions.profileChangeStatus.setOnClickListener {
            StatusDialog(requireContext(), currentStatus) { status -> vm.setStatus(status) }.show()
        }

        // TODO(robinlinden): Remove hack. It's used to make sure we can scroll to the settings
        //  further down when in landscape orientation. This is only needed if the view is recreated
        //  while we're on this screen as Android changes the size of the contents of the NestedScrollView
        //  when that happens.
        if (savedInstanceState != null) {
            needsHacks.updatePadding(bottom = (150 * resources.displayMetrics.density).toInt())
        }
    }

    /** Строка «монеты»: вращающаяся монета с «S», количество и кнопка «Змейка». */
    private fun coinsBar(): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return android.widget.LinearLayout(ctx).apply {
            tag = "coins_bar"
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x33FDE047, 0x14D97706),
            ).apply { cornerRadius = 18 * d }
            addView(com.stoyko.smessenger.ui.CoinView(ctx).apply { tag = "coins_icon" }, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(
                android.widget.TextView(ctx).apply {
                    tag = "coins_text"
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary))
                    setPadding(dp(12), 0, dp(8), 0)
                },
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                com.stoyko.smessenger.ui.Btn.nice(ctx, getString(R.string.games_title), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
                    com.stoyko.smessenger.games.GamesHub.show(ctx) { buySelfGift() }
                },
                android.widget.LinearLayout.LayoutParams(dp(104), dp(44)),
            )
            setOnClickListener { com.stoyko.smessenger.games.GamesHub.show(ctx) { buySelfGift() } }
            layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(10), dp(12), dp(2))
            }
        }
    }

    private fun updateCoinsBar(bar: View) {
        val ctx = context ?: return
        val n = com.stoyko.smessenger.chat.Coins.balance(ctx)
        val tv = bar.findViewWithTag<android.widget.TextView>("coins_text") ?: return
        val old = tv.getTag(R.id.tag_btn_norm) as? Int
        val C = com.stoyko.smessenger.chat.Coins
        tv.text = resources.getQuantityString(R.plurals.coins_count, n, n) +
            if (C.dailyAvailable(ctx)) "\n" + getString(R.string.daily_ready_short) else ""
        // Витрина достижений (выбранные мной, до 6) ровными плитками под монетами и одна кнопка
        // «📊 Статистика игр» - обновляются сразу (слушатель монет и статистики).
        val parent = bar.parent as? android.view.ViewGroup
        if (parent != null) {
            val GS = com.stoyko.smessenger.games.stats.GameStats
            val stats = C.statsLine(ctx)
            val old = parent.findViewWithTag<View>("my_stats_chips")
            if (old?.getTag(R.id.tag_btn_norm) != stats) {
                val idx = old?.let { parent.indexOfChild(it) } ?: (parent.indexOfChild(bar) + 1)
                old?.let { parent.removeView(it) }
                val snap = GS.mine(ctx)
                val block = com.stoyko.smessenger.games.stats.GameStatsSheet.profileBlock(ctx, snap, GS.showcase(ctx), null) {
                    // Витрину видят и друзья - шлём им свежую карточку.
                    vm.broadcastCard()
                    coinsListener()
                }.apply {
                    tag = "my_stats_chips"
                    setTag(R.id.tag_btn_norm, stats)
                }
                val dd = resources.displayMetrics.density
                parent.addView(block, idx, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins((12 * dd).toInt(), (6 * dd).toInt(), (12 * dd).toInt(), (2 * dd).toInt())
                })
            }
        }
        tv.setTag(R.id.tag_btn_norm, n)
        if (old != null && old != n) bar.findViewWithTag<com.stoyko.smessenger.ui.CoinView>("coins_icon")?.pulse()
    }

    private val coinsListener: () -> Unit = {
        if (view != null) binding.needsHacks.findViewWithTag<View>("coins_bar")?.let { updateCoinsBar(it) }
    }

    private val giftsListener: () -> Unit = {
        if (view != null) fillExtra(binding.userStatusMessage.text.toString())
    }

    override fun onDestroyView() {
        com.stoyko.smessenger.chat.Gifts.unlisten(giftsListener)
        com.stoyko.smessenger.chat.Coins.unlisten(coinsListener)
        super.onDestroyView()
    }

    private fun copyToxId() {
        val clipboard = requireActivity().getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getText(R.string.tox_id), vm.toxId.string()))
        Toast.makeText(requireContext(), getString(R.string.tox_id_copied_full, vm.toxId.string().length), Toast.LENGTH_SHORT).show()
    }

    /** Статус целиком (в шапке он обрезается) и поля расширенного профиля. */
    private fun fillExtra(statusMessage: String) {
        val box = if (view == null) return else binding.profileExtra
        box.removeAllViews()
        val p = vm.profileCard.mine
        com.stoyko.smessenger.chat.AnimatedEmoji.attachStatus(binding.userName, p.emojiStatus, 22)
        // «Мои подарки» - в самом верху, НАД карточкой с подписью и ключом шифрования (ID),
        // чтобы подарки было видно сразу, а не после прокрутки.
        val parent: android.view.ViewGroup? = binding.needsHacks
        if (parent != null) {
            // Монеты - самой первой строкой: живая монета «S» и сколько их у меня.
            val coinsBar = parent.findViewWithTag<View>("coins_bar") ?: coinsBar().also { parent.addView(it, 0) }
            updateCoinsBar(coinsBar)
            val gifts = parent.findViewWithTag<android.widget.LinearLayout>("gifts_box") ?: android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                tag = "gifts_box"
                parent.addView(this, parent.indexOfChild(coinsBar) + 1)
            }
            // Аккаунт разработчика - крупная золотая плашка прямо над витриной
            // (у друзей она так же видна в моём профиле).
            val designer = com.stoyko.smessenger.chat.DevAccount.isMeDesigner(requireContext())
            val tester = !designer && com.stoyko.smessenger.chat.DevAccount.isMeTester(requireContext())
            val dev = com.stoyko.smessenger.chat.DevAccount.isMe(requireContext()) || designer || tester
            val badge = parent.findViewWithTag<View>("dev_big_badge")
            if (dev && badge == null) {
                val m = (12 * resources.displayMetrics.density).toInt()
                parent.addView(
                    com.stoyko.smessenger.chat.DevAccount.bigBadge(requireContext(), designer = designer, tester = tester),
                    parent.indexOfChild(gifts),
                    android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(m, m / 2, m, m / 2)
                    },
                )
            } else if (!dev && badge != null) {
                parent.removeView(badge)
            }
            // Только у самого разработчика: кнопка канала и «📊 Статистика» под плашкой.
            val realDev = dev && !designer && !tester
            val extras = parent.findViewWithTag<View>("dev_extras")
            val badgeNow = parent.findViewWithTag<View>("dev_big_badge")
            if (realDev && extras == null && badgeNow != null) {
                val m = (12 * resources.displayMetrics.density).toInt()
                parent.addView(
                    com.stoyko.smessenger.chat.DevAccount.devExtras(requireContext(), mine = true),
                    parent.indexOfChild(badgeNow) + 1,
                    android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(m, m / 2, m, m / 2)
                    },
                )
            } else if (!realDev && extras != null) {
                parent.removeView(extras)
            }
            // В профиле - только витрина (до 12 подарков, выбранных мной); все подарки - в галерее.
            val ctx = requireContext()
            val key = com.stoyko.smessenger.chat.Gifts.showcase(ctx).joinToString(",") { it.at.toString() } +
                "/" + com.stoyko.smessenger.chat.Gifts.received(ctx).size
            if (gifts.contentDescription?.toString() != key) {
                gifts.contentDescription = key
                com.stoyko.smessenger.chat.GiftGallery.fillShowcase(gifts) { anchor -> openGiftGallery(anchor) }
            }
        }
        val rows = listOf(
            R.string.profile_status_message to statusMessage,
            R.string.profile_username to p.username.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }.orEmpty(),
            R.string.profile_bio to p.bio,
            R.string.profile_city to p.city,
            R.string.profile_birthday to p.birthday,
            R.string.profile_link to p.link,
        )
        for ((title, value) in rows) {
            if (value.isBlank()) continue
            box.addView(infoRow(requireContext(), getString(title), value))
        }
    }

    private fun openEditor() {
        val b = if (view == null) return else binding
        ProfileEditor.show(
            requireContext(),
            ProfileEditor.Values(b.userName.text.toString(), b.userStatusMessage.text.toString(), vm.profileCard.mine),
            bindAvatar = { iv ->
                val size = (104 * resources.displayMetrics.density).toInt()
                com.stoyko.smessenger.profile.SelfAvatar.show(iv, currentName, vm.publicKey.string(), size)
            },
            onPhoto = { showAvatarOptions() },
            pickEmoji = { cb -> pickEmojiStatus(cb) },
            availabilityLabel = {
                getString(
                    when (if (::currentStatus.isInitialized) currentStatus else UserStatus.None) {
                        UserStatus.None -> R.string.status_available
                        UserStatus.Away -> R.string.status_away
                        UserStatus.Busy -> R.string.status_busy
                    },
                )
            },
            onAvailability = { refresh ->
                StatusDialog(requireContext(), if (::currentStatus.isInitialized) currentStatus else UserStatus.None) { status ->
                    vm.setStatus(status)
                    currentStatus = status
                    refresh()
                }.show()
            },
        ) { v ->
            if (v.name != b.userName.text.toString()) vm.setName(v.name)
            if (v.statusMessage != b.userStatusMessage.text.toString()) vm.setStatusMessage(v.statusMessage)
            vm.setExtProfile(v.ext)
            if (view != null) fillExtra(v.statusMessage)
            Toast.makeText(requireContext(), R.string.profile_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /** Выбор эмодзи-статуса: панель эмодзи по разделам + «Убрать». */
    private fun pickEmojiStatus(onPicked: (String) -> Unit) {
        val ctx = requireContext()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val d = resources.displayMetrics.density
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        root.addView(
            android.widget.TextView(ctx).apply {
                setText(R.string.emoji_status_remove)
                textSize = 15f
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.settingsAccent))
                setPadding((18 * d).toInt(), (6 * d).toInt(), (18 * d).toInt(), (6 * d).toInt())
                setOnClickListener {
                    onPicked("")
                    sheet.dismiss()
                }
            },
        )
        root.addView(
            com.stoyko.smessenger.ui.chat.EmojiPanel(ctx) { e ->
                onPicked(e)
                sheet.dismiss()
            },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (380 * d).toInt()),
        )
        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        sheet.behavior.isDraggable = false
        sheet.setOnShowListener { sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) = binding.run {
        super.onCreateContextMenu(menu, v, menuInfo)
        when (v.id) {
            R.id.profile_share_id -> requireActivity().menuInflater.inflate(
                R.menu.user_profile_share_id_context_menu,
                menu,
            )
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean = binding.run {
        return when (item.itemId) {
            R.id.copy -> {
                copyToxId()
                true
            }
            R.id.copy_link -> {
                com.stoyko.smessenger.ui.ProfileLink.copy(requireContext(), vm.toxId.string(), currentName)
                true
            }
            R.id.qr -> {
                createQrCodeDialog().show()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun createQrCodeDialog(): AlertDialog {
        val qrSize =
            min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * QR_CODE_TO_SCREEN_RATIO
        val bmp = asQr(vm.toxId, Px(qrSize.toInt()), qrCodePadding.asPx(resources))
        val qrCode = ImageView(requireContext()).apply {
            setPadding(qrCodePadding.asPx(resources).px)
            setImageBitmap(bmp)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.tox_id)
            .setView(qrCode)
            .setPositiveButton(getString(R.string.share)) { _, _ ->
                vm.viewModelScope.launch {
                    val qrImageUri = getQrForSharing()
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        clipData = ClipData.newRawUri(null, qrImageUri)
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, qrImageUri)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.tox_id_share)))
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()
    }

    private fun saveQrForSharing(qrBmp: Bitmap): Uri {
        val imagesFolder = File(requireContext().cacheDir, "shared_images").apply { mkdirs() }
        val file = File(imagesFolder, "smessenger_id_qr.png")
        FileOutputStream(file).use { stream ->
            qrBmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        }

        return FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    private fun asQr(id: ToxID, qrSize: Px, padding: Px): Bitmap {
        // В QR - только сам ID (76 символов), без приписок: любая камера покажет чистый ID.
        val qrData = QrCode.encodeText(id.string().uppercase(), QrCode.Ecc.LOW)
        var bmpQr: Bitmap = createBitmap(qrData.size, qrData.size, Bitmap.Config.RGB_565)
        for (x in 0 until qrData.size) {
            for (y in 0 until qrData.size) {
                bmpQr[x, y] = if (qrData.getModule(x, y)) Color.BLACK else Color.WHITE
            }
        }

        bmpQr = bmpQr.scale(qrSize.px, qrSize.px, false)

        val bmpQrWithPadding =
            createBitmap(bmpQr.width + 2 * padding.px, bmpQr.height + 2 * padding.px, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmpQrWithPadding)
        canvas.drawPaint(
            Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            },
        )
        canvas.drawBitmap(bmpQr, padding.px.toFloat(), padding.px.toFloat(), null)

        return bmpQrWithPadding
    }

    private suspend fun getQrForSharing(): Uri = withContext(Dispatchers.IO) {
        val bmp = asQr(vm.toxId, qrCodeSharedImageSize, qrCodeSharedImagePadding)
        saveQrForSharing(bmp)
    }
}

/** Строка «заголовок / значение» для карточек профиля (своего и собеседника). */
fun infoRow(context: android.content.Context, title: String, value: String): View {
    val d = context.resources.displayMetrics.density
    return android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        addView(
            android.widget.TextView(context).apply {
                text = value
                textSize = 15f
                setTextIsSelectable(true)
                autoLinkMask = android.text.util.Linkify.WEB_URLS
            },
        )
        addView(
            android.widget.TextView(context).apply {
                text = title
                textSize = 12f
                alpha = 0.6f
            },
        )
    }
}
