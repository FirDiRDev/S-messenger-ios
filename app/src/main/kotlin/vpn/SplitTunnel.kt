// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
// SPDX-FileCopyrightText: 2026 Nova-Android contributors (список российских приложений, GPL-3.0)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.stoyko.smessenger.R

/**
 * Раздельное туннелирование: какой трафик идёт через VPN.
 *  - весь телефон;
 *  - только выбранные приложения;
 *  - все, кроме выбранных.
 * «Российские приложения напрямую»: банки, Госуслуги, маркетплейсы, операторы, такси - с
 * зарубежного адреса они не пускают в аккаунт или просят лишних подтверждений, поэтому всегда
 * идут мимо VPN. Список пакетов сверен с каталогом RuStore (из проекта Nova, GPL-3.0) и
 * дополнен признаками по началу имени пакета.
 * Отдельно - быстрые наборы: «браузеры и соцсети», «игры», «выбрать всё», «снять всё».
 */
object SplitTunnel {
    const val ALL = 0
    const val ONLY = 1
    const val EXCEPT = 2

    private const val PREFS = "vpn_split"

    /** Признаки по началу имени: ловят новые и редкие приложения российских компаний. */
    private val RU_PREFIXES = listOf(
        "ru.", "com.idamob.tinkoff", "com.yandex.", "com.vk.", "com.vkontakte.", "com.mail.", "com.sber.", "com.alfa.",
        "com.vtb.", "com.gazprombank.", "com.openbank.", "com.sovcombank.", "com.sovcomcard", "com.ubrr", "com.psbank.",
        "com.rosbank.", "com.mironline.", "com.cardmobile.", "com.bspb", "com.ftc.", "com.raiffeisen.", "com.ozon.",
        "com.wildberries.", "com.avito.", "com.hh.", "com.pochta.", "com.drom.", "com.kinopoisk", "com.rutube.",
        "com.megafon.", "com.mts.", "com.beeline.", "com.tele2.", "com.rostelecom.", "com.domru.", "com.taxsee.",
        "ru.dublgis", "com.punicapp.whoosh",
    )

    /**
     * Точный список (Nova, сверено с RuStore 15.09.2026): банки, бизнес, инвестиции, Госуслуги,
     * Яндекс, VK, операторы, такси, шеринг, аптеки.
     */
    private val RU_EXACT: Set<String> = setOf(
        "ru.sberbankmobile", "ru.vtb24.mobilebanking.android", "ru.alfabank.mobile.android", "com.idamob.tinkoff.android",
        "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews", "logo.com.mbanking", "ru.sovcomcard.halva.v1",
        "ru.rshb.dbo", "ru.mkb.mobile", "ru.bankuralsib.mb.android", "ru.lewis.dbo", "ru.ozon.app.android",
        "ru.ozon.fintech.finance", "com.yandex.bank", "ru.wildberries.bank", "ru.akbars.mobile", "ru.bspb",
        "ru.simpls.brs2.mobbank", "cz.bsc.rc", "ru.otpbank.mobile", "com.bank.domrf.v2", "com.bssys.novikomretail",
        "com.idamobile.android.crediteuropa", "ru.ftc.faktura.absolutbank", "ru.zenitonline.android", "ru.skbbank.ib",
        "com.svoi.bank", "ru.avangard", "ru.centrinvest.mobilebanking2018", "cb.ibank", "com.bankffin.portfolio",
        "ru.bcs.bcsbank", "ru.nspk.sbpay", "ru.nspk.mirpay", "ru.sberbank_sbbol", "ru.vtb.smb", "ru.alfabank.oavdo.amc",
        "ru.tinkoff.sme", "ru.zhuck.webapp", "modulbank.ru.app", "ru.psbank.msb.dev.psb_appstore",
        "ru.gazprombank.mobile.gpbbusiness", "ru.raiffeisen.android.rbo", "ru.rb.business", "ru.rshb.dboul",
        "ru.uralsib.business", "ru.diftechsvc", "ru.ozon.fintech.sme", "wildberries.business", "ru.bspb.b2b",
        "ru.sberbank.investor", "ru.vtb.invest", "ru.tinkoff.investing", "ru.alfadirect.app", "ru.alfacapital.lk",
        "ru.gazprombank.invest", "ru.gazprombank.broker", "ru.psbank.invest", "ru.sovcombank.investor", "ru.region.finance",
        "ru.sinara_finance.invest", "ru.raiffeisen.capitalapp", "rshbma.invest.mapp", "ru.kode.dominvest.mobile",
        "ru.akbars.akbf", "com.arqa.absolut", "ru.broker.my", "ru.finam.terminal", "ru.tradernet.terminal",
        "ru.rosbank.android", "ru.pochtabank.android", "ru.homecredit.mobilebank",
        "com.wildberries.ru", "ru.apteka", "ru.uteka.app", "ru.medicine_it.medonline", "com.apteka.sklad",
        "ru.apteka.aptekaonline", "ru.gosuslugi.goskey", "ru.rostel", "ru.crptech.mark", "com.gnivts.selfemployed",
        "ru.fns.lkfl", "ru.mfc.mfcmain", "com.programmisty.emiasapp", "ru.dnevnik.app", "ru.mes.dnevnik", "ru.rzd.pass",
        "com.octopod.russianpost.client.android", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.taxi",
        "ru.foodfox.client", "ru.yandex.market", "ru.beru.android", "ru.yandex.translate", "ru.yandex.music",
        "ru.yandex.disk", "ru.yandex.mail", "com.yandex.mobile.drive", "ru.yandex.metro", "com.yandex.lavka",
        "ru.yandex.superapp", "ru.yandex.telemost", "ru.yandex.weatherplugin", "com.yandex.browser",
        "ru.yandex.searchplugin", "com.yandex.searchapp", "com.vkontakte.android", "com.vk.vkvideo", "com.vk.im",
        "ru.vk.store", "ru.ok.android", "ru.rutube.app", "ru.oneme.app", "ru.citymobil.driver", "ru.taxovichkof.android",
        "com.taxsee.driver", "com.taxsee.taxsee", "ru.dublgis.dgismobile", "ru.urentbike.app", "com.punicapp.whoosh",
        "com.ustasapp", "com.dlilb.profplus", "ru.litres.android.international", "ru.litres.android", "com.dartit.RTcabinet",
        "ru.pyaterochka.app.browser", "ru.yandex.uber", "ru.megafon.mlk", "ru.tele2.mytele2", "ru.beeline.services",
        "ru.mts.mymts", "ru.tinkoff.mvno", "com.mm.rk",
    )

