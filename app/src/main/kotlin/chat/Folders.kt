// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.Contact

/** Папка чатов: своё название и выбранные чаты. */
data class ChatFolder(val name: String, val members: Set<String>)

/** Папки хранятся только на этом устройстве. */
object Folders {
    private const val KEY = "folders"
    private const val MAX_NAME = 24

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chat_folders", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<ChatFolder> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.optJSONArray("m") ?: JSONArray()
            ChatFolder(o.optString("n"), (0 until m.length()).map { m.getString(it) }.toSet())
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, folders: List<ChatFolder>) {
        val arr = JSONArray()
        folders.forEach { f ->
            arr.put(JSONObject().put("n", f.name).put("m", JSONArray(f.members.toList())))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Управление папками: список, «Новая папка», изменить/удалить. [onChanged] - обновить вкладки. */
    fun manage(ctx: Context, contacts: List<Contact>, onChanged: () -> Unit) {
        val folders = list(ctx)
        val items = folders.map { "📁 " + it.name } + ctx.getString(R.string.folder_new)
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.folders) + " — " + ctx.getString(R.string.folder_manage_hint))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == folders.size) {
                    edit(ctx, contacts, null, onChanged)
                } else {
                    actions(ctx, contacts, which, onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Удалить папку (с подтверждением). */
    fun confirmDelete(ctx: Context, index: Int, onChanged: () -> Unit) {
        val folders = list(ctx)
        val f = folders.getOrNull(index) ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.folder_delete)
            .setMessage(ctx.getString(R.string.folder_delete_confirm, f.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                save(ctx, list(ctx).filterIndexed { i, _ -> i != index })
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Действия с папкой (долгое нажатие на её вкладку): изменить или удалить. */
    fun actions(ctx: Context, contacts: List<Contact>, index: Int, onChanged: () -> Unit) {
        val f = list(ctx).getOrNull(index) ?: return
        AlertDialog.Builder(ctx)
            .setTitle("📁 " + f.name)
            .setItems(arrayOf("✏️  " + ctx.getString(R.string.folder_edit), "🗑  " + ctx.getString(R.string.folder_delete))) { _, a ->
                if (a == 0) edit(ctx, contacts, index, onChanged) else confirmDelete(ctx, index, onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Создать (index == null) или изменить папку: название, затем выбор чатов. */
    private fun edit(ctx: Context, contacts: List<Contact>, index: Int?, onChanged: () -> Unit) {
        val folders = list(ctx)
        val old = index?.let { folders.getOrNull(it) }
        val nameEdit = EditText(ctx).apply {
            setText(old?.name.orEmpty())
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME))
            setSingleLine()
            hint = ctx.getString(R.string.folder_name_hint)
        }
        val box = android.widget.FrameLayout(ctx).apply {
            val p = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(nameEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (old == null) R.string.folder_new else R.string.folder_edit)
            .setView(box)
            .setPositiveButton(R.string.next) { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (contacts.isEmpty()) {
                    Toast.makeText(ctx, R.string.folder_no_contacts, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val labels = contacts.map { it.name.ifEmpty { it.publicKey.take(8) } }.toTypedArray()
                val checked = BooleanArray(contacts.size) { old?.members?.contains(contacts[it].publicKey) == true }
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.folder_pick_chats)
                    .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton(R.string.save) { _, _ ->
                        val members = contacts.filterIndexed { i, _ -> checked[i] }.map { it.publicKey }.toSet()
                        val f = ChatFolder(name, members)
                        val updated = folders.toMutableList()
                        if (index == null) updated.add(f) else updated[index] = f
                        save(ctx, updated)
                        onChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
