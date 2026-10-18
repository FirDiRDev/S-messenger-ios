// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.call

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentCallBinding
import com.stoyko.smessenger.hasPermission
import com.stoyko.smessenger.requireStringArg
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.CallPhase
import com.stoyko.smessenger.domain.feature.CallState

private const val PERMISSION = Manifest.permission.RECORD_AUDIO
const val ARG_VIDEO = "video"

class CallFragment : BaseFragment<FragmentCallBinding>(FragmentCallBinding::inflate) {
    private val vm: CallViewModel by viewModels { vmFactory }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.startCamera()
        } else {
            Toast.makeText(requireContext(), R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleCamera() {
        if (vm.video.cameraOn.value) {
            vm.stopCamera()
        } else if (requireContext().hasPermission(Manifest.permission.CAMERA)) {
            vm.startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startSendingAudio()
        } else {
            Toast.makeText(requireContext(), getString(R.string.call_mic_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private var pulse: android.animation.ValueAnimator? = null

    // Видео: своё окошко можно перетаскивать и менять местами с видео собеседника,
    // а кнопки прячутся сами, чтобы не мешать смотреть.
    private var swapped = false
    private var controlsShown = true
    private val hideControls = Runnable { setControls(false) }
    private var lastRemote: android.graphics.Bitmap? = null
    private var lastLocal: android.graphics.Bitmap? = null

    private fun setControls(show: Boolean) {
        val b = if (view == null) return else binding
        if (controlsShown == show) return
        controlsShown = show
        val d = if (com.stoyko.smessenger.ui.UiPrefs.animations) 220L else 0L
        b.controlContainer.animate().translationY(if (show) 0f else b.controlContainer.height.toFloat()).alpha(if (show) 1f else 0f).setDuration(d).start()
        b.header.animate().alpha(if (show) 1f else 0f).translationY(if (show) 0f else -b.header.height * 0.3f).setDuration(d).start()
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        val b = if (view == null) return else binding
        b.root.removeCallbacks(hideControls)
        if (b.remoteVideo.visibility == View.VISIBLE) b.root.postDelayed(hideControls, 4000)
    }

    private fun render() {
        val b = if (view == null) return else binding
        val big = if (swapped) lastLocal else lastRemote
        val small = if (swapped) lastRemote else lastLocal
        big?.let { b.remoteVideo.setImageBitmap(it) }
        small?.let { b.localVideo.setImageBitmap(it) }
    }

    private fun applyMirror() {
        val b = if (view == null) return else binding
        val front = vm.video.frontCamera.value
        b.localVideo.scaleX = if (!swapped && front) -1f else 1f
        b.remoteVideo.scaleX = if (swapped && front) -1f else 1f
    }

    /** Перетаскивание своего окошка с прилипанием к ближайшему углу; касание - поменять местами. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPip() {
        val b = binding
        val card = b.localVideoCard
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val slop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = v.translationX
                    startY = v.translationY
                    dragging = false
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        v.translationX = startX + dx
                        v.translationY = startY + dy
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) {
                        snap(v)
                    } else if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        swapped = !swapped
                        applyMirror()
                        render()
                        binding.remoteVideo.alpha = 0.4f
                        binding.remoteVideo.animate().alpha(1f).setDuration(220).start()
                    }
                }
            }
            true
        }
    }

    private fun snap(v: View) {
        val parent = v.parent as View
        val m = 16 * resources.displayMetrics.density
        // Исходное место окошка - верхний правый угол; считаем смещения относительно него.
        val left = -(parent.width - v.width - 2 * m) + 0f
        val top = 0f
        val bottom = parent.height - v.height - v.top - (binding.controlContainer.height) + 0f
        val cx = v.translationX
        val cy = v.translationY
        val tx = if (cx < left / 2) left else 0f
        val ty = if (cy > bottom / 2) bottom.coerceAtLeast(0f) else top
        v.animate().translationX(tx).translationY(ty).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    private var lastPhase: CallPhase? = null
    private var tunnelRtt: Long? = null
    private var dotsFrame = 0

    /** Бегущие точки у «Соединение…», «Подключение…», «Вызов…». */
    private val dots: Runnable = object : Runnable {
        override fun run() {
            val b = if (view == null) return else binding
            val p = vm.phase.value
            if (p == CallPhase.ACTIVE || p == CallPhase.NONE) return
            dotsFrame++
            b.callStatus.text = phaseText(p) + ".".repeat(dotsFrame % 4)
            b.callStatus.postDelayed(this, 420)
        }
    }

    private fun phaseText(p: CallPhase): String = when (p) {
        CallPhase.CONNECTING -> "📶 " + getString(R.string.call_phase_connecting)
        CallPhase.SECURING -> "🔐 " + getString(R.string.call_phase_securing)
        CallPhase.RINGING -> "📞 " + getString(R.string.call_phase_ringing)
        else -> ""
    }

    private fun showPhase(phase: CallPhase) {
        val b = if (view == null) return else binding
        if (phase == lastPhase) return
        val prev = lastPhase
        lastPhase = phase
        // Смена надписи - мягко: старая уезжает вверх, новая появляется.
        b.callStatus.animate().cancel()
        b.callStatus.alpha = 0f
        b.callStatus.translationY = 10 * resources.displayMetrics.density
        b.callStatus.animate().alpha(1f).translationY(0f).setDuration(260).start()
        b.callStatus.removeCallbacks(dots)
        when (phase) {
            CallPhase.CONNECTING, CallPhase.SECURING, CallPhase.RINGING -> {
                b.callStatus.text = phaseText(phase)
                b.callStatus.postDelayed(dots, 420)
                b.callTimer.visibility = View.GONE
                if (phase == CallPhase.RINGING) {
                    com.stoyko.smessenger.call.CallTones.startRingback()
                } else {
                    com.stoyko.smessenger.call.CallTones.stopRingback()
                }
            }
            CallPhase.ACTIVE -> {
                com.stoyko.smessenger.call.CallTones.stopRingback()
                if (prev != null && prev != CallPhase.ACTIVE) com.stoyko.smessenger.call.CallTones.connected()
                val path = vm.connectionLabel()?.let { " · " + getString(it) }.orEmpty()
                val checked = if (tunnelRtt != null) " · " + getString(R.string.call_tunnel_ok) else ""
                b.callStatus.text = "🔒 " + getString(R.string.call_phase_active) + path + checked
                b.callTimer.base = vm.activeSince.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime()
                b.callTimer.visibility = View.VISIBLE
                b.callTimer.start()
            }
            CallPhase.NONE -> {
                com.stoyko.smessenger.call.CallTones.stopRingback()
                b.callTimer.stop()
            }
        }
    }

    /** Ведёт звонок по этапам: соединение → проверка защищённого канала → вызов (у звонящего). */
    private fun runStages() {
        viewLifecycleOwner.lifecycleScope.launch {
            // «Соединение…» видно хотя бы полсекунды, чтобы этапы не мелькали.
            kotlinx.coroutines.delay(650)
            if (vm.inCall.value !is CallState.InCall) return@launch
            vm.setPhase(CallPhase.SECURING)
            // Создание туннеля: живая проверка сквозного шифрования (и на Wi-Fi, и в моб. сети).
            tunnelRtt = vm.verifyTunnel(if (vm.outgoing) 4000 else 1800)
            if (vm.inCall.value !is CallState.InCall) return@launch
            vm.setPhase(if (vm.outgoing) CallPhase.RINGING else CallPhase.ACTIVE)
        }
    }

    override fun onDestroyView() {
        binding.callStatus.removeCallbacks(dots)
        // Ушли с экрана во время «Вызова…» - гудки не должны играть в пустоту.
        if (vm.phase.value != CallPhase.RINGING || vm.inCall.value !is CallState.InCall) {
            com.stoyko.smessenger.call.CallTones.stopRingback()
        }
        binding.root.removeCallbacks(hideControls)
        pulse?.cancel()
        pulse = null
        vm.video.remoteRenderer = null
        vm.video.localRenderer = null
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = insets.top + (24 * resources.displayMetrics.density).toInt())
            controlContainer.updatePadding(bottom = insets.bottom + (32 * resources.displayMetrics.density).toInt())
            compat
        }

        vm.setActiveContact(PublicKey(requireStringArg(CONTACT_PUBLIC_KEY)))
        vm.contact.observe(viewLifecycleOwner) {
            avatarImageView.setFrom(it)
            callName.text = it.name.ifEmpty { getString(R.string.contact_default_name) }
        }

        // Этапы звонка: «Соединение…» → «Подключение…» (защищённый канал) → «Вызов…» (гудки) →
        // разговор. Таймер идёт только после ответа собеседника.
        vm.phase.asLiveData().observe(viewLifecycleOwner) { phase -> showPhase(phase) }

        // Видео: камера всегда «головой вверх», поэтому экран звонка держим вертикальным.
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        vm.video.remoteRenderer = { bmp ->
            lastRemote = bmp
            if (remoteVideo.visibility != View.VISIBLE) {
                remoteVideo.alpha = 0f
                remoteVideo.visibility = View.VISIBLE
                remoteVideo.animate().alpha(1f).setDuration(300).start()
                glow.visibility = View.GONE
                ring.visibility = View.INVISIBLE
                avatarImageView.visibility = View.INVISIBLE
                scheduleHide()
            }
            render()
        }
        vm.video.localRenderer = { bmp ->
            lastLocal = bmp
            render()
        }
        setupPip()
        controlContainer.blurSource = remoteVideo
        controlContainer.tintColor = 0x551B1A2E
        controlContainer.fallbackColor = 0x881B1A2E.toInt()
        controlContainer.edgeTop = true
        // Касание по экрану - показать/спрятать кнопки (когда идёт видео).
        callRoot.setOnClickListener {
            if (remoteVideo.visibility == View.VISIBLE) {
                setControls(!controlsShown)
            }
        }
        vm.video.cameraOn.asLiveData().observe(viewLifecycleOwner) { on ->
            videoToggle.setImageResource(if (on) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
            if (on && localVideoCard.visibility != View.VISIBLE) {
                localVideoCard.scaleX = 0.6f
                localVideoCard.scaleY = 0.6f
                localVideoCard.alpha = 0f
                localVideoCard.visibility = View.VISIBLE
                localVideoCard.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250).start()
            } else if (!on) {
                localVideoCard.visibility = View.GONE
                lastLocal = null
                if (swapped) {
                    swapped = false
                    applyMirror()
                    render()
                }
            }
        }
        // Своё изображение с фронтальной камеры показываем зеркально, как в зеркале.
        vm.video.frontCamera.asLiveData().observe(viewLifecycleOwner) { applyMirror() }
        videoToggle.setOnClickListener { toggleCamera() }
        switchCamera.setOnClickListener { vm.switchCamera() }

        // Кольцо вокруг аватара «дышит», пока идёт звонок.
        ring.alpha = 0.6f

        // Аватар собеседника мягко пульсирует, пока идёт звонок.
        pulse = android.animation.ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                ring.scaleX = v
                ring.scaleY = v
                glow.alpha = 0.6f + (v - 1f) * 5f
            }
            start()
        }
        controlContainer.translationY = 200f
        controlContainer.animate().translationY(0f).setDuration(420)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()

        endCall.setOnClickListener {
            requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            com.stoyko.smessenger.call.CallTones.stopRingback()
            vm.endCall()
            findNavController().popBackStack()
        }

        vm.sendingAudio.asLiveData().observe(viewLifecycleOwner) { sending ->
            if (sending) {
                microphoneControl.setImageResource(R.drawable.ic_mic)
            } else {
                microphoneControl.setImageResource(R.drawable.ic_mic_off)
            }
            highlight(microphoneControl, !sending)
        }

        microphoneControl.setOnClickListener {
            if (vm.sendingAudio.value) {
                vm.stopSendingAudio()
            } else {
                if (requireContext().hasPermission(PERMISSION)) {
                    vm.startSendingAudio()
                } else {
                    requestPermissionLauncher.launch(PERMISSION)
                }
            }
        }

        updateSpeakerphoneIcon()
        speakerphone.setOnClickListener {
            vm.toggleSpeakerphone()
            updateSpeakerphoneIcon()
        }

        backToChat.setOnClickListener {
            findNavController().popBackStack()
        }

        if (vm.inCall.value is CallState.InCall) {
            vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
                if (inCall == CallState.NotInCall) {
                    findNavController().popBackStack()
                }
            }
            return
        }

        startCall()
        updateSpeakerphoneIcon()

        if (requireContext().hasPermission(PERMISSION)) {
            vm.startSendingAudio()
        }
    }

    /** Включённая кнопка - светлая с тёмным значком. */
    private fun highlight(button: android.widget.ImageButton, on: Boolean) {
        button.background?.mutate()?.setTint(if (on) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
        button.setColorFilter(if (on) 0xFF1B1A2E.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun updateSpeakerphoneIcon() {
        val on = vm.speakerphoneOn
        val icon = if (on) R.drawable.ic_speakerphone else R.drawable.ic_speakerphone_off
        binding.speakerphone.setImageResource(icon)
        highlight(binding.speakerphone, on)
    }

    private fun startCall() {
        val withVideo = arguments?.getBoolean(ARG_VIDEO, false) == true
        if (!vm.startCall(withVideo)) {
            Toast.makeText(requireContext(), R.string.call_contact_offline, Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
            return
        }
        if (withVideo) toggleCamera()
        runStages()
        vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
            if (inCall == CallState.NotInCall) {
                com.stoyko.smessenger.call.CallTones.stopRingback()
                findNavController().popBackStack()
            }
        }
    }
}