    /** Быстрый набор «Браузеры и соцсети» (то, ради чего VPN обычно и включают). */
    private val SOCIAL = listOf(
        "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta", "com.opera.browser", "com.opera.mini.native",
        "com.brave.browser", "com.microsoft.emmx", "com.vivaldi.browser", "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser", "com.sec.android.app.sbrowser", "com.mi.globalbrowser",
        "com.google.android.youtube", "app.revanced.android.youtube", "com.instagram.android", "com.facebook.katana",
        "com.facebook.orca", "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "com.discord", "com.whatsapp",
        "com.whatsapp.w4b", "com.reddit.frontpage", "com.pinterest", "com.spotify.music", "com.linkedin.android",
        "com.snapchat.android", "tv.twitch.android.app", "com.netflix.mediaclient", "com.openai.chatgpt",
        "com.google.android.apps.bard", "com.anthropic.claude", "com.viber.voip", "com.patreon.android",
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context) = prefs(ctx).getInt("mode", ALL)
    fun setMode(ctx: Context, m: Int) = prefs(ctx).edit().putInt("mode", m).apply()
    fun apps(ctx: Context): Set<String> = prefs(ctx).getStringSet("apps", emptySet()) ?: emptySet()
    fun setApps(ctx: Context, s: Set<String>) = prefs(ctx).edit().putStringSet("apps", HashSet(s)).apply()
    fun excludeRu(ctx: Context) = prefs(ctx).getBoolean("exclude_ru", true)
    fun setExcludeRu(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("exclude_ru", v).apply()

    fun isRussian(pkg: String) = pkg in RU_EXACT || RU_PREFIXES.any { pkg.startsWith(it) }

    /** Короткое описание выбора - для кнопки на экране VPN. */
    fun summary(ctx: Context): String {
        val n = apps(ctx).size
        return when (mode(ctx)) {
            ONLY -> L("Только выбранные: $n", "Only selected: $n")
            EXCEPT -> L("Все, кроме $n", "All except $n")
            else -> L("Весь телефон", "Whole phone")
        } + if (excludeRu(ctx)) L(" · 🇷🇺 напрямую", " · 🇷🇺 direct") else ""
    }

    /**
     * Применить к создаваемому туннелю. Сам мессенджер идёт мимо туннеля (у Xray он ходит через
     * локальный прокси), кроме WARP ([includeSelf]) - там прокси нет и мессенджер идёт туннелем.
     */
    fun apply(ctx: Context, b: VpnService.Builder, includeSelf: Boolean = false) {
        val own = ctx.packageName
        val chosen = apps(ctx) - own
        when (mode(ctx)) {
            ONLY -> {
                val allowed = if (excludeRu(ctx)) chosen.filterNot { isRussian(it) } else chosen.toList()
                var any = false
                allowed.forEach { if (runCatching { b.addAllowedApplication(it) }.isSuccess) any = true }
                if (includeSelf && runCatching { b.addAllowedApplication(own) }.isSuccess) any = true
                // Ни одно выбранное приложение не установлено - не превращаем VPN в «весь телефон».
                if (!any) runCatching { b.addDisallowedApplication(own) }
            }
            else -> {
                if (!includeSelf) runCatching { b.addDisallowedApplication(own) }
                val skip = HashSet<String>()
                if (mode(ctx) == EXCEPT) skip += chosen
                if (excludeRu(ctx)) skip += installedPackages(ctx).filter { isRussian(it) }
                skip.remove(own)
                skip.forEach { runCatching { b.addDisallowedApplication(it) } }
            }
        }
    }

    /** Все установленные пакеты (без загрузки названий и значков - быстро). */
    private fun installedPackages(ctx: Context): List<String> = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getInstalledApplications(0).map { it.packageName }
    }.getOrElse { installed(ctx, system = false).map { it.pkg } }

