// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import com.stoyko.smessenger.R
import com.stoyko.smessenger.domain.tox.ProxyType

/** SOCKS5-прокси (ядро поддерживает только SOCKS5). */
data class ProxyEntry(val host: String, val port: Int) {
    val id get() = "$host:$port"

    /** Ссылка, которой можно поделиться (её понимает и приложение). */
    fun link() = "smessenger:socks?server=$host&port=$port"
}

/**
 * Список прокси: несколько серверов, проверка доступности и задержки,
 * включение одним нажатием, добавление по ссылке (socks5://, tg://socks, t.me/socks, host:port).
 */
object Proxies {
    private const val KEY = "list"
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Результат проверки: задержка в мс, -1 - недоступен, null - ещё проверяется. */
    private val ping = ConcurrentHashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("proxies", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<ProxyEntry> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { parse(arr.getString(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<ProxyEntry>) {
        prefs(ctx).edit().putString(KEY, JSONArray(list.map { it.id }).toString()).apply()
    }

    fun add(ctx: Context, p: ProxyEntry) {
        val l = list(ctx)
        if (l.none { it.id == p.id }) save(ctx, l + p)
    }

    /** Разбор ссылки или строки host:port. */
    fun parse(raw: String): ProxyEntry? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        try {
            if (s.contains("server=")) {
                val q = s.substringAfter('?', "")
                val params = q.split('&').mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null
                }.toMap()
                val host = params["server"] ?: return null
                val port = params["port"]?.toIntOrNull() ?: 1080
                return ProxyEntry(host, port).takeIf { port in 1..65535 && host.isNotBlank() }
            }
            val noScheme = s.substringAfter("://")
            val hostPort = noScheme.substringAfterLast('@').trimEnd('/')
            val i = hostPort.lastIndexOf(':')
            if (i <= 0) return null
            val host = hostPort.substring(0, i).trim('[', ']')
            val port = hostPort.substring(i + 1).toIntOrNull() ?: return null
            return ProxyEntry(host, port).takeIf { port in 1..65535 && host.isNotBlank() }
        } catch (e: Exception) {
            return null
        }
    }

    fun isActive(settings: Settings, p: ProxyEntry) =
        settings.proxyType == ProxyType.SOCKS5 && settings.proxyAddress == p.host && settings.proxyPort == p.port

    /**
     * Проверка: TCP-подключение + приветствие SOCKS5 (без пароля).
     * Возвращает задержку в мс или -1.
     */
    private fun check(p: ProxyEntry): Long = try {
        val start = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(p.host, p.port), 4000)
            s.soTimeout = 4000
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            val r = ByteArray(2)
            var n = 0
            while (n < 2) {
                val k = s.getInputStream().read(r, n, 2 - n)
                if (k < 0) break
                n += k
            }
            if (n == 2 && r[0].toInt() == 5 && r[1].toInt() == 0) (System.nanoTime() - start) / 1_000_000 else -1L
        }
    } catch (e: Exception) {
        -1L
    }

    /** Экран «Прокси»: переключатель, список с задержкой, «Добавить прокси». */
    fun show(ctx: Context, settings: Settings, onChanged: () -> Unit) {
        var items = list(ctx)
        val density = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (8 * density).toInt()
            setPadding(p * 2, p, p * 2, p * 2)
        }
        val toggle = SwitchCompat(ctx).apply {
            text = ctx.getString(R.string.proxy_use)
            isChecked = settings.proxyType != ProxyType.None
            textSize = 16f
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        val hint = TextView(ctx).apply {
            text = ctx.getString(R.string.proxy_hint)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
        }
        val listView = ListView(ctx)
        root.addView(toggle)
        root.addView(hint)
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (260 * density).toInt()))

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                    val p = (12 * density).toInt()
                    setPadding(p, p, p, p)
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                }
                val p = items[position]
                val active = isActive(settings, p)
                val status = when (val ms = ping[p.id]) {
                    null -> ctx.getString(R.string.proxy_checking)
                    -1L -> ctx.getString(R.string.proxy_unavailable)
                    else -> ctx.getString(R.string.proxy_ping, ms)
                }
                tv.text = (if (active) "✅ " else "○ ") + p.id + "\n" + status
                return tv
            }
        }
        listView.adapter = adapter

        fun recheck() {
            items.forEach { p ->
                ping.remove(p.id)
                pool.execute {
                    val ms = check(p)
                    ping[p.id] = ms
                    main.post { adapter.notifyDataSetChanged() }
                }
            }
            adapter.notifyDataSetChanged()
        }

        fun activate(p: ProxyEntry?) {
            if (p == null) {
                settings.proxyType = ProxyType.None
            } else {
                settings.proxyType = ProxyType.SOCKS5
                settings.proxyAddress = p.host
                settings.proxyPort = p.port
                // Весь трафик через прокси: UDP отключаем, как делает и экран настроек.
                settings.udpEnabled = false
            }
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = p != null
            toggle.setOnCheckedChangeListener { _, c -> onToggle(c) }
            adapter.notifyDataSetChanged()
            onChanged()
            com.stoyko.smessenger.ui.RestartPrompt.network(ctx)
        }

        onToggleImpl = { checked ->
            if (checked) {
                val first = items.firstOrNull { (ping[it.id] ?: 0) >= 0 } ?: items.firstOrNull()
                if (first == null) {
                    Toast.makeText(ctx, R.string.proxy_add_first, Toast.LENGTH_SHORT).show()
                    toggle.isChecked = false
                } else {
                    activate(first)
                }
            } else {
                activate(null)
            }
        }
        toggle.setOnCheckedChangeListener { _, c -> onToggle(c) }

        listView.setOnItemClickListener { _, _, position, _ -> activate(items[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val p = items[position]
            AlertDialog.Builder(ctx)
                .setTitle(p.id)
                .setItems(arrayOf(ctx.getString(R.string.share), ctx.getString(R.string.copy_link), ctx.getString(R.string.delete))) { _, which ->
                    when (which) {
                        0 -> ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, p.link())
                                },
                                ctx.getString(R.string.share),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        1 -> {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("proxy", p.link()))
                            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            if (isActive(settings, p)) activate(null)
                            items = items.filter { it.id != p.id }
                            save(ctx, items)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
                .show()
            true
        }

        // Готовые варианты: Tor через Orbot или InviZible Pro (мосты obfs4/Snowflake/meek
        // настраиваются в них), и ссылка на встроенный VPN для остальных типов прокси.
        val accent = ContextCompat.getColor(ctx, R.color.colorPrimary)
        root.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.proxy_tor_preset)
                textSize = 14f
                setTextColor(accent)
                setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener {
                    add(ctx, ProxyEntry("127.0.0.1", 9050))
                    items = list(ctx)
                    recheck()
                    Toast.makeText(ctx, R.string.proxy_tor_added, Toast.LENGTH_LONG).show()
                }
            },
        )
        root.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.proxy_more_types)
                textSize = 14f
                setTextColor(accent)
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener { com.stoyko.smessenger.vpn.VpnScreen(ctx).show() }
            },
        )

        // Кнопки - одного размера и вида, ровным рядом (раньше были разной ширины, вразнобой).
        lateinit var dialog: AlertDialog
        val buttons = com.stoyko.smessenger.ui.Btn.row(
            ctx,
            listOf(
                com.stoyko.smessenger.ui.Btn.tonal(ctx, ctx.getString(R.string.proxy_check)) { recheck() },
                com.stoyko.smessenger.ui.Btn.primary(ctx, ctx.getString(R.string.proxy_add)) {
                    askAdd(ctx) { p ->
                        add(ctx, p)
                        items = list(ctx)
                        recheck()
                    }
                },
            ),
            height = 48,
            gap = 10,
        )
        root.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * density).toInt() })
        root.addView(
            com.stoyko.smessenger.ui.Btn.tonal(ctx, ctx.getString(R.string.close)) { runCatching { dialog.dismiss() } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply { topMargin = (10 * density).toInt() },
        )
        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.proxy_title)
            .setView(root)
            .create()
        dialog.setOnDismissListener { onToggleImpl = null }
        dialog.show()
        recheck()
    }

    private var onToggleImpl: ((Boolean) -> Unit)? = null
    private fun onToggle(checked: Boolean) = onToggleImpl?.invoke(checked)

    /** Добавление: адрес и порт или вставить ссылку. */
    fun askAdd(ctx: Context, onAdd: (ProxyEntry) -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val host = EditText(ctx).apply {
            hint = ctx.getString(R.string.proxy_host_or_link)
            setSingleLine()
        }
        val port = EditText(ctx).apply {
            hint = ctx.getString(R.string.pref_heading_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1080")
        }
        // Если в буфере обмена есть ссылка на прокси - подставим её.
        try {
            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val t = clip?.getItemAt(0)?.text?.toString().orEmpty()
            if (t.contains("server=") || t.startsWith("socks5://")) host.setText(t)
        } catch (_: Exception) {
        }
        box.addView(host)
        box.addView(port)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.proxy_add)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val h = host.text.toString().trim()
                val p = parse(h) ?: parse("$h:${port.text}")
                if (p == null) {
                    Toast.makeText(ctx, R.string.bad_host, Toast.LENGTH_SHORT).show()
                } else {
                    onAdd(p)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
