// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * Перекраска всего мессенджера в цвета сезона: фирменный фиолетовый (шапки, меню,
 * настройки, кнопки, значки, ссылки) заменяется на осенний оранжевый или зимний синий на
 * всех экранах; в шапках всех экранов (группы, каналы, настройки, профили) - снег с
 * гирляндой или листопад.
 */
object SeasonTint {
    private val BRAND = setOf(0xFF7B6CF6.toInt(), 0xFF5B4BDB.toInt(), 0xFF2A1F8F.toInt(), 0xFF3A2DAF.toInt(), 0xFF4B3CCB.toInt(), 0xFF8B7CFF.toInt())

    private fun palette(k: Seasonal.Kind): IntArray? = when (k) {
        Seasonal.Kind.AUTUMN -> intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt(), 0xFF9A3412.toInt())
        Seasonal.Kind.WINTER -> intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt(), 0xFF1E3A8A.toInt())
        Seasonal.Kind.SPRING -> intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt(), 0xFF9D174D.toInt())
        Seasonal.Kind.SUMMER -> intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt(), 0xFF115E59.toInt())
        Seasonal.Kind.NONE -> null
    }

    @Volatile
    private var kindCache: Seasonal.Kind? = null

    private fun kindOf(ctx: android.content.Context): Seasonal.Kind =
        kindCache ?: Seasonal.current(ctx).also { kindCache = it }

    /** Цвета своего пузыря сообщения по сезону (null - обычные фирменные). */
    fun bubbleColors(k: Seasonal.Kind): IntArray? = when (k) {
        Seasonal.Kind.AUTUMN -> intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt())
        Seasonal.Kind.WINTER -> intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt())
        Seasonal.Kind.SPRING -> intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())
        Seasonal.Kind.SUMMER -> intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt())
        Seasonal.Kind.NONE -> null
    }

    /**
     * Пузырь сообщения в цветах сезона - сразу при показе строки (раньше пузыри перекрашивались
     * только при перестройке экрана, а при прокрутке снова становились фиолетовыми).
     * Свой - градиент сезона, чужой - тонкая рамка цвета сезона.
     */
    fun bubble(v: View, outgoing: Boolean) {
        val k = kindOf(v.context)
        val cols = bubbleColors(k) ?: return
        val g = v.background?.mutate() as? GradientDrawable ?: return
        if (outgoing) {
            g.orientation = GradientDrawable.Orientation.TL_BR
            g.colors = cols
        } else {
            g.setStroke((1 * v.resources.displayMetrics.density).toInt().coerceAtLeast(1), (cols[1] and 0x00FFFFFF) or 0x55000000)
        }
    }

    fun install(activity: FragmentActivity) {
        val kind = Seasonal.current(activity)
        if (kind == Seasonal.Kind.NONE) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?) {
                    // Сразу, до первого кадра: экран открывается уже в цветах сезона.
                    apply(v, kind)
                }
            },
            true,
        )
        // Раньше экран перекрашивался один раз (и ещё раз через 0.4 с) - всё, что появлялось
        // позже (строки списков, сообщения, панели, окна и меню), оставалось фиолетовым, а
        // цвета «прыгали». Теперь каждое окно приложения (сам экран, диалоги, нижние панели,
        // всплывающие меню) перекрашивается перед каждой отрисовкой после изменений -
        // без мигания и без пропусков.
        watch(activity.window.decorView, kind)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val scan = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                for (w in windowViews()) watch(w, kind)
                handler.postDelayed(this, if (activity.hasWindowFocus()) 600 else 250)
            }
        }
        handler.post(scan)
    }

    /** Все окна процесса (диалоги, нижние панели, меню). */
    private fun windowViews(): List<View> {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
        }
        return runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val g = c.getMethod("getInstance").invoke(null)
            val f = c.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(g) as? List<View>)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Следить за окном: после любой перестройки - перекрасить до отрисовки кадра. */
    private fun watch(root: View, kind: Seasonal.Kind) {
        if (root.getTag(com.stoyko.smessenger.R.id.tag_season_watch) != null) return
        root.setTag(com.stoyko.smessenger.R.id.tag_season_watch, true)
        var dirty = true
        val vto = root.viewTreeObserver
        vto.addOnGlobalLayoutListener { dirty = true }
        vto.addOnPreDrawListener {
            if (dirty) {
                dirty = false
                runCatching { apply(root, kind) }
            }
            true
        }
    }

    /** Не вешать гирлянду/снег поверх шапок внутри [root] (профиль - там аватарка). */
    fun noGarland(root: View?) {
        root?.setTag(com.stoyko.smessenger.R.id.tag_no_garland, true)
    }

    private fun underNoGarland(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p.getTag(com.stoyko.smessenger.R.id.tag_no_garland) != null) return true
            p = p.parent
        }
        return false
    }

    /** Перекрасить дерево экрана. */
    fun apply(root: View?, kind: Seasonal.Kind = root?.let { Seasonal.current(it.context) } ?: Seasonal.Kind.NONE) {
        if (root == null) return
        val pal = palette(kind) ?: return
        walk(root, pal, kind)
    }

    private fun map(c: Int, pal: IntArray): Int? = when (c or 0xFF000000.toInt()) {
        0xFF7B6CF6.toInt(), 0xFF8B7CFF.toInt() -> pal[0]
        0xFF5B4BDB.toInt(), 0xFF4B3CCB.toInt() -> pal[1]
        0xFF2A1F8F.toInt(), 0xFF3A2DAF.toInt() -> pal[2]
        else -> null
    }?.let { (c and 0xFF000000.toInt()) or (it and 0x00FFFFFF) }

    /** Есть ли во вложенных слоях фирменный цвет (чтобы не мутировать лишнее). */
    private fun hasBrand(dr: android.graphics.drawable.Drawable?, pal: IntArray, depth: Int): Boolean {
        if (dr == null || depth > 4) return false
        return when (dr) {
            is GradientDrawable -> android.os.Build.VERSION.SDK_INT >= 24 &&
                ((dr.colors?.any { map(it, pal) != null } == true) || (dr.color?.defaultColor?.let { map(it, pal) } != null))
            is ColorDrawable -> map(dr.color, pal) != null
            is android.graphics.drawable.LayerDrawable -> (0 until dr.numberOfLayers).any { hasBrand(dr.getDrawable(it), pal, depth + 1) }
            is android.graphics.drawable.InsetDrawable -> hasBrand(dr.drawable, pal, depth + 1)
            is android.graphics.drawable.StateListDrawable -> if (android.os.Build.VERSION.SDK_INT >= 29) {
                (0 until dr.stateCount).any { hasBrand(dr.getStateDrawable(it), pal, depth + 1) }
            } else {
                hasBrand(dr.current, pal, depth + 1)
            }
            else -> false
        }
    }

    private fun tintDrawable(dr: android.graphics.drawable.Drawable?, pal: IntArray, depth: Int) {
        if (dr == null || depth > 4) return
        when (dr) {
            is GradientDrawable -> if (android.os.Build.VERSION.SDK_INT >= 24) {
                val cols = dr.colors
                if (cols != null && cols.any { map(it, pal) != null }) {
                    dr.colors = cols.map { map(it, pal) ?: it }.toIntArray()
                } else {
                    dr.color?.defaultColor?.let { c -> map(c, pal)?.let { dr.setColor(it) } }
                }
            }
            is ColorDrawable -> map(dr.color, pal)?.let { dr.color = it }
            is android.graphics.drawable.LayerDrawable -> for (i in 0 until dr.numberOfLayers) tintDrawable(dr.getDrawable(i), pal, depth + 1)
            is android.graphics.drawable.InsetDrawable -> tintDrawable(dr.drawable, pal, depth + 1)
            is android.graphics.drawable.StateListDrawable -> if (android.os.Build.VERSION.SDK_INT >= 29) {
                for (i in 0 until dr.stateCount) tintDrawable(dr.getStateDrawable(i), pal, depth + 1)
            } else {
                tintDrawable(dr.current, pal, depth + 1)
            }
        }
    }

    private fun walk(v: View, pal: IntArray, kind: Seasonal.Kind) {
        // Свои сезонные надписи (SeasonTitle) не трогаем.
        if (v.getTag(com.stoyko.smessenger.R.id.tag_season) == "title") return
        // Фон: фирменный градиент или заливка.
        when (val bg = v.background) {
            is GradientDrawable -> if (android.os.Build.VERSION.SDK_INT >= 24) {
                val cols = bg.colors
                if (cols != null && cols.any { (it or 0xFF000000.toInt()) in BRAND }) {
                    v.background = (bg.constantState?.newDrawable()?.mutate() as? GradientDrawable ?: bg).apply {
                        colors = cols.map { map(it, pal) ?: it }.toIntArray()
                    }
                } else {
                    bg.color?.defaultColor?.let { c -> map(c, pal)?.let { nc -> (bg.mutate() as GradientDrawable).setColor(nc) } }
                }
            }
            is ColorDrawable -> map(bg.color, pal)?.let { v.setBackgroundColor(it) }
            // Пузыри сообщений, кнопки с нажатием, карточки: слои, «рябь», состояния.
            is android.graphics.drawable.LayerDrawable, is android.graphics.drawable.StateListDrawable, is android.graphics.drawable.InsetDrawable ->
                if (hasBrand(bg, pal, 0)) {
                    val m = bg.mutate()
                    tintDrawable(m, pal, 0)
                    v.invalidate()
                }
        }
        v.backgroundTintList?.defaultColor?.let { c -> map(c, pal)?.let { v.backgroundTintList = ColorStateList.valueOf(it) } }
        if (v is TextView) map(v.currentTextColor, pal)?.let { v.setTextColor(it) }
        if (v is ImageView) v.imageTintList?.defaultColor?.let { c -> map(c, pal)?.let { v.imageTintList = ColorStateList.valueOf(it) } }
        // Шапки всех экранов: снег с гирляндой или листопад поверх.
        if (v is androidx.appcompat.widget.Toolbar && android.os.Build.VERSION.SDK_INT >= 23 && v.foreground == null && v.background !is android.graphics.drawable.LayerDrawable &&
            v.getTag(com.stoyko.smessenger.R.id.tag_no_garland) == null && !underNoGarland(v) &&
            (v.parent as? View)?.foreground !is Seasonal.SeasonDrawable
        ) {
            v.foreground = Seasonal.SeasonDrawable(v.context, kind, header = true, garland = true)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), pal, kind)
    }
}
