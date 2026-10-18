// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import androidx.lifecycle.asLiveData
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.databinding.FragmentGroupListBinding
import com.stoyko.smessenger.databinding.GroupListItemBinding
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.vmFactory

class GroupListFragment : BaseFragment<FragmentGroupListBinding>(FragmentGroupListBinding::inflate) {
    private val vm: GroupListViewModel by viewModels { vmFactory }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            groupList.updatePadding(bottom = insets.bottom + (96 * resources.displayMetrics.density).toInt())
            compat
        }
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val adapter = GroupAdapter(requireContext())
        groupList.adapter = adapter
        com.stoyko.smessenger.groups.GroupProfile.changes.asLiveData().observe(viewLifecycleOwner) {
            adapter.notifyDataSetChanged()
        }
        vm.groups.observe(viewLifecycleOwner) {
            adapter.items = it
            adapter.notifyDataSetChanged()
            empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
        groupList.setOnItemClickListener { _, _, position, _ -> open(adapter.items[position].chatId) }

        fab.scaleX = 0f
        fab.scaleY = 0f
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(400)
            .setInterpolator(OvershootInterpolator(2.5f)).start()
        fab.setOnClickListener { showActions() }
    }

    private fun open(chatId: String) = findNavController().navigate(
        R.id.action_groupListFragment_to_groupChatFragment,
        bundleOf(GROUP_CHAT_ID to chatId),
    )

    private fun showActions() {
        val items = arrayOf(
            getString(R.string.create_group),
            getString(R.string.create_channel),
            getString(R.string.join_by_id),
        )
        AlertDialog.Builder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askText(R.string.create_group, R.string.group_name_hint) { create(it, false) }
                    1 -> askText(R.string.create_channel, R.string.group_name_hint) { create(it, true) }
                    2 -> askText(R.string.join_by_id, R.string.group_id_hint) { join(it) }
                }
            }
            .show()
    }

    private fun askText(title: Int, hint: Int, onOk: (String) -> Unit) {
        val edit = EditText(requireContext()).apply {
            this.hint = getString(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(requireContext()).apply {
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

    private fun create(name: String, isChannel: Boolean) {
        if (!vm.isToxRunning()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val id = try {
                vm.create(name, isChannel)
            } catch (e: Exception) {
                if (view != null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.group_create_failed) + "\n" + e.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            if (view == null) return@launch
            // Новый канал: при первом открытии - обучение «Как вести канал» (особенно для бизнеса).
            if (isChannel) ChannelTutorial.markPending(requireContext(), id)
            run {
                open(id)
            }
        }
    }

    private fun join(id: String) {
        if (!vm.isToxRunning()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val chatId = vm.join(id)
            if (view == null) return@launch
            if (chatId == null) {
                Toast.makeText(requireContext(), R.string.group_join_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), R.string.group_join_started, Toast.LENGTH_LONG).show()
                open(chatId)
            }
        }
    }
}
