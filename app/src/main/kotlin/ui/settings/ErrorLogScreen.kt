// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.settings

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.domain.Diagnostics
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Журнал ошибок на весь экран. Раньше там были только перехваченные исключения, и журнал почти
 * всегда был пуст («ошибок не записано»), хотя предупреждения и ошибки в системном журнале были.
 * Теперь три вкладки: «Ошибки» (вылеты и перехваченные ошибки), «Системный журнал» (предупреждения
 * и ошибки этого приложения из logcat) и «Устройство» (модель, Android, версия). Кнопки -
 * одинаковые и крупные: Копировать, Поделиться, Очистить, Закрыть.
 */
object ErrorLogScreen {
    private val NOISE = listOf(
        "W/InsetsSource", "RippleDrawable.STYLE_PATTERNED", "requestLayout() improperly called",
        "W/WindowOnBackDispatcher", "E/ImeBackDispatcher", "W/BpBinder", "destroyed after setInheritRt",
        "W/HWUI", "W/MIUIInput", "E/SBE",
    )

    /** Предупреждения и ошибки только этого процесса из системного журнала. */
    fun systemLog(): String = try {
        val pid = android.os.Process.myPid().toString()
        // С Android 7 журнал фильтруется по процессу самим logcat - быстрее и ничего не теряется.
        val cmd = if (Build.VERSION.SDK_INT >= 24) {
            listOf("logcat", "-d", "-v", "time", "--pid=$pid", "-t", "3000", "*:W")
        } else {
            listOf("logcat", "-d", "-v", "time", "-t", "3000", "*:W")
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        val mine = if (Build.VERSION.SDK_INT >= 24) lines else lines.filter { it.contains("($pid)") || it.contains(" $pid ") }
        // Служебный «шум» системы (вставки окна, анимации ripple и т. п.) - не ошибки приложения.
        mine.filter { line -> !line.startsWith("-----") && NOISE.none { line.contains(it) } }.takeLast(1200).joinToString("\n")
    } catch (e: Exception) {
        "logcat: $e"
    }

    /** Сеть и энергия: тип подключения, VPN, режим батареи, последние проверки обновлений. */
    /** События этого и прошлого запуска: экраны, сеть, звонки, VPN - что было перед ошибкой. */
    fun events(ctx: Context): String = buildString {
        Diagnostics.context?.let { c -> runCatching { append(ctx.getString(R.string.log_state)).append(": ").append(c()).append("\n\n") } }
        val prev = Diagnostics.previousCrumbs()
        if (prev.isNotBlank()) append("--- ").append(ctx.getString(R.string.log_prev_run)).append(" ---\n").append(prev).append("\n\n")
        append("--- ").append(ctx.getString(R.string.log_this_run)).append(" ---\n").append(Diagnostics.crumbsText())
    }

    fun networkInfo(ctx: Context): String = buildString {
        val f = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US)
        fun time(t: Long) = if (t <= 0) "-" else f.format(java.util.Date(t))
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = if (Build.VERSION.SDK_INT >= 23) runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() else null
        val type = when {
            caps == null -> "нет сети"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "другая"
        }
        append("Сеть: ").append(type)
        if (caps != null) {
            append(", интернет ").append(if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "проверен" else "не подтверждён")
            append(", ↓").append(caps.linkDownstreamBandwidthKbps / 1000).append(" Мбит/с")
        }
        append('\n')
        val vpn = com.stoyko.smessenger.vpn.VpnState
        append("VPN: ").append(vpn.status.name)
        if (vpn.profileName.isNotBlank()) append(" · ").append(vpn.profileName)
        if (vpn.error.isNotBlank()) append(" · ошибка: ").append(vpn.error)
        append(", мессенджер через VPN: ").append(if (vpn.toxRouted) "да" else "нет").append('\n')
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        append("Энергосбережение системы: ").append(if (pm?.isPowerSaveMode == true) "вкл" else "выкл")
        append(", исключение из оптимизации батареи: ")
            .append(
                if (Build.VERSION.SDK_INT < 23 || pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) "да" else "НЕТ (связь в фоне может рваться)",
            )
            .append('\n')
        val hint = com.stoyko.smessenger.domain.feature.PowerHint
        append("Экономия интерфейса: ").append(if (com.stoyko.smessenger.ui.EnergySaver.on) "вкл" else "выкл")
        append(", ядро: экран ").append(if (hint.screenOff) "выкл" else "вкл")
        append(", экономия ").append(if (hint.saver) "вкл" else "выкл").append('\n')
        // 4.12.1: класс телефона, облегчённый режим и экран - чтобы по журналу было видно,
        // почему анимации упрощены (или нет).
        val lm = com.stoyko.smessenger.ui.LiteMode
        append("Телефон: ").append(runCatching { com.stoyko.smessenger.ui.DeviceProfile.describe(ctx) }.getOrDefault("?"))
        append(", ядер ").append(Runtime.getRuntime().availableProcessors())
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            append(", ОЗУ ").append(mi.totalMem / (1024 * 1024)).append(" МБ")
        }
        append('\n')
        append("Облегчённый режим: ").append(if (lm.on) "вкл" else "выкл")
        val why = listOfNotNull(
            "слабый телефон".takeIf { lm.weakDevice },
            "перегрев".takeIf { lm.hot },
            "подтормаживания".takeIf { lm.janky },
        )
        if (why.isNotEmpty()) append(" (").append(why.joinToString(", ")).append(")")
        append(", анимации: ").append(if (com.stoyko.smessenger.ui.Kinetics.enabled) "вкл" else "выкл")
        runCatching {
            val scale = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            append(", системный масштаб анимаций ").append(scale)
        }
        append(", шрифт ×").append(ctx.resources.configuration.fontScale).append('\n')
        val up = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        append("Обновления: последняя проверка ").append(time(up.getLong("last_check", 0)))
        append(", последняя неудача ").append(time(up.getLong("last_fail", 0)))
        val found = up.getInt("found_code", 0)
        if (found > BuildConfig.VERSION_CODE) append(", найдена версия ").append(found)
        append('\n')
    }

    /** Подсветка: вылеты и ошибки - красным, предупреждения - жёлтым, время - серым. */
    private fun colorize(text: String, query: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        val q = query.trim().lowercase()
        var shown = 0
        for (line in text.lineSequence()) {
            if (q.isNotEmpty() && !line.lowercase().contains(q)) continue
            val start = sb.length
            sb.append(line).append('\n')
            shown++
            val color = when {
                line.contains("ВЫЛЕТ") || line.contains("FATAL") || line.contains(" E/") || line.startsWith("E/") ||
                    line.contains("Exception") || line.contains("Error") -> 0xFFEF4444.toInt()
                line.contains(" W/") || line.startsWith("W/") || line.contains("Warn") -> 0xFFF59E0B.toInt()
                line.startsWith("===") || line.startsWith("[") -> 0xFF7B6CF6.toInt()
                else -> 0
            }
            if (color != 0) {
                sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (line.contains("ВЫЛЕТ") || line.contains("FATAL")) {
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sb.length > 120_000) break
        }
        if (q.isNotEmpty() && shown == 0) sb.append("— ничего не найдено по «").append(query).append("» —")
        return sb
    }

    /** Сводка над журналом: сколько вылетов, ошибок и предупреждений. */
    private fun summary(text: String): String {
        var crashes = 0
        var errors = 0
        var warns = 0
        for (l in text.lineSequence()) {
            when {
                l.contains("ВЫЛЕТ") || l.contains("FATAL") -> crashes++
                l.contains(" E/") || l.startsWith("E/") || l.contains("Exception") -> errors++
                l.contains(" W/") || l.startsWith("W/") -> warns++
            }
        }
        return "💥 $crashes   ⛔ $errors   ⚠️ $warns"
    }

    fun deviceInfo(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        return buildString {
            append("S-messenger ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Экран: ").append(dm.widthPixels).append('×').append(dm.heightPixels)
                .append(", плотность ").append(dm.densityDpi).append(" dpi\n")
            append("Процессор: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
            val rt = Runtime.getRuntime()
            append("Память: занято ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append(" МБ из ")
                .append(rt.maxMemory() / 1048576).append(" МБ\n")
        }
    }

    fun show(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorBackground))
        }
        root.addView(
            TextView(ctx).apply {
                setText(R.string.pref_error_log)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                setPadding(dp(20), dp(16), dp(20), dp(8))
            },
        )
        val tabs = TabLayout(ctx).apply {
            // Порядок важен: сначала допустимое везде выравнивание, потом режим, потом нужное
            // (иначе в журнал - «MODE_SCROLLABLE + GRAVITY_FILL is not supported»).
            tabGravity = TabLayout.GRAVITY_CENTER
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
        }
        listOf(R.string.log_tab_errors, R.string.log_tab_system, R.string.log_tab_events, R.string.log_tab_network, R.string.log_tab_device).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        root.addView(tabs)
        val counts = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        val search = android.widget.EditText(ctx).apply {
            setHint(R.string.log_search_hint)
            setSingleLine()
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(counts)
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(6), dp(12), 0)
        })
        val text = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        // Строки переносятся по ширине экрана (раньше текст и консоль уходили за край телефона).
        text.setHorizontallyScrolling(false)
        if (Build.VERSION.SDK_INT >= 23) text.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var current = ""
        fun render() {
            text.text = colorize(current.takeLast(120_000), search.text?.toString().orEmpty())
        }
        fun load(tab: Int) {
            text.setText(R.string.loading)
            Thread {
                val s = when (tab) {
                    0 -> Diagnostics.history().ifEmpty { ctx.getString(R.string.error_log_empty_long) }
                    1 -> systemLog().ifEmpty { ctx.getString(R.string.error_log_system_empty) }
                    2 -> events(ctx)
                    3 -> networkInfo(ctx)
                    else -> deviceInfo(ctx)
                }
                text.post {
                    current = s
                    counts.text = if (tab <= 1) summary(s) else ""
                    counts.visibility = if (tab <= 1) android.view.View.VISIBLE else android.view.View.GONE
                    render()
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }.start()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) = render()
        })
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) = load(t.position)
            override fun onTabUnselected(t: TabLayout.Tab) = Unit
            override fun onTabReselected(t: TabLayout.Tab) = load(t.position)
        })

        fun full() = deviceInfo(ctx) + "\n=== Сеть ===\n" + networkInfo(ctx) +
            "\n=== События ===\n" + events(ctx) +
            "\n=== Ошибки ===\n" + Diagnostics.history() +
            "\n\n=== Системный журнал ===\n" + systemLog()

        // Кнопки: сетка 2×2, одинаковой ширины и высоты - удобно на любом экране.
        fun button(label: Int, filled: Boolean, click: () -> Unit) = MaterialButton(
            ctx,
            null,
            if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            setText(label)
            isAllCaps = false
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(46)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(6), 0, dp(6), 0)
            cornerRadius = dp(14)
            setOnClickListener { click() }
        }
        fun row(a: MaterialButton, b: MaterialButton) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(a, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
            addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        }
        val copy = button(R.string.copy, false) {
            Thread {
                val all = full()
                text.post {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("log", all.takeLast(200_000)))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        val share = button(R.string.share, true) {
            Thread {
                val all = full()
                // Журнал уходит файлом (.txt): мессенджеры обрезают длинный текст, а файл - целиком.
                val uri = runCatching {
                    val dir = java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                    val f = java.io.File(dir, "S-messenger-log-${BuildConfig.VERSION_CODE}-$stamp.txt")
                    dir.listFiles()?.forEach { if (it != f) it.delete() }
                    f.writeText(all)
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
                }.getOrNull()
                text.post {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        if (uri != null) {
                            send.putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "S-messenger ${BuildConfig.VERSION_NAME} — журнал")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } else {
                            send.putExtra(Intent.EXTRA_TEXT, all.takeLast(200_000))
                        }
                        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share)))
                    }
                }
            }.start()
        }
        val clear = button(R.string.clear, false) {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setMessage(R.string.error_log_clear_confirm)
                .setPositiveButton(R.string.clear) { _, _ ->
                    Diagnostics.clearHistory()
                    runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) }
                    load(tabs.selectedTabPosition)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val close = button(R.string.close, false) { dialog.dismiss() }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            addView(row(copy, share))
            addView(row(clear, close), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        root.addView(buttons)
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        load(0)
    }
}
