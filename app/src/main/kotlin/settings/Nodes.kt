// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.settings

import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R

/** Свой узел (релей) сети: адрес, UDP-порт, TCP-порты и публичный ключ (64 hex). */
data class NodeEntry(
    val name: String,
    val host: String,
    val port: Int,
    val tcpPorts: List<Int>,
    val key: String,
    val enabled: Boolean = true,
) {
    val id get() = "$host:$port"

    /** Короткая ссылка, как в aTox: tox://КЛЮЧ@адрес:порт (можно вставлять пачкой). */
    fun toxLink(): String = "tox://$key@$host:${tcpPorts.firstOrNull() ?: port}"

    /** Ссылка для друзей: откроется в приложении и предложит добавить узел. */
    fun link(): String = "smessenger:node?host=" + Uri.encode(host) + "&port=$port&tcp=" + tcpPorts.joinToString(",") +
        "&key=$key&name=" + Uri.encode(name)
}

/**
 * «Мои узлы»: свои серверы-релеи (например, купленный VPS).
 * Добавить / изменить / проверить / выключить / удалить / поделиться. Свои узлы используются
 * первыми - соединение быстрее и надёжнее, особенно в сетях с блокировками.
 *
 * Хранятся в my_nodes.json; включённые копируются в user_nodes.json (его читает ядро).
 */
object Nodes {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val ping = ConcurrentHashMap<String, Long>()

    private fun file(ctx: Context) = File(ctx.filesDir, "my_nodes.json")
    private fun coreFile(ctx: Context) = File(ctx.filesDir, "user_nodes.json")

