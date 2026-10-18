// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentSettingsBinding
import com.stoyko.smessenger.ui.GlassFrameLayout
import com.stoyko.smessenger.ui.UiPrefs

/**
 * Настройки по разделам: сверху карточка профиля, ниже - список разделов
 * («Уведомления и звуки», «Конфиденциальность», «Данные и память», «Оформление», «VPN»…).
 * Раздел открывается на том же экране: список уезжает, карточки раздела выезжают.
 */
class SettingsSections(
    private val context: Context,
    private val b: FragmentSettingsBinding,
    private val vm: SettingsViewModel,
    private val owner: LifecycleOwner,
    private val openProfile: () -> Unit,
    private val showAbout: () -> Unit,
    /** «Изменить профиль» сразу в редакторе (фото, имя, статус - одной кнопкой). */
    private val openEditor: (() -> Unit)? = null,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val st = vm.appSettings
    private val content = b.settingsContent
    private val menu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val sections = LinkedHashMap<String, Pair<Int, List<View>>>()
    private var current: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val refreshers = ArrayList<() -> Unit>()

    @Suppress("DEPRECATION")
    private val textColor = context.resources.getColor(R.color.textPrimary)

    @Suppress("DEPRECATION")
    private val secondary = context.resources.getColor(R.color.textSecondary)

    @Suppress("DEPRECATION")
    private val surface = context.resources.getColor(R.color.colorSurface)

    val inSection: Boolean get() = current != null

    fun setup() {
        val notifications = notificationsCard()
        val data = dataCard()
        val appearance = appearanceCard()
        val about = aboutCard()
        val battery = batteryCard()
        val personal = personalCard()
        extendChats()

        // Новые карточки - в конец, перед строкой версии.
        val versionIndex = content.indexOfChild(b.version)
        listOf(notifications, data, appearance, about, battery, personal).forEachIndexed { i, v -> content.addView(v, versionIndex + i) }

        sections["notifications"] = R.string.sec_notifications to listOf(notifications)
        sections["privacy"] = R.string.sec_privacy to listOf(b.cardPrivacy, b.cardPassword)
        sections["data"] = R.string.sec_data to listOf(data)
        // «Оформление» и «Персонализация» - один раздел.
        sections["appearance"] = R.string.sec_appearance to listOf(appearance, personal)
        sections["chats"] = R.string.sec_chats to listOf(b.cardChats)
        sections["general"] = R.string.sec_general to listOf(b.cardGeneral)
        // Прокси - внутри «Сеть и узлы».
        sections["proxy"] = R.string.sec_network to listOf(b.cardNetwork, b.cardProxy)
        sections["network"] = R.string.sec_network to listOf(b.cardNetwork, b.cardProxy)
        sections["advanced"] = R.string.sec_advanced to listOf(b.cardAdvanced)
        sections["about"] = R.string.sec_about to listOf(about)
        sections["battery"] = R.string.sec_battery to listOf(battery)
        sections["personal"] = R.string.sec_appearance to listOf(appearance, personal)

        buildMenu()
        content.addView(menu, content.indexOfChild(b.settingsHeader) + 1)
        b.settingsHeader.visibility = View.GONE
        sections.values.flatMap { it.second }.forEach { it.visibility = View.GONE }
        refreshAll()
    }

    // ---------- Меню ----------

    private fun buildMenu() {
        menu.addView(profileCard())
        menu.addView(
            group(
                row("🔔", 0xFFEF4444.toInt(), R.string.sec_notifications) { open("notifications") },
                row("🔒", 0xFF64748B.toInt(), R.string.sec_privacy) { open("privacy") },
                row("💾", 0xFF22C55E.toInt(), R.string.sec_data) { open("data") },
                row("🎨", 0xFF0EA5E9.toInt(), R.string.sec_appearance) { open("appearance") },
                row("🔋", 0xFF84CC16.toInt(), R.string.sec_battery) { open("battery") },
                row("💬", 0xFFF59E0B.toInt(), R.string.sec_chats) { open("chats") },
                row("⚙️", 0xFF8B5CF6.toInt(), R.string.sec_general) { open("general") },
            ),
        )
        val vpnRow = row("🛡", 0xFF10B981.toInt(), R.string.sec_vpn) { com.stoyko.smessenger.vpn.VpnScreen(context).show() }
        val vpnValue = vpnRow.findViewWithTag<TextView>("value")
        refreshers += {
            vpnValue?.text = when (com.stoyko.smessenger.vpn.VpnState.status) {
                com.stoyko.smessenger.vpn.VpnState.Status.On -> context.getString(R.string.vpn_on_short)
                com.stoyko.smessenger.vpn.VpnState.Status.Connecting -> "…"
                else -> context.getString(R.string.vpn_off)
            }
        }
        // «Сеть и узлы» вместе с прокси (справа - включён ли прокси).
        val proxyRow = row("📡", 0xFF06B6D4.toInt(), R.string.sec_network) { open("network") }
        val proxyValue = proxyRow.findViewWithTag<TextView>("value")
        refreshers += {
            proxyValue?.text = if (vm.getProxyType() == com.stoyko.smessenger.domain.tox.ProxyType.None) {
                context.getString(R.string.vpn_off)
            } else {
                vm.getProxyType().name
            }
        }
        menu.addView(
            group(
                vpnRow,
                row("📶", 0xFF8B5CF6.toInt(), R.string.transport_title) { com.stoyko.smessenger.transport.ui.ChannelsScreen(context).show() },
                proxyRow,
                row("🧩", 0xFF6366F1.toInt(), R.string.sec_advanced) { open("advanced") },
                row("🔄", 0xFF14B8A6.toInt(), R.string.sec_device_sync) { com.stoyko.smessenger.profile.DeviceSync.show(context) },
            ),
        )
        menu.addView(
            group(
                row("✦", 0xFF7B6CF6.toInt(), R.string.sec_ai) {
                    com.stoyko.smessenger.ai.AiSettings.show(context)
                },
                row("❓", 0xFFF97316.toInt(), R.string.sec_help) {
                    com.stoyko.smessenger.settings.Nodes.showGuide(context)
                },
                row("ℹ️", 0xFF94A3B8.toInt(), R.string.sec_about) { open("about") },
            ),
        )
        // «Написать разработчику» - отдельная красивая объёмная кнопка (предложения и ошибки -
        // прямо разработчику, его ID встроен), по ней мягко пробегает блик.
        val dev = com.stoyko.smessenger.ui.Btn.nice(
            context,
            context.getString(R.string.write_developer_btn),
            intArrayOf(0xFF34D399.toInt(), 0xFF0E9F6E.toInt()),
        ) { (context as? com.stoyko.smessenger.MainActivity)?.writeDeveloper() }
        val d = context.resources.displayMetrics.density
        menu.addView(
            com.stoyko.smessenger.ui.ShineFrame(context).apply { addView(dev, android.widget.FrameLayout.LayoutParams(-1, -1)) },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (58 * d).toInt()).apply {
                topMargin = (14 * d).toInt()
                bottomMargin = (6 * d).toInt()
            },
        )
        // Канал разработчика - новости и обновления (4.10.0).
        menu.addView(
            com.stoyko.smessenger.ui.DevChannel.button(context),
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * d).toInt()
                bottomMargin = (6 * d).toInt()
            },
        )
        com.stoyko.smessenger.vpn.VpnState.listen(vpnListener)
        // Карточки разделов появляются одна за другой.
        com.stoyko.smessenger.ui.Kinetics.stagger((0 until menu.childCount).map { menu.getChildAt(it) }, step = 50)
    }

    private val vpnListener: () -> Unit = { refreshAll() }

    fun release() {
        com.stoyko.smessenger.vpn.VpnState.unlisten(vpnListener)
        io.shutdown()
    }

    fun refreshAll() = refreshers.forEach { runCatching { it() } }

    private fun profileCard(): View {
        val card = card()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rippleBg()
            isClickable = true
            setOnClickListener { openProfile() }
        }
        val avatar = com.google.android.material.imageview.ShapeableImageView(context).apply {
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(32).toFloat()).build()
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        box.addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        val name = TextView(context).apply {
            textSize = 19f
            paint.isFakeBoldText = true
            setTextColor(textColor)
            maxLines = 1
            text = context.getString(R.string.app_name)
        }
        val sub = TextView(context).apply {
            textSize = 13f
            setTextColor(secondary)
            maxLines = 1
        }
        val note = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF22C55E.toInt())
            setText(R.string.settings_e2e_note)
        }
        texts.addView(name)
        texts.addView(sub)
        texts.addView(note)
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (openEditor != null) {
            val edit = TextView(context).apply {
                setText(R.string.profile_edit_short)
                textSize = 14f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF7B6CF6.toInt(), 0xFF5B4BDB.toInt()),
                ).apply { cornerRadius = dp(18).toFloat() }
                setOnClickListener { openEditor.invoke() }
            }
            com.stoyko.smessenger.ui.Kinetics.squish(edit, 0.9f)
            com.stoyko.smessenger.ui.Buttons3d.apply(edit)
            box.addView(edit)
        } else {
            box.addView(chevron())
        }
        card.addView(box)

        val pk = vm.selfPublicKey
        val size = dp(64)
        com.stoyko.smessenger.profile.SelfAvatar.show(avatar, "", pk, size)
        sub.text = if (pk.isEmpty()) "" else "ID " + pk.take(8) + "…" + pk.takeLast(4)
        vm.self()?.observe(owner) { u ->
            name.text = u.name.ifEmpty { context.getString(R.string.app_name) }
            com.stoyko.smessenger.profile.SelfAvatar.show(avatar, u.name, pk, size)
        }
        return card
    }

    private fun card() = CardView(context).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(surface)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
    }

    private fun rippleBg() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x225B4BDB),
        null,
        GradientDrawable().apply { setColor(Color.WHITE) },
    )

    private fun group(vararg rows: View): View {
        val card = card()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                box.addView(
                    View(context).apply { setBackgroundColor(0x1A888888) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(66) },
                )
            }
            box.addView(r)
        }
        card.addView(box)
        return card
    }

    private fun chevron() = TextView(context).apply {
        text = "›"
        textSize = 22f
        setTextColor(secondary)
    }

    private fun row(icon: String, color: Int, title: Int, click: () -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            background = rippleBg()
            isClickable = true
            setOnClickListener {
                animate().scaleX(0.98f).scaleY(0.98f).setDuration(70).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                }.start()
                click()
            }
        }
        r.addView(
            TextView(context).apply {
                text = icon
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    colors = intArrayOf(color, darker(color))
                    orientation = GradientDrawable.Orientation.TL_BR
                }
            },
            LinearLayout.LayoutParams(dp(34), dp(34)),
        )
        r.addView(
            TextView(context).apply {
                setText(title)
                textSize = 16f
                setTextColor(textColor)
                setPadding(dp(16), 0, dp(8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        r.addView(
            TextView(context).apply {
                tag = "value"
                textSize = 14f
                setTextColor(secondary)
                setPadding(0, 0, dp(6), 0)
            },
        )
        r.addView(chevron())
        return r
    }

    private fun darker(c: Int): Int {
        val f = 0.78f
        return Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    // ---------- Переходы между меню и разделами ----------

    private fun open(key: String) {
        val (title, views) = sections[key] ?: return
        current = key
        b.toolbar.title = context.getString(title)
        slide(out = listOf(menu, b.version), into = views, forward = true)
        b.root.findViewById<View>(R.id.settingsScroll)?.scrollTo(0, 0)
    }

    /** Назад из раздела в меню. true - обработано. */
    fun back(): Boolean {
        val key = current ?: return false
        current = null
        b.toolbar.title = context.getString(R.string.settings)
        slide(out = sections[key]?.second.orEmpty(), into = listOf(menu, b.version), forward = false)
        refreshAll()
        return true
    }

    private fun slide(out: List<View>, into: List<View>, forward: Boolean) {
        val w = content.width.coerceAtLeast(1).toFloat()
        val animate = UiPrefs.animations
        out.forEach { v ->
            v.animate().cancel()
            v.visibility = View.GONE
            v.alpha = 1f
            v.translationX = 0f
        }
        into.forEachIndexed { i, v ->
            v.visibility = View.VISIBLE
            if (animate) {
                v.alpha = 0f
                v.translationX = (if (forward) 0.25f else -0.25f) * w
                v.animate().alpha(1f).translationX(0f).setStartDelay(30L * i).setDuration(260)
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }
    }

    // ---------- Карточки новых разделов ----------

    private fun sectionCard(heading: Int): Pair<CardView, LinearLayout> {
        val card = card()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(
            TextView(context).apply {
                setText(heading)
                textSize = 15f
                paint.isFakeBoldText = true
                @Suppress("DEPRECATION")
                setTextColor(context.resources.getColor(R.color.settingsAccent))
                setPadding(dp(18), dp(16), dp(18), dp(6))
            },
        )
        card.addView(box)
        return card to box
    }

    private fun switchRow(box: LinearLayout, title: Int, hint: Int?, value: Boolean, set: (Boolean) -> Unit): SwitchCompat {
        val sw = SwitchCompat(context).apply {
            setText(title)
            textSize = 16f
            setTextColor(textColor)
            isChecked = value
            setPadding(dp(18), dp(10), dp(12), dp(10))
            setOnCheckedChangeListener { _, c -> set(c) }
        }
        box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (hint != null) box.addView(hintView(hint))
        return sw
    }

    private fun hintView(res: Int) = TextView(context).apply {
        setText(res)
        textSize = 13f
        setTextColor(secondary)
        setPadding(dp(18), 0, dp(18), dp(8))
    }

    private fun actionRow(box: LinearLayout, title: Int, click: (TextView) -> Unit): TextView {
        val tv = TextView(context).apply {
            setText(title)
            textSize = 16f
            @Suppress("DEPRECATION")
            setTextColor(context.resources.getColor(R.color.settingsAccent))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rippleBg()
        }
        tv.setOnClickListener { click(tv) }
        box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return tv
    }

    /** Перенести готовую строку из старой карточки (вместе с [extra] следующими элементами). */
    private fun take(v: View, extra: Int = 0): List<View> {
        val parent = v.parent as? ViewGroup ?: return emptyList()
        val idx = parent.indexOfChild(v)
        val out = (0..extra).mapNotNull { parent.getChildAt(idx + it) }
        out.forEach { parent.removeView(it) }
        // Лишний разделитель над перенесённой строкой.
        val prev = parent.getChildAt(idx - 1)
        if (prev != null && prev.javaClass == View::class.java) {
            parent.removeView(prev)
        } else {
            val next = parent.getChildAt(idx)
            if (next != null && next.javaClass == View::class.java && idx == 1) parent.removeView(next)
        }
        return out
    }

    private fun notificationsCard(): View {
        val (card, box) = sectionCard(R.string.sec_notifications)
        switchRow(box, R.string.notify_private, null, st.notifyPrivate) { st.notifyPrivate = it }
        switchRow(box, R.string.notify_groups, null, st.notifyGroups) { st.notifyGroups = it }
        switchRow(box, R.string.notify_channels, null, st.notifyChannels) { st.notifyChannels = it }
        switchRow(box, R.string.notify_sound, R.string.notify_sound_hint, st.notifySound) { st.notifySound = it }
        take(b.settingHideNotificationText.parent as View).forEach { box.addView(it) }
        // Новое сообщение при выключенном экране: коротко включить экран (видно на блокировке).
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        switchRow(box, R.string.notify_wake_screen, R.string.notify_wake_screen_hint, prefs.getBoolean("notify_wake_screen", true)) {
            prefs.edit().putBoolean("notify_wake_screen", it).apply()
        }
        // Xiaomi/POCO/Redmi, Honor, Huawei: отдельное разрешение «Экран блокировки».
        actionRow(box, R.string.notify_lockscreen) { openLockscreenPermission() }
        box.addView(hintView(R.string.notify_lockscreen_hint))
        actionRow(box, R.string.notify_system) {
            val i = if (Build.VERSION.SDK_INT >= 26) {
                Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            }
            runCatching { context.startActivity(i) }
        }
        return card
    }

    /** Окно разрешений приложения, где включается «Показывать на экране блокировки». */
    private fun openLockscreenPermission() {
        val pkg = context.packageName
        val tries = listOf(
            // MIUI / HyperOS: «Другие разрешения» → «Экран блокировки».
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ).putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            // Honor / Huawei: диспетчер уведомлений.
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.notificationmanager.ui.NotificationManagmentActivity"),
            // Обычный Android: уведомления приложения (там «На заблокированном экране»).
            if (Build.VERSION.SDK_INT >= 26) {
                Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, pkg)
            } else {
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            },
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    private fun dataCard(): View {
        val (card, box) = sectionCard(R.string.sec_data)
        take(b.settingFtAutoAccept.parent as View).forEach { box.addView(it) }
        val usage = TextView(context).apply {
            textSize = 14f
            setTextColor(secondary)
            setPadding(dp(18), dp(10), dp(18), dp(4))
            setText(R.string.storage_counting)
        }
        box.addView(usage)
        val sc = com.stoyko.smessenger.settings.StorageCleaner
        fun fmt(v: Long) = Formatter.formatShortFileSize(context, v)
        fun count() {
            io.execute {
                val received = sc.size(sc.received(context))
                val sent = sc.size(sc.sentCopies(context))
                val stories = sc.size(sc.stories(context))
                val web = sc.size(sc.web(context))
                val cache = sc.cacheSize(context)
                usage.post {
                    usage.text = context.getString(
                        R.string.storage_breakdown,
                        fmt(received), fmt(sent), fmt(stories), fmt(web), fmt(cache),
                        fmt(received + sent + stories + web + cache),
                    )
                }
            }
        }
        count()
        actionRow(box, R.string.storage_clear_cache) {
            io.execute {
                sc.clearCache(context)
                usage.post {
                    Toast.makeText(context, R.string.storage_cleared, Toast.LENGTH_SHORT).show()
                    count()
                }
            }
        }
        actionRow(box, R.string.storage_clear_sent) {
            io.execute {
                val freed = sc.clearSentCopiesNow(context)
                usage.post {
                    Toast.makeText(context, context.getString(R.string.storage_freed, fmt(freed)), Toast.LENGTH_SHORT).show()
                    count()
                }
            }
        }
        actionRow(box, R.string.storage_clear_browser) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.storage_clear_browser)
                .setItems(
                    arrayOf<CharSequence>(
                        context.getString(R.string.browser_clear_cache_only),
                        context.getString(R.string.browser_clear_everything),
                    ),
                ) { _, which ->
                    io.execute {
                        if (which == 0) sc.clearBrowserCache(context) else sc.clearBrowserAll(context)
                        usage.post {
                            Toast.makeText(context, R.string.storage_cleared, Toast.LENGTH_SHORT).show()
                            count()
                        }
                    }
                }
                .show()
        }
        val keep = intArrayOf(0, 1, 2, 3, 7, 30)
        val keepNames = arrayOf<CharSequence>(
            context.getString(R.string.keep_forever),
            context.getString(R.string.keep_day_1),
            context.getString(R.string.keep_days_2),
            context.getString(R.string.keep_days_3),
            context.getString(R.string.keep_week),
            context.getString(R.string.keep_month),
        )
        val keepRow = actionRow(box, R.string.storage_keep) {}
        fun showKeep() {
            keepRow.text = context.getString(R.string.storage_keep) + ": " + keepNames[keep.indexOf(sc.keepDays(context)).coerceAtLeast(0)]
        }
        showKeep()
        keepRow.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.storage_keep)
                .setSingleChoiceItems(keepNames, keep.indexOf(sc.keepDays(context)).coerceAtLeast(0)) { d, which ->
                    sc.setKeepDays(context, keep[which])
                    showKeep()
                    d.dismiss()
                    if (keep[which] > 0) {
                        io.execute {
                            sc.autoClean(context, force = true)
                            usage.post { count() }
                        }
                    }
                }
                .show()
        }
        // Предел места: при превышении приложение само чистит кэш и самые старые файлы.
        val limits = intArrayOf(0, 100, 250, 500, 1024, 2048, 5120, 10240)
        val limitNames = limits.map {
            if (it == 0) context.getString(R.string.storage_limit_off) else fmt(it.toLong() * 1024 * 1024)
        }.toTypedArray<CharSequence>()
        val limitRow = actionRow(box, R.string.storage_limit) {}
        fun showLimit() {
            limitRow.text = context.getString(R.string.storage_limit) + ": " +
                limitNames[limits.indexOf(sc.limitMb(context)).coerceAtLeast(0)]
        }
        showLimit()
        limitRow.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.storage_limit)
                .setSingleChoiceItems(limitNames, limits.indexOf(sc.limitMb(context)).coerceAtLeast(0)) { d, which ->
                    sc.setLimitMb(context, limits[which])
                    showLimit()
                    d.dismiss()
                    if (limits[which] > 0) {
                        io.execute {
                            sc.enforceLimit(context)
                            usage.post { count() }
                        }
                    }
                }
                .show()
        }
        box.addView(hintView(R.string.storage_hint))
        return card
    }

    private fun dirSize(f: File): Long = runCatching {
        if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun appearanceCard(): View {
        val (card, box) = sectionCard(R.string.sec_appearance)
        take(b.theme.parent as View).forEach { box.addView(it) }
        // Строка «Тема» - ровно как остальные строки карточки: тот же отступ, размер и цвет
        // текста, подпись по центру по высоте напротив списка (раньше съезжала вверх).
        (b.theme.parent as? ViewGroup)?.let { row ->
            row.setPadding(dp(18), dp(6), dp(8), dp(6))
            row.minimumHeight = dp(52)
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>().forEach { tv ->
                tv.textSize = 16f
                tv.setTextColor(textColor)
                tv.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                tv.setPadding(0, 0, 0, 0)
            }
        }
        take(b.settingGlass.parent as View, extra = 1).forEach { box.addView(it) }
        // 4.12.0: обои для всех чатов - встроенные живые («Дождливая ночь»), своё фото или видео.
        run {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(14), dp(10))
                background = rippleBg()
            }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = com.stoyko.smessenger.ai.t("🎞 Обои чатов", "🎞 Chat wallpaper")
                textSize = 16f
                setTextColor(textColor)
            })
            val value = TextView(context).apply {
                textSize = 13f
                setTextColor(secondary)
            }
            fun label() {
                value.text = com.stoyko.smessenger.ai.t("Для всех чатов: ", "For all chats: ") +
                    com.stoyko.smessenger.ui.chat.ChatWallpaper.globalLabel(context) +
                    com.stoyko.smessenger.ai.t(" · свои у чата - в его меню", " · per chat - in the chat menu")
            }
            label()
            texts.addView(value)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Живой предпросмотр встроенных обоев.
            row.addView(
                com.stoyko.smessenger.ui.LoopVideoView(
                    context,
                    com.stoyko.smessenger.ui.LoopVideoView.raw(context, R.raw.live_rain_icon),
                    R.drawable.live_rain_poster,
                ).apply {
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                    }
                    clipToOutline = true
                },
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(10) },
            )
            row.setOnClickListener {
                val act = context as? android.app.Activity ?: return@setOnClickListener
                com.stoyko.smessenger.ui.chat.ChatWallpaper.showGlobalPicker(act) { label() }
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        switchRow(box, R.string.pref_animations, R.string.pref_animations_hint, st.animations) {
            st.animations = it
            UiPrefs.animations = it
            GlassFrameLayout.liquidEnabled = it
            com.stoyko.smessenger.ui.Kinetics.enabled = it
        }
        // Отдельные анимации - выключить то, что тормозит на слабом телефоне.
        box.addView(TextView(context).apply {
            setText(R.string.anim_each_title)
            textSize = 13.5f
            paint.isFakeBoldText = true
            @Suppress("DEPRECATION")
            setTextColor(context.resources.getColor(R.color.settingsAccent))
            setPadding(dp(18), dp(12), dp(18), dp(2))
        })
        for (k in com.stoyko.smessenger.ui.AnimPrefs.K.entries) {
            val sw = SwitchCompat(context).apply {
                text = com.stoyko.smessenger.ui.AnimPrefs.title(k)
                textSize = 15f
                setTextColor(textColor)
                isChecked = com.stoyko.smessenger.ui.AnimPrefs.on(k)
                setPadding(dp(18), dp(8), dp(12), dp(2))
                setOnCheckedChangeListener { _, c -> com.stoyko.smessenger.ui.AnimPrefs.set(context, k, c) }
            }
            box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(TextView(context).apply {
                text = com.stoyko.smessenger.ui.AnimPrefs.hint(k)
                textSize = 12.5f
                setTextColor(secondary)
                setPadding(dp(18), 0, dp(18), dp(6))
            })
        }

        // Режим для слабых устройств: авто / всегда / выключен.
        val liteRow = TextView(context).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(18), dp(14), dp(18), dp(4))
            background = rippleBg()
        }
        fun liteLabel() {
            val lm = com.stoyko.smessenger.ui.LiteMode
            val value = when (lm.setting(context)) {
                lm.ON -> context.getString(R.string.lite_on)
                lm.OFF -> context.getString(R.string.lite_off)
                else -> context.getString(if (lm.on) R.string.lite_auto_on else R.string.lite_auto_off)
            }
            liteRow.text = context.getString(R.string.lite_title) + ": " + value
        }
        liteLabel()
        liteRow.setOnClickListener {
            val lm = com.stoyko.smessenger.ui.LiteMode
            val items = arrayOf<CharSequence>(context.getString(R.string.lite_auto), context.getString(R.string.lite_on), context.getString(R.string.lite_off))
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.lite_title)
                .setSingleChoiceItems(items, lm.setting(context)) { dlg, which ->
                    lm.set(context, which)
                    liteLabel()
                    dlg.dismiss()
                    android.widget.Toast.makeText(context, if (lm.on) R.string.lite_enabled_toast else R.string.lite_disabled_toast, android.widget.Toast.LENGTH_LONG).show()
                }
                .setNeutralButton(R.string.lite_what) { _, _ ->
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle(R.string.lite_title)
                        .setMessage(R.string.lite_explain)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                .show()
        }
        box.addView(liteRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(hintView(R.string.lite_hint))

        // Язык приложения: как в системе / русский / английский.
        val langRow = TextView(context).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(18), dp(14), dp(18), dp(10))
            val codes = listOf("", "ru", "en")
            val names = arrayOf<CharSequence>(context.getString(R.string.lang_system), "Русский", "English")
            fun label() = context.getString(R.string.pref_language_value, names[codes.indexOf(com.stoyko.smessenger.ui.AppLanguage.get(context)).coerceAtLeast(0)])
            text = label()
            setOnClickListener {
                val cur = codes.indexOf(com.stoyko.smessenger.ui.AppLanguage.get(context)).coerceAtLeast(0)
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.pref_language)
                    .setSingleChoiceItems(names, cur) { dlg, which ->
                        dlg.dismiss()
                        if (which == cur) return@setSingleChoiceItems
                        com.stoyko.smessenger.ui.AppLanguage.set(context, codes[which])
                        text = label()
                        // Язык меняется везде (экраны, уведомления, службы) - перезапускаем приложение.
                        com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.let {
                            com.stoyko.smessenger.CrashGuard.restartApp(it, clearCache = false)
                        }
                    }
                    .show()
            }
        }
        box.addView(langRow)
        box.addView(hintView(R.string.pref_language_hint))
        // Режим планшета: Авто / Всегда / Выключен - доступен на любом устройстве.
        val paneRow = TextView(context).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(18), dp(14), dp(18), dp(4))
            val modes = arrayOf<CharSequence>(
                context.getString(R.string.pref_two_pane_auto),
                context.getString(R.string.pref_two_pane_on),
                context.getString(R.string.pref_two_pane_off),
            )
            fun label() = context.getString(R.string.pref_two_pane) + ": " + modes[com.stoyko.smessenger.ui.TwoPane.mode(context)]
            text = label()
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.pref_two_pane)
                    .setSingleChoiceItems(modes, com.stoyko.smessenger.ui.TwoPane.mode(context)) { dlg, which ->
                        dlg.dismiss()
                        com.stoyko.smessenger.ui.TwoPane.setMode(context, which)
                        text = label()
                        b.root.postDelayed({ com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.recreate() }, 250)
                    }
                    .show()
            }
        }
        box.addView(paneRow)
        box.addView(hintView(R.string.pref_two_pane_hint))
        // Какое устройство определено - под него подстроены шрифт, кнопки и экран.
        box.addView(hintView(R.string.pref_two_pane_hint).apply {
            text = context.getString(R.string.device_detected, com.stoyko.smessenger.ui.DeviceProfile.describe(context))
        })

        // Масштаб интерфейса: подстроить под свой экран (авто - по размеру экрана).
        val scaleRow = TextView(context).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(18), dp(14), dp(18), dp(10))
            fun label() = context.getString(
                R.string.pref_ui_scale_value,
                com.stoyko.smessenger.ui.ScreenFit.userScale(context).let {
                    if (it <= 0f) context.getString(R.string.pref_ui_scale_auto) else "${(it * 100).toInt()}%"
                },
            )
            text = label()
            setOnClickListener {
                val scales = com.stoyko.smessenger.ui.ScreenFit.SCALES
                val names = scales.map { if (it <= 0f) context.getString(R.string.pref_ui_scale_auto) else "${(it * 100).toInt()}%" }
                    .toTypedArray<CharSequence>()
                val cur = scales.indexOfFirst { it == com.stoyko.smessenger.ui.ScreenFit.userScale(context) }.coerceAtLeast(0)
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.pref_ui_scale)
                    .setSingleChoiceItems(names, cur) { dlg, which ->
                        com.stoyko.smessenger.ui.ScreenFit.setUserScale(context, scales[which])
                        dlg.dismiss()
                        text = label()
                        // Новый масштаб применяется сразу - окно пересоздаётся.
                        com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.recreate()
                    }
                    .show()
            }
        }
        box.addView(scaleRow)

        // Размер текста сообщений с живым примером.
        val label = TextView(context).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(18), dp(12), dp(18), 0)
        }
        box.addView(label)
        val preview = TextView(context).apply {
            setText(R.string.pref_text_size_preview)
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                colors = intArrayOf(0xFF7B6CF6.toInt(), 0xFF4B3CCB.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }
        box.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                setMargins(dp(48), dp(8), dp(18), dp(4))
            },
        )
        fun apply(size: Int) {
            label.text = context.getString(R.string.pref_text_size, size)
            preview.textSize = size.toFloat()
        }
        val seek = SeekBar(context).apply {
            max = 26 - 12
            progress = st.messageTextSize - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val size = 12 + p
                    apply(size)
                    if (fromUser) {
                        st.messageTextSize = size
                        UiPrefs.messageTextSize = size.toFloat()
                        // В режиме для слабовидящих: запоминаем выбор как «свой» (вернётся при выключении).
                        com.stoyko.smessenger.ui.Accessibility.applyRuntime(context, fresh = true)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        apply(st.messageTextSize)
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(8), 0, dp(8), dp(4))
        })
        return card
    }

    /** Дополнения к «Чаты и звонки». */
    private fun extendChats() {
        val box = (b.cardChats.getChildAt(0) as? LinearLayout) ?: return
        val insertAt = box.indexOfChild(b.settingErrorLog).takeIf { it >= 0 } ?: box.childCount
        val tmp = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        switchRow(tmp, R.string.pref_send_by_enter, R.string.pref_send_by_enter_hint, st.sendByEnter) {
            st.sendByEnter = it
            UiPrefs.sendByEnter = it
        }
        val labels = arrayOf(
            context.getString(R.string.video_quality_auto),
            context.getString(R.string.video_quality_low),
            context.getString(R.string.video_quality_high),
        )
        actionRow(tmp, R.string.pref_video_quality) { tv ->
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.pref_video_quality)
                .setSingleChoiceItems(labels, st.callVideoQuality.coerceIn(0, 2)) { d, which ->
                    st.callVideoQuality = which
                    tv.text = context.getString(R.string.pref_video_quality) + ": " + labels[which]
                    d.dismiss()
                }
                .show()
        }.also { it.text = context.getString(R.string.pref_video_quality) + ": " + labels[st.callVideoQuality.coerceIn(0, 2)] }
        val views = (0 until tmp.childCount).map { tmp.getChildAt(it) }
        tmp.removeAllViews()
        views.forEachIndexed { i, v -> box.addView(v, insertAt + i) }
        // Журнал ошибок - в «О приложении».
    }

    /** Батарея и работа в фоне: режим расхода, исключение из экономии заряда, автозапуск. */
    private fun batteryCard(): View {
        val (card, box) = sectionCard(R.string.sec_battery)
        val status = TextView(context).apply {
            textSize = 14f
            setTextColor(secondary)
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        box.addView(status)
        refreshers += {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val free = Build.VERSION.SDK_INT < 23 || pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            status.setText(if (free) R.string.battery_status_ok else R.string.battery_status_limited)
        }
        val modes = arrayOf<CharSequence>(
            context.getString(R.string.battery_mode_max),
            context.getString(R.string.battery_mode_balanced),
            context.getString(R.string.battery_mode_saver),
        )
        val modeRow = actionRow(box, R.string.battery_mode) {}
        fun showMode() {
            modeRow.text = context.getString(R.string.battery_mode) + ": " + modes[st.batteryMode.coerceIn(0, 2)]
        }
        showMode()
        modeRow.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.battery_mode)
                .setSingleChoiceItems(modes, st.batteryMode.coerceIn(0, 2)) { d, which ->
                    st.batteryMode = which
                    showMode()
                    d.dismiss()
                }
                .show()
        }
        box.addView(hintView(R.string.battery_mode_hint))
        actionRow(box, R.string.battery_unrestrict) {
            (context as? android.app.Activity)?.let { com.stoyko.smessenger.requestBatteryExemption(it) }
        }
        actionRow(box, R.string.battery_autostart) { openAutostart() }
        box.addView(hintView(R.string.battery_autostart_hint))
        return card
    }

    /** Экран автозапуска у производителей, которые убивают фоновые приложения. */
    private fun openAutostart() {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            // Дополнено по проекту Nova: Honor/Huawei новых прошивок, OnePlus, OPPO, iQOO, Meizu, Tecno/Infinix/itel.
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
            "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
            "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        )
        for ((pkg, cls) in candidates) {
            val i = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
        runCatching {
            context.startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Персонализация: фон чата, форма пузырей, анимация набора текста, акцентный цвет. */
    /** Выбор иконки приложения: сетка значков, после выбора - перезапуск. */
    private fun pickAppIcon(done: () -> Unit) {
        val icons = com.stoyko.smessenger.ui.AppIcon.ICONS
        val grid = android.widget.GridLayout(context).apply {
            columnCount = 4
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        var dlg: androidx.appcompat.app.AlertDialog? = null
        val cur = com.stoyko.smessenger.ui.AppIcon.choice(context)
        for (ic in icons) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                if (ic.id == cur) background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * context.resources.displayMetrics.density
                    setColor(0x335B4BDB)
                }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(ic.res)
                }, LinearLayout.LayoutParams(dp(54), dp(54)))
                addView(TextView(context).apply {
                    setText(ic.title)
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 2
                    setTextColor(textColor)
                })
                setOnClickListener {
                    dlg?.dismiss()
                    if (ic.id == com.stoyko.smessenger.ui.AppIcon.choice(context)) return@setOnClickListener
                    com.stoyko.smessenger.ui.AppIcon.setChoice(context, ic.id)
                    done()
                    com.stoyko.smessenger.ui.AppIcon.apply(context)
                    com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.let { com.stoyko.smessenger.CrashGuard.restartApp(it, clearCache = false) }
                }
            }
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            })
        }
        dlg = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.icon_title)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun personalCard(): View {
        val (card, box) = sectionCard(R.string.sec_personal)
        val look = com.stoyko.smessenger.ui.chat.ChatLook(context)
        val bgRow = actionRow(box, R.string.chat_background) {}
        fun showBg() {
            bgRow.text = context.getString(R.string.chat_background) + ": " +
                context.getString(com.stoyko.smessenger.ui.chat.ChatLook.BACKGROUND_NAMES[look.background.coerceIn(0, com.stoyko.smessenger.ui.chat.ChatLook.BACKGROUND_NAMES.size - 1)])
        }
        showBg()
        bgRow.setOnClickListener {
            val names = com.stoyko.smessenger.ui.chat.ChatLook.BACKGROUND_NAMES.map { context.getString(it) }.toTypedArray<CharSequence>()
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.chat_background)
                .setSingleChoiceItems(names, look.background) { d, which ->
                    look.background = which
                    showBg()
                    d.dismiss()
                }
                .show()
        }
        val radiusRow = actionRow(box, R.string.bubble_radius) {}
        val radii = intArrayOf(6, 12, 18, 24)
        fun showRadius() {
            radiusRow.text = context.getString(R.string.bubble_radius) + ": " + look.bubbleRadius + " dp"
        }
        showRadius()
        radiusRow.setOnClickListener {
            val names = radii.map { "$it dp" }.toTypedArray<CharSequence>()
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.bubble_radius)
                .setSingleChoiceItems(names, radii.indexOf(look.bubbleRadius).coerceAtLeast(0)) { d, which ->
                    look.bubbleRadius = radii[which]
                    showRadius()
                    d.dismiss()
                }
                .show()
        }
        // Сезонное оформление: осень (оранжевое, листья) / Новый год (холодные цвета, снег, гирлянда).
        val seasonRow = actionRow(box, R.string.season_title) {}
        val seasons = arrayOf<CharSequence>(
            context.getString(R.string.season_off),
            context.getString(R.string.season_auto),
            context.getString(R.string.season_autumn),
            context.getString(R.string.season_winter),
            context.getString(R.string.season_spring),
            context.getString(R.string.season_summer),
        )
        fun showSeason() {
            seasonRow.text = context.getString(R.string.season_title) + ": " + seasons[com.stoyko.smessenger.ui.Seasonal.mode(context).coerceIn(0, 5)]
        }
        showSeason()
        seasonRow.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.season_title)
                .setSingleChoiceItems(seasons, com.stoyko.smessenger.ui.Seasonal.mode(context)) { dlg, which ->
                    dlg.dismiss()
                    com.stoyko.smessenger.ui.Seasonal.setMode(context, which)
                    showSeason()
                    // Тема и иконка меняются вместе - приложение перезапускается само.
                    com.stoyko.smessenger.ui.AppIcon.apply(context)
                    com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.let { com.stoyko.smessenger.CrashGuard.restartApp(it, clearCache = false) }
                }
                .show()
        }
        box.addView(hintView(R.string.season_hint))
        // Музыка сезонной темы во всём приложении.
        val tm = com.stoyko.smessenger.ui.ThemeMusic
        switchRow(box, R.string.theme_music, R.string.theme_music_hint, tm.enabled(context)) { tm.setEnabled(context, it) }
        val volRow = actionRow(box, R.string.theme_music_volume) {}
        val vols = intArrayOf(15, 30, 45, 65, 85, 100)
        fun showVol() {
            volRow.text = context.getString(R.string.theme_music_volume) + ": " + tm.volume(context) + " %"
        }
        showVol()
        volRow.setOnClickListener {
            val names = vols.map { "$it %" }.toTypedArray<CharSequence>()
            val cur = vols.indexOfFirst { it >= tm.volume(context) }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.theme_music_volume)
                .setSingleChoiceItems(names, cur) { d, which ->
                    tm.setVolume(context, vols[which])
                    showVol()
                    d.dismiss()
                }
                .show()
        }
        // Иконка приложения на рабочем столе.
        val iconRow = actionRow(box, R.string.icon_title) {}
        fun showIcon() {
            val cur = com.stoyko.smessenger.ui.AppIcon.ICONS.firstOrNull { it.id == com.stoyko.smessenger.ui.AppIcon.choice(context) }
            iconRow.text = context.getString(R.string.icon_title) + ": " + context.getString(cur?.title ?: R.string.icon_auto)
        }
        showIcon()
        iconRow.setOnClickListener { pickAppIcon { showIcon() } }
        box.addView(hintView(R.string.icon_hint))
        switchRow(box, R.string.typing_animation, R.string.typing_animation_hint, look.typingAnimation) { look.typingAnimation = it }
        // Режим для слабовидящих: крупный текст и элементы, без размытия.
        val a11y = com.stoyko.smessenger.ui.Accessibility
        fun recreateSoon() = b.root.postDelayed({ com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.recreate() }, 250)
        switchRow(box, R.string.pref_a11y, R.string.pref_a11y_hint, a11y.isOn(context)) {
            a11y.set(context, it)
            recreateSoon()
        }
        // Настройки режима (видны, только когда режим включён): размер текста, контраст, движение.
        if (a11y.isOn(context)) {
            val levels = arrayOf<CharSequence>(
                context.getString(R.string.a11y_level_large),
                context.getString(R.string.a11y_level_xlarge),
                context.getString(R.string.a11y_level_max),
            )
            val levelRow = actionRow(box, R.string.a11y_level) {}
            levelRow.text = context.getString(R.string.a11y_level) + ": " + levels[a11y.level(context)]
            levelRow.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.a11y_level)
                    .setSingleChoiceItems(levels, a11y.level(context)) { dlg, which ->
                        dlg.dismiss()
                        a11y.setLevel(context, which)
                        recreateSoon()
                    }
                    .show()
            }
            box.addView(hintView(R.string.a11y_level_hint))
            switchRow(box, R.string.a11y_contrast, R.string.a11y_contrast_hint, a11y.contrastSetting(context)) {
                a11y.setContrast(context, it)
                recreateSoon()
            }
            switchRow(box, R.string.a11y_motion, R.string.a11y_motion_hint, a11y.motionSetting(context)) {
                a11y.setMotion(context, it)
            }
        }
        // «Эффект воды» в боковом меню.
        switchRow(box, R.string.pref_water_fx, R.string.pref_water_fx_hint, com.stoyko.smessenger.ui.WaterRipple.isOn(context)) {
            com.stoyko.smessenger.ui.WaterRipple.set(context, it)
        }
        // Вид кнопок: стандартные (плоские) или объёмные 3D-клавиши.
        switchRow(box, R.string.pref_buttons3d, R.string.pref_buttons3d_hint, com.stoyko.smessenger.ui.Buttons3d.isOn(context)) {
            com.stoyko.smessenger.ui.Buttons3d.set(context, it)
            // Применяем сразу: окно пересоздаётся (как при смене масштаба).
            b.root.postDelayed({ com.stoyko.smessenger.ui.Kinetics.activityOf(context)?.recreate() }, 250)
        }
        switchRow(box, R.string.autoplay_voices, R.string.autoplay_voices_hint, look.autoplayVoices) { look.autoplayVoices = it }
        // Фото перед отправкой: пересжатие до 2560 точек (быстрее, без GPS в файле) или оригинал.
        switchRow(box, R.string.photo_original, R.string.photo_original_hint, com.stoyko.smessenger.media.MediaOptimizer.original(context)) {
            com.stoyko.smessenger.media.MediaOptimizer.setOriginal(context, it)
        }
        switchRow(box, R.string.link_previews, R.string.link_previews_hint, look.linkPreviews) { look.linkPreviews = it }
        switchRow(box, R.string.animated_emoji, R.string.animated_emoji_hint, look.animatedEmoji) { look.animatedEmoji = it }
        switchRow(box, R.string.scroll_down_button, null, look.scrollDownButton) { look.scrollDownButton = it }
        return card
    }

    private fun aboutCard(): View {
        val (card, box) = sectionCard(R.string.sec_about)
        box.addView(
            TextView(context).apply {
                setText(R.string.developer_credit)
                textSize = 14f
                setTextColor(textColor)
                setPadding(dp(18), dp(4), dp(18), dp(8))
            },
        )
        box.addView(
            com.stoyko.smessenger.chat.CoinShop.donateButton(context),
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                setMargins(dp(16), dp(4), dp(16), dp(10))
            },
        )
        actionRow(box, R.string.about) { showAbout() }
        actionRow(box, R.string.ob_replay) { com.stoyko.smessenger.ui.Onboarding.show(context) }
        actionRow(box, R.string.tour_start) {
            // Экскурсовод показывает главный экран: возвращаемся к списку чатов и запускаем его там.
            com.stoyko.smessenger.ui.Tour.launch(context)
        }
        val updRow = actionRow(box, R.string.update_check) { com.stoyko.smessenger.update.Updater.checkNow(context) }
        updRow.text = context.getString(R.string.update_check) + " · " + com.stoyko.smessenger.update.Updater.currentVersion
        // Фоновые обновления: скачать и поставить сами (нужно разрешение на установку).
        switchRow(box, R.string.update_auto_install, R.string.update_auto_install_hint, com.stoyko.smessenger.update.Updater.autoInstall(context)) { v ->
            com.stoyko.smessenger.update.Updater.setAutoInstall(context, v)
            if (v && !com.stoyko.smessenger.update.SessionInstaller.allowed(context)) com.stoyko.smessenger.update.SessionInstaller.askPermission(context)
        }

        actionRow(box, R.string.sec_help) { com.stoyko.smessenger.settings.Nodes.showGuide(context) }
        take(b.settingErrorLog).forEach { box.addView(it) }
        return card
    }
}
