// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.profile.ProfileCard
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.DialogTracker
import java.util.Calendar

/**
 * День рождения в профиле:
 *  - в свой день рождения человек получает 500 монет - раз в год;
 *  - чтобы монеты нельзя было «фармить», дату после первого указания можно менять (или
 *    стирать) не чаще одного раза в 365 дней, а подарок выдаётся только один раз за
 *    календарный год, даже если дату меняли;
 *  - в день рождения друга (дата из его карточки профиля) мессенджер один раз предлагает
 *    поздравить его и выбрать подарок.
 */
object Birthday {
    const val BONUS = 500
    private const val PREFS = "birthday"
    private const val YEAR_MS = 365L * 24 * 3600 * 1000

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Разобрать «дд.мм.гггг» → (день, месяц 0..11) или null. */
    fun parse(s: String): Pair<Int, Int>? {
        val p = s.trim().split('.', '/', '-')
        if (p.size < 2) return null
        val d = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (d !in 1..31 || m !in 1..12) return null
        return d to (m - 1)
    }

    /** Сегодня ли день рождения (29 февраля в невисокосный год - 28-го). */
    fun isToday(s: String, cal: Calendar = Calendar.getInstance()): Boolean {
        val (d, m) = parse(s) ?: return false
        val cm = cal.get(Calendar.MONTH)
        val cd = cal.get(Calendar.DAY_OF_MONTH)
        if (m != cm) return false
        if (d == cd) return true
        return d == 29 && m == Calendar.FEBRUARY && cd == 28 && cal.getActualMaximum(Calendar.DAY_OF_MONTH) == 28
    }

    /** Когда дату можно будет сменить снова (0 - можно сейчас). */
    fun lockedUntil(ctx: Context): Long {
        val at = prefs(ctx).getLong("changed_at", 0L)
        if (at <= 0L) return 0L
        val until = at + YEAR_MS
        return if (until > System.currentTimeMillis()) until else 0L
    }

    /**
     * Можно ли сменить дату [old] → [new]. Первое указание - всегда можно; после него смена
     * или удаление блокируются на год.
     */
    fun canChange(ctx: Context, old: String, new: String): Boolean {
        if (old == new) return true
        if (old.isBlank() && !prefs(ctx).contains("changed_at")) return true
        return lockedUntil(ctx) == 0L
    }

    /** Отметить смену даты (вызывается при сохранении профиля). */
    fun onChanged(ctx: Context, old: String, new: String) {
        if (old == new) return
        prefs(ctx).edit().putLong("changed_at", System.currentTimeMillis()).apply()
    }

    fun lockText(ctx: Context): String {
        val until = lockedUntil(ctx)
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(until))
        return ctx.getString(R.string.bd_locked, date)
    }

    /**
     * Проверка при запуске: свой праздник (подарок 500 монет) и праздники друзей.
     * [friends] - (ключ, имя) всех контактов; [onChat] / [onGift] - открыть чат / профиль друга.
     */
    fun check(activity: Activity, friends: List<Pair<String, String>>, onChat: (String) -> Unit, onGift: (String) -> Unit) {
        if (activity.isFinishing) return
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val p = prefs(activity)
        val mine = runCatching {
            com.stoyko.smessenger.profile.ExtProfile.fromJson(
                activity.getSharedPreferences("profile_card", Context.MODE_PRIVATE).getString("mine", null),
            )?.birthday
        }.getOrNull().orEmpty()
        if (isToday(mine, cal) && p.getInt("bonus_year", 0) != year) {
            p.edit().putInt("bonus_year", year).commit()
            Coins.add(activity, BONUS)
            celebrate(activity)
            return
        }
        // Друзья: поздравить можно один раз за год каждого.
        val today = friends.filter { (pk, _) ->
            isToday(ProfileCard.peer(activity, pk)?.birthday.orEmpty(), cal) && p.getInt("asked_$pk", 0) != year
        }
        if (today.isEmpty()) return
        val e = p.edit()
        today.forEach { e.putInt("asked_${it.first}", year) }
        e.apply()
        askFriends(activity, today, onChat, onGift)
    }

    private fun card(ctx: Context, emoji: String, title: String, text: String): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF472B6.toInt(), 0xFF8B5CF6.toInt())).apply { cornerRadius = 24 * d }
            addView(TextView(ctx).apply {
                this.text = emoji
                textSize = 56f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                this.text = title
                textSize = 21f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(6))
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(0xEEFFFFFF.toInt())
            })
        }
    }

    private fun celebrate(activity: Activity) {
        val box = card(activity, "🎂", activity.getString(R.string.bd_happy_title), activity.getString(R.string.bd_happy_text, BONUS))
        val dlg = AlertDialog.Builder(activity).setView(box).create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        box.addView(Btn.primary(activity, activity.getString(R.string.bd_thanks), intArrayOf(0xFFFDE047.toInt(), 0xFFF59E0B.toInt())) { dlg.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * activity.resources.displayMetrics.density).toInt() })
        DialogTracker.add(dlg)
        runCatching { dlg.show() }
        runCatching { com.stoyko.smessenger.ui.Kinetics.springIn(box) }
    }

    private fun askFriends(activity: Activity, list: List<Pair<String, String>>, onChat: (String) -> Unit, onGift: (String) -> Unit) {
        val names = list.joinToString(", ") { it.second.ifBlank { "…" } }
        val box = card(activity, "🎉", activity.getString(R.string.bd_friend_title), activity.getString(R.string.bd_friend_text, names))
        val dlg = AlertDialog.Builder(activity).setView(box).create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val d = activity.resources.displayMetrics.density
        val first = list.first().first
        box.addView(Btn.primary(activity, activity.getString(R.string.bd_congratulate), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
            dlg.dismiss()
            onChat(first)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * d).toInt() })
        box.addView(Btn.primary(activity, activity.getString(R.string.bd_buy_gift), intArrayOf(0xFFFDE047.toInt(), 0xFFF59E0B.toInt())) {
            dlg.dismiss()
            onGift(first)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        DialogTracker.add(dlg)
        runCatching { dlg.show() }
    }
}