    class App(val pkg: String, val label: String, val system: Boolean)

    /** Приложения: с иконкой на рабочем столе, а с [system] - ещё и системные службы. */
    fun installed(ctx: Context, system: Boolean = false): List<App> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, App>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName || pkg in out) continue
            val sys = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            out[pkg] = App(pkg, runCatching { ri.loadLabel(pm)?.toString() }.getOrNull() ?: pkg, sys)
        }
        if (system) {
            @Suppress("DEPRECATION")
            val all = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            for (ai in all) {
                val pkg = ai.packageName
                if (pkg == ctx.packageName || pkg in out) continue
                out[pkg] = App(pkg, runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg), true)
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    private fun L(ru: String, en: String) = if (java.util.Locale.getDefault().language == "ru") ru else en

    /**
     * Окно настройки: режим (три крупные кнопки), «российские напрямую», быстрые наборы,
     * поиск, системные приложения, счётчик выбранных. Сохранение сразу переподключает VPN.
     */
    fun show(ctx: Context, changed: () -> Unit = {}) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = 0xFF5B4BDB.toInt()
        val sub = androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        var mode = mode(ctx)
        // Режим: три крупные «пилюли» с пояснением снизу.
        val modeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val modeHint = TextView(ctx).apply {
            textSize = 12f
            setTextColor(sub)
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        val modeViews = ArrayList<TextView>()
        val modes = listOf(
            Triple(ALL, L("📱 Весь\nтелефон", "📱 Whole\nphone"), L("Все приложения идут через VPN (кроме исключений ниже).", "Every app goes through the VPN (except the exclusions below).")),
            Triple(ONLY, L("✅ Только\nвыбранные", "✅ Only\nselected"), L("Через VPN - только отмеченные приложения, остальные напрямую.", "Only the checked apps use the VPN, the rest go direct.")),
            Triple(EXCEPT, L("🚫 Все,\nкроме", "🚫 All\nexcept"), L("Через VPN - всё, кроме отмеченных приложений.", "Everything uses the VPN except the checked apps.")),
        )
        lateinit var refreshList: () -> Unit
        fun paintModes() {
            modeViews.forEachIndexed { i, tv ->
                val on = modes[i].first == mode
                tv.setTextColor(if (on) Color.WHITE else accent)
                tv.background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    if (on) setColor(accent) else {
                        setColor(0x145B4BDB)
                        setStroke(dp(1), 0x405B4BDB)
                    }
                }
            }
            modeHint.text = modes.first { it.first == mode }.third
        }
        modes.forEachIndexed { i, (m, label, _) ->
            val tv = TextView(ctx).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setOnClickListener {
                    mode = m
                    paintModes()
                    refreshList()
                }
            }
            com.stoyko.smessenger.ui.Kinetics.squish(tv, 0.94f)
            modeViews += tv
            modeRow.addView(tv, LinearLayout.LayoutParams(0, dp(54), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        box.addView(modeRow)
        box.addView(modeHint)
        val ru = SwitchCompat(ctx).apply {
            setText(R.string.split_exclude_ru)
            isChecked = excludeRu(ctx)
            textSize = 13.5f
        }
        box.addView(ru)
        val ruInfo = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(sub)
            setPadding(0, 0, 0, dp(6))
        }
        box.addView(ruInfo)
        // Быстрые наборы.
        val selected = HashSet(apps(ctx))
        var all: List<App> = emptyList()
        var shown: List<App> = emptyList()
        var showSystem = false
        val counter = TextView(ctx).apply {
            textSize = 12f
            setTextColor(accent)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(label: String, act: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(accent)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = 15 * d
                setColor(0x145B4BDB)
            }
            setOnClickListener {
                act()
                refreshList()
            }
        }.also { presets.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(6) }) }
        preset(L("🌐 Браузеры и соцсети", "🌐 Browsers & social")) {
            val have = all.map { it.pkg }.toHashSet()
            selected += SOCIAL.filter { it in have }
            if (mode == ALL) mode = ONLY
            paintModes()
        }
        preset(L("🎮 Игры", "🎮 Games")) {
            val pm = ctx.packageManager
            all.forEach { a ->
                val game = runCatching {
                    val ai = pm.getApplicationInfo(a.pkg, 0)
                    if (android.os.Build.VERSION.SDK_INT >= 26) ai.category == ApplicationInfo.CATEGORY_GAME else false
                }.getOrDefault(false)
                if (game) selected += a.pkg
            }
        }
        preset(L("☑ Выбрать все", "☑ Select all")) { selected += shown.map { it.pkg } }
        preset(L("✖ Снять все", "✖ Clear")) { selected.clear() }
        preset(L("⚙ Системные", "⚙ System")) {
            showSystem = !showSystem
            reload(ctx, showSystem) { all = it; refreshList() }
        }
        box.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(presets)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        val search = EditText(ctx).apply {
            setHint(R.string.split_search)
            setSingleLine()
            textSize = 14f
        }
        box.addView(search)
        box.addView(counter)
        val listView = ListView(ctx).apply { isFastScrollEnabled = true }
        val loading = TextView(ctx).apply {
            setText(R.string.loading)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
        }
        box.addView(loading)
        // Список - на ~45 % высоты экрана (на планшетах и в альбомной ориентации - выше).
        val h = (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt().coerceIn(dp(240), dp(620))
        box.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        val pm = ctx.packageManager
        val icons = HashMap<String, Drawable?>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val a = shown[p]
                val row = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    addView(ImageView(ctx), LinearLayout.LayoutParams(dp(34), dp(34)))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, dp(8), 0)
                        addView(TextView(ctx).apply { textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(TextView(ctx).apply { textSize = 10.5f; maxLines = 1; alpha = 0.6f; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(ctx).apply { isClickable = false; isFocusable = false })
                }
                val icon = icons.getOrPut(a.pkg) { runCatching { pm.getApplicationIcon(a.pkg) }.getOrNull() }
                (row.getChildAt(0) as ImageView).setImageDrawable(icon)
                val texts = row.getChildAt(1) as LinearLayout
                val rus = isRussian(a.pkg)
                (texts.getChildAt(0) as TextView).text = a.label + (if (rus) "  🇷🇺" else "") + (if (a.system) "  ⚙" else "")
                (texts.getChildAt(1) as TextView).text = a.pkg
                val cb = row.getChildAt(2) as CheckBox
                cb.isChecked = a.pkg in selected
                // Российское приложение при включённом «напрямую» всегда мимо VPN - отмечать бессмысленно.
                row.alpha = if (rus && ru.isChecked) 0.55f else 1f
                return row
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, p, _ ->
            val a = shown[p]
            if (!selected.remove(a.pkg)) selected += a.pkg
            refreshList()
        }
        refreshList = {
            val q = search.text.toString().trim().lowercase()
            // Отмеченные - сверху, чтобы сразу видеть выбор.
            shown = all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
                .sortedWith(compareByDescending<App> { it.pkg in selected }.thenBy { it.label.lowercase() })
            adapter.notifyDataSetChanged()
            val n = selected.count { s -> all.any { it.pkg == s } }
            counter.text = when (mode) {
                ALL -> L("Сейчас через VPN идёт весь телефон - отметки не нужны", "The whole phone uses the VPN now - no need to check apps")
                ONLY -> L("Через VPN: $n прил.", "Through VPN: $n apps")
                else -> L("Мимо VPN: $n прил.", "Bypassing VPN: $n apps")
            }
            listView.alpha = if (mode == ALL) 0.5f else 1f
            val ruCount = all.count { isRussian(it.pkg) }
            ruInfo.text = L("Найдено российских приложений: $ruCount", "Russian apps found: $ruCount")
        }
        ru.setOnCheckedChangeListener { _, _ -> refreshList() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) = refreshList()
        })
        paintModes()
        reload(ctx, false) {
            all = it
            loading.visibility = View.GONE
            refreshList()
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.split_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                setMode(ctx, mode)
                setExcludeRu(ctx, ru.isChecked)
                setApps(ctx, selected)
                changed()
                if (VpnState.status == VpnState.Status.On) SmVpnService.start(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reload(ctx: Context, system: Boolean, done: (List<App>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val list = runCatching { installed(ctx, system) }.getOrDefault(emptyList())
            main.post { done(list) }
        }, "split-apps").apply { isDaemon = true }.start()
    }
}
