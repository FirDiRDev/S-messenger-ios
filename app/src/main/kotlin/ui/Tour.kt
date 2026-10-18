// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.stoyko.smessenger.R

/**
 * «Экскурсовод»: после первой установки (и по кнопке в Настройках) по очереди подсвечивает
 * элементы главного экрана - затемнение с «окошком», пульсирующее кольцо и подсказка, что
 * здесь можно сделать. Нажатие - следующий шаг, «Пропустить» - закончить.
 */
object Tour {
    private const val PREFS = "tour"

    /** Экскурсию попросили из Настроек - запустить, когда откроется главный экран. */
    fun requestFromSettings(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pending", true).apply()

    /** Запустить экскурсию с любого экрана: возвращаемся к списку чатов, там она и начнётся. */
    fun launch(ctx: Context) {
        requestFromSettings(ctx)
        val activity = com.stoyko.smessenger.ui.Kinetics.activityOf(ctx) as? androidx.fragment.app.FragmentActivity
        val nav = activity?.supportFragmentManager?.findFragmentById(R.id.nav_host_fragment)
            ?.let { androidx.navigation.fragment.NavHostFragment.findNavController(it) }
        nav?.popBackStack(R.id.contactListFragment, false)
    }

    fun takePending(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getBoolean("pending", false)
        if (v) p.edit().putBoolean("pending", false).apply()
        return v
    }

    private class Step(
        val target: () -> View?,
        val title: Int,
        val text: Int,
        /** Подготовка шага (открыть/закрыть меню). true - что-то сдвинулось, нужно дождаться анимации. */
        val before: (() -> Boolean)? = null,
        /** Живой эмодзи в карточке (для шагов, где показать на экране нечего). */
        val emoji: String? = null,
        /** Докрутить до элемента (меню длинное). true - прокрутили, нужно подождать и проверить ещё раз. */
        val reveal: (() -> Boolean)? = null,
    )

    /** Первый вид в дереве, подходящий под условие. */
    private fun findView(root: View?, match: (View) -> Boolean): View? {
        if (root == null) return null
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findView(root.getChildAt(i), match)?.let { return it }
        return null
    }

    /** Строка пункта бокового меню (ищем по тексту пункта). */
    private fun navItem(nav: NavigationView?, id: Int): View? {
        val title = nav?.menu?.findItem(id)?.title?.toString() ?: return null
        val tv = findView(nav) { it is TextView && it.text?.toString() == title && it.isShown } ?: return null
        return (tv.parent as? View)?.takeIf { it.width > 0 } ?: tv
    }

    /** Внутренний список бокового меню (в нём и шапка с аватаром, и пункты). */
    private fun navList(nav: NavigationView?): ViewGroup? =
        findView(nav) { it !== nav && it is ViewGroup && (it.javaClass.name.endsWith("NavigationMenuView") || it.javaClass.name.endsWith("RecyclerView")) } as? ViewGroup

    private fun menuTitles(menu: android.view.Menu): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            out.add(item.title?.toString().orEmpty())
            item.subMenu?.let { out.addAll(menuTitles(it)) }
        }
        return out
    }

    /** Плавная прокрутка списка меню на dy пикселей. */
    private fun smoothScroll(list: View, dy: Int) {
        var done = 0
        ValueAnimator.ofInt(0, dy).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                list.scrollBy(0, v - done)
                done = v
            }
            start()
        }
    }

    /**
     * Докрутить меню так, чтобы пункт был целиком виден. Пункты ниже экрана ещё не созданы
     * списком - тогда крутим в нужную сторону и проверяем снова.
     */
    private fun revealNav(nav: NavigationView?, id: Int): Boolean {
        val list = navList(nav) ?: return false
        val title = nav?.menu?.findItem(id)?.title?.toString() ?: return false
        val d = list.resources.displayMetrics.density
        val lr = IntArray(2)
        list.getLocationOnScreen(lr)
        val top = lr[1]
        val bottom = top + list.height
        val tv = findView(list) { it is TextView && it.text?.toString() == title }
        if (tv != null) {
            val row = (tv.parent as? View) ?: tv
            val r = IntArray(2)
            row.getLocationOnScreen(r)
            val margin = (56 * d).toInt()
            val dy = when {
                r[1] < top + margin / 2 -> r[1] - top - margin
                r[1] + row.height > bottom - margin -> r[1] + row.height - bottom + margin
                else -> 0
            }
            if (dy == 0) return false
            if (dy > 0 && !list.canScrollVertically(1)) return false
            if (dy < 0 && !list.canScrollVertically(-1)) return false
            smoothScroll(list, dy)
            return true
        }
        val order = menuTitles(nav.menu)
        val target = order.indexOf(title)
        val visible = ArrayList<Int>()
        for (i in 0 until list.childCount) {
            val t = findView(list.getChildAt(i)) { it is TextView && it.text?.toString().orEmpty() in order } as? TextView ?: continue
            visible.add(order.indexOf(t.text.toString()))
        }
        val down = visible.isEmpty() || target > (visible.maxOrNull() ?: -1)
        if (down && !list.canScrollVertically(1)) return false
        if (!down && !list.canScrollVertically(-1)) return false
        smoothScroll(list, (if (down) 1 else -1) * list.height * 2 / 3)
        return true
    }

    /** Меню - в самое начало (к шапке с аватаром и переключателем темы). */
    private fun navToTop(nav: NavigationView?): Boolean {
        val list = navList(nav) ?: return false
        if (!list.canScrollVertically(-1)) return false
        list.scrollBy(0, -100_000)
        return true
    }

    /** Экскурсия по главному экрану (список чатов). */
    /** Экскурсия уже идёт - вторую поверх не запускаем (двойной запуск ломал шаги). */
    @Volatile
    private var active = false

    fun startMain(fragment: Fragment, onDone: (() -> Unit)? = null) {
        if (active) return
        val root = fragment.view
        val activity = fragment.activity
        if (root == null || activity == null) {
            onDone?.invoke()
            return
        }
        val drawer = root.findViewById<View>(R.id.drawerLayout) as? DrawerLayout
        val nav = root.findViewById<View>(R.id.navView) as? NavigationView
        val toolbar = root.findViewById<View>(R.id.toolbar) as? ViewGroup
        fun navButton(): View? {
            val tb = toolbar ?: return null
            for (i in 0 until tb.childCount) {
                val c = tb.getChildAt(i)
                if (c is android.widget.ImageButton) return c
            }
            return null
        }
        // Первый чат в списке (если есть) - на нём показываем возможности чата.
        // Меню открывается/закрывается только если нужно - так каждый пункт меню находится
        // надёжно, даже если шторку закрыли посреди экскурсии (или окно пересоздалось).
        fun openMenu(): Boolean {
            val dl = drawer ?: return false
            if (dl.isDrawerOpen(GravityCompat.START)) return false
            dl.openDrawer(GravityCompat.START)
            return true
        }
        fun closeMenu(): Boolean {
            val dl = drawer ?: return false
            if (!dl.isDrawerOpen(GravityCompat.START)) return false
            dl.closeDrawer(GravityCompat.START)
            return true
        }
        fun navStep(id: Int, title: Int, text: Int) = Step({ navItem(nav, id) }, title = title, text = text, before = { openMenu() }, reveal = { revealNav(nav, id) })
        fun firstChat(): View? = (root.findViewById<View>(R.id.contactList) as? ViewGroup)
            ?.let { l -> (0 until l.childCount).map { l.getChildAt(it) }.firstOrNull { it.isShown && it.height > 0 } }
        val steps = listOf(
            Step({ root.findViewById<View>(R.id.fabAddContact) }, R.string.tour_fab_title, R.string.tour_fab_text),
            Step({ root.findViewById<View>(R.id.contactList) }, R.string.tour_list_title, R.string.tour_list_text),
            Step({ root.findViewById<View>(R.id.tabs) }, R.string.tour_tabs_title, R.string.tour_tabs_text),
            // Лупа в шапке: поиск чатов и скрытых чатов по PIN.
            Step({ root.findViewById<View>(R.id.toolbarSearch) }, R.string.tour_search_title, R.string.tour_search_text),
            Step({ root.findViewById<View>(R.id.vpnShield) }, R.string.tour_vpn_title, R.string.tour_vpn_text),
            Step({ navButton() }, R.string.tour_menu_title, R.string.tour_menu_text),
            Step(
                { nav?.getHeaderView(0)?.findViewById<View>(R.id.themeToggle) },
                R.string.tour_theme_title,
                R.string.tour_theme_text,
                before = { openMenu() },
                reveal = { navToTop(nav) },
            ),
            Step(
                { nav?.getHeaderView(0)?.findViewById<View>(R.id.drawerEmojiStatus)?.takeIf { it.isShown } ?: nav?.getHeaderView(0) },
                R.string.tour_drawer_head_title,
                R.string.tour_drawer_head_text,
                before = { openMenu() },
                reveal = { navToTop(nav) },
            ),
            Step(
                { nav },
                R.string.tour_drawer_title,
                R.string.tour_drawer_text,
                before = { openMenu() },
                reveal = { navToTop(nav) },
            ),
            // Пункты меню - каждый подсвечивается отдельно, к нему «подлетает» палец.
            navStep(R.id.news_feed, R.string.tour_feed_title, R.string.tour_feed_text),
            // Лупа в меню: тот же поиск чатов (и скрытых по PIN).
            navStep(R.id.search_chats, R.string.tour_search_title, R.string.tour_search_menu_text),
            navStep(R.id.groups, R.string.tour_menu_chats_title, R.string.tour_menu_chats_text),
            navStep(R.id.browser, R.string.tour_menu_web_title, R.string.tour_menu_web_text),
            navStep(R.id.entertainment, R.string.tour_fun_title, R.string.tour_fun_text),
            navStep(R.id.games, R.string.tour_games_title, R.string.tour_games_text),
            navStep(R.id.games, R.string.tour_games_list_title, R.string.tour_games_list_text),
            navStep(R.id.games, R.string.tour_coins_title, R.string.tour_coins_text),
            navStep(R.id.metro_map, R.string.tour_metro_title, R.string.tour_metro_text),
            navStep(R.id.restart_app, R.string.tour_menu_net_title, R.string.tour_menu_net_text),
            navStep(R.id.settings, R.string.tour_settings_title, R.string.tour_settings_text),
            navStep(R.id.invite, R.string.tour_invite_title, R.string.tour_invite_text),
            // Дальше - главный экран: меню закрываем.
            Step(
                { findView(root) { it is com.stoyko.smessenger.ai.AiRow } },
                R.string.tour_ai_title,
                R.string.tour_ai_text,
                before = { closeMenu() },
            ),
            Step({ firstChat() ?: root.findViewById<View>(R.id.contactList) }, R.string.tour_share_id_title, R.string.tour_share_id_text, before = { closeMenu() }),
            Step({ null }, R.string.tour_link_title, R.string.tour_link_text, emoji = "🔗"),
            Step({ root.findViewById<View>(R.id.tabs) }, R.string.tour_groups_title, R.string.tour_groups_text),
            // Возможности внутри чата - с живым эмодзи-иллюстрацией.
            // Возможности чата - на первом чате списка (чатов нет - с живой эмодзи-картинкой).
            Step({ firstChat() }, R.string.tour_chat_title, R.string.tour_chat_text, emoji = "💬"),
            Step({ firstChat() }, R.string.ob_reactions_title, R.string.tour_reactions_text, emoji = "😍"),
            Step({ firstChat() }, R.string.tour_e2e_title, R.string.tour_e2e_text, emoji = "🔒"),
            Step({ firstChat() }, R.string.tour_stickers_title, R.string.tour_stickers_text, emoji = "😻"),
            Step({ firstChat() }, R.string.tour_video_title, R.string.tour_video_text, emoji = "🎬"),
            Step({ firstChat() }, R.string.tour_live_title, R.string.tour_live_text, emoji = "✨"),
            Step({ firstChat() }, R.string.tour_anim_title, R.string.tour_anim_text, emoji = "🫧"),
            Step({ firstChat() }, R.string.tour_spoiler_title, R.string.tour_spoiler_text, emoji = "🫣"),
            // Профиль: галерея «Мои подарки» и витрина до 12 подарков (3.5.0).
            Step({ null }, R.string.tour_gifts_title, R.string.tour_gifts_text, emoji = "🎁"),
            Step({ null }, R.string.ob_tv_title, R.string.tour_tv_text, emoji = "📡"),
            Step({ null }, R.string.ob_m3_title, R.string.tour_m3_text, emoji = "💎"),
            Step({ null }, R.string.ob_shop_title, R.string.tour_shop_text, emoji = "🛒"),
            Step({ null }, R.string.ob_forward_title, R.string.tour_forward_text, emoji = "↪️"),
            Step({ null }, R.string.ob_export_title, R.string.tour_export_text, emoji = "💾"),
            Step({ null }, R.string.ob_update_title, R.string.tour_update_text, emoji = "⬆️"),
            Step({ null }, R.string.lite_tour_title, R.string.lite_tour_text, emoji = "🪶"),
            Step({ null }, R.string.arc_tour_title, R.string.arc_tour_text, emoji = "🗜"),
            Step({ null }, R.string.ob_done_title, R.string.ob_done_text, emoji = "🚀"),
        )
        active = true
        TourOverlay(activity, steps) {
            active = false
            drawer?.closeDrawer(GravityCompat.START)
            navToTop(nav)
            onDone?.invoke()
        }.start()
    }

    @SuppressLint("ViewConstructor")
    private class TourOverlay(
        private val activity: Activity,
        private val steps: List<Step>,
        private val done: () -> Unit,
    ) : FrameLayout(activity) {
        private val d = resources.displayMetrics.density
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC8000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF7DD3FC.toInt()
            strokeWidth = 3 * d
        }
        private val hole = RectF()
        private val shown = RectF()
        private var pulse = 0f
        private var index = -1
        /** Номер текущего показа: отложенные проверки старого шага не перебивают новый. */
        private var showToken = 0
        private val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.WHITE)
            }
            elevation = 8 * d
        }
        private val title = TextView(activity).apply {
            textSize = 18f
            setTextColor(0xFF1B1B1F.toInt())
            paint.isFakeBoldText = true
        }
        private val text = TextView(activity).apply {
            textSize = 15f
            setTextColor(0xFF45454F.toInt())
            setLineSpacing(0f, 1.15f)
            setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
        }
        // Живой эмодзи сверху карточки (шаги без элемента на экране).
        private val emojiBox = FrameLayout(activity)
        private val emojiText = TextView(activity).apply {
            textSize = 46f
            gravity = Gravity.CENTER
        }
        private val emojiAnim = android.widget.ImageView(activity)

        // «Палец», который подлетает к нужной кнопке и «нажимает» её.
        private val hand = TextView(activity).apply {
            text = "👆"
            textSize = 40f
            alpha = 0f
            elevation = 12 * d
        }
        private var handTap: android.animation.Animator? = null
        private val tapRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2.5f * d
        }
        private var tapT = -1f
        private var tapX = 0f
        private var tapY = 0f

        private val counter = TextView(activity).apply {
            textSize = 13f
            setTextColor(0xFF8A8A93.toInt())
        }
        private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
        }

        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isClickable = true
            emojiBox.addView(emojiText, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            emojiBox.addView(emojiAnim, LayoutParams((72 * d).toInt(), (72 * d).toInt(), Gravity.CENTER))
            card.addView(emojiBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (76 * d).toInt()))
            card.addView(title)
            card.addView(text)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                TextView(activity).apply {
                    setText(R.string.ob_skip)
                    textSize = 15f
                    setTextColor(0xFF8A8A93.toInt())
                    setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                    setOnClickListener { finish() }
                },
            )
            row.addView(
                TextView(activity).apply {
                    setText(R.string.ob_next)
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                    setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * d
                        setColor(0xFF5B4BDB.toInt())
                    }
                    setOnClickListener { next() }
                    Buttons3d.apply(this)
                },
            )
            card.addView(row)
            addView(card, LayoutParams((resources.displayMetrics.widthPixels - 40 * d).toInt().coerceAtMost((420 * d).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hand, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        /** Палец плавно летит к элементу и «нажимает» его (пульсирующая волна в точке нажатия). */
        private fun moveHand() {
            handTap?.cancel()
            if (hole.isEmpty) {
                hand.animate().alpha(0f).setDuration(180).start()
                tapT = -1f
                return
            }
            val hw = hand.width.takeIf { it > 0 } ?: (48 * d).toInt()
            tapX = hole.centerX()
            tapY = hole.centerY()
            // Кончик пальца - в центр элемента (палец ниже точки нажатия).
            val tx = tapX - hw * 0.35f
            val ty = (tapY - 6 * d).coerceAtMost(height - 80 * d)
            if (hand.alpha < 0.1f) {
                hand.translationX = tx + 60 * d
                hand.translationY = ty + 90 * d
            }
            hand.animate().alpha(1f).translationX(tx).translationY(ty).setDuration(520)
                .setInterpolator(OvershootInterpolator(1.2f))
                .withEndAction { tapLoop() }
                .start()
        }

        private fun tapLoop() {
            if (hole.isEmpty || !isAttachedToWindow) return
            val set = android.animation.AnimatorSet()
            val down = android.animation.ObjectAnimator.ofFloat(hand, View.SCALE_X, 1f, 0.82f, 1f)
            val downY = android.animation.ObjectAnimator.ofFloat(hand, View.SCALE_Y, 1f, 0.82f, 1f)
            val ripple = ValueAnimator.ofFloat(0f, 1f).apply {
                addUpdateListener {
                    tapT = it.animatedValue as Float
                    invalidate()
                }
            }
            set.playTogether(down, downY, ripple)
            set.duration = 700
            set.startDelay = 250
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    tapT = -1f
                    if (!cancelled) postDelayed({ if (handTap === set) tapLoop() }, 600)
                }
            })
            handTap = set
            set.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // Экран закрыли посреди экскурсии - разрешаем запустить её снова.
            if (index < steps.size) active = false
        }

        fun start() {
            val decor = activity.window.decorView as? ViewGroup ?: return done()
            decor.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            alpha = 0f
            animate().alpha(1f).setDuration(250).start()
            pulseAnim.start()
            // Первый шаг - только после того, как окно экскурсии получило размеры. Раньше шаг
            // считался до разметки (ширина и высота = 0) - на многих телефонах подсветка и
            // карточка уезжали в угол.
            if (width > 0 && height > 0) next() else post { next() }
        }

        private fun finish() {
            pulseAnim.cancel()
            handTap?.cancel()
            handTap = null
            com.stoyko.smessenger.chat.AnimatedEmoji.stop(emojiAnim)
            animate().alpha(0f).setDuration(200).withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                done()
            }.start()
        }

        private fun next() {
            index++
            if (index >= steps.size) return finish()
            val step = steps[index]
            val moved = runCatching { step.before?.invoke() == true }.getOrDefault(false)
            // Шагу с открытием меню нужно время на анимацию шторки.
            // Шторка открывается с разной скоростью на разных телефонах - меряем после её анимации
            // и ещё раз чуть позже (если элемент сдвинулся).
            val token = ++showToken
            if (moved) {
                postDelayed({ if (token == showToken) show(step, 0, token) }, 420L)
            } else {
                show(step, 0, token)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Ширина карточки - по реальному окну (складные, планшеты, поворот экрана).
            val lp = card.layoutParams
            lp.width = (w - 32 * d).toInt().coerceAtMost((440 * d).toInt())
            card.layoutParams = lp
            if (index in steps.indices && oldw != 0) {
                val token = ++showToken
                post { if (token == showToken) show(steps[index], 0, token) }
            }
        }

        /** Отступы системных панелей и выреза камеры (Xiaomi, Samsung, Pixel - у всех разные). */
        private fun safeTop(): Int {
            val i = androidx.core.view.ViewCompat.getRootWindowInsets(this)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            return i?.top ?: (24 * d).toInt()
        }

        private fun safeBottom(): Int {
            val i = androidx.core.view.ViewCompat.getRootWindowInsets(this)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            return i?.bottom ?: (24 * d).toInt()
        }

        private fun show(step: Step, attempt: Int = 0, token: Int = showToken) {
            if (token != showToken || !isAttachedToWindow) return
            if (width == 0 || height == 0) {
                post { show(step, attempt, token) }
                return
            }
            // Длинное меню: сначала докручиваем до пункта, потом подсвечиваем (до 8 попыток).
            if (step.reveal != null && attempt < 8 && runCatching { step.reveal.invoke() }.getOrDefault(false)) {
                hand.animate().alpha(0f).setDuration(120).start()
                postDelayed({ show(step, attempt + 1, token) }, 320L)
                return
            }
            val found = step.target()
            // Шторка ещё едет или элемент не успел появиться - ждём немного и смотрим снова.
            if (step.emoji == null && (found == null || !found.isShown || found.width == 0) && attempt < 10) {
                postDelayed({ show(step, attempt + 1, token) }, 200L)
                return
            }
            val v = found
            val loc = IntArray(2)
            val me = IntArray(2)
            getLocationOnScreen(me)
            if (v == null || !v.isShown || v.width == 0) {
                // Элемента нет на экране - показываем подсказку по центру.
                hole.setEmpty()
            } else {
                v.getLocationOnScreen(loc)
                val pad = 6 * d
                hole.set(
                    loc[0] - me[0] - pad,
                    loc[1] - me[1] - pad,
                    loc[0] - me[0] + v.width + pad,
                    loc[1] - me[1] + v.height + pad,
                )
                // Слишком большой элемент (список) подсвечиваем верхней частью.
                val maxH = height * 0.45f
                if (hole.height() > maxH) hole.bottom = hole.top + maxH
            }
            animateHole()
            // Карточка: старая уезжает влево, новая выезжает справа.
            card.animate().cancel()
            title.setText(step.title)
            text.setText(step.text)
            counter.text = "${index + 1} / ${steps.size}"
            if (step.emoji != null) {
                emojiBox.visibility = View.VISIBLE
                emojiText.text = step.emoji
                emojiText.visibility = View.VISIBLE
                com.stoyko.smessenger.chat.AnimatedEmoji.stop(emojiAnim)
                com.stoyko.smessenger.chat.AnimatedEmoji.play(
                    emojiAnim,
                    com.stoyko.smessenger.chat.AnimatedEmoji.code(step.emoji),
                    loop = -1,
                ) { emojiText.visibility = View.INVISIBLE }
                emojiBox.scaleX = 0.4f
                emojiBox.scaleY = 0.4f
                emojiBox.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(2.6f)).start()
            } else {
                com.stoyko.smessenger.chat.AnimatedEmoji.stop(emojiAnim)
                emojiBox.visibility = View.GONE
            }
            card.post { placeCard() }
            postDelayed({ moveHand() }, 120)
        }

        private fun animateHole() {
            val from = RectF(shown)
            val to = RectF(hole)
            if (from.isEmpty) from.set(to.centerX(), to.centerY(), to.centerX(), to.centerY())
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    shown.set(
                        from.left + (to.left - from.left) * f,
                        from.top + (to.top - from.top) * f,
                        from.right + (to.right - from.right) * f,
                        from.bottom + (to.bottom - from.bottom) * f,
                    )
                    invalidate()
                }
                start()
            }
        }

        private fun placeCard() {
            val lp = card.layoutParams as LayoutParams
            val margin = 16 * d
            val ch = card.height.toFloat()
            val top = safeTop() + 8 * d
            val bottom = height - safeBottom() - 8 * d
            lp.leftMargin = ((width - card.width) / 2).coerceAtLeast(0)
            val y = when {
                hole.isEmpty -> (height - ch) / 2
                hole.bottom + margin + ch < bottom -> hole.bottom + margin
                hole.top - margin - ch > top -> hole.top - margin - ch
                // Не помещается ни сверху, ни снизу (маленький экран) - по центру свободной части.
                else -> (top + (bottom - top - ch) / 2)
            }
            lp.topMargin = y.coerceIn(top, (bottom - ch).coerceAtLeast(top)).toInt()
            card.layoutParams = lp
            card.alpha = 0f
            card.translationX = 60 * d
            card.translationY = 12 * d
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            if (!shown.isEmpty) {
                val r = 16 * d
                canvas.drawRoundRect(shown, r, r, clear)
                val grow = pulse * 10 * d
                ring.alpha = ((1f - pulse) * 255).toInt()
                canvas.drawRoundRect(
                    shown.left - grow, shown.top - grow, shown.right + grow, shown.bottom + grow,
                    r + grow, r + grow, ring,
                )
            }
            // Волна «нажатия» под пальцем.
            if (tapT in 0f..1f && !hole.isEmpty) {
                tapRing.alpha = ((1f - tapT) * 230).toInt()
                canvas.drawCircle(tapX, tapY, 8 * d + tapT * 34 * d, tapRing)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) next()
            return true
        }
    }
}
