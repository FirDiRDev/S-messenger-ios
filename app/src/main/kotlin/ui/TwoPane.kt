// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.stoyko.smessenger.R

/**
 * Режим планшета: на широком экране (планшет или раскрытый складной телефон, особенно в
 * горизонтальном положении) слева всегда список чатов, справа - открытый чат или настройки.
 * На телефонах ничего не меняется. Выключается в Настройках → Оформление.
 */
object TwoPane {
    private const val PREFS = "ui"
    private const val KEY = "two_pane"
    const val ARG_SIDE = "side_pane"

    /** Режим планшета: 0 - авто (по модели и экрану), 1 - всегда включён, 2 - выключен. */
    fun mode(ctx: Context): Int = runCatching {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains("two_pane_mode")) p.getInt("two_pane_mode", 0) else if (p.getBoolean(KEY, true)) 0 else 2
    }.getOrDefault(0)

    fun setMode(ctx: Context, m: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("two_pane_mode", m).apply()

    fun enabled(ctx: Context) = mode(ctx) != 2

    fun setEnabled(ctx: Context, on: Boolean) = setMode(ctx, if (on) 0 else 2)

    /**
     * Сейчас два окна? Авто - планшеты и раскрытые складные (по модели или ширине экрана);
     * «Всегда» - на любом экране шире 600 dp (например, телефон в горизонтальном положении).
     */
    fun active(ctx: Context): Boolean {
        val c = ctx.resources.configuration
        return when (mode(ctx)) {
            2 -> false
            1 -> c.screenWidthDp >= 600
            else -> (c.smallestScreenWidthDp >= 600 || DeviceProfile.tabletLike(ctx)) && c.screenWidthDp >= 720
        }
    }

    /**
     * Разметка двух окон: слева контейнер списка чатов, справа - обычная навигация.
     * [makeList] создаёт фрагмент списка для левого окна.
     */
    fun setup(activity: FragmentActivity, savedInstanceState: android.os.Bundle?, makeList: () -> androidx.fragment.app.Fragment) {
        val fm = activity.supportFragmentManager
        val navHost = activity.findViewById<View>(R.id.nav_host_fragment) ?: return
        val root = navHost.parent as? ConstraintLayout ?: return
        if (!active(activity)) {
            // Были два окна (до поворота) - левое убираем, иначе система будет искать его контейнер.
            fm.findFragmentById(R.id.side_pane)?.let { runCatching { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() } }
            return
        }
        val d = activity.resources.displayMetrics.density
        val width = activity.resources.configuration.screenWidthDp
        val sideDp = (width * 0.38f).toInt().coerceIn(320, 420)
        val side = FragmentContainerView(activity).apply { id = R.id.side_pane }
        root.addView(
            side,
            ConstraintLayout.LayoutParams((sideDp * d).toInt(), 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )
        // Тонкая линия-разделитель между окнами.
        val divider = View(activity).apply {
            id = View.generateViewId()
            setBackgroundColor(ContextCompat.getColor(activity, R.color.dividerColor))
        }
        root.addView(
            divider,
            ConstraintLayout.LayoutParams((1 * d).toInt().coerceAtLeast(1), 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                leftToRight = R.id.side_pane
            },
        )
        val lp = navHost.layoutParams as ConstraintLayout.LayoutParams
        lp.leftToLeft = ConstraintLayout.LayoutParams.UNSET
        lp.leftToRight = divider.id
        navHost.layoutParams = lp
        if (fm.findFragmentById(R.id.side_pane) == null) {
            fm.beginTransaction().replace(R.id.side_pane, makeList()).commitNowAllowingStateLoss()
        }
        // Справа, пока открыт «список чатов» основной навигации, - заглушка «Выберите чат».
        val holder = placeholder(activity)
        root.addView(
            holder,
            ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                leftToRight = divider.id
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )
        root.post {
            val host = fm.findFragmentById(R.id.nav_host_fragment) as? androidx.navigation.fragment.NavHostFragment ?: return@post
            // Заглушку показываем с задержкой: при переходе из чата в чат правое окно на миг
            // возвращается к списку - без задержки заглушка «мигала».
            val show = Runnable {
                if (host.navController.currentDestination?.id == R.id.contactListFragment) {
                    holder.alpha = 0f
                    holder.visibility = View.VISIBLE
                    holder.animate().alpha(1f).setDuration(160).start()
                }
            }
            host.navController.addOnDestinationChangedListener { _, dest, _ ->
                holder.removeCallbacks(show)
                if (dest.id == R.id.contactListFragment) holder.postDelayed(show, 180) else holder.visibility = View.GONE
            }
            holder.visibility = if (host.navController.currentDestination?.id == R.id.contactListFragment) View.VISIBLE else View.GONE
        }
    }

    /** Фрагмент - левое окно планшета? */
    fun isSide(f: androidx.fragment.app.Fragment) = f.arguments?.getBoolean(ARG_SIDE) == true

    /** Основная навигация (правое окно) - для левого списка. */
    fun mainNav(activity: FragmentActivity): androidx.navigation.NavController? =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? androidx.navigation.fragment.NavHostFragment)?.navController

    /** Правое окно, пока чат не выбран: красивая заглушка поверх основного списка. */
    fun placeholder(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        return FrameLayout(ctx).apply {
            tag = "two_pane_placeholder"
            isClickable = true
            isFocusable = true
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorBackground))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            col.addView(
                TextView(ctx).apply {
                    text = "💬"
                    textSize = 54f
                    gravity = Gravity.CENTER
                },
            )
            col.addView(
                TextView(ctx).apply {
                    setText(R.string.two_pane_pick_chat)
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
                    setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
                    background = null
                },
            )
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            elevation = 30 * d
            setOnTouchListener { _, _ -> true }
        }
    }
}