    fun list(ctx: Context): List<NodeEntry> = try {
        val f = file(ctx)
        if (!f.exists()) {
            importLegacy(ctx)
        } else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Узлы, импортированные раньше JSON-файлом, переносим в список. */
    private fun importLegacy(ctx: Context): List<NodeEntry> = try {
        val f = coreFile(ctx)
        if (!f.exists()) {
            emptyList()
        } else {
            val arr = JSONObject(f.readText()).getJSONArray("nodes")
            (0 until arr.length()).mapNotNull { fromCoreJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fromJson(o: JSONObject): NodeEntry? {
        val tcp = o.optJSONArray("tcp")
        return NodeEntry(
            o.optString("name"),
            o.getString("host"),
            o.optInt("port", 33445),
            if (tcp == null) emptyList() else (0 until tcp.length()).map { tcp.getInt(it) },
            o.getString("key").uppercase(),
            o.optBoolean("enabled", true),
        ).takeIf { validKey(it.key) && it.host.isNotBlank() }
    }

    private fun fromCoreJson(o: JSONObject): NodeEntry? {
        val tcp = o.optJSONArray("tcp_ports")
        val host = o.optString("ipv4").takeIf { it.isNotBlank() && it != "-" } ?: o.optString("ipv6")
        return NodeEntry(
            o.optString("name", o.optString("maintainer")),
            host,
            o.optInt("port", 33445),
            if (tcp == null) emptyList() else (0 until tcp.length()).map { tcp.getInt(it) },
            o.optString("public_key").uppercase(),
            true,
        ).takeIf { validKey(it.key) && it.host.isNotBlank() }
    }

    fun validKey(k: String) = k.length == 64 && k.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    /** Сохранить и обновить файл для ядра (только включённые). */
    fun save(ctx: Context, list: List<NodeEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("host", it.host).put("port", it.port)
                    .put("tcp", JSONArray(it.tcpPorts)).put("key", it.key).put("enabled", it.enabled),
            )
        }
        file(ctx).writeText(arr.toString())
        val core = JSONArray()
        list.filter { it.enabled }.forEach {
            core.put(
                JSONObject().put("ipv4", it.host).put("port", it.port).put("tcp_ports", JSONArray(it.tcpPorts))
                    .put("public_key", it.key).put("status_udp", true).put("status_tcp", it.tcpPorts.isNotEmpty())
                    .put("maintainer", it.name),
            )
        }
        coreFile(ctx).writeText(JSONObject().put("nodes", core).toString())
    }

    private val TOX_LINK = Regex("(?:tox://)?([0-9A-Fa-f]{64})@([A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:]+\\]):(\\d{1,5})")

    /**
     * Все узлы из текста: пачка ссылок tox://КЛЮЧ@адрес:порт (по одной на строку или через пробел),
     * ссылки smessenger:node?... и JSON. Повторы убираются.
     */
    fun parseMany(raw: String): List<NodeEntry> {
        val out = LinkedHashMap<String, NodeEntry>()
        for (m in TOX_LINK.findAll(raw)) {
            val key = m.groupValues[1].uppercase()
            val host = m.groupValues[2].trim('[', ']')
            val port = m.groupValues[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
            // Порт из ссылки годится и для UDP, и для TCP-релея; 33445 - стандартный запасной.
            val tcp = listOf(port, 33445).distinct()
            out["$host:$port"] = NodeEntry(host, host, port, tcp, key)
        }
        raw.split(Regex("\\s+")).filter { it.startsWith("smessenger:node") }.forEach { l ->
            parseLink(l)?.let { out[it.id] = it }
        }
        if (out.isEmpty()) parseLink(raw)?.let { out[it.id] = it }
        return out.values.toList()
    }

    /** Разбор ссылки smessenger:node?... (или JSON одного узла). */
    fun parseLink(raw: String): NodeEntry? = try {
        val s = raw.trim()
        if (s.startsWith("tox://") || TOX_LINK.matches(s)) {
            TOX_LINK.find(s)?.let { m ->
                val port = m.groupValues[3].toInt()
                NodeEntry(m.groupValues[2], m.groupValues[2], port, listOf(port, 33445).distinct(), m.groupValues[1].uppercase())
            }
        } else if (s.startsWith("{")) {
            fromCoreJson(JSONObject(s)) ?: fromJson(JSONObject(s))
        } else {
            val q = s.substringAfter('?', "")
            val p = q.split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null
            }.toMap()
            val host = p["host"] ?: return null
            val key = p["key"]?.uppercase() ?: return null
            NodeEntry(
                p["name"].orEmpty(),
                host,
                p["port"]?.toIntOrNull() ?: 33445,
                p["tcp"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 },
                key,
            ).takeIf { validKey(key) }
        }
    } catch (e: Exception) {
        null
    }

    fun add(ctx: Context, n: NodeEntry) {
        save(ctx, list(ctx).filterNot { it.id == n.id } + n)
    }

    /** Проверка: TCP-подключение к порту узла, задержка в мс или -1. */
    private fun check(n: NodeEntry): Long {
        val ports = (n.tcpPorts.ifEmpty { listOf(n.port) })
        for (port in ports) {
            try {
                val start = System.nanoTime()
                Socket().use { s -> s.connect(InetSocketAddress(n.host, port), 4000) }
                return (System.nanoTime() - start) / 1_000_000
            } catch (_: Exception) {
            }
        }
        return -1
    }

    private fun status(ctx: Context, n: NodeEntry): String {
        if (!n.enabled) return ctx.getString(R.string.node_disabled)
        return when (val p = ping[n.id]) {
            null -> "…"
            -1L -> "🔴 " + ctx.getString(R.string.node_unreachable)
            else -> "🟢 $p " + ctx.getString(R.string.ms)
        }
    }

    /** Главный список «Мои узлы». [onChanged] - применить (перезагрузить список узлов ядра). */
    fun show(ctx: Context, onChanged: () -> Unit) {
        val nodes = list(ctx)
        val items = nodes.map { n ->
            val title = n.name.ifEmpty { n.host }
            "🛰  $title\n     ${n.host}:${n.port}  ·  TCP ${n.tcpPorts.joinToString(",").ifEmpty { "—" }}  ·  ${status(ctx, n)}"
        } + listOf(
            "➕  " + ctx.getString(R.string.node_add),
            "📋  " + ctx.getString(R.string.node_paste),
            "📤  " + ctx.getString(R.string.node_share_all),
            "📖  " + ctx.getString(R.string.node_guide),
            "🔄  " + ctx.getString(R.string.nodes_check_title),
            "🌐  " + ctx.getString(R.string.nodes_sources_title),
        )
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.my_nodes)
            .setItems(items.toTypedArray()) { _, which ->
                when (which - nodes.size) {
                    0 -> edit(ctx, null) { onChanged(); show(ctx, onChanged) }
                    1 -> pasteFromClipboard(ctx) { onChanged(); show(ctx, onChanged) }
                    2 -> shareAll(ctx)
                    3 -> showGuide(ctx)
                    4 -> NodeHealth.checkAndReport(ctx) { onChanged() }
                    5 -> NodeHealth.editSources(ctx)
                    else -> actions(ctx, nodes[which], onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.node_check_all, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            dialog.dismiss()
            checkAll(ctx) { show(ctx, onChanged) }
        }
        if (nodes.isEmpty()) {
            Toast.makeText(ctx, R.string.node_none_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAll(ctx: Context, done: () -> Unit) {
        val nodes = list(ctx).filter { it.enabled }
        if (nodes.isEmpty()) {
            done()
            return
        }
        Toast.makeText(ctx, R.string.node_checking, Toast.LENGTH_SHORT).show()
        var left = nodes.size
        nodes.forEach { n ->
            pool.execute {
                ping[n.id] = check(n)
                main.post {
                    left--
                    if (left == 0) done()
                }
            }
        }
    }

    private fun actions(ctx: Context, n: NodeEntry, onChanged: () -> Unit) {
        val items = arrayOf(
            "✏️  " + ctx.getString(R.string.node_edit),
            "📡  " + ctx.getString(R.string.node_check),
            (if (n.enabled) "⏸  " else "▶️  ") + ctx.getString(if (n.enabled) R.string.node_disable else R.string.node_enable),
            "🔗  " + ctx.getString(R.string.share),
            "🗑  " + ctx.getString(R.string.delete),
        )
        AlertDialog.Builder(ctx)
            .setTitle(n.name.ifEmpty { n.host })
            .setItems(items) { _, a ->
                when (a) {
                    0 -> edit(ctx, n) { onChanged(); show(ctx, onChanged) }
                    1 -> {
                        Toast.makeText(ctx, R.string.node_checking, Toast.LENGTH_SHORT).show()
                        pool.execute {
                            val ms = check(n)
                            ping[n.id] = ms
                            main.post {
                                Toast.makeText(
                                    ctx,
                                    if (ms >= 0) ctx.getString(R.string.node_ok, ms) else ctx.getString(R.string.node_unreachable),
                                    Toast.LENGTH_LONG,
                                ).show()
                                show(ctx, onChanged)
                            }
                        }
                    }
                    2 -> {
                        save(ctx, list(ctx).map { if (it.id == n.id) it.copy(enabled = !it.enabled) else it })
                        onChanged()
                        show(ctx, onChanged)
                    }
                    3 -> ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, n.toxLink() + "\n" + n.link())
                            },
                            ctx.getString(R.string.share),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    4 -> AlertDialog.Builder(ctx)
                        .setMessage(ctx.getString(R.string.node_delete_confirm, n.name.ifEmpty { n.host }))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            save(ctx, list(ctx).filterNot { it.id == n.id })
                            onChanged()
                            show(ctx, onChanged)
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> show(ctx, onChanged) }
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show(ctx, onChanged) }
            .show()
    }

    private fun pasteFromClipboard(ctx: Context, onAdded: () -> Unit) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        val found = parseMany(text)
        when {
            found.isEmpty() -> Toast.makeText(ctx, R.string.node_paste_failed, Toast.LENGTH_LONG).show()
            found.size == 1 -> confirmAdd(ctx, found[0], onAdded)
            else -> AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.node_add_many, found.size))
                .setMessage(found.joinToString("\n") { "• ${it.host}:${it.port}" })
                .setPositiveButton(R.string.save) { _, _ ->
                    val ids = found.map { it.id }.toSet()
                    save(ctx, list(ctx).filterNot { it.id in ids } + found)
                    Toast.makeText(ctx, ctx.getString(R.string.node_added_many, found.size), Toast.LENGTH_SHORT).show()
                    onAdded()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Поделиться всеми своими узлами - пачкой ссылок tox://, их можно вставить в StoykoMessenger или aTox. */
    private fun shareAll(ctx: Context) {
        val nodes = list(ctx)
        if (nodes.isEmpty()) {
            Toast.makeText(ctx, R.string.node_none_hint, Toast.LENGTH_LONG).show()
            return
        }
        val text = nodes.joinToString("\n") { it.toxLink() }
        val host = ctx as? com.stoyko.smessenger.MainActivity
        if (host != null) {
            AlertDialog.Builder(ctx)
                .setItems(
                    arrayOf<CharSequence>(ctx.getString(R.string.share_to_contact), ctx.getString(R.string.share_other_app)),
                ) { _, which ->
                    if (which == 0) host.shareToContact(text) else shareExternalText(ctx, text)
                }
                .show()
            return
        }
        shareExternalText(ctx, text)
    }

    private fun shareExternalText(ctx: Context, text: String) {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                ctx.getString(R.string.node_share_all),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** «Добавить узел …?» - для ссылок из буфера и из других приложений. */
    fun confirmAdd(ctx: Context, n: NodeEntry, onAdded: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.node_add)
            .setMessage("${n.name.ifEmpty { n.host }}\n${n.host}:${n.port}\nTCP: ${n.tcpPorts.joinToString(", ")}\n${n.key.take(16)}…")
            .setPositiveButton(R.string.save) { _, _ ->
                add(ctx, n)
                Toast.makeText(ctx, R.string.node_saved, Toast.LENGTH_SHORT).show()
                onAdded()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun field(ctx: Context, hint: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(ctx).apply {
            setHint(hint)
            setText(value)
            inputType = type
            setSingleLine()
        }

    /** Форма узла: название, адрес, порт, TCP-порты, ключ. */
    private fun edit(ctx: Context, old: NodeEntry?, onSaved: () -> Unit) {
        val name = field(ctx, R.string.node_name, old?.name.orEmpty())
        val host = field(ctx, R.string.node_host, old?.host.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(ctx, R.string.node_port, (old?.port ?: 33445).toString(), InputType.TYPE_CLASS_NUMBER)
        val tcp = field(ctx, R.string.node_tcp_ports, (old?.tcpPorts ?: listOf(443, 3389, 33445)).joinToString(", "))
        val key = field(ctx, R.string.node_key, old?.key.orEmpty()).apply {
            typeface = Typeface.MONOSPACE
            setSingleLine(false)
            maxLines = 3
        }
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            listOf(name, host, port, tcp, key).forEach { addView(it) }
        }
        val scroll = ScrollView(ctx).apply { addView(box) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (old == null) R.string.node_add else R.string.node_edit)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Проверяем поля, не закрывая окно при ошибке.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val h = host.text.toString().trim()
            val p = port.text.toString().trim().toIntOrNull()
            val k = key.text.toString().trim().replace(" ", "").uppercase()
            val tcpPorts = tcp.text.toString().split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..65535 }.distinct()
            when {
                h.isEmpty() -> host.error = ctx.getString(R.string.bad_host)
                p == null || p !in 1..65535 -> port.error = ctx.getString(R.string.bad_port)
                !validKey(k) -> key.error = ctx.getString(R.string.node_bad_key)
                else -> {
                    val n = NodeEntry(name.text.toString().trim(), h, p, tcpPorts, k, old?.enabled ?: true)
                    val rest = list(ctx).filterNot { it.id == old?.id || it.id == n.id }
                    save(ctx, rest + n)
                    Toast.makeText(ctx, R.string.node_saved, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSaved()
                }
            }
        }
    }

    /** Команды из инструкции - для кнопки «Скопировать команды». */
    val SERVER_COMMANDS = """curl -fsSL https://get.docker.com | sh
sudo useradd --home-dir /var/lib/tox-bootstrapd --create-home --system --shell /sbin/nologin --user-group tox-bootstrapd
sudo chmod 700 /var/lib/tox-bootstrapd
sudo docker run -d --name tox-bootstrapd --restart always \
  --user "${'$'}(id -u tox-bootstrapd):${'$'}(id -g tox-bootstrapd)" \
  -v /var/lib/tox-bootstrapd/:/var/lib/tox-bootstrapd/ \
  --ulimit nofile=32768:32768 \
  -p 443:443 -p 3389:3389 -p 33445:33445 -p 33445:33445/udp \
  toxchat/bootstrap-node
sudo ufw allow 443/tcp; sudo ufw allow 3389/tcp; sudo ufw allow 33445/tcp; sudo ufw allow 33445/udp
sudo docker logs tox-bootstrapd 2>&1 | grep -i "public key"
"""

    /** Инструкция: как купить сервер, создать свой узел и добавить его в приложение. */
    fun showGuide(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        val text = TextView(ctx).apply {
            setText(R.string.node_guide_text)
            textSize = 14f
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        val scroll = ScrollView(ctx).apply {
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.node_guide)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.node_copy_commands) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("commands", SERVER_COMMANDS))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
