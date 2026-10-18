// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.friendrequest

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentFriendRequestBinding
import com.stoyko.smessenger.requireStringArg
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.PublicKey

const val FRIEND_REQUEST_PUBLIC_KEY = "FRIEND_REQUEST_PUBLIC_KEY"

class FriendRequestFragment : BaseFragment<FragmentFriendRequestBinding>(FragmentFriendRequestBinding::inflate) {
    private val vm: FriendRequestViewModel by viewModels { vmFactory }
    // null, пока запрос не загружен из базы (кнопки до этого выключены).
    private var friendRequest: FriendRequest? = null
    private var handled = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(left = insets.left, top = insets.top)
            content.updatePadding(left = insets.left, right = insets.right)
            buttons.updatePadding(bottom = (16 * resources.displayMetrics.density).toInt() + insets.bottom)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        reject.isEnabled = false
        accept.isEnabled = false

        vm.byId(PublicKey(requireStringArg(FRIEND_REQUEST_PUBLIC_KEY))).observe(viewLifecycleOwner) { request ->
            if (request == null) {
                // Запрос уже обработан (или удалён) - просто закрываем экран.
                if (!handled) {
                    handled = true
                    findNavController().popBackStack()
                }
                return@observe
            }
            friendRequest = request
            // Ник и аватарка из карточки запроса; длинный ID - мелко внизу, для сверки.
            val card = com.stoyko.smessenger.chat.RequestCard.parse(request.message)
            val who = com.stoyko.smessenger.chat.RequestCard.title(card, request.publicKey)
            val px = (112 * resources.displayMetrics.density).toInt()
            avatar.setImageBitmap(
                card.bitmap()?.let { com.stoyko.smessenger.chat.RequestCard.circle(it, px) }
                    ?: com.stoyko.smessenger.ui.AvatarFactory.create(resources, card.name.ifBlank { who }, request.publicKey, com.stoyko.smessenger.ui.Px(px)),
            )
            name.text = who
            subtitle.text = com.stoyko.smessenger.ai.t("хочет добавить вас в контакты", "wants to add you as a contact")
            val text = card.text.trim()
            message.text = text
            message.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            from.text = "ID: " + request.publicKey
            androidx.core.app.NotificationManagerCompat.from(requireContext()).cancel(request.publicKey.hashCode())
            reject.isEnabled = true
            accept.isEnabled = true
        }

        accept.setOnClickListener {
            val request = friendRequest ?: return@setOnClickListener
            if (handled) return@setOnClickListener
            handled = true
            vm.accept(request)
            findNavController().popBackStack()
        }

        reject.setOnClickListener {
            val request = friendRequest ?: return@setOnClickListener
            if (handled) return@setOnClickListener
            handled = true
            vm.reject(request)
            findNavController().popBackStack()
        }
    }
}
