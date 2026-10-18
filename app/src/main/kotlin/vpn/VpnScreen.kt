// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import com.stoyko.smessenger.ui.safeDismiss
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Экран встроенного VPN, как в Happ: вставить ссылку/подписку, реальный пинг,
 * выбор сервера и подключение одним касанием.
 */
class VpnScreen(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val store = VpnStore(context)
    private val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen))
    private val main = Handler(Looper.getMainLooper())
    private var items: List<VpnProfile> = store.profiles()
    private val list = ListView(context)
    private val power = PowerOrb(context)
    private val heroBg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E1650.toInt(), 0xFF2E2378.toInt(), 0xFF173A63.toInt()))
    private val statusText = TextView(context)
    private val serverText = TextView(context)
    private val empty = TextView(context)
    private val pillFlag = TextView(context)
    private val pillName = TextView(context)
    private val pillMeta = TextView(context)
    private val pillPing = TextView(context)
    private val statMode = TextView(context)
    private val statPing = TextView(context)
    private val statScope = TextView(context)
    private val statsRow = LinearLayout(context)
    private val serverPill = LinearLayout(context)
    private val stateListener: () -> Unit = { render() }

    @Suppress("DEPRECATION")
    private val bg = context.resources.getColor(R.color.colorBackground)

    @Suppress("DEPRECATION")
    private val surface = context.resources.getColor(R.color.colorSurface)

    @Suppress("DEPRECATION")
    private val textColor = context.resources.getColor(R.color.textPrimary)

    @Suppress("DEPRECATION")
    private val secondary = context.resources.getColor(R.color.textSecondary)

    private val ticker = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            render()
            main.postDelayed(this, 1000)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = items[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(12), dp(9))
                addView(
                    TextView(context).apply {
                        textSize = 17f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(dp(40), dp(40)),
                )
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                    addView(TextView(context).apply { textSize = 15f; maxLines = 1 })
                    addView(TextView(context).apply { textSize = 12f; maxLines = 1 })
                }
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply { textSize = 12f; setPadding(dp(8), dp(3), dp(8), dp(3)) })
            }
            val selected = p.id == store.selectedId || (store.selectedId.isEmpty() && position == 0)
            val icon = row.getChildAt(0) as TextView
            if (selecting) {
                // Режим выбора: квадратик-галочка.
                val on = p.id in checked
                icon.text = if (on) "✓" else ""
                icon.setTextColor(Color.WHITE)
                icon.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    if (on) setColor(0xFF5B4BDB.toInt()) else setStroke(dp(2), secondary)
                }
            } else {
                // Флаг страны в кружке; выбранный - с зелёным кольцом.
                icon.text = VpnUi.flagOf(p.name, p.protocol)
                icon.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (selected) 0x2622C55E else 0x145B4BDB)
                    if (selected) setStroke(dp(2), 0xFF22C55E.toInt())
                }
            }
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).apply {
                text = VpnUi.cleanName(p.name)
                setTextColor(textColor)
                paint.isFakeBoldText = selected
            }
            (texts.getChildAt(1) as TextView).apply {
                text = "${p.protocol.uppercase()} · ${p.host}:${p.port}"
                setTextColor(secondary)
            }
            (row.getChildAt(2) as TextView).apply {
                if (p.id in pinging) {
                    text = "• • •"
                    setTextColor(secondary)
                    if (getTag(R.id.vpn_ping_anim) == null) {
                        val a = android.animation.ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.25f).apply {
                            duration = 450
                            repeatMode = android.animation.ValueAnimator.REVERSE
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            start()
                        }
                        setTag(R.id.vpn_ping_anim, a)
                    }
                    return row
                }
                (getTag(R.id.vpn_ping_anim) as? android.animation.Animator)?.let {
                    it.cancel()
                    setTag(R.id.vpn_ping_anim, null)
                    alpha = 1f
                    scaleX = 0.6f
                    scaleY = 0.6f
                    animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(260).start()
                }
                val (label, color) = when {
                    p.ping == 0L -> "—" to secondary
                    p.ping < 0 -> "✕" to 0xFFE5484D.toInt()
                    p.ping < 300 -> context.getString(R.string.vpn_ms, p.ping) to 0xFF22C55E.toInt()
                    p.ping < 800 -> context.getString(R.string.vpn_ms, p.ping) to 0xFFF59E0B.toInt()
                    else -> context.getString(R.string.vpn_ms, p.ping) to 0xFFE5484D.toInt()
                }
                text = label
                setTextColor(color)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(color and 0x00FFFFFF or 0x22000000)
                }
            }
            row.background = if (selected) GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x145B4BDB)
            } else {
                null
            }
            return row
        }
    }

    fun show() {
        current = this
        // Подписка по умолчанию: добавлена впервые - сразу загружаем серверы.
        val freshDefault = runCatching { store.ensureDefaults() }.getOrDefault(false)
        if (freshDefault) {
            main.postDelayed({ if (dialog.isShowing) refreshSubscriptions() }, 800)
        } else {
            // Автообновление подписок (старше 6 часов) - тихо, в фоне.
            VpnSubscriptions.autoUpdate(context) { n -> if (n > 0 && dialog.isShowing) reload() }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        // Планшет или телефон боком: две колонки, как в YPtun на компьютере - слева кнопка и
        // состояние, справа серверы и режимы. На телефоне - одна колонка.
        val body: LinearLayout
        if (twoPane) {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(
                android.widget.ScrollView(context).apply {
                    isFillViewport = true
                    isVerticalScrollBarEnabled = false
                    addView(buildHero())
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f),
            )
            body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f))
        } else {
            root.addView(buildHero())
            body = root
        }

        // Вкладки: «Серверы», «Обход DPI», «Фрагментация», «WARP». Открыть вкладку - только
        // посмотреть/настроить; работает всегда ровно ОДИН режим - тот, что выбран кнопкой
        // «Использовать этот режим» (он отмечен зелёной точкой).
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0x1A5B4BDB)
            }
        }
        MODE_KEYS.forEachIndexed { i, key ->
            val c = TextView(context).apply {
                tag = key
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { showTab(key) }
            }
            c.setTag(R.id.tag_buttons3d, true)
            com.stoyko.smessenger.ui.Kinetics.squish(c, 0.92f)
            tabs.addView(c, LinearLayout.LayoutParams(0, dp(38), 1f))
            tabLabels[key] = context.getString(MODE_LABELS[i])
        }
        modeChips = tabs
        advancedBox.addView(
            tabs,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(10), dp(12), dp(8))
            },
        )
        val content = FrameLayout(context)
        advancedBox.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(advancedBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        simpleScroll.addView(simpleBox)
        body.addView(simpleScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(serverPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        modeScroll.addView(modePanel)
        content.addView(modeScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Серверы: если сейчас работает другой режим - плашка с переключением.
        serverPage.addView(
            activeBanner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), 0, dp(16), dp(8))
            },
        )

        // Действия: два ровных ряда одинаковых кнопок, остальное - в «Ещё».
        fun actionRow(vararg b: TextView) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
            b.forEach { addView(it, chipLp()) }
        }
        serverViews += actionRow(
            chip(R.string.vpn_paste) { pasteFromClipboard() },
            chip(R.string.vpn_add) { addDialog() },
            chip(R.string.vpn_ping_all) { pingAll() },
        ).also { serverPage.addView(it) }
        // Фильтр: все / обычные / обход белых списков (БС) / чёрные списки (ЧС) + сортировка по пингу.
        val catRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val catLabels = listOf(R.string.vpn_cat_all, R.string.vpn_cat_normal, R.string.vpn_cat_white, R.string.vpn_cat_black, R.string.vpn_cat_ping)
        val catViews = ArrayList<TextView>()
        fun paintCats() {
            catViews.forEachIndexed { i, v ->
                val on = if (i == 4) byPing else i == category
                v.background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(if (on) 0xFF5B4BDB.toInt() else 0x1F5B4BDB)
                }
                v.setTextColor(if (on) Color.WHITE else 0xFF5B4BDB.toInt())
            }
        }
        catLabels.forEachIndexed { i, res ->
            val v = TextView(context).apply {
                setText(res)
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                paint.isFakeBoldText = true
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener {
                    if (i == 4) byPing = !byPing else category = i
                    paintCats()
                    reload()
                }
            }
            catViews += v
            catRow.addView(v, LinearLayout.LayoutParams(0, dp(34), if (i == 4) 1.2f else 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        paintCats()
        serverViews += catRow.also { serverPage.addView(it) }
        serverViews += actionRow(
            // Обновить подписки: свежий список серверов и повторный пинг.
            chip(R.string.vpn_refresh) { refreshSubscriptions() },
            chip(R.string.split_title) { SplitTunnel.show(context) },
            chip(R.string.vpn_more) { serverMore() },
        ).also { serverPage.addView(it) }
        // Панель режима выбора: «Выбрано N · Все · Удалить · Отмена».
        val bar2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            visibility = View.GONE
        }
        val count = TextView(context).apply {
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        bar2.addView(count, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar2.addView(chip(R.string.vpn_select_all) {
            if (checked.size == items.size) checked.clear() else items.forEach { checked += it.id }
            updateSelectCount()
            adapter.notifyDataSetChanged()
        }, chipLp())
        bar2.addView(chip(R.string.delete) { deleteChecked() }, chipLp())
        bar2.addView(chip(android.R.string.cancel) { setSelecting(false) }, chipLp())
        serverPage.addView(bar2)
        selectBar = bar2
        selectCount = count

        empty.setText(R.string.vpn_empty)
        empty.setTextColor(secondary)
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(24), dp(24), dp(24), dp(24))
        serverPage.addView(empty)
        serverViews += list
        list.adapter = adapter
        list.divider = null
        list.setPadding(dp(8), 0, dp(8), dp(12))
        list.clipToPadding = false
        // Ползунок быстрой прокрутки справа - удобно листать длинные подписки.
        list.isFastScrollEnabled = true
        list.isFastScrollAlwaysVisible = false
        list.setOnItemClickListener { _, _, position, _ ->
            val p = items[position]
            if (selecting) {
                if (!checked.remove(p.id)) checked += p.id
                updateSelectCount()
                adapter.notifyDataSetChanged()
            } else {
                select(p)
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (selecting) {
                val p = items[position]
                if (!checked.remove(p.id)) checked += p.id
                updateSelectCount()
                adapter.notifyDataSetChanged()
            } else {
                itemActions(items[position])
            }
            true
        }
        serverPage.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Отступы под строку состояния и кнопки телефона: справка и кнопки внизу больше не уходят под панель.
        // Шапка уходит под строку состояния (цветной фон до самого верха), низ - над кнопками телефона.
        root.fitSystemBars(top = false)
        // Планшет / альбомная ориентация: экран VPN не растягивается на всю ширину, а стоит по центру.
        dialog.setContentView(com.stoyko.smessenger.ui.Adaptive.centered(context, root, maxDp = if (twoPane) 1180 else 760, bg = bg))
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.setOnDismissListener {
            VpnState.unlisten(stateListener)
            BgJobs.unlisten(bgListener)
            if (current === this) current = null
        }
        VpnState.listen(stateListener)
        BgJobs.listen(bgListener)
        applyUiMode()
        // Переключатель «Самый быстрый» - тоже только для серверов.
        renderMode()
        reload()
        dialog.show()
        main.post(ticker)
        // Первое открытие VPN - обучение: режимы, как получить, белые списки.
        main.postDelayed({ if (dialog.isShowing) VpnTutorial.showOnce(context) }, 450)
        // Как в Happ: при открытии сразу проверяем пинг и сортируем (если включён автовыбор).
        if (store.mode == VpnStore.MODE_SERVER && autoFastest && items.isNotEmpty() && VpnState.status != VpnState.Status.Connecting) pingAll()
    }

    /** «Ещё» на вкладке серверов: редкие действия и переключатели. */
    private fun serverMore() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        var dlg: android.app.Dialog? = null
        fun item(text: Int, click: () -> Unit) = com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(text)) {
            dlg?.dismiss()
            click()
        }.also { box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) }) }
        item(R.string.vpn_delete_dead) { deleteDead() }
        com.stoyko.smessenger.ui.Btn.tonal(context, com.stoyko.smessenger.ai.t("🧹 Подписки и мёртвые ссылки", "🧹 Subscriptions & dead links")) {
            dlg?.dismiss()
            manageSubscriptions()
        }.also { box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) }) }
        com.stoyko.smessenger.ui.Btn.tonal(context, tt("📂 Импорт из файла (.yaml .conf .ovpn .json .txt)", "📂 Import file (.yaml .conf .ovpn .json .txt)")) {
            dlg?.dismiss()
            pickFile()
        }.also { box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) }) }
        com.stoyko.smessenger.ui.Btn.tonal(context, tt("🔐 OpenVPN-профили", "🔐 OpenVPN profiles")) {
            dlg?.dismiss()
            showOvpn()
        }.also { box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) }) }
        // DNS через HTTPS и маскировка TLS для серверов (к режимам «Обход DPI»/«WARP» не относится).
        item(R.string.vpn_tweaks_btn) { VpnTweaks.showDialog(context) }
        item(R.string.vpn_match_button) { VpnNodeMatch.start(context) { if (dialog.isShowing) reload() } }
        item(R.string.vpn_select_many) { setSelecting(true) }
        item(R.string.vpn_help_btn) { showHelp(VpnStore.MODE_SERVER) }
        // Совместный режим: подключение к серверу через обход DPI.
        box.addView(comboSwitch(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        box.addView(
            SwitchCompat(context).apply {
                setText(R.string.vpn_auto_remove_dead)
                textSize = 14f
                isChecked = store.autoRemoveDead
                setOnCheckedChangeListener { _, c ->
                    store.autoRemoveDead = c
                    if (c) removeDeadNow(silent = false)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
        )
        box.addView(
            SwitchCompat(context).apply {
                setText(R.string.vpn_auto_fastest)
                textSize = 14f
                isChecked = autoFastest
                setOnCheckedChangeListener { _, c ->
                    autoFastest = c
                    if (c) {
                        applyAutoFastest()
                        reload()
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
        )
        box.addView(hint(R.string.vpn_speed_hint))
        dlg = AlertDialog.Builder(context)
            .setTitle(R.string.vpn_more_title)
            .setView(android.widget.ScrollView(context).apply { addView(box) })
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- Шапка-панель: кнопка, статус, текущий сервер, сводка ----------

    private fun tt(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)

    /**
     * Невысокий экран (Tecno, J8 и т.п.) или крупный шрифт в системе - всё компактнее,
     * чтобы кнопка, сводка и список помещались без прокрутки шапки.
     */
    private val shortScreen: Boolean
        get() = context.resources.let { r ->
            val h = r.configuration.screenHeightDp.takeIf { it > 0 } ?: (r.displayMetrics.heightPixels / r.displayMetrics.density).toInt()
            h < 680 || (r.configuration.fontScale >= 1.25f && h < 820)
        }

    /** Две колонки: планшет, раскладушка или телефон боком. */
    private val twoPane: Boolean by lazy {
        val c = context.resources.configuration
        val w = c.screenWidthDp
        val h = c.screenHeightDp
        (w >= 600 && w > h * 1.15f) || w >= 840
    }

    private fun buildHero(): View {
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = heroBg
            setPadding(0, 0, 0, dp(14))
        }
        val r = dp(28).toFloat()
        // Одна колонка - скруглён низ; две колонки - правый край.
        heroBg.cornerRadii = if (twoPane) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), 0)
        }
        bar.addView(iconButton(R.drawable.ic_back) { dialog.safeDismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), 0, 0, 0)
                addView(TextView(context).apply {
                    text = "S-VPN"
                    textSize = 19f
                    letterSpacing = 0.04f
                    paint.isFakeBoldText = true
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = tt("Защищённое подключение", "Secure connection")
                    textSize = 11.5f
                    setTextColor(0xB3FFFFFF.toInt())
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(iconButton(R.drawable.ic_help_circle) { VpnTutorial.show(context) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(iconButton(R.drawable.ic_settings) { showOptions() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        hero.addView(bar)

        // Кнопка + статус.
        power.setOnClickListener { toggle() }
        power.contentDescription = context.getString(R.string.vpn_title)
        com.stoyko.smessenger.ui.Kinetics.squish(power, 0.94f)
        val orbSize = if (shortScreen) 132 else 168
        hero.addView(power, LinearLayout.LayoutParams(dp(orbSize), dp(orbSize)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        statusText.textSize = 20f
        statusText.paint.isFakeBoldText = true
        statusText.gravity = Gravity.CENTER
        statusText.setTextColor(Color.WHITE)
        hero.addView(statusText, VpnUi.matchWrap())
        serverText.textSize = 13f
        serverText.gravity = Gravity.CENTER
        serverText.maxLines = 2
        serverText.setTextColor(0xCCFFFFFF.toInt())
        serverText.setPadding(dp(24), dp(2), dp(24), 0)
        hero.addView(serverText, VpnUi.matchWrap())

        // Текущий сервер / режим - «таблетка», по нажатию - список.
        serverPill.orientation = LinearLayout.HORIZONTAL
        serverPill.gravity = Gravity.CENTER_VERTICAL
        serverPill.setPadding(dp(12), dp(10), dp(12), dp(10))
        VpnUi.pressable(serverPill, VpnUi.rounded(0x1FFFFFFF, dp(18).toFloat(), dp(1), 0x26FFFFFF), 0x40FFFFFF)
        pillFlag.textSize = 20f
        pillFlag.gravity = Gravity.CENTER
        pillFlag.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
        serverPill.addView(pillFlag, LinearLayout.LayoutParams(dp(40), dp(40)))
        val pt = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        pillName.textSize = 15f
        pillName.maxLines = 1
        pillName.ellipsize = android.text.TextUtils.TruncateAt.END
        pillName.paint.isFakeBoldText = true
        pillName.setTextColor(Color.WHITE)
        pillMeta.textSize = 12f
        pillMeta.maxLines = 1
        pillMeta.ellipsize = android.text.TextUtils.TruncateAt.END
        pillMeta.setTextColor(0xB3FFFFFF.toInt())
        pt.addView(pillName)
        pt.addView(pillMeta)
        serverPill.addView(pt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pillPing.textSize = 12f
        pillPing.paint.isFakeBoldText = true
        pillPing.setPadding(dp(8), dp(3), dp(8), dp(3))
        serverPill.addView(pillPing)
        serverPill.addView(TextView(context).apply {
            text = "›"
            textSize = 22f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(8), 0, 0, dp(2))
        })
        serverPill.setOnClickListener { openCurrentTarget() }
        hero.addView(serverPill, VpnUi.matchWrap().apply { setMargins(dp(16), dp(14), dp(16), 0) })

        // Сводка: режим · пинг · охват (охват - нажать и выбрать).
        statsRow.orientation = LinearLayout.HORIZONTAL
        fun stat(label: String, value: TextView, click: () -> Unit): View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            VpnUi.pressable(this, VpnUi.rounded(0x14FFFFFF, dp(14).toFloat()), 0x40FFFFFF)
            addView(TextView(context).apply {
                text = label
                textSize = 10.5f
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setTextColor(0x99FFFFFF.toInt())
            })
            value.textSize = 13.5f
            value.gravity = Gravity.CENTER
            value.maxLines = 1
            value.ellipsize = android.text.TextUtils.TruncateAt.END
            value.paint.isFakeBoldText = true
            value.setTextColor(Color.WHITE)
            addView(value, VpnUi.matchWrap())
            setOnClickListener { click() }
        }
        val cells = listOf(
            stat(tt("РЕЖИМ", "MODE"), statMode) { pickModeDialog() },
            stat(tt("ПИНГ", "PING"), statPing) { if (store.mode == VpnStore.MODE_SERVER) pingAll() else openCurrentTarget() },
            stat(tt("ОХВАТ", "SCOPE"), statScope) { pickScopeDialog() },
        )
        cells.forEachIndexed { i, v ->
            statsRow.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        hero.addView(statsRow, VpnUi.matchWrap().apply { setMargins(dp(16), dp(10), dp(16), 0) })

        // Обзор / все режимы.
        hero.addView(uiSwitch, VpnUi.matchWrap().apply { setMargins(dp(16), dp(12), dp(16), 0) })
        hero.fitSystemBars(bottom = false, ime = false)
        return hero
    }

    /** Нажали на текущий сервер: открываем полный список (или вкладку работающего режима). */
    private fun openCurrentTarget() {
        if (!advanced) {
            advanced = true
            applyUiMode()
        }
        showTab(store.mode)
    }

    private fun modeTitle(key: String): String = when {
        !advanced && autoMode -> tt("Авто", "Auto")
        else -> when (key) {
            VpnStore.MODE_SERVER -> context.getString(R.string.vpn_mode_server_short)
            VpnStore.MODE_DPI -> context.getString(R.string.vpn_mode_dpi_short)
            VpnStore.MODE_FRAGMENT -> context.getString(R.string.vpn_mode_fragment_short)
            VpnStore.MODE_WARP -> context.getString(R.string.vpn_mode_warp_short)
            else -> key
        }
    }

    private fun pickModeDialog() {
        val keys = listOf("auto", VpnStore.MODE_SERVER, VpnStore.MODE_WARP, VpnStore.MODE_DPI, VpnStore.MODE_FRAGMENT)
        val labels = arrayOf(
            "⚡ " + tt("Авто - приложение выберет само", "Auto - the app decides"),
            "🌐 " + context.getString(R.string.vpn_mode_server_short) + " · " + tt("свои и встроенные", "yours & built-in"),
            "☁️ WARP · Cloudflare / AmneziaWG",
            "🛡 " + context.getString(R.string.vpn_mode_dpi_short) + " · " + tt("без сервера", "no server"),
            "🧩 " + context.getString(R.string.vpn_mode_fragment_short) + " · " + tt("дробление пакетов", "packet split"),
        )
        val cur = if (autoMode && !advanced) 0 else keys.indexOf(store.mode).coerceAtLeast(1)
        com.stoyko.smessenger.ui.DialogTracker.add(
            AlertDialog.Builder(context)
                .setTitle(tt("Способ подключения", "Connection method"))
                .setSingleChoiceItems(labels, cur) { d, w ->
                    d.dismiss()
                    chooseMode(keys[w])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    /** Выбор способа: «auto» или режим. Если VPN работает - переподключаемся. */
    private fun chooseMode(key: String) {
        if (key == "auto") {
            autoMode = true
            if (advanced) {
                advanced = false
                applyUiMode()
            }
            VpnAuto.prepare(context, store)
        } else {
            autoMode = false
            store.mode = key
        }
        if (VpnState.status == VpnState.Status.On || VpnState.status == VpnState.Status.Connecting) {
            if (store.ready()) SmVpnService.start(context) else SmVpnService.stop(context)
        }
        if (advanced) renderMode() else buildSimple(animate = false)
        render()
    }

    private fun pickScopeDialog() {
        val labels = arrayOf(
            context.getString(R.string.vpn_scope_whole) + " - " + tt("все приложения", "all apps"),
            context.getString(R.string.vpn_scope_app) + " - " + tt("остальное напрямую", "the rest goes direct"),
        )
        com.stoyko.smessenger.ui.DialogTracker.add(
            AlertDialog.Builder(context)
                .setTitle(tt("Что пускать через VPN", "What goes through VPN"))
                .setSingleChoiceItems(labels, if (store.fullTunnel) 0 else 1) { d, w ->
                    d.dismiss()
                    applyScope(w == 0)
                }
                .setNeutralButton(R.string.split_title) { _, _ -> SplitTunnel.show(context) }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    private fun applyScope(full: Boolean) {
        if (store.fullTunnel == full) return
        store.fullTunnel = full
        Toast.makeText(context, if (full) R.string.vpn_scope_whole_on else R.string.vpn_scope_app_on, Toast.LENGTH_SHORT).show()
        // VPN уже включён - переподключаем с новым охватом (весь телефон требует разрешения системы).
        if (VpnState.status == VpnState.Status.On || VpnState.status == VpnState.Status.Connecting) connect()
        render()
        if (!advanced) buildSimple(animate = false)
    }

    /** Шапка под состояние: цвет фона, кнопка, текущий сервер и сводка. */
    private fun renderHero() {
        val st = VpnState.status
        power.state = when (st) {
            VpnState.Status.On -> PowerOrb.State.On
            VpnState.Status.Connecting -> PowerOrb.State.Connecting
            VpnState.Status.Error -> PowerOrb.State.Error
            VpnState.Status.Off -> PowerOrb.State.Off
        }
        heroBg.colors = when (st) {
            VpnState.Status.On -> intArrayOf(0xFF0B2E3A.toInt(), 0xFF0F4C47.toInt(), 0xFF173A63.toInt())
            VpnState.Status.Error -> intArrayOf(0xFF3A1020.toInt(), 0xFF4A1830.toInt(), 0xFF2A1A55.toInt())
            else -> intArrayOf(0xFF1E1650.toInt(), 0xFF2E2378.toInt(), 0xFF173A63.toInt())
        }
        val mode = store.mode
        val p = if (mode == VpnStore.MODE_SERVER) {
            store.profiles().let { l -> l.firstOrNull { it.id == store.selectedId } ?: l.firstOrNull() }
        } else {
            null
        }
        when {
            p != null -> {
                pillFlag.text = VpnUi.flagOf(p.name, p.protocol)
                pillName.text = VpnUi.cleanName(p.name)
                pillMeta.text = "${p.protocol.uppercase()} · ${p.host}"
            }
            mode == VpnStore.MODE_WARP -> {
                pillFlag.text = "☁️"
                val nm = WarpSeeds.name(context, store.warpConf)
                pillName.text = "WARP" + (nm?.let { " · $it" } ?: "")
                pillMeta.text = Regex("""(?im)^\s*Endpoint\s*=\s*(\S+)""").find(store.warpConf)?.groupValues?.get(1)
                    ?: tt("Нет конфига - нажмите, чтобы выбрать", "No config - tap to choose")
            }
            mode == VpnStore.MODE_DPI || mode == VpnStore.MODE_FRAGMENT -> {
                pillFlag.text = if (mode == VpnStore.MODE_DPI) "🛡" else "🧩"
                pillName.text = modeTitle(mode)
                pillMeta.text = store.targetName(context).ifBlank { tt("Без сервера, трафик идёт напрямую", "No server, traffic goes direct") }
            }
            else -> {
                pillFlag.text = "➕"
                pillName.text = context.getString(R.string.vpn_no_server)
                pillMeta.text = tt("Нажмите, чтобы выбрать или добавить", "Tap to choose or add")
            }
        }
        val ping = p?.ping ?: 0L
        if (p != null && p.id in pinging) {
            pillPing.text = "• • •"
            pillPing.setTextColor(Color.WHITE)
            pillPing.background = VpnUi.rounded(0x26FFFFFF, dp(10).toFloat())
        } else if (p != null && ping != 0L) {
            val c = VpnUi.pingColor(ping)
            pillPing.text = if (ping < 0) "✕" else context.getString(R.string.vpn_ms, ping)
            pillPing.setTextColor(Color.WHITE)
            pillPing.background = VpnUi.rounded(c, dp(10).toFloat())
        } else {
            pillPing.text = ""
            pillPing.background = null
        }
        statMode.text = modeTitle(mode)
        statPing.text = when {
            pinging.isNotEmpty() && pingTotal > 1 -> "${pingTotal - pinging.size}/$pingTotal"
            p == null -> "—"
            p.id in pinging -> "…"
            ping > 0 -> context.getString(R.string.vpn_ms, ping)
            ping < 0 -> "✕"
            else -> tt("проверить", "check")
        }
        statPing.setTextColor(if (p != null && ping > 0) lighten(VpnUi.pingColor(ping)) else Color.WHITE)
        statScope.text = if (store.fullTunnel) tt("Телефон", "Phone") else tt("Мессенджер", "Messenger")
    }

    private fun lighten(c: Int): Int = Color.rgb(
        (Color.red(c) + 255) / 2,
        (Color.green(c) + 255) / 2,
        (Color.blue(c) + 255) / 2,
    )

    // ---------- Обычный режим: максимум автоматики ----------

    /** Продвинутый вид (все вкладки и настройки) или обычный (всё само). */
    private var advanced: Boolean
        get() = prefs.getBoolean("advanced", false)
        set(v) = prefs.edit().putBoolean("advanced", v).apply()

    /** «Авто»: при включении приложение само выбирает, чем подключаться. */
    private var autoMode: Boolean
        get() = prefs.getBoolean("auto_mode", true)
        set(v) = prefs.edit().putBoolean("auto_mode", v).apply()

    private val advancedBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val simpleScroll = android.widget.ScrollView(context).apply { isFillViewport = true }
    private val simpleBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(24))
    }
    private var nodesStatus: TextView? = null
    private var dpiStatus: TextView? = null
    private val bgListener: () -> Unit = { updateBgLabels() }

    private val uiSwitch: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = VpnUi.rounded(0x1FFFFFFF, dp(22).toFloat())
            listOf(
                false to tt("🏠 Обзор", "🏠 Overview"),
                true to tt("🗂 Серверы и режимы", "🗂 Servers & modes"),
            ).forEach { (adv, label) ->
                addView(
                    TextView(context).apply {
                        tag = adv
                        text = label
                        textSize = 13f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(6), 0, dp(6), 0)
                        setOnClickListener {
                            if (advanced != adv) {
                                advanced = adv
                                applyUiMode()
                            }
                        }
                        com.stoyko.smessenger.ui.Kinetics.squish(this, 0.94f)
                    },
                    LinearLayout.LayoutParams(0, dp(38), 1f),
                )
            }
        }
    }

    private fun applyUiMode() {
        val adv = advanced
        for (i in 0 until uiSwitch.childCount) {
            val c = uiSwitch.getChildAt(i) as TextView
            val on = c.tag == adv
            c.setTextColor(if (on) 0xFF2E2378.toInt() else 0xD9FFFFFF.toInt())
            c.paint.isFakeBoldText = on
            c.background = if (on) VpnUi.rounded(Color.WHITE, dp(19).toFloat()) else null
        }
        // Список серверов - шапка ужимается, чтобы было видно больше строк (в две колонки - не нужно).
        val compact = adv && !twoPane
        val small = compact || shortScreen
        val size = dp(if (compact) 96 else if (shortScreen) 132 else 168)
        power.layoutParams = (power.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = size
            height = size
        }
        statsRow.visibility = if (compact) View.GONE else View.VISIBLE
        serverText.visibility = if (small && compact) View.GONE else View.VISIBLE
        power.requestLayout()
        advancedBox.visibility = if (adv) View.VISIBLE else View.GONE
        simpleScroll.visibility = if (adv) View.GONE else View.VISIBLE
        if (!adv) buildSimple() else renderMode()
        render()
    }

    private fun buildSimple(animate: Boolean = true) {
        val scrollY = simpleScroll.scrollY
        simpleBox.removeAllViews()
        val ps = store.profiles()
        val alive = ps.count { it.ping > 0 }

        // 1) Способ подключения: 4 карточки с пояснением, активная - с рамкой и галочкой.
        simpleBox.addView(VpnUi.section(context, tt("Способ подключения", "Connection method"), secondary))
        val current = if (autoMode) "auto" else store.mode
        val warpN = WarpSeeds.count(context)
        val modes = listOf(
            Triple("auto", "⚡", tt("Авто", "Auto") to tt("Подберёт лучшее само", "Picks the best itself")),
            Triple(VpnStore.MODE_SERVER, "🌐", context.getString(R.string.vpn_mode_server_short) to tt("$alive из ${ps.size} на связи", "$alive of ${ps.size} online")),
            Triple(VpnStore.MODE_WARP, "☁️", "WARP" to tt("$warpN готовых конфигов", "$warpN ready configs")),
            Triple(VpnStore.MODE_DPI, "🛡", context.getString(R.string.vpn_mode_dpi_short) to tt("Без сервера, против DPI", "No server, beats DPI")),
        )
        val modeViews = modes.map { (key, emoji, texts) ->
            modeCard(emoji, texts.first, texts.second, key == current || (key == VpnStore.MODE_DPI && current == VpnStore.MODE_FRAGMENT)) {
                chooseMode(key)
            }
        }
        simpleBox.addView(gridOf(modeViews, 2, 8))
        simpleBox.addView(hint(if (autoMode) R.string.vpn_simple_auto_hint else R.string.vpn_simple_manual_hint).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        // 2) Быстрые действия - сетка значков.
        simpleBox.addView(VpnUi.section(context, tt("Быстрые действия", "Quick actions"), secondary))
        val actions = card().apply { setPadding(dp(6), dp(10), dp(6), dp(10)) }
        actions.addView(
            gridOf(
                listOf(
                    actionTile("📋", context.getString(R.string.vpn_paste), 0xFF5B4BDB.toInt()) { pasteFromClipboard() },
                    actionTile("📂", tt("Из файла", "From file"), 0xFF0284C7.toInt()) { pickFile() },
                    actionTile("➕", context.getString(R.string.vpn_add), 0xFF0EA5E9.toInt()) { addDialog() },
                    actionTile("🔄", tt("Обновить", "Refresh"), 0xFF10B981.toInt()) { refreshSubscriptions() },
                    actionTile("📶", tt("Пинг всех", "Ping all"), 0xFFF59E0B.toInt()) { pingAll() },
                    actionTile("☁️", tt("WARP-конфиги", "WARP configs"), 0xFF6366F1.toInt()) { pickWarpSeed() },
                    actionTile("🔐", "OpenVPN", 0xFFEA580C.toInt()) { showOvpn() },
                    actionTile("🗂", tt("Подписки", "Subscriptions"), 0xFF0891B2.toInt()) { manageSubscriptions() },
                    actionTile("📱", tt("Приложения", "Apps"), 0xFFEC4899.toInt()) { SplitTunnel.show(context) { buildSimple(false) } },
                    actionTile("🌍", tt("Сайты напрямую", "Direct sites"), 0xFF14B8A6.toInt()) { DomainBypass.show(context) },
                    actionTile("🧪", tt("DNS и обход", "DNS & bypass"), 0xFF64748B.toInt()) { VpnTweaks.showDialog(context) },
                    actionTile("📡", tt("Раздача", "Share"), 0xFF8B5CF6.toInt()) { Gateway.show(context) },
                ),
                4,
                4,
            ),
        )
        simpleBox.addView(actions, VpnUi.matchWrap())

        // 3) Лучшие серверы: пять самых быстрых, нажать - выбрать.
        val top = ps.filter { it.ping > 0 }.sortedBy { it.ping }.take(5).ifEmpty { ps.take(5) }
        simpleBox.addView(VpnUi.section(context, tt("Лучшие серверы", "Best servers"), secondary))
        val srv = card().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        if (top.isEmpty()) {
            srv.addView(hint(R.string.vpn_simple_no_servers).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) })
        } else {
            top.forEach { srv.addView(serverRow(it), VpnUi.matchWrap()) }
        }
        srv.addView(
            TextView(context).apply {
                text = tt("Все серверы и подписки (${ps.size})  ›", "All servers & subscriptions (${ps.size})  ›")
                textSize = 14f
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                setTextColor(VpnUi.ACCENT)
                setPadding(0, dp(12), 0, dp(12))
                VpnUi.pressable(this, VpnUi.rounded(0x145B4BDB, dp(14).toFloat()))
                setOnClickListener {
                    advanced = true
                    applyUiMode()
                    showTab(VpnStore.MODE_SERVER)
                }
            },
            VpnUi.matchWrap().apply { setMargins(dp(6), dp(6), dp(6), dp(4)) },
        )
        simpleBox.addView(srv, VpnUi.matchWrap())

        // 4) Подбор в фоне - с полосой прогресса в шторке.
        simpleBox.addView(VpnUi.section(context, tt("Автоподбор в фоне", "Background search"), secondary))
        val bg = card()
        bg.addView(hint(R.string.vpn_simple_bg_hint))
        val nodesBtn = com.stoyko.smessenger.ui.Btn.tile(context, "🔗", context.getString(R.string.vpn_simple_pick_nodes)) {
            // Остановка - мгновенная: флаг, прерывание проверок в фоне, надпись «Останавливается…».
            if (BgJobs.isRunning(BgJobs.NODES)) BgJobs.cancel(context, BgJobs.NODES) else VpnNodeMatch.startInBackground(context)
            updateBgLabels()
        }
        val dpiBtn = com.stoyko.smessenger.ui.Btn.tile(context, "🛡", context.getString(R.string.vpn_simple_pick_dpi)) {
            when {
                BgJobs.isRunning(BgJobs.DPI) -> BgJobs.cancel(context, BgJobs.DPI)
                // Прошлый подбор ещё дозавершается (доли секунды после «Остановить»).
                DpiBenchmark.running -> Toast.makeText(context, R.string.dpi_bg_running, Toast.LENGTH_SHORT).show()
                DpiBenchmark.runInBackground(context) -> Toast.makeText(context, R.string.dpi_bg_started, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(context, R.string.dpi_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        bg.addView(
            com.stoyko.smessenger.ui.Btn.grid(context, listOf(nodesBtn, dpiBtn), cols = 2, height = 72),
            VpnUi.matchWrap().apply { topMargin = dp(8) },
        )
        nodesStatus = hint(0, "").also { bg.addView(it) }
        dpiStatus = hint(0, "").also { bg.addView(it) }
        simpleBox.addView(bg, VpnUi.matchWrap())

        // 5) Автоматика - переключатели.
        simpleBox.addView(VpnUi.section(context, tt("Автоматика", "Automation"), secondary))
        val auto = card().apply { setPadding(dp(16), dp(6), dp(12), dp(6)) }
        fun sw(emoji: String, text: String, value: Boolean, set: (Boolean) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply { this.text = emoji; textSize = 17f; setPadding(0, 0, dp(12), 0) })
            val s = SwitchCompat(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(textColor)
                isChecked = value
                setOnCheckedChangeListener { _, c -> set(c) }
            }
            row.addView(s, LinearLayout.LayoutParams(0, dp(50), 1f))
            if (auto.childCount > 0) {
                auto.addView(View(context).apply { setBackgroundColor(0x14808080) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            auto.addView(row, VpnUi.matchWrap())
        }
        sw("🏎", context.getString(R.string.vpn_auto_fastest), autoFastest) { autoFastest = it; if (it) applyAutoFastest() }
        sw("🧹", context.getString(R.string.vpn_auto_remove_dead), store.autoRemoveDead) { store.autoRemoveDead = it }
        sw("💬", context.getString(R.string.vpn_route_tox), store.routeTox) { store.routeTox = it }
        sw("🔁", tt("Авто-переподключение при обрыве", "Auto-reconnect on drops"), VpnWatchdog.enabled(context)) { VpnWatchdog.setEnabled(context, it) }
        simpleBox.addView(auto, VpnUi.matchWrap())
        simpleBox.addView(hint(R.string.vpn_simple_footer).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), 0) })
        updateBgLabels()
        if (animate) {
            com.stoyko.smessenger.ui.Btn.animateIn((0 until simpleBox.childCount).map { simpleBox.getChildAt(it) })
        } else {
            simpleScroll.post { simpleScroll.scrollTo(0, scrollY) }
        }
    }

    /** Ровная сетка: [cols] колонок, ячейки одной ширины, последняя строка добита пустыми. */
    private fun gridOf(views: List<View>, cols: Int, gap: Int): LinearLayout {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(cols).forEachIndexed { r, chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val v = chunk.getOrNull(i) ?: View(context)
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(gap) })
            }
            box.addView(row, VpnUi.matchWrap().apply { if (r > 0) topMargin = dp(gap) })
        }
        return box
    }

    /** Карточка способа подключения. */
    private fun modeCard(emoji: String, title: String, sub: String, on: Boolean, click: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            val bgd = if (on) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6D5DF0.toInt(), 0xFF4B3CCB.toInt())).apply { cornerRadius = dp(18).toFloat() }
            } else {
                VpnUi.rounded(surface, dp(18).toFloat(), dp(1), 0x1F808080)
            }
            VpnUi.pressable(this, bgd, if (on) 0x40FFFFFF else 0x335B4BDB)
            elevation = if (on) dp(3).toFloat() else dp(1).toFloat()
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                TextView(context).apply {
                    text = emoji
                    textSize = 18f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (on) 0x33FFFFFF else 0x1A5B4BDB)
                    }
                },
                LinearLayout.LayoutParams(dp(36), dp(36)),
            )
            head.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            if (on) head.addView(VpnUi.pill(context, "✓ " + tt("Выбран", "Active"), 0xFF22C55E.toInt(), solid = true))
            addView(head, VpnUi.matchWrap())
            addView(TextView(context).apply {
                text = title
                textSize = 15.5f
                maxLines = 1
                paint.isFakeBoldText = true
                setTextColor(if (on) Color.WHITE else textColor)
                setPadding(0, dp(10), 0, 0)
            })
            addView(TextView(context).apply {
                text = sub
                textSize = 12f
                maxLines = 2
                setTextColor(if (on) 0xD9FFFFFF.toInt() else secondary)
                setPadding(0, dp(2), 0, 0)
            })
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                click()
            }
        }

    /** Значок действия: цветной кружок и подпись под ним. */
    private fun actionTile(emoji: String, label: String, color: Int, click: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(6))
            VpnUi.pressable(this, VpnUi.rounded(Color.TRANSPARENT, dp(14).toFloat()), (color and 0x00FFFFFF) or 0x33000000)
            addView(VpnUi.badge(context, emoji, color, if (shortScreen) 42 else 48))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setLineSpacing(0f, 0.92f)
                    setTextColor(textColor)
                    setPadding(0, dp(6), 0, 0)
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this, 9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)),
            )
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                click()
            }
        }

    /** Строка сервера на обзоре: флаг, имя, протокол, пинг. */
    private fun serverRow(p: VpnProfile): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(9), dp(10), dp(9))
        val sel = p.id == store.selectedId && store.mode == VpnStore.MODE_SERVER
        VpnUi.pressable(this, VpnUi.rounded(if (sel) 0x1A5B4BDB else Color.TRANSPARENT, dp(14).toFloat()))
        addView(VpnUi.badge(context, VpnUi.flagOf(p.name, p.protocol), if (sel) VpnUi.GREEN else VpnUi.ACCENT, 38))
        val tx = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(TextView(context).apply {
                text = VpnUi.cleanName(p.name)
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                paint.isFakeBoldText = sel
                setTextColor(textColor)
            })
            addView(TextView(context).apply {
                text = p.protocol.uppercase() + " · " + p.host
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(secondary)
            })
        }
        addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val label = when {
            p.id in pinging -> "• • •"
            p.ping > 0 -> context.getString(R.string.vpn_ms, p.ping)
            p.ping < 0 -> "✕"
            else -> "—"
        }
        addView(VpnUi.pill(context, label, VpnUi.pingColor(p.ping)))
        if (sel) addView(TextView(context).apply { text = "✓"; textSize = 16f; setTextColor(VpnUi.GREEN); setPadding(dp(8), 0, 0, 0) })
        setOnClickListener {
            select(p)
            buildSimple(animate = false)
        }
    }

    private fun updateBgLabels() {
        fun line(id: Int, idle: Int): String {
            val st = BgJobs.state(id) ?: return context.getString(idle)
            if (st.finished && st.text == context.getString(R.string.bg_jobs_stopping)) return "⏹ " + st.text
            return if (st.finished) "✓ " + st.text.lineSequence().firstOrNull().orEmpty()
            else "⏳ ${st.done}/${st.total} · " + st.text.lineSequence().firstOrNull().orEmpty() + " · " + context.getString(R.string.vpn_simple_tap_stop)
        }
        nodesStatus?.text = "🔗 " + line(BgJobs.NODES, R.string.vpn_simple_idle_nodes)
        dpiStatus?.text = "🛡 " + line(BgJobs.DPI, R.string.vpn_simple_idle_dpi)
    }

    // ---------- Режимы: сервер / обход DPI / фрагментация / WARP ----------

    private val serverViews = ArrayList<View>()
    private var modeChips: LinearLayout? = null
    private val tabLabels = HashMap<String, String>()
    private val serverPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val modeScroll = android.widget.ScrollView(context).apply { isFillViewport = true }
    private val modePanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(24))
    }
    private val activeBanner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Открытая вкладка (что смотрим). Работает при этом всегда режим [VpnStore.mode]. */
    private var viewTab: String = store.mode

    private fun showTab(key: String) {
        if (viewTab == key) return
        viewTab = key
        renderMode()
        // Страница въезжает сбоку.
        val page = if (key == VpnStore.MODE_SERVER) serverPage else modeScroll
        if (com.stoyko.smessenger.ui.Kinetics.enabled) {
            page.alpha = 0f
            page.translationX = dp(24).toFloat()
            page.animate().alpha(1f).translationX(0f).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /**
     * Сделать режим рабочим. Работает только один: при включённом VPN старый режим
     * полностью останавливается (ядро, движок DPI, туннель WARP) и запускается новый.
     */
    private fun setMode(key: String) {
        if (store.mode == key) return
        store.mode = key
        renderMode()
        render()
        Toast.makeText(context, context.getString(R.string.vpn_mode_switched, tabLabels[key].orEmpty()), Toast.LENGTH_SHORT).show()
        if (VpnState.status == VpnState.Status.On || VpnState.status == VpnState.Status.Connecting) {
            if (store.ready()) SmVpnService.start(context) else SmVpnService.stop(context)
        }
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(surface)
        }
        elevation = dp(1).toFloat()
    }

    private fun cardLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(10)
    }

    private fun renderMode() {
        val mode = store.mode
        modeChips?.let { row ->
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i) as TextView
                val key = c.tag as String
                val open = key == viewTab
                val active = key == mode
                // Зелёная точка - режим, который сейчас работает.
                c.text = (if (active) "● " else "") + tabLabels[key].orEmpty()
                c.setTextColor(if (open) Color.WHITE else if (active) 0xFF10B981.toInt() else 0xFF5B4BDB.toInt())
                c.paint.isFakeBoldText = open || active
                c.background = if (open) {
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF7B6CF6.toInt(), 0xFF5B4BDB.toInt()))
                        .apply { cornerRadius = dp(19).toFloat() }
                } else {
                    null
                }
                if (open && com.stoyko.smessenger.ui.Kinetics.enabled) {
                    c.scaleX = 0.9f
                    c.scaleY = 0.9f
                    c.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                }
            }
        }
        val serverTab = viewTab == VpnStore.MODE_SERVER
        serverPage.visibility = if (serverTab) View.VISIBLE else View.GONE
        modeScroll.visibility = if (serverTab) View.GONE else View.VISIBLE
        // Плашка на вкладке серверов, если работает другой режим.
        activeBanner.removeAllViews()
        if (serverTab && mode != VpnStore.MODE_SERVER) {
            val c = card()
            c.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x1FF59E0B)
                setStroke(dp(1), 0x66F59E0B)
            }
            c.addView(TextView(context).apply {
                text = context.getString(R.string.vpn_other_mode_active, tabLabels[mode].orEmpty())
                textSize = 13f
                setTextColor(textColor)
                setPadding(0, 0, 0, dp(8))
            })
            c.addView(
                com.stoyko.smessenger.ui.Btn.primary(context, context.getString(R.string.vpn_use_servers)) { setMode(VpnStore.MODE_SERVER) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
            )
            activeBanner.addView(c)
        }
        // Серверы работают вместе с усилениями - показываем, с какими.
        if (serverTab && mode == VpnStore.MODE_SERVER && (store.serverViaDpi || VpnTweaks.fragment)) {
            val parts = listOfNotNull(
                if (store.serverViaDpi) context.getString(R.string.vpn_mode_dpi) else null,
                if (VpnTweaks.fragment) context.getString(R.string.vpn_mode_fragment) else null,
            )
            activeBanner.addView(
                TextView(context).apply {
                    text = context.getString(R.string.vpn_combo_active, parts.joinToString(" + "))
                    textSize = 12f
                    setTextColor(0xFF10B981.toInt())
                    paint.isFakeBoldText = true
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0x1A10B981)
                    }
                },
            )
        }
        if (serverTab) {
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        } else {
            selectBar?.visibility = View.GONE
        }
        modePanel.removeAllViews()
        if (serverTab) return
        when (viewTab) {
            VpnStore.MODE_DPI -> dpiPanel()
            VpnStore.MODE_FRAGMENT -> fragmentPanel()
            VpnStore.MODE_WARP -> warpPanel()
        }
        // Включить этот режим / он уже включён.
        val useBox = card()
        if (mode == viewTab) {
            useBox.addView(TextView(context).apply {
                text = context.getString(R.string.vpn_mode_is_active)
                textSize = 15f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(0xFF10B981.toInt())
            })
            useBox.addView(hint(R.string.vpn_mode_is_active_hint).apply { gravity = Gravity.CENTER })
        } else {
            useBox.addView(
                com.stoyko.smessenger.ui.Btn.primary(
                    context,
                    context.getString(R.string.vpn_use_mode),
                    intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt()),
                ) { setMode(viewTab) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
            )
            useBox.addView(hint(0, context.getString(R.string.vpn_use_mode_hint, tabLabels[mode].orEmpty())).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
        modePanel.addView(useBox, cardLp())
        // Общие для режимов без сервера настройки.
        val common = card()
        common.addView(title("⚙️ " + context.getString(R.string.vpn_common_title)))
        common.addView(
            chipRow(
                context,
                listOf(
                    chip(R.string.split_title) { SplitTunnel.show(context) },
                    chip(R.string.vpn_tweaks_btn) { VpnTweaks.showDialog(context) },
                ),
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        common.addView(
            chipRow(
                context,
                listOf(
                    com.stoyko.smessenger.ui.Btn.tonal(context, com.stoyko.smessenger.ai.t("🌐 Сайты напрямую", "🌐 Direct sites")) { DomainBypass.show(context) },
                    com.stoyko.smessenger.ui.Btn.tonal(context, com.stoyko.smessenger.ai.t("📡 Раздача", "📡 Share")) { Gateway.show(context) },
                ),
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        modePanel.addView(common, cardLp())
        modePanel.addView(helpCard(viewTab), cardLp())
        val kids = (0 until modePanel.childCount).map { modePanel.getChildAt(it) }
        com.stoyko.smessenger.ui.Btn.animateIn(kids)
    }

    /** «Что это и как настроить» - раскрывающаяся карточка с пошаговой инструкцией. */
    private fun helpCard(mode: String): View {
        val c = card()
        val body = TextView(context).apply {
            text = context.getString(helpRes(mode))
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(textColor)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        val head = TextView(context).apply {
            text = "ℹ️ " + context.getString(R.string.vpn_help_title) + "  ▾"
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(0xFF5B4BDB.toInt())
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                text = "ℹ️ " + context.getString(R.string.vpn_help_title) + if (open) "  ▴" else "  ▾"
                if (open) {
                    body.alpha = 0f
                    body.translationY = -dp(8).toFloat()
                    body.animate().alpha(1f).translationY(0f).setDuration(220).start()
                }
            }
        }
        c.addView(head)
        c.addView(body)
        return c
    }

    private fun helpRes(mode: String) = when (mode) {
        VpnStore.MODE_DPI -> R.string.vpn_help_dpi
        VpnStore.MODE_FRAGMENT -> R.string.vpn_help_fragment
        VpnStore.MODE_WARP -> R.string.vpn_help_warp
        else -> R.string.vpn_help_server
    }

    /** Справка: общее описание VPN и инструкция для вкладки. */
    private fun showHelp(mode: String) {
        val tv = TextView(context).apply {
            text = context.getString(R.string.vpn_about_modes) + "\n\n" + context.getString(helpRes(mode))
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setTextColor(textColor)
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.vpn_help_title)
            .setView(android.widget.ScrollView(context).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 16f
        paint.isFakeBoldText = true
        setTextColor(textColor)
    }

    private fun dpiPanel() {
        val c = card()
        val st = DpiStrategies.byId(store.dpiStrategy)
        c.addView(title("🛡 " + context.getString(R.string.vpn_mode_dpi)))
        c.addView(hint(R.string.dpi_mode_hint))
        c.addView(
            TextView(context).apply {
                text = context.getString(R.string.dpi_strategy_current, st.title, st.note)
                textSize = 13f
                setTextColor(textColor)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x125B4BDB)
                }
            },
        )
        c.addView(
            chipRow(
                context,
                listOf(
                    chip(R.string.dpi_auto) { autoSelectStrategy() },
                    chip(R.string.dpi_choose) { chooseStrategy() },
                    chip(R.string.dpi_custom) { customArgs() },
                ),
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
        )
        if (!DpiEngine.available(context)) c.addView(hint(R.string.dpi_unavailable))
        modePanel.addView(c, cardLp())

        // Стратегии из открытых источников.
        val net = card()
        net.addView(title("🌐 " + context.getString(R.string.dpi_sources_title)))
        val dl = DpiStrategies.all(context).size - DpiStrategies.list.size
        val upd = DpiSources.lastUpdate(context)
        net.addView(
            hint(
                0,
                if (dl > 0) {
                    context.getString(R.string.dpi_sources_have, dl, android.text.format.DateUtils.getRelativeTimeSpanString(upd))
                } else {
                    context.getString(R.string.dpi_sources_hint)
                },
            ),
        )
        net.addView(
            chipRow(
                context,
                listOf(
                    chip(R.string.dpi_sources_download) { downloadStrategies() },
                    chip(R.string.dpi_sources_manage) { manageSources() },
                ),
            ),
        )
        modePanel.addView(net, cardLp())

        // Совместный режим: свой сервер + обход DPI.
        val combo = card()
        combo.addView(title("🔗 " + context.getString(R.string.vpn_combo_title)))
        combo.addView(hint(R.string.vpn_combo_hint))
        combo.addView(comboSwitch())
        modePanel.addView(combo, cardLp())
    }

    /** Переключатель «Сервер + обход DPI». */
    private fun comboSwitch() = SwitchCompat(context).apply {
        setText(R.string.vpn_combo_switch)
        setTextColor(textColor)
        textSize = 14f
        isChecked = store.serverViaDpi
        setOnCheckedChangeListener { _, c ->
            store.serverViaDpi = c
            if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_SERVER) SmVpnService.start(context)
            renderMode()
        }
    }

    private fun downloadStrategies() {
        val wait = AlertDialog.Builder(context).setMessage(R.string.dpi_sources_loading).setCancelable(false).show()
        Thread {
            val r = runCatching { DpiSources.download(context) }
            main.post {
                runCatching { wait.dismiss() }
                val (fresh, total, errors) = r.getOrDefault(Triple(0, 0, listOf(r.exceptionOrNull()?.message ?: "?")))
                val msg = if (total > 0) {
                    context.getString(R.string.dpi_sources_done, total, fresh)
                } else {
                    context.getString(R.string.dpi_sources_failed)
                } + if (errors.isNotEmpty()) "\n\n" + errors.joinToString("\n") else ""
                AlertDialog.Builder(context)
                    .setTitle(R.string.dpi_sources_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.dpi_auto) { _, _ -> if (total > 0) autoSelectStrategy() }
                    .setNegativeButton(R.string.close, null)
                    .show()
                renderMode()
            }
        }.start()
    }

    private fun manageSources() {
        val field = EditText(context).apply {
            setHint(R.string.dpi_sources_custom_hint)
            setText(DpiSources.custom(context).joinToString("\n"))
            textSize = 13f
            minLines = 3
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(hint(0, context.getString(R.string.dpi_sources_builtin) + "\n" + DpiSources.BUILT_IN.joinToString("\n") { "• " + it.name }))
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.dpi_sources_manage)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                DpiSources.setCustom(context, field.text.toString().lines().map { it.trim() }.filter { it.startsWith("http") })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fragmentPanel() {
        val c = card()
        c.addView(title("🧩 " + context.getString(R.string.vpn_mode_fragment)))
        c.addView(hint(R.string.fragment_mode_hint))
        modePanel.addView(c, cardLp())
        // Совместно с сервером: фрагменты для подключения к своему серверу.
        val combo = card()
        combo.addView(title("🔗 " + context.getString(R.string.vpn_combo_title)))
        combo.addView(hint(R.string.vpn_combo_fragment_hint))
        combo.addView(
            chipRow(context, listOf(chip(R.string.vpn_tweaks_btn) { VpnTweaks.showDialog(context) })),
        )
        modePanel.addView(combo, cardLp())
    }

    private fun warpPanel() {
        val c = card()
        c.addView(title("☁️ " + context.getString(R.string.vpn_mode_warp)))
        c.addView(hint(R.string.warp_mode_hint))
        val nova = NovaLink.available(context)
        if (nova) warpEngineBlock(c)
        val masque = nova && store.warpEngine == VpnStore.WARP_MASQUE
        if (masque) {
            masqueBlock(c)
            modePanel.addView(c, cardLp())
            return
        }
        val conf = store.warpConf
        c.addView(
            TextView(context).apply {
                text = if (conf.isBlank()) {
                    context.getString(R.string.warp_none)
                } else {
                    context.getString(R.string.warp_ready, conf.lines().firstOrNull { it.trim().lowercase().startsWith("endpoint") }?.substringAfter('=')?.trim().orEmpty())
                }
                textSize = 13f
                setTextColor(textColor)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x125B4BDB)
                }
            },
        )
        c.addView(
            chipRow(
                context,
                listOf(
                    chip(R.string.warp_get) { generateWarp() },
                    chip(R.string.warp_new_endpoint) {
                        if (store.warpConf.isBlank()) {
                            generateWarp()
                        } else {
                            store.warpConf = Warp.withNewEndpoint(store.warpConf)
                            renderMode()
                            if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_WARP) SmVpnService.start(context)
                        }
                    },
                    chip(R.string.warp_import) { importWarp() },
                ),
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
        )
        // Готовые проверенные конфиги (из Nova): работают без генераторов и регистрации.
        val seeds = WarpSeeds.count(context)
        if (seeds > 0) {
            val idx = WarpSeeds.currentIndex(context, store.warpConf)
            c.addView(
                com.stoyko.smessenger.ui.Btn.tonal(
                    context,
                    com.stoyko.smessenger.ai.t("🧩 Готовый конфиг", "🧩 Ready config") + (if (idx > 0) " " + (WarpSeeds.name(context, store.warpConf)?.let { "«$it»" } ?: "$idx/$seeds") else " ($seeds)") + " ›",
                ) { pickWarpSeed() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) },
            )
            c.addView(hint(0, com.stoyko.smessenger.ai.t(
                "50 проверенных конфигураций WARP с маскировкой AmneziaWG. Не подключается - нажмите ещё раз (следующий), сторож VPN тоже сам перебирает их при обрыве.",
                "50 verified WARP configs with AmneziaWG masking. Doesn't connect - tap again (next one); the VPN watchdog also rotates them by itself when the link drops.",
            )))
        }
        modePanel.addView(c, cardLp())
    }

    /**
     * Выбор ядра WARP. Встроенный AmneziaWG работает в процессе приложения (переход с серверов
     * Xray на него - с быстрым перезапуском), ядро Nova - в своём процессе (без перезапуска).
     */
    private fun warpEngineBlock(c: LinearLayout) {
        val cur = store.warpEngine
        val t = { r: String, e: String -> com.stoyko.smessenger.ai.t(r, e) }
        fun pick(e: String) {
            if (store.warpEngine == e) return
            store.warpEngine = e
            renderMode()
            // Работающий WARP переключается сразу на выбранное ядро.
            if (store.mode == VpnStore.MODE_WARP && (VpnState.status == VpnState.Status.On || VpnState.status == VpnState.Status.Connecting) && store.ready()) {
                SmVpnService.start(context)
            }
        }
        val items = listOf(
            VpnStore.WARP_AWG to t("AmneziaWG", "AmneziaWG"),
            VpnStore.WARP_NOVA to t("Nova WG", "Nova WG"),
            VpnStore.WARP_MASQUE to "MASQUE",
        )
        val views = items.map { (id, label) ->
            if (id == cur) {
                com.stoyko.smessenger.ui.Btn.primary(context, "✓ $label") { pick(id) }.apply { textSize = 13f; minimumHeight = dp(44) }
            } else {
                com.stoyko.smessenger.ui.Btn.tonal(context, label) { pick(id) }.apply { textSize = 13f }
            }
        }
        c.addView(hint(0, t("Ядро WARP", "WARP engine")).apply { setPadding(paddingLeft, dp(10), paddingRight, dp(2)) })
        c.addView(chipRow(context, views, height = 46, gap = 6))
        c.addView(
            hint(
                0,
                when (cur) {
                    VpnStore.WARP_NOVA -> t(
                        "Ядро Nova в отдельном процессе: тот же конфиг WARP, маскировка AmneziaWG 3.x, переход с серверов на WARP и обратно - без перезапуска приложения.",
                        "Nova engine in a separate process: same WARP config, AmneziaWG 3.x masking, switching between servers and WARP without restarting the app.",
                    )
                    VpnStore.WARP_MASQUE -> t(
                        "Cloudflare MASQUE (HTTP/3): для оператора выглядит как обычный сайт и проходит там, где WireGuard режут. Тоже ядро Nova, без перезапуска приложения.",
                        "Cloudflare MASQUE (HTTP/3): looks like a regular website to the carrier and gets through where WireGuard is blocked. Also the Nova engine, no app restart.",
                    )
                    else -> t(
                        "Встроенное ядро AmneziaWG. Переход с серверов на WARP и обратно - с быстрым перезапуском приложения (два ядра Go в одном процессе несовместимы).",
                        "Built-in AmneziaWG engine. Switching between servers and WARP restarts the app quickly (two Go engines can't share one process).",
                    )
                },
            ),
        )
    }

    /** MASQUE: состояние ключа и его сброс. */
    private fun masqueBlock(c: LinearLayout) {
        val t = { r: String, e: String -> com.stoyko.smessenger.ai.t(r, e) }
        val has = store.masqueIdentity.isNotBlank()
        c.addView(
            TextView(context).apply {
                text = if (has) {
                    t("🔑 Ключ MASQUE получен - можно включать.", "🔑 MASQUE key obtained - ready to connect.")
                } else {
                    t(
                        "🔑 Ключа MASQUE ещё нет - он будет получен при первом включении (регистрация WARP в Cloudflare, 5-30 секунд). Если Cloudflare у оператора недоступен, сначала включите любой сервер - регистрация пройдёт через него.",
                        "🔑 No MASQUE key yet - it will be obtained on first connect (WARP registration with Cloudflare, 5-30 seconds). If Cloudflare is blocked by your carrier, turn on any server first - registration will go through it.",
                    )
                }
                textSize = 13f
                setTextColor(textColor)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x125B4BDB)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        if (has) {
            c.addView(
                com.stoyko.smessenger.ui.Btn.tonal(context, t("♻️ Получить новый ключ MASQUE", "♻️ Get a new MASQUE key")) {
                    AlertDialog.Builder(context)
                        .setMessage(t("Старый ключ будет забыт, новый выдаст Cloudflare при следующем включении. Нужно, если MASQUE перестал подключаться.", "The old key will be forgotten; Cloudflare issues a new one on the next connect. Use it if MASQUE stopped connecting."))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            store.resetMasque()
                            renderMode()
                            if (store.mode == VpnStore.MODE_WARP && VpnState.status == VpnState.Status.On) SmVpnService.start(context)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) },
            )
        }
    }

    /** Список готовых конфигов WARP: именные (FIX, AMFITON, OMSK, SHARP) сверху, затем остальные. */
    private fun pickWarpSeed() {
        val all = WarpSeeds.all(context)
        if (all.isEmpty()) return
        val cur = all.indexOf(store.warpConf)
        val labels = ArrayList<String>()
        labels += com.stoyko.smessenger.ai.t("🔁 Следующий по кругу", "🔁 Next in rotation")
        all.forEachIndexed { i, conf ->
            val ep = Regex("""(?im)^\s*Endpoint\s*=\s*(\S+)""").find(conf)?.groupValues?.get(1).orEmpty()
            val nm = WarpSeeds.name(context, conf)
            labels += (if (i == cur) "✅ " else if (nm != null) "⭐ " else "") + (nm ?: "#${i + 1}") +
                (if (nm != null && ep in nm) "" else "  ·  $ep")
        }
        val mine = WarpSeeds.user(context)
        com.stoyko.smessenger.ui.DialogTracker.add(
            AlertDialog.Builder(context)
                .setTitle(com.stoyko.smessenger.ai.t("🧩 Готовые конфиги WARP", "🧩 Ready WARP configs"))
                .setItems(labels.toTypedArray()) { _, w ->
                    if (w == 0) useWarpSeed() else applyWarpSeed(all[w - 1])
                }
                .setPositiveButton(tt("📂 Свой .conf / YAML", "📂 Own .conf / YAML")) { _, _ -> pickFile() }
                .apply {
                    if (mine.isNotEmpty()) {
                        setNeutralButton(tt("🗑 Свои (${mine.size})", "🗑 Mine (${mine.size})")) { _, _ ->
                            val checked = BooleanArray(mine.size)
                            com.stoyko.smessenger.ui.DialogTracker.add(
                                AlertDialog.Builder(context)
                                    .setTitle(tt("Удалить свои конфиги WARP", "Delete own WARP configs"))
                                    .setMultiChoiceItems(mine.map { it.first }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                                    .setPositiveButton(R.string.delete) { _, _ ->
                                        mine.forEachIndexed { i, (_, conf) -> if (checked[i]) WarpSeeds.removeUser(context, conf) }
                                        renderMode()
                                        render()
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .create(),
                            ).show()
                        }
                    }
                }
                .setNegativeButton(R.string.close, null)
                .create(),
        ).show()
    }

    private fun applyWarpSeed(conf: String) {
        val i = WarpSeeds.all(context).indexOf(conf)
        if (i >= 0) context.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putInt("warp_seed_i", i).apply()
        store.warpConf = conf
        Toast.makeText(
            context,
            com.stoyko.smessenger.ai.t("Готовый конфиг WARP: ", "Ready WARP config: ") + (WarpSeeds.name(context, conf) ?: "№${i + 1}"),
            Toast.LENGTH_SHORT,
        ).show()
        renderMode()
        render()
        if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_WARP) SmVpnService.start(context)
    }

    private fun useWarpSeed() {
        val conf = WarpSeeds.next(context) ?: return
        store.warpConf = conf
        Toast.makeText(context, com.stoyko.smessenger.ai.t("Готовый конфиг WARP: ", "Ready WARP config: ") + (WarpSeeds.name(context, conf) ?: "№" + WarpSeeds.currentIndex(context, conf)), Toast.LENGTH_SHORT).show()
        renderMode()
        render()
        if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_WARP) SmVpnService.start(context)
    }

    private fun generateWarp() {
        val wait = AlertDialog.Builder(context).setMessage(R.string.warp_generating).setCancelable(false).show()
        Thread {
            val r = runCatching { Warp.generate() }
            main.post {
                runCatching { wait.dismiss() }
                r.onSuccess {
                    store.warpConf = it
                    Toast.makeText(context, R.string.warp_generated, Toast.LENGTH_SHORT).show()
                    renderMode()
                    render()
                }.onFailure {
                    // Генераторы недоступны - берём готовый проверенный конфиг.
                    val seed = WarpSeeds.next(context)
                    if (seed != null) {
                        store.warpConf = seed
                        Toast.makeText(context, com.stoyko.smessenger.ai.t("Генераторы недоступны - взят готовый конфиг WARP", "Generators unavailable - using a ready WARP config"), Toast.LENGTH_LONG).show()
                        renderMode()
                        render()
                    } else {
                        Toast.makeText(context, context.getString(R.string.warp_generate_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun importWarp() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val field = EditText(context).apply {
            setHint(R.string.warp_import_hint)
            setText(if (Warp.looksLikeConf(clip)) clip else store.warpConf)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 10
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.warp_import)
            .setView(FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton(R.string.save) { _, _ ->
                val t = field.text.toString().trim()
                if (runCatching { Warp.parse(t) }.isFailure) {
                    Toast.makeText(context, R.string.warp_bad_conf, Toast.LENGTH_LONG).show()
                } else {
                    store.warpConf = t
                    renderMode()
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyStrategy(s: DpiStrategy) {
        store.dpiPicked = true
        store.dpiStrategy = s.id
        store.dpiArgs = s.args
        renderMode()
        render()
        if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_DPI) SmVpnService.start(context)
    }

    private fun chooseStrategy() {
        val list = DpiStrategies.all(context)
        val names = list.map { "${it.title}\n${it.note}" }.toTypedArray<CharSequence>()
        AlertDialog.Builder(context)
            .setTitle(R.string.dpi_choose)
            .setSingleChoiceItems(names, list.indexOfFirst { it.id == store.dpiStrategy }.coerceAtLeast(0)) { d, which ->
                applyStrategy(list[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun customArgs() {
        val field = EditText(context).apply {
            setText(store.dpiArgs)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
            addView(hint(R.string.dpi_custom_hint))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.dpi_custom)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = DpiEngine.sanitize(field.text.toString())
                if (v.isNotBlank()) {
                    store.dpiArgs = v
                    store.dpiStrategy = "custom"
                    renderMode()
                    if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_DPI) SmVpnService.start(context)
                }
            }
            .setNeutralButton(R.string.dpi_reset) { _, _ -> applyStrategy(DpiStrategies.list[0]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Автоподбор: все стратегии по очереди, с полосой прогресса; лучшая применяется сама. */
    private fun autoSelectStrategy() {
        if (!DpiEngine.available(context)) {
            Toast.makeText(context, R.string.dpi_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val extra = DpiStrategies.all(context).size - DpiStrategies.list.size
        if (extra > 0) {
            // Есть скачанные стратегии - проверить только встроенные (быстрее) или все.
            AlertDialog.Builder(context)
                .setTitle(R.string.dpi_auto)
                .setItems(
                    arrayOf<CharSequence>(
                        context.getString(R.string.dpi_auto_builtin, DpiStrategies.list.size),
                        context.getString(R.string.dpi_auto_all, DpiStrategies.all(context).size),
                    ),
                ) { _, which -> runAutoSelect(if (which == 0) DpiStrategies.list else DpiStrategies.all(context)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        runAutoSelect(DpiStrategies.list)
    }

    private fun runAutoSelect(strategies: List<DpiStrategy>) {
        if (DpiBenchmark.running) {
            Toast.makeText(context, R.string.dpi_bg_running, Toast.LENGTH_SHORT).show()
            return
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val bar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = strategies.size }
        val label = TextView(context).apply {
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(6), 0, dp(6))
        }
        // Сервисы: серый - проверяется, зелёный - открывается с этой стратегией, красный - нет.
        val rows = LinkedHashMap<String, TextView>()
        val best = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF10B981.toInt())
            paint.isFakeBoldText = true
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(hint(R.string.dpi_auto_hint))
            addView(bar)
            addView(label)
        }
        for (s in DpiBenchmark.SERVICES) {
            val row = TextView(context).apply {
                textSize = 15f
                setTextColor(textColor)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            rows[s.key] = row
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        box.addView(best)
        fun paint(states: Map<String, Boolean>) {
            for (s in DpiBenchmark.SERVICES) {
                val row = rows[s.key] ?: continue
                val st = states[s.key]
                row.text = s.emoji + "  " + context.getString(s.titleRes) + "   " + when (st) {
                    true -> context.getString(R.string.svc_ok)
                    false -> context.getString(R.string.svc_fail)
                    else -> "…"
                }
                val color = when (st) {
                    true -> 0xFF22C55E.toInt()
                    false -> 0xFFE5484D.toInt()
                    else -> 0xFF94A3B8.toInt()
                }
                row.setTextColor(if (st == null) textColor else color)
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor((color and 0x00FFFFFF) or 0x1F000000)
                }
            }
        }
        paint(emptyMap())
        var foreground = true
        var bestOk = -1
        val d = AlertDialog.Builder(context)
            .setTitle(R.string.dpi_auto)
            .setView(android.widget.ScrollView(context).apply { addView(box) })
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel.set(true) }
            // Подбор в фоне: окно закрывается, в конце - уведомление и стратегия применится сама.
            .setNeutralButton(R.string.dpi_bg_button) { _, _ ->
                foreground = false
                Toast.makeText(context, R.string.dpi_bg_started, Toast.LENGTH_LONG).show()
            }
            .show()
        DpiBenchmark.run(context, cancel, { i, total, name, states ->
            bar.progress = i
            label.text = "$i / $total · $name"
            if (foreground) paint(states)
            val ok = states.count { it.value }
            if (ok > bestOk) {
                bestOk = ok
                best.text = context.getString(R.string.dpi_best_so_far, name, ok)
            }
        }, done = { result, results ->
            runCatching { d.dismiss() }
            if (cancel.get()) return@run
            if (!foreground || !dialog.isShowing) {
                DpiBenchmark.applyWhenDone(context, result)
                if (dialog.isShowing) renderMode()
                return@run
            }
            if (result == null) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.dpi_auto)
                    .setMessage(R.string.dpi_auto_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@run
            }
            applyStrategy(result.strategy)
            showBenchmarkResult(result, results)
        }, list = strategies)
    }

    /** Итог подбора: какие сервисы открываются (зелёным) и запасные стратегии. */
    private fun showBenchmarkResult(best: DpiBenchmark.Result, results: List<DpiBenchmark.Result>) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        box.addView(TextView(context).apply {
            text = context.getString(R.string.dpi_auto_best, best.strategy.title)
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(textColor)
            setPadding(0, 0, 0, dp(8))
        })
        for (s in DpiBenchmark.SERVICES) {
            val st = best.services[s.key] ?: continue
            val color = if (st) 0xFF22C55E.toInt() else 0xFFE5484D.toInt()
            box.addView(TextView(context).apply {
                text = s.emoji + "  " + context.getString(s.titleRes) + "   " + context.getString(if (st) R.string.svc_ok else R.string.svc_fail)
                textSize = 15f
                setTextColor(color)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor((color and 0x00FFFFFF) or 0x1F000000)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        val others = results.filter { it.ok > 0 && it.strategy.id != best.strategy.id }
            .sortedWith(compareByDescending<DpiBenchmark.Result> { it.ok }.thenBy { it.avgMs }).take(4)
        if (others.isNotEmpty()) {
            box.addView(hint(0, context.getString(R.string.dpi_auto_others) + "\n" + others.joinToString("\n") {
                "• ${it.strategy.title}: ${it.ok} · ${it.avgMs} ms"
            }).apply { setPadding(0, dp(12), 0, 0) })
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.dpi_auto_done)
            .setView(android.widget.ScrollView(context).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Удалить серверы, не ответившие на проверку (автоматически, если включено). */
    private fun removeDeadNow(silent: Boolean) {
        // Автоудаление - только после двух неудачных проверок подряд (случайный сбой сети не в счёт).
        val dead = store.profiles().filter { it.ping < 0 && it.fails >= 2 }
        if (dead.isEmpty()) return
        dead.forEach { store.remove(it.id) }
        if (dialog.isShowing) reload()
        if (!silent || dead.isNotEmpty()) {
            Toast.makeText(context, context.getString(R.string.vpn_auto_removed, dead.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun iconButton(icon: Int, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        setOnClickListener { click() }
    }

    /**
     * Кнопка в ряду VPN: все одного размера, текст всегда влезает - до двух строк и сам
     * уменьшается (раньше длинные подписи обрезались «…», а кнопки выглядели вразнобой).
     */
    private fun chip(text: Int, click: () -> Unit): TextView =
        com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(text)) { click() }.apply {
            textSize = 13f
            maxLines = 2
            ellipsize = null
            setLineSpacing(0f, 0.95f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }

    /** Ряд кнопок VPN одной высоты (выше обычного - чтобы влезали две строки). */
    private fun chipRow(ctx: Context, views: List<android.view.View>, height: Int = 54, gap: Int = 8) =
        com.stoyko.smessenger.ui.Btn.row(ctx, views, height, gap)

    private fun chipLp() = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }

    // ---------- Сортировка, автовыбор, множественный выбор ----------

    private val prefs = context.getSharedPreferences("vpn_ui", Context.MODE_PRIVATE)

    /** Автоматически выбирать самый быстрый сервер после проверки пинга (как в Happ). */
    private var autoFastest: Boolean
        get() = prefs.getBoolean("auto_fastest", true)
        set(v) = prefs.edit().putBoolean("auto_fastest", v).apply()

    /** Режим выбора нескольких серверов (удалить сразу много). */
    private val checked = LinkedHashSet<String>()
    private var selecting = false
    private var selectBar: LinearLayout? = null
    private var selectCount: TextView? = null

    /** Фильтр списка: 0 - все, 1 - обычные, 2 - БС, 3 - ЧС. */
    private var category = 0

    /** Сортировка по пингу (быстрые сверху); иначе - по подпискам, как пришли. */
    private var byPing = true

    private fun filtered(l: List<VpnProfile>): List<VpnProfile> {
        val subs = store.subscriptions().associateBy { it.id }
        val f = if (category == 0) l else l.filter { VpnStore.category(it, subs[it.subId]) == category }
        return if (byPing) sorted(f) else f
    }

    /** Сначала рабочие - от меньшего пинга к большему, затем непроверенные, в конце нерабочие. */
    private fun sorted(l: List<VpnProfile>): List<VpnProfile> = l.sortedWith(
        compareBy<VpnProfile> {
            when {
                it.ping > 0 -> 0
                it.ping == 0L -> 1
                else -> 2
            }
        }.thenBy { if (it.ping > 0) it.ping else Long.MAX_VALUE },
    )

    private fun fastest(): VpnProfile? = store.profiles().filter { it.ping > 0 }.minByOrNull { it.ping }

    private fun applyAutoFastest() {
        if (!autoFastest) return
        val f = fastest() ?: return
        if (f.id == store.selectedId) return
        store.selectedId = f.id
        // Если VPN уже работает - переключаемся на самый быстрый сразу.
        if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_SERVER) SmVpnService.start(context, f.id)
        Toast.makeText(context, context.getString(R.string.vpn_auto_selected, f.name), Toast.LENGTH_SHORT).show()
    }

    private fun setSelecting(on: Boolean) {
        selecting = on
        if (!on) checked.clear()
        selectBar?.visibility = if (on) View.VISIBLE else View.GONE
        updateSelectCount()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectCount() {
        selectCount?.text = context.getString(R.string.vpn_selected_count, checked.size)
    }

    private fun deleteChecked() {
        if (checked.isEmpty()) return
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.vpn_delete_selected_confirm, checked.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                checked.toList().forEach { store.remove(it) }
                setSelecting(false)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Удалить все серверы, которые не ответили на проверку пинга. */
    private fun deleteDead() {
        val dead = store.profiles().filter { it.ping < 0 }
        if (dead.isEmpty()) {
            Toast.makeText(context, R.string.vpn_no_dead, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.vpn_delete_dead_confirm, dead.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                dead.forEach { store.remove(it.id) }
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val rebuildSimple = Runnable { if (dialog.isShowing && !advanced) buildSimple(animate = false) }

    private fun reload() {
        // Обычный вид: перестраиваем не на каждый ответ пинга, а раз в 0,4 с и без повторной анимации.
        if (!advanced) {
            main.removeCallbacks(rebuildSimple)
            main.postDelayed(rebuildSimple, 400)
        }
        items = filtered(store.profiles())
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty() && viewTab == VpnStore.MODE_SERVER) View.VISIBLE else View.GONE
        render()
    }

    private fun render() {
        val st = VpnState.status
        statusText.text = when (st) {
            VpnState.Status.On -> context.getString(R.string.vpn_on_title)
            VpnState.Status.Connecting -> context.getString(R.string.vpn_connecting)
            VpnState.Status.Error -> context.getString(R.string.vpn_error)
            VpnState.Status.Off -> tt("Не подключено", "Not connected")
        }
        serverText.text = when (st) {
            VpnState.Status.Error -> VpnState.error
            VpnState.Status.On -> "⏱ " + elapsed() + "  ·  " + VpnState.profileName
            VpnState.Status.Connecting -> VpnState.profileName
            else -> context.getString(R.string.vpn_off_tap)
        }
        renderHero()
        adapter.notifyDataSetChanged()
        updateBgLabels()
    }

    private fun elapsed(): String {
        val s = ((System.currentTimeMillis() - VpnState.since) / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun toggle() {
        power.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        when (VpnState.status) {
            VpnState.Status.On, VpnState.Status.Connecting -> SmVpnService.stop(context)
            else -> connect()
        }
    }

    private fun connect() {
        // Обычный режим + «Авто»: что включать, приложение решает само.
        if (!advanced && autoMode) {
            VpnAuto.prepare(context, store)
            buildSimple(animate = false)
        }
        if (store.mode != VpnStore.MODE_WARP && !XrayCore.supported) {
            Toast.makeText(context, R.string.vpn_android7, Toast.LENGTH_LONG).show()
            return
        }
        if (!store.ready()) {
            Toast.makeText(context, if (store.mode == VpnStore.MODE_WARP) R.string.warp_no_config else R.string.vpn_no_server, Toast.LENGTH_LONG).show()
            if (store.mode == VpnStore.MODE_WARP) generateWarp()
            return
        }
        // Работает VPN другого приложения - спрашиваем, а не отключаем его молча.
        if (ForeignVpn.active(context)) {
            ForeignVpn.confirmThen(context) { startOwnVpn() }
            return
        }
        startOwnVpn()
    }

    private fun startOwnVpn() {
        val ask = SmVpnService.prepareIntent(context)
        if (ask != null) {
            val a = context as? Activity
            if (a == null) {
                Toast.makeText(context, R.string.vpn_no_permission, Toast.LENGTH_LONG).show()
                return
            }
            @Suppress("DEPRECATION")
            a.startActivityForResult(ask, REQUEST_VPN)
        } else {
            SmVpnService.start(context)
        }
    }

    private fun select(p: VpnProfile) {
        var changed = p.id != store.selectedId
        store.selectedId = p.id
        // Выбрали сервер - значит, работает режим «Серверы» (DPI/WARP выключаются).
        if (store.mode != VpnStore.MODE_SERVER) {
            store.mode = VpnStore.MODE_SERVER
            changed = true
            renderMode()
        }
        reload()
        // Как в Happ: выбор другого сервера при включённом VPN - сразу переподключение.
        if (changed && VpnState.status == VpnState.Status.On) SmVpnService.start(context, p.id)
    }

    private fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        importText(text)
    }

    /** Ссылки, список ссылок (в т.ч. base64) или адрес подписки. */
    fun importText(text: String, fileName: String = "") {
        val t = text.trim().removePrefix("\uFEFF")
        if ((t.startsWith("http://") || t.startsWith("https://")) && t.none { it == '\n' || it == ' ' }) {
            addSubscription(t, "")
            return
        }
        // OpenVPN (.ovpn): храним и открываем в приложении OpenVPN.
        if (OvpnStore.looksLike(t)) {
            val p = OvpnStore.add(context, t, fileName)
            Toast.makeText(
                context,
                if (p != null) tt("Профиль OpenVPN добавлен: ", "OpenVPN profile added: ") + p.name else tt("Такой профиль OpenVPN уже есть", "This OpenVPN profile already exists"),
                Toast.LENGTH_LONG,
            ).show()
            showOvpn()
            return
        }
        // WireGuard / AmneziaWG (.conf) - в свои конфиги WARP и сразу выбрать.
        if (Warp.looksLikeConf(t) && runCatching { Warp.parse(t) }.isSuccess) {
            val name = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "AmneziaWG" }
            WarpSeeds.addUser(context, listOf(name to t))
            store.warpConf = t
            Toast.makeText(context, tt("Конфиг WARP добавлен: ", "WARP config added: ") + name, Toast.LENGTH_LONG).show()
            chooseMode(VpnStore.MODE_WARP)
            return
        }
        // YAML Clash / Mihomo: серверы, WARP (AmneziaWG), подписки провайдеров.
        if (VpnYaml.looksLike(t)) {
            importYaml(t)
            return
        }
        val list = VpnLinks.parseMany(t)
        if (list.isEmpty()) {
            Toast.makeText(context, R.string.vpn_bad_link, Toast.LENGTH_LONG).show()
            return
        }
        val n = store.add(list)
        Toast.makeText(context, context.getString(R.string.vpn_added, n), Toast.LENGTH_SHORT).show()
        reload()
        pingAll()
    }

    private fun importYaml(t: String) {
        val y = VpnYaml.convert(t)
        if (y.isEmpty) {
            Toast.makeText(context, R.string.vpn_bad_link, Toast.LENGTH_LONG).show()
            return
        }
        val parts = ArrayList<String>()
        val servers = y.links.mapNotNull { runCatching { VpnLinks.parse(it) }.getOrNull() }
        if (servers.isNotEmpty()) parts += tt("серверов: ", "servers: ") + store.add(servers)
        if (y.warp.isNotEmpty()) {
            val n = WarpSeeds.addUser(context, y.warp)
            parts += tt("конфигов WARP: ", "WARP configs: ") + n
            // Серверов нет, только WARP - сразу ставим первый конфиг.
            if (servers.isEmpty()) {
                y.warp.firstOrNull()?.let { store.warpConf = it.second }
                chooseMode(VpnStore.MODE_WARP)
            }
        }
        val have = store.subscriptions().map { it.url }.toHashSet()
        val newSubs = y.subs.filter { have.add(it.first) }
        newSubs.forEach { (url, name) -> addSubscription(url, name) }
        if (newSubs.isNotEmpty()) parts += tt("подписок: ", "subscriptions: ") + newSubs.size
        Toast.makeText(context, "YAML ✓ " + parts.joinToString(", "), Toast.LENGTH_LONG).show()
        reload()
        if (servers.isNotEmpty()) pingAll()
        // В конфиге резалась реклама - предлагаем то же самое через DNS AdGuard.
        if (y.adBlock && VpnTweaks.dns != "adguard") {
            com.stoyko.smessenger.ui.DialogTracker.add(
                AlertDialog.Builder(context)
                    .setTitle(tt("🚫 Блокировка рекламы", "🚫 Ad blocking"))
                    .setMessage(tt(
                        "В этом конфиге включена блокировка рекламы. Включить её и здесь (DNS AdGuard через HTTPS)? Действует для серверов (Xray).",
                        "This config blocks ads. Enable it here too (AdGuard DNS over HTTPS)? Applies to servers (Xray).",
                    ))
                    .setPositiveButton(tt("Включить", "Enable")) { _, _ ->
                        VpnTweaks.setDns(context, "adguard")
                        if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_SERVER) SmVpnService.start(context)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create(),
            ).show()
        }
    }

    /** Файл с телефона: .txt, .json, .yaml, .conf, .ovpn - формат определяется сам. */
    private fun pickFile() {
        val a = context as? Activity ?: return
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching {
            @Suppress("DEPRECATION")
            a.startActivityForResult(Intent.createChooser(i, tt("Файл конфигурации", "Config file")), REQUEST_FILE)
        }.onFailure { Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_LONG).show() }
    }

    /** Профили OpenVPN: пинг, открыть в OpenVPN, удалить. */
    private fun showOvpn() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        var dlg: Dialog? = null
        fun fill() {
            box.removeAllViews()
            val l = OvpnStore.list(context)
            if (l.isEmpty()) {
                box.addView(hint(0, tt(
                    "Профилей пока нет. Нажмите «📂 Из файла» и выберите .ovpn - или вставьте текст профиля из буфера.",
                    "No profiles yet. Tap “📂 From file” and pick an .ovpn - or paste the profile text.",
                )).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
            }
            l.forEach { p ->
                val r = p.remote
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    VpnUi.pressable(this, VpnUi.rounded(Color.TRANSPARENT, dp(14).toFloat()))
                }
                row.addView(VpnUi.badge(context, VpnUi.flagOf(p.name, "").let { if (it == "🌐") "🔐" else it }, VpnUi.ACCENT, 38))
                row.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), 0, dp(6), 0)
                        addView(TextView(context).apply {
                            text = VpnUi.cleanName(p.name)
                            textSize = 14.5f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(textColor)
                        })
                        addView(TextView(context).apply {
                            text = (r?.let { "${it.third.uppercase()} · ${it.first}:${it.second}" } ?: "OpenVPN") +
                                (if (p.needsLogin) tt(" · нужен логин", " · login needed") else "")
                            textSize = 11.5f
                            maxLines = 1
                            setTextColor(secondary)
                        })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                val ms = OvpnStore.ping(p.id)
                row.addView(
                    VpnUi.pill(
                        context,
                        when {
                            ms == null -> "—"
                            ms < 0 -> "✕"
                            ms == 0L -> "UDP"
                            else -> context.getString(R.string.vpn_ms, ms)
                        },
                        if (ms == null || ms == 0L) 0xFF94A3B8.toInt() else VpnUi.pingColor(ms),
                    ),
                )
                row.setOnClickListener { OvpnStore.open(context, p) }
                row.setOnLongClickListener {
                    com.stoyko.smessenger.ui.DialogTracker.add(
                        AlertDialog.Builder(context)
                            .setTitle(p.name)
                            .setItems(arrayOf(tt("▶ Открыть в OpenVPN", "▶ Open in OpenVPN"), tt("📤 Поделиться", "📤 Share"), tt("🗑 Удалить", "🗑 Delete"))) { _, w ->
                                when (w) {
                                    0 -> OvpnStore.open(context, p)
                                    1 -> share(p.text)
                                    else -> {
                                        OvpnStore.remove(context, p.id)
                                        fill()
                                    }
                                }
                            }
                            .create(),
                    ).show()
                    true
                }
                box.addView(row, VpnUi.matchWrap())
            }
        }
        fill()
        OvpnStore.pingAll(context) { if (dlg?.isShowing == true) fill() }
        dlg = com.stoyko.smessenger.ui.DialogTracker.add(
            AlertDialog.Builder(context)
                .setTitle(tt("🔐 OpenVPN-профили", "🔐 OpenVPN profiles"))
                .setMessage(tt(
                    "Нажмите профиль - он откроется в приложении OpenVPN (Connect или for Android). Удержание - меню.",
                    "Tap a profile to open it in the OpenVPN app. Long press for more.",
                ))
                .setView(android.widget.ScrollView(context).apply { addView(box) })
                .setPositiveButton(tt("📂 Из файла", "📂 From file")) { _, _ -> pickFile() }
                .setNegativeButton(R.string.close, null)
                .create(),
        )
        dlg?.show()
    }

    private fun addSubscription(url: String, name: String) {
        val sub = store.addSubscription(url, name)
        Toast.makeText(context, R.string.vpn_sub_loading, Toast.LENGTH_SHORT).show()
        VpnSubscriptions.update(context, sub) { r ->
            r.onSuccess {
                Toast.makeText(context, context.getString(R.string.vpn_added, it), Toast.LENGTH_SHORT).show()
                reload()
                pingAll()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.vpn_sub_error, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addDialog() {
        val field = EditText(context).apply {
            setHint(R.string.vpn_link_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 6
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                TextView(context).apply {
                    setText(R.string.vpn_add_hint)
                    textSize = 13f
                    setTextColor(secondary)
                },
            )
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.vpn_add)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> importText(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun share(text: String) {
        // Как в Happ, но внутри мессенджера: сразу контакту или в другое приложение.
        val host = context as? com.stoyko.smessenger.MainActivity
        if (host != null) {
            AlertDialog.Builder(context)
                .setItems(
                    arrayOf<CharSequence>(context.getString(R.string.share_to_contact), context.getString(R.string.share_other_app)),
                ) { _, which ->
                    if (which == 0) {
                        dialog.safeDismiss()
                        host.shareToContact(text)
                    } else {
                        shareExternal(text)
                    }
                }
                .show()
            return
        }
        shareExternal(text)
    }

    private fun shareExternal(text: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    context.getString(R.string.share),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Подписки: когда обновлялась, сколько серверов, не загружается ли («мёртвая» ссылка -
     * три неудачи подряд). Нажатие - удалить подписку вместе с её серверами.
     */
    private fun manageSubscriptions() {
        val t = { ru: String, en: String -> com.stoyko.smessenger.ai.t(ru, en) }
        val subs = store.subscriptions()
        if (subs.isEmpty()) {
            Toast.makeText(context, R.string.vpn_no_subs, Toast.LENGTH_SHORT).show()
            return
        }
        val counts = store.profiles().groupingBy { it.subId }.eachCount()
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val dead = subs.filter { VpnSubscriptions.failCount(context, it) >= 3 }
        val labels = subs.map { s ->
            val f = VpnSubscriptions.failCount(context, s)
            val state = when {
                f >= 3 -> t("⚠ не загружается ($f раз подряд)", "⚠ not loading ($f times in a row)")
                s.updated > 0 -> t("✅ обновлена ", "✅ updated ") + fmt.format(java.util.Date(s.updated))
                else -> t("ещё не загружалась", "not loaded yet")
            }
            "${s.name.ifBlank { s.url.take(40) }}\n${counts[s.id] ?: 0} " + t("серв.", "servers") + " · " + state
        }.toTypedArray<CharSequence>()
        val b = AlertDialog.Builder(context)
            .setTitle(t("Подписки", "Subscriptions"))
            .setItems(labels) { _, i ->
                val s = subs[i]
                AlertDialog.Builder(context)
                    .setTitle(s.name.ifBlank { s.url })
                    .setMessage(s.url)
                    .setPositiveButton(t("Удалить", "Delete")) { _, _ ->
                        store.removeSubscription(s.id)
                        reload()
                    }
                    .setNeutralButton(t("Обновить", "Update")) { _, _ ->
                        VpnSubscriptions.update(context, s) { if (dialog.isShowing) reload() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.close, null)
        if (dead.isNotEmpty()) {
            b.setPositiveButton(t("Удалить нерабочие (${dead.size})", "Delete dead (${dead.size})")) { _, _ ->
                dead.forEach { store.removeSubscription(it.id) }
                reload()
                Toast.makeText(context, t("Удалено подписок: ${dead.size}", "Subscriptions removed: ${dead.size}"), Toast.LENGTH_SHORT).show()
            }
        }
        b.show()
    }

    // Анимация пинга, как в Happ: пока сервер проверяется - пульсирующие точки, затем значение «выпрыгивает».
    private val pinging = HashSet<String>()
    private var pingTotal = 0

    private fun refreshSubscriptions() {
        val subs = store.subscriptions()
        if (subs.isEmpty()) {
            Toast.makeText(context, R.string.vpn_no_subs, Toast.LENGTH_SHORT).show()
            pingAll()
            return
        }
        Toast.makeText(context, R.string.vpn_refreshing, Toast.LENGTH_SHORT).show()
        var left = subs.size
        var added = 0
        var failed = 0
        for (sub in subs) {
            VpnSubscriptions.update(context, sub) { r ->
                r.getOrNull()?.let { added += it } ?: failed++
                left--
                if (left == 0) {
                    if (dialog.isShowing) reload()
                    Toast.makeText(context, context.getString(R.string.vpn_refreshed, added, failed), Toast.LENGTH_LONG).show()
                    pingAll()
                }
            }
        }
    }

    private fun pingAll() {
        // Включён WARP: проверка серверов в этом процессе невозможна (иначе все «мёртвые» и удалятся).
        if (GoGuard.conflict(GoGuard.XRAY)) {
            Toast.makeText(context, R.string.vpn_ping_warp, Toast.LENGTH_LONG).show()
            return
        }
        val ps = store.profiles()
        if (ps.isEmpty()) return
        ps.forEach { pinging.add(it.id) }
        pingTotal = ps.size
        reload()
        var left = ps.size
        // Порядок проверки: выбранный, затем живые по скорости, новые, и в конце мёртвые - быстрые
        // результаты видны сразу, а автовыбор срабатывает раньше.
        val sel = store.selectedId
        val ordered = ps.sortedWith(
            compareBy<VpnProfile>({ it.id != sel }, { if (it.ping > 0) 0 else if (it.ping == 0L) 1 else 2 }, { if (it.ping > 0) it.ping else Long.MAX_VALUE }),
        )
        XrayCore.pingAsync(context, ordered) { p, ms ->
            pinging.remove(p.id)
            pendingPings[p.id] = ms
            left--
            // Все проверены - убираем нерабочие (если включено) и выбираем самый быстрый.
            if (left == 0) {
                main.removeCallbacks(flushPings)
                savePings()
                // Не ответил ни один сервер - выясняем почему (часы, сеть, DPI) и говорим прямо,
                // а не оставляем список из одних «✖». Мёртвыми их в этом случае не удаляем.
                val all = store.profiles()
                val anyAlive = all.any { it.ping > 0 }
                if (!anyAlive && ps.size >= 3) {
                    val sample = ps.shuffled().take(6)
                    Thread({
                        val why = runCatching { XrayCore.diagnoseAllDead(context, sample) }.getOrNull()
                        if (why != null) main.post {
                            if (!dialog.isShowing) return@post
                            runCatching {
                                androidx.appcompat.app.AlertDialog.Builder(context)
                                    .setTitle(if (java.util.Locale.getDefault().language == "ru") "Серверы не ответили" else "No server answered")
                                    .setMessage(why)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    }, "vpn-diagnose").apply { isDaemon = true }.start()
                } else if (store.autoRemoveDead) {
                    removeDeadNow(silent = true)
                }
                applyAutoFastest()
                if (dialog.isShowing) reload()
            } else if (!flushQueued) {
                // Ответы копим и сохраняем пачкой раз в 0,4 с: раньше на КАЖДЫЙ ответ весь список
                // серверов разбирался и записывался заново в главном потоке - с сотнями серверов
                // экран VPN подвисал, пока шла проверка.
                flushQueued = true
                main.postDelayed(flushPings, 400)
            }
        }
    }

    /** Ответы пинга, ещё не записанные в список серверов. */
    private val pendingPings = HashMap<String, Long>()
    private var flushQueued = false
    private val flushPings = Runnable {
        savePings()
        if (dialog.isShowing) reload()
    }

    private fun savePings() {
        flushQueued = false
        if (pendingPings.isEmpty()) return
        val got = HashMap(pendingPings)
        pendingPings.clear()
        store.saveProfiles(store.profiles().map { p -> got[p.id]?.let { p.withPing(it) } ?: p })
    }

    private fun itemActions(p: VpnProfile) {
        val sub = store.subscriptions().firstOrNull { it.id == p.subId }
        val labels = arrayListOf(
            context.getString(R.string.vpn_connect),
            context.getString(R.string.vpn_ping),
            context.getString(R.string.vpn_rename),
            context.getString(R.string.vpn_copy),
            context.getString(R.string.delete),
            context.getString(R.string.vpn_select_many),
        )
        if (sub != null) {
            labels += context.getString(R.string.vpn_sub_update)
            labels += context.getString(R.string.vpn_sub_delete)
        }
        // Поделиться сервером/подпиской - в том числе с контактом в StoykoMessenger.
        val shareIdx = labels.size
        labels += context.getString(R.string.vpn_share_server)
        if (sub != null) labels += context.getString(R.string.vpn_share_sub)
        AlertDialog.Builder(context)
            .setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    shareIdx -> share(p.link)
                    shareIdx + 1 -> sub?.let { share(it.url) }
                    0 -> {
                        store.selectedId = p.id
                        reload()
                        if (VpnState.status == VpnState.Status.On) SmVpnService.start(context, p.id) else connect()
                    }
                    1 -> XrayCore.pingAsync(context, listOf(p.also { pinging.add(it.id); reload() })) { _, ms ->
                        pinging.remove(p.id)
                        store.profiles().firstOrNull { it.id == p.id }?.let { store.update(it.withPing(ms)) }
                        if (dialog.isShowing) reload()
                    }
                    2 -> rename(p)
                    3 -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("vpn", p.link))
                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        store.remove(p.id)
                        reload()
                    }
                    5 -> {
                        checked.clear()
                        checked += p.id
                        setSelecting(true)
                    }
                    6 -> sub?.let { s ->
                        VpnSubscriptions.update(context, s) { r ->
                            r.onSuccess { reload() }.onFailure {
                                Toast.makeText(context, context.getString(R.string.vpn_sub_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    7 -> sub?.let { s ->
                        store.removeSubscription(s.id)
                        reload()
                    }
                }
            }
            .show()
    }

    private fun rename(p: VpnProfile) {
        val field = EditText(context).apply {
            setText(p.name)
            setSelection(p.name.length)
        }
        val box = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.vpn_rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = field.text.toString().trim()
                if (t.isNotEmpty()) {
                    store.update(p.copy(name = t))
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Переключатель «📱 Весь телефон / 💬 Только мессенджер». */
    private fun scopeSwitch(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0x1A00C2A8)
            }
        }
        val whole = TextView(context)
        val app = TextView(context)
        fun paint() {
            val full = store.fullTunnel
            for ((v, on) in listOf(whole to full, app to !full)) {
                v.setTextColor(if (on) Color.WHITE else secondary)
                v.paint.isFakeBoldText = on
                v.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF00C2A8.toInt(), 0xFF0E9F6E.toInt())).apply { cornerRadius = dp(18).toFloat() } else null
            }
        }
        fun pick(full: Boolean) {
            if (store.fullTunnel == full) return
            store.fullTunnel = full
            paint()
            Toast.makeText(context, if (full) R.string.vpn_scope_whole_on else R.string.vpn_scope_app_on, Toast.LENGTH_SHORT).show()
            // VPN уже включён - переподключаем с новым охватом (весь телефон требует разрешения системы).
            if (VpnState.status == VpnState.Status.On || VpnState.status == VpnState.Status.Connecting) connect()
        }
        for ((v, res, full) in listOf(Triple(whole, R.string.vpn_scope_whole, true), Triple(app, R.string.vpn_scope_app, false))) {
            v.setText(res)
            v.textSize = 13f
            v.gravity = Gravity.CENTER
            v.maxLines = 2
            v.setPadding(dp(6), dp(8), dp(6), dp(8))
            v.setOnClickListener { pick(full) }
            com.stoyko.smessenger.ui.Kinetics.squish(v, 0.94f)
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
        return row
    }

    /**
     * Настройки VPN одним списком: охват (весь телефон / мессенджер), приложения, сайты
     * напрямую, раздача на другие устройства, DNS и обход блокировок, сторож туннеля.
     */
    private fun showOptions() {
        val t = { ru: String, en: String -> com.stoyko.smessenger.ai.t(ru, en) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun section(text: String) = box.addView(TextView(context).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(0xFF5B4BDB.toInt())
            paint.isFakeBoldText = true
            setPadding(0, dp(12), 0, dp(4))
        })
        lateinit var dlg: AlertDialog
        fun nav(icon: String, title: String, value: String, click: () -> Unit) {
            box.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x0F5B4BDB)
                }
                addView(TextView(context).apply { text = icon; textSize = 20f }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply { text = title; textSize = 14.5f; setTextColor(textColor); paint.isFakeBoldText = true })
                    addView(TextView(context).apply { text = value; textSize = 12f; setTextColor(secondary); maxLines = 2 })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(secondary) })
                setOnClickListener {
                    runCatching { dlg.dismiss() }
                    click()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        section(t("Куда идёт трафик", "Where traffic goes"))
        val full = SwitchCompat(context).apply {
            setText(R.string.vpn_full_tunnel)
            isChecked = store.fullTunnel
            setOnCheckedChangeListener { _, c -> store.fullTunnel = c }
        }
        box.addView(full)
        box.addView(hint(R.string.vpn_full_tunnel_hint))
        nav("📱", context.getString(R.string.split_title), SplitTunnel.summary(context)) { SplitTunnel.show(context) { render() } }
        nav("🌐", t("Сайты напрямую", "Direct sites"), DomainBypass.summary(context)) { DomainBypass.show(context) }
        nav("📡", t("Раздача на другие устройства", "Share with other devices"), Gateway.summary(context)) { Gateway.show(context) }
        val tox = SwitchCompat(context).apply {
            setText(R.string.vpn_route_tox)
            isChecked = store.routeTox
            setOnCheckedChangeListener { _, c -> store.routeTox = c }
        }
        box.addView(tox)
        box.addView(hint(R.string.vpn_route_tox_hint))
        section(t("Надёжность", "Reliability"))
        box.addView(SwitchCompat(context).apply {
            text = t("Авто-переподключение", "Auto-reconnect")
            isChecked = VpnWatchdog.enabled(context)
            setOnCheckedChangeListener { _, c -> VpnWatchdog.setEnabled(context, c); if (!c) VpnWatchdog.stop() }
        })
        box.addView(hint(0, t(
            "Каждые 40 с и после смены сети (Wi-Fi ↔ мобильная) проверяется живой запрос через туннель; связь пропала - VPN переподключается сам.",
            "Every 40 s and after a network change (Wi-Fi ↔ mobile) a live request goes through the tunnel; if it fails the VPN reconnects by itself.",
        )))
        box.addView(SwitchCompat(context).apply {
            text = t("Переходить на другой сервер при обрыве", "Switch servers when the link drops")
            isChecked = VpnWatchdog.failover(context)
            setOnCheckedChangeListener { _, c -> VpnWatchdog.setFailover(context, c) }
        })
        box.addView(hint(0, t(
            "Переподключение не помогло - берётся самый быстрый из живых серверов (для WARP - следующий готовый конфиг).",
            "If reconnecting doesn't help, the fastest live server is used (for WARP - the next ready config).",
        )))
        box.addView(SwitchCompat(context).apply {
            setText(R.string.vpn_auto_remove_dead)
            isChecked = store.autoRemoveDead
            setOnCheckedChangeListener { _, c -> store.autoRemoveDead = c }
        })
        section(t("Обход блокировок", "Unblocking"))
        nav("🛡", context.getString(R.string.dpi_title), t("DNS через HTTPS, фрагментация TLS", "DNS over HTTPS, TLS fragmentation")) { VpnTweaks.showDialog(context) }
        box.addView(hint(R.string.vpn_tile_hint))
        val ver = XrayCore.version(context)
        if (ver.isNotBlank()) box.addView(hint(0, "Xray $ver"))
        dlg = AlertDialog.Builder(context)
            .setTitle(R.string.vpn_options)
            .setView(android.widget.ScrollView(context).apply { addView(box) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (VpnState.status == VpnState.Status.On) Toast.makeText(context, R.string.vpn_options_reconnect, Toast.LENGTH_SHORT).show()
            }
            .create()
        dlg.show()
    }

    private fun hint(res: Int, raw: String? = null) = TextView(context).apply {
        if (raw != null) text = raw else setText(res)
        textSize = 12f
        setTextColor(secondary)
        setPadding(0, dp(2), 0, dp(10))
    }

    companion object {
        const val REQUEST_VPN = 7311
        const val REQUEST_FILE = 7312
        private val MODE_KEYS = listOf(VpnStore.MODE_SERVER, VpnStore.MODE_DPI, VpnStore.MODE_FRAGMENT, VpnStore.MODE_WARP)
        private val MODE_LABELS = listOf(R.string.vpn_mode_server_short, R.string.vpn_mode_dpi_short, R.string.vpn_mode_fragment_short, R.string.vpn_mode_warp_short)
        private var current: VpnScreen? = null

        /** Результат системного запроса разрешения на VPN (из MainActivity). */
        fun onPermissionResult(context: Context, granted: Boolean) {
            if (granted) {
                SmVpnService.start(context)
            } else {
                Toast.makeText(context, R.string.vpn_no_permission, Toast.LENGTH_LONG).show()
            }
            current?.render()
        }

        /** Выбран файл конфигурации (из MainActivity). */
        fun onFilePicked(context: Context, uri: android.net.Uri?) {
            uri ?: return
            val cr = context.contentResolver
            val name = runCatching {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            val text = runCatching {
                cr.openInputStream(uri)?.use { ins ->
                    val buf = java.io.ByteArrayOutputStream()
                    val b = ByteArray(16 * 1024)
                    while (buf.size() < 6 * 1024 * 1024) {
                        val n = ins.read(b)
                        if (n < 0) break
                        buf.write(b, 0, n)
                    }
                    buf.toString("UTF-8")
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, R.string.vpn_bad_link, Toast.LENGTH_LONG).show()
                return
            }
            val s = current ?: VpnScreen(context).also { it.show() }
            s.importText(text, name)
        }

        /** Открыть экран и сразу импортировать ссылку (из vless://… и т.п.). */
        fun openWithLink(context: Context, link: String) {
            val s = VpnScreen(context)
            s.show()
            s.importText(link)
        }
    }
}
