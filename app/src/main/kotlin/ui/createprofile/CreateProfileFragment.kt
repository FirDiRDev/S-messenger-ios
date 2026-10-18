// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.createprofile

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentProfileBinding
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.domain.tox.ToxSaveStatus

private const val TAG = "CreateProfile"

class CreateProfileFragment : BaseFragment<FragmentProfileBinding>(FragmentProfileBinding::inflate) {
    private val viewModel: CreateProfileViewModel by viewModels { vmFactory }
    private val loopAnimators = mutableListOf<ValueAnimator>()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Log.i(TAG, "Importing file $uri")
        // Полный профиль (.smsave): файл читается и сверяется с описью (размер и SHA-256 каждого
        // файла) с процентами; ключи - сейчас, всё остальное разложится при перезапуске.
        // Повреждённый файл отклоняется, данные не меняются.
        val ctx = requireContext().applicationContext
        setBusy(true)
        val progress = com.stoyko.smessenger.profile.BackupProgress.show(requireContext(), R.string.smsave_progress_import)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.stoyko.smessenger.profile.FullBackup.importFrom(ctx, uri, progress) }
                    .getOrElse { com.stoyko.smessenger.profile.FullBackup.ImportResult.Failed(importError(ctx, it)) }
            }
            progress.dismiss()
            if (isViewGone()) {
                com.stoyko.smessenger.profile.FullBackup.discardPending(ctx)
                return@launch
            }
            setBusy(false)
            when (result) {
                is com.stoyko.smessenger.profile.FullBackup.ImportResult.Failed -> {
                    shake(binding.btnImport)
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.smsave_err_title)
                        .setMessage(result.reason)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                is com.stoyko.smessenger.profile.FullBackup.ImportResult.Full -> importSave(result.save, null)
                is com.stoyko.smessenger.profile.FullBackup.ImportResult.KeysOnly -> importSave(result.save, null)
            }
        }
    }

    private fun importError(ctx: android.content.Context, e: Throwable): String =
        ctx.getString(R.string.smsave_err_corrupt, e.message ?: e.javaClass.simpleName)

    private fun importSave(save: ByteArray, password: String?) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val status = try {
                viewModel.importProfile(save, password)
            } catch (e: Throwable) {
                com.stoyko.smessenger.domain.Diagnostics.record("импорт профиля", e, fatal = false)
                ToxSaveStatus.Null
            }
            if (isViewGone()) return@launch
            setBusy(false)
            when (status) {
                ToxSaveStatus.Ok -> {
                    val ctx = requireContext()
                    if (com.stoyko.smessenger.profile.FullBackup.hasPending(ctx)) {
                        // Переписка, подарки, монеты, игры, сеть и настройки - после перезапуска.
                        Toast.makeText(ctx, R.string.backup_full_restoring, Toast.LENGTH_LONG).show()
                        com.stoyko.smessenger.CrashGuard.restartApp(requireActivity(), clearCache = false)
                    } else {
                        findNavController().popBackStack()
                    }
                }
                ToxSaveStatus.Encrypted -> askPassword(save, wrongBefore = password != null)
                else -> {
                    // Ключи не открылись - подготовленный профиль не раскладываем.
                    com.stoyko.smessenger.profile.FullBackup.discardPending(requireContext())
                    shake(binding.btnImport)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.import_tox_save_failed, status.name),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun askPassword(save: ByteArray, wrongBefore: Boolean) {
        if (wrongBefore) {
            Toast.makeText(requireContext(), getString(R.string.incorrect_password), Toast.LENGTH_LONG).show()
        }
        val passwordEdit = EditText(requireContext()).apply {
            hint = getString(R.string.password)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            transformationMethod = PasswordTransformationMethod()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.unlock_profile)
            .setView(passwordEdit)
            .setPositiveButton(android.R.string.ok) { _, _ -> importSave(save, passwordEdit.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Пароль не введён - подготовленный профиль не раскладываем.
                context?.applicationContext?.let { com.stoyko.smessenger.profile.FullBackup.discardPending(it) }
            }
            .show()
    }

    private fun isViewGone() = view == null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            content.updatePadding(left = insets.left, top = insets.top, right = insets.right, bottom = insets.bottom)
            compat
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            activity?.finish()
        }

        playEntrance()

        username.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnCreate.performClick()
                true
            } else {
                false
            }
        }

        com.stoyko.smessenger.ui.Kinetics.shimmer(btnCreate)
        btnCreate.setOnClickListener {
            val name = username.text?.toString()?.trim().orEmpty().ifEmpty { getString(R.string.name_default) }
            val statusMessage = getString(R.string.status_message_default)
            setBusy(true)
            viewLifecycleOwner.lifecycleScope.launch {
                // Даже создание ViewModel (и всего, что за ней) - внутри защиты.
                val result = try {
                    viewModel.createProfile(name, statusMessage)
                } catch (e: Throwable) {
                    com.stoyko.smessenger.domain.Diagnostics.record("кнопка «Создать»", e, fatal = false)
                    Result.failure(e)
                }
                if (isViewGone()) return@launch
                result.onSuccess {
                    logoContainer.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setDuration(260).start()
                    card.animate().translationY(-40f).alpha(0f).setDuration(260)
                        .withEndAction { if (!isViewGone()) findNavController().popBackStack() }
                        .start()
                }.onFailure { e ->
                    Log.e(TAG, "Profile creation failed", e)
                    setBusy(false)
                    shake(card)
                    showError(getString(R.string.profile_create_failed, e.message ?: e.javaClass.simpleName), e)
                }
            }
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
    }

    /** Ошибка с подробностями, которые можно отправить разработчику. */
    private fun showError(message: String, e: Throwable) {
        val details = message + "\n\n" + e.stackTraceToString().take(4000)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.share) { _, _ ->
                startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, details)
                        },
                        getString(R.string.share),
                    ),
                )
            }
            .show()
    }

    private fun setBusy(busy: Boolean) = binding.run {
        btnCreate.isEnabled = !busy
        btnImport.isEnabled = !busy
        username.isEnabled = !busy
        btnCreate.text = if (busy) "" else getString(R.string.create)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** Появление экрана: логотип «выпрыгивает», остальное поднимается снизу по очереди. */
    private fun playEntrance() = binding.run {
        logoContainer.scaleX = 0.3f
        logoContainer.scaleY = 0.3f
        logoContainer.alpha = 0f
        logoContainer.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(80).setDuration(650).setInterpolator(OvershootInterpolator(2.2f)).start()

        listOf(title, slogan, card, importLabel, btnImport, footer).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 60f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(220L + i * 90L).setDuration(480).setInterpolator(DecelerateInterpolator(2f)).start()
        }

        // Логотип мягко «дышит», светящиеся круги медленно плавают.
        loopAnimators += breathe(logo, 1f, 1.06f, 1800)
        loopAnimators += float(glowTop, 40f, 5200)
        loopAnimators += float(glowBottom, -50f, 6400)
    }

    private fun breathe(v: View, from: Float, to: Float, duration: Long) = ValueAnimator.ofFloat(from, to).apply {
        this.duration = duration
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val s = it.animatedValue as Float
            v.scaleX = s
            v.scaleY = s
        }
        start()
    }

    private fun float(v: View, dy: Float, duration: Long) = ValueAnimator.ofFloat(0f, dy).apply {
        this.duration = duration
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            v.translationY = it.animatedValue as Float
            v.translationX = (it.animatedValue as Float) / 2
        }
        start()
    }

    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, 24f).apply {
            duration = 420
            interpolator = CycleInterpolator(3f)
            start()
        }
    }

    override fun onDestroyView() {
        loopAnimators.forEach { it.cancel() }
        loopAnimators.clear()
        super.onDestroyView()
    }
}
