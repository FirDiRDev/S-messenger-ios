// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.ChatManager

/** Отложенное сообщение: target - ключ собеседника или "g:<id группы>". */
data class ScheduledMessage(val id: Long, val target: String, val text: String, val at: Long)

/**
 * Отложенная отправка: сообщение уходит само в выбранное время
 * (если собеседник не в сети - встанет в очередь и уйдёт при подключении).
 * Хранится только на этом устройстве; проверяет очередь фоновый сервис.
 */
@Singleton
class ScheduledMessages @Inject constructor(
    private val context: Context,
    private val chatManager: ChatManager,
    private val groupManager: com.stoyko.smessenger.groups.GroupManager,
) {
    private val prefs = context.getSharedPreferences("scheduled", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    @Synchronized
    fun all(): List<ScheduledMessage> = try {
        val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ScheduledMessage(o.getLong("id"), o.getString("t"), o.getString("m"), o.getLong("at"))
        }.sortedBy { it.at }
    } catch (e: Exception) {
        emptyList()
    }

    fun forTarget(target: String) = all().filter { it.target == target }

    @Synchronized
    private fun save(list: List<ScheduledMessage>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.target).put("m", it.text).put("at", it.at)) }
        prefs.edit().putString("list", arr.toString()).apply()
        _changes.value = _changes.value + 1
    }

    fun add(target: String, text: String, at: Long) {
        save(all() + ScheduledMessage(System.nanoTime(), target, text, at))
    }

    fun remove(id: Long) {
        save(all().filterNot { it.id == id })
    }

    fun sendNow(id: Long) {
        val m = all().firstOrNull { it.id == id } ?: return
        remove(id)
        deliver(m)
    }

    private fun deliver(m: ScheduledMessage) {
        if (m.target.startsWith("g:")) {
            groupManager.sendText(m.target.removePrefix("g:"), m.text)
        } else {
            chatManager.sendMessage(PublicKey(m.target), m.text)
        }
    }

    /** Запускается фоновым сервисом: раз в 10 секунд отправляет подошедшие сообщения. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val due = all().filter { it.at <= now }
                    if (due.isNotEmpty()) {
                        save(all().filter { it.at > now })
                        due.forEach { deliver(it) }
                    }
                } catch (e: Exception) {
                    Log.e("Scheduled", "tick", e)
                }
                delay(10_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        fun label(context: Context, at: Long): String =
            DateFormat.getMediumDateFormat(context).format(Date(at)) + " " + DateFormat.getTimeFormat(context).format(Date(at))

        /**
         * Выбор времени: быстрые варианты («через час», «сегодня вечером», «завтра утром»)
         * или своя дата и время.
         */
        fun pickTime(context: Context, onPicked: (Long) -> Unit) {
            val now = Calendar.getInstance()
            fun at(h: Int, plusDays: Int): Long = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, plusDays)
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
            val options = mutableListOf<Pair<String, () -> Unit>>()
            options += context.getString(R.string.schedule_in_1h) to { onPicked(System.currentTimeMillis() + 3_600_000L) }
            if (now.get(Calendar.HOUR_OF_DAY) < 19) {
                options += context.getString(R.string.schedule_tonight) to { onPicked(at(20, 0)) }
            }
            options += context.getString(R.string.schedule_tomorrow_morning) to { onPicked(at(9, 1)) }
            options += context.getString(R.string.schedule_custom) to {
                DatePickerDialog(context, { _, y, mo, d ->
                    TimePickerDialog(context, { _, h, mi ->
                        val c = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0) }
                        if (c.timeInMillis <= System.currentTimeMillis()) {
                            android.widget.Toast.makeText(context, R.string.schedule_past, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            onPicked(c.timeInMillis)
                        }
                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
                }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
                    datePicker.minDate = System.currentTimeMillis() - 1000
                }.show()
            }
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.schedule_send)
                .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Список запланированных в чате: отправить сейчас / удалить. */
        fun showList(context: Context, store: ScheduledMessages, target: String) {
            val list = store.forTarget(target)
            if (list.isEmpty()) return
            val items = list.map { "🕓 " + label(context, it.at) + "\n" + Markup.snippet(it.text) }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.scheduled_messages)
                .setItems(items) { _, which ->
                    val m = list[which]
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setMessage(Markup.snippet(m.text))
                        .setPositiveButton(R.string.schedule_send_now) { _, _ -> store.sendNow(m.id) }
                        .setNegativeButton(R.string.delete) { _, _ -> store.remove(m.id) }
                        .setNeutralButton(android.R.string.cancel, null)
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
