// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.addcontact

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.hasPermission
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentAddContactBinding
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.domain.tox.ToxID
import com.stoyko.smessenger.domain.tox.ToxIdValidator

class AddContactFragment : BaseFragment<FragmentAddContactBinding>(FragmentAddContactBinding::inflate) {
    private val viewModel: AddContactViewModel by viewModels { vmFactory }

    private var toxIdValid: Boolean = false
    private var messageValid: Boolean = true

    private var contacts: List<Contact> = listOf()

    private fun isAddAllowed(): Boolean = toxIdValid && messageValid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!viewModel.isToxRunning() && !viewModel.tryLoadTox()) findNavController().navigateUp()
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            openScanner()
        } else {
            Toast.makeText(requireContext(), R.string.qr_need_camera, Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan() {
        if (requireContext().hasPermission(android.Manifest.permission.CAMERA)) {
            openScanner()
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** Встроенный сканер: после распознавания запрос в контакты отправляется сразу. */
    private fun openScanner() {
        QrScanner(requireContext()) { text ->
            if (view == null) return@QrScanner
            val raw = QrScanner.extractId(text)
            binding.toxId.setText(raw)
            val id = ToxID(raw)
            when {
                ToxIdValidator.validate(id) != ToxIdValidator.Result.NO_ERROR ->
                    Toast.makeText(requireContext(), R.string.qr_not_id, Toast.LENGTH_LONG).show()
                id == viewModel.toxId ->
                    Toast.makeText(requireContext(), R.string.tox_id_error_self_add, Toast.LENGTH_LONG).show()
                contacts.any { it.publicKey == id.toPublicKey().string() } -> {
                    Toast.makeText(requireContext(), R.string.tox_id_error_already_exists, Toast.LENGTH_SHORT).show()
                    openChat(id)
                }
                else -> {
                    val msg = binding.message.text.toString().ifBlank { getString(R.string.add_contact_message_default) }
                    viewModel.addContact(id, msg)
                    Toast.makeText(requireContext(), R.string.qr_request_sent, Toast.LENGTH_SHORT).show()
                    openChat(id)
                }
            }
        }.show()
    }

    private fun openChat(id: ToxID) {
        view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
        findNavController().navigate(
            R.id.chatFragment,
            bundleOf(CONTACT_PUBLIC_KEY to id.toPublicKey().string()),
            navOptions { popUpTo(R.id.contactListFragment) },
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(left = insets.left, top = insets.top)
            content.updatePadding(left = insets.left, right = insets.right)
            compat
        }

        viewModel.contacts.observe(viewLifecycleOwner) {
            contacts = it
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view)
                .hide(WindowInsetsCompat.Type.ime())
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        toxId.doAfterTextChanged { s ->
            val input = ToxID(s?.toString() ?: "")
            val inputLength = s?.toString()?.length ?: 0
            toxId.error = when (ToxIdValidator.validate(input)) {
                ToxIdValidator.Result.INCORRECT_LENGTH -> getString(R.string.tox_id_error_length, inputLength)
                ToxIdValidator.Result.INVALID_CHECKSUM -> getString(R.string.tox_id_error_checksum)
                ToxIdValidator.Result.NOT_HEX -> getString(R.string.tox_id_error_hex)
                ToxIdValidator.Result.NO_ERROR -> null
            }

            if (input == viewModel.toxId) {
                toxId.error = getString(R.string.tox_id_error_self_add)
            }

            if (toxId.error == null) {
                if (contacts.find { it.publicKey == input.toPublicKey().string() } != null) {
                    toxId.error = getString(R.string.tox_id_error_already_exists)
                }
            }

            toxIdValid = toxId.error == null
            add.isEnabled = isAddAllowed()
        }

        message.doAfterTextChanged { s ->
            val content = s?.toString() ?: ""
            message.error = if (content.isNotEmpty()) {
                null
            } else {
                getString(R.string.add_contact_message_error_empty)
            }

            messageValid = message.error == null
            add.isEnabled = isAddAllowed()
        }

        com.stoyko.smessenger.ui.Kinetics.shimmer(add)
        add.setOnClickListener {
            val id = ToxID(toxId.text.toString())
            viewModel.addContact(id, message.text.toString())
            WindowInsetsControllerCompat(requireActivity().window, view)
                .hide(WindowInsetsCompat.Type.ime())
            findNavController().navigate(
                R.id.chatFragment,
                bundleOf(CONTACT_PUBLIC_KEY to id.toPublicKey().string()),
                navOptions { popUpTo(R.id.contactListFragment) },
            )
        }

        if (requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            readQr.setOnClickListener { startScan() }
        } else {
            readQr.visibility = View.GONE
        }

        add.isEnabled = false

        toxId.setText(arguments?.getString("toxId"), TextView.BufferType.EDITABLE)
        // Готовый текст запроса (например, заказ из канала).
        arguments?.getString("message")?.takeIf { it.isNotBlank() && savedInstanceState == null }?.let { message.setText(it) }
        // Проще добавить контакт: если в буфере обмена уже лежит чей-то ID - подставляем сам.
        if (arguments?.getString("toxId").isNullOrBlank() && savedInstanceState == null) {
            val clip = (requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
            val found = Regex("[0-9A-Fa-f]{76}").find(clip)?.value?.uppercase()
            if (found != null && ToxID(found) != viewModel.toxId) {
                toxId.setText(found, TextView.BufferType.EDITABLE)
                Toast.makeText(requireContext(), R.string.add_contact_from_clipboard, Toast.LENGTH_SHORT).show()
            }
        }
        // Открыли экран сразу для сканирования (кнопка «Сканировать QR» в меню).
        if (arguments?.getBoolean("scan") == true && savedInstanceState == null) view.post { if (this@AddContactFragment.view != null) startScan() }
    }
}
