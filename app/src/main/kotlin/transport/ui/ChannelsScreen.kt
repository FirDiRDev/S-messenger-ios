// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import io.nayuki.qrcodegen.QrCode
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.transport.BridgeMode
import com.stoyko.smessenger.transport.Channel
import com.stoyko.smessenger.transport.TorRunner
import com.stoyko.smessenger.transport.TransportManager
import com.stoyko.smessenger.transport.TransportMode
import com.stoyko.smessenger.transport.TransportPermissions
import com.stoyko.smessenger.transport.TransportPrefs
import com.stoyko.smessenger.transport.WifiDirect
import com.stoyko.smessenger.transport.bt.BtKeys
import com.stoyko.smessenger.transport.bt.BtLink
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss

/**
 * Настройки → «Каналы связи»: режим (Авто / Tox / Tor / Без интернета), живое состояние
 * (текущий канал, загрузка Tor, телефоны рядом), мосты Tor, Wi-Fi Direct и Bluetooth.
 * Экран строится кодом, обновляется по событиям [TransportManager] (без опроса в цикле).
 */
class ChannelsScreen(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val bg = context.resources.getColor(R.color.colorBackground)

    @Suppress("DEPRECATION")
    private val surface = context.resources.getColor(R.color.colorSurface)

    @Suppress("DEPRECATION")
    private val textColor = context.resources.getColor(R.color.textPrimary)

    @Suppress("DEPRECATION")
    private val secondary = context.resources.getColor(R.color.textSecondary)

    @Suppress("DEPRECATION")
    private val accent = context.resources.getColor(R.color.settingsAccent)

    // Шапка состояния.
    private val pulse = PulseView(context)
    private val heroEmoji = TextView(context)
    private val heroTitle = TextView(context)
    private val heroDetail = TextView(context)
    private val torBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)

    private val modeTiles = LinkedHashMap<TransportMode, LinearLayout>()
    private val bridgeChips = LinkedHashMap<BridgeMode, TextView>()
    private val afterChips = LinkedHashMap<Int, TextView>()
    private val customBox = LinearLayout(context)
    private val torInfo = TextView(context)

    private val wifiStatus = TextView(context)
    private val wifiNet = TextView(context)
    private val wifiQr = ImageView(context)
    private val wifiPeers = LinearLayout(context)
    private var qrFor: String? = null

    private val btSwitch = SwitchCompat(context)
    private val btStatus = TextView(context)
    private val btNearby = LinearLayout(context)

    private val listener: () -> Unit = { if (dialog.isShowing) render() }
    private var permWatch: Runnable? = null

    fun show() {
        BtKeys.init(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header())
        val scroll = ScrollView(context).apply { isFillViewport = true }
        // На планшетах - колонка не шире 640 dp по центру.
        val frame = FrameLayout(context)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        frame.addView(col, FrameLayout.LayoutParams(minOf(context.resources.displayMetrics.widthPixels, dp(640)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.addView(frame)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(heroCard())
        col.addView(modeCard())
        col.addView(torCard())
        col.addView(wifiCard())
        col.addView(btCard())
        col.addView(securityCard())

        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.setOnDismissListener {
            TransportManager.unlisten(listener)
            pulse.stop()
            permWatch?.let { main.removeCallbacks(it) }
            permWatch = null
        }
        TransportManager.listen(listener)
        render()
        dialog.show()
        com.stoyko.smessenger.ui.Kinetics.stagger((0 until col.childCount).map { col.getChildAt(it) }, step = 40)
        TransportManager.poke(context)
    }

    // ---------- Построение ----------

    private fun header(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7B6CF6.toInt(), 0xFF5B4BDB.toInt()))
        }
        bar.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                contentDescription = context.getString(R.string.transport_back)
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        bar.addView(
            TextView(context).apply {
                setText(R.string.transport_title)
                textSize = 19f
                paint.isFakeBoldText = true
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return bar
    }

    private fun card(): Pair<View, LinearLayout> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(surface)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(14), dp(7), dp(14), dp(7)) }
        }
        return box to box
    }

    private fun heading(box: LinearLayout, res: Int) {
        box.addView(
            TextView(context).apply {
                setText(res)
                textSize = 15f
                paint.isFakeBoldText = true
                setTextColor(accent)
                setPadding(0, 0, 0, dp(6))
            },
        )
    }

    private fun hint(box: LinearLayout, res: Int): TextView = TextView(context).apply {
        setText(res)
        textSize = 13f
        setTextColor(secondary)
        setPadding(0, dp(2), 0, dp(8))
        box.addView(this)
    }

    private fun heroCard(): View {
        val (card, box) = card()
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val holder = FrameLayout(context)
        holder.addView(pulse, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        heroEmoji.textSize = 30f
        heroEmoji.gravity = Gravity.CENTER
        holder.addView(heroEmoji, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        box.addView(holder, LinearLayout.LayoutParams(dp(80), dp(80)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        heroTitle.textSize = 18f
        heroTitle.paint.isFakeBoldText = true
        heroTitle.setTextColor(textColor)
        heroDetail.textSize = 13f
        heroDetail.setTextColor(secondary)
        torBar.max = 100
        torBar.visibility = View.GONE
        texts.addView(heroTitle)
        texts.addView(heroDetail)
        texts.addView(torBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(6) })
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun modeCard(): View {
        val (card, box) = card()
        heading(box, R.string.transport_sec_mode)
        val items = listOf(
            Triple(TransportMode.AUTO, "✨", R.string.transport_mode_auto to R.string.transport_mode_auto_hint),
            Triple(TransportMode.TOX, Channel.TOX.emoji, R.string.transport_mode_tox to R.string.transport_mode_tox_hint),
            Triple(TransportMode.TOR, Channel.TOR.emoji, R.string.transport_mode_tor to R.string.transport_mode_tor_hint),
            Triple(TransportMode.OFFLINE, Channel.WIFI.emoji, R.string.transport_mode_offline to R.string.transport_mode_offline_hint),
        )
        // Сетка 2×2: на любом экране все четыре режима видны сразу.
        for (rowItems in items.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, (mode, emoji, texts) ->
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener { pickMode(mode) }
                    com.stoyko.smessenger.ui.Kinetics.squish(this, 0.96f)
                }
                tile.addView(TextView(context).apply { text = emoji; textSize = 22f })
                tile.addView(
                    TextView(context).apply {
                        setText(texts.first)
                        textSize = 15f
                        paint.isFakeBoldText = true
                        setTextColor(textColor)
                        maxLines = 1
                    },
                )
                tile.addView(
                    TextView(context).apply {
                        setText(texts.second)
                        textSize = 12f
                        setTextColor(secondary)
                        maxLines = 2
                    },
                )
                modeTiles[mode] = tile
                row.addView(
                    tile,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (i == 0) marginEnd = dp(5) else marginStart = dp(5)
                    },
                )
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        return card
    }

    private fun chip(text: CharSequence, click: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        setOnClickListener { click() }
    }

    private fun chipRow(chips: List<View>): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEach { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun torCard(): View {
        val (card, box) = card()
        heading(box, R.string.transport_sec_tor)
        box.addView(
            TextView(context).apply {
                setText(R.string.transport_bridges)
                textSize = 14f
                setTextColor(textColor)
            },
        )
        val names = mapOf(
            BridgeMode.AUTO to R.string.transport_br_auto,
            BridgeMode.SNOWFLAKE to R.string.transport_br_snowflake,
            BridgeMode.MEEK to R.string.transport_br_meek,
            BridgeMode.OBFS4 to R.string.transport_br_obfs4,
            BridgeMode.CUSTOM to R.string.transport_br_custom,
            BridgeMode.NONE to R.string.transport_br_none,
        )
        names.forEach { (m, res) -> bridgeChips[m] = chip(context.getString(res)) { pickBridges(m) } }
        box.addView(chipRow(bridgeChips.values.toList()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        hint(box, R.string.transport_bridges_hint)

        // Свои мосты.
        customBox.orientation = LinearLayout.VERTICAL
        val edit = EditText(context).apply {
            setText(TransportPrefs.customBridgesRaw(context))
            textSize = 13f
            setTextColor(textColor)
            setHintTextColor(secondary)
            setHint(R.string.transport_custom_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
        }
        customBox.addView(edit)
        customBox.addView(
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_save), accent) {
                TransportPrefs.setCustomBridges(context, edit.text.toString())
                TorRunner.reconfigure(context)
                Toast.makeText(context, R.string.transport_saved, Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        box.addView(customBox)

        val autoSw = SwitchCompat(context).apply {
            setText(R.string.transport_auto_tor_switch)
            textSize = 15f
            setTextColor(textColor)
            isChecked = TransportPrefs.autoTorAllowed(context)
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, c ->
                TransportPrefs.setAutoTorAllowed(context, c)
                TransportManager.poke(context)
            }
        }
        box.addView(autoSw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(
            TextView(context).apply {
                setText(R.string.transport_auto_after)
                textSize = 14f
                setTextColor(textColor)
                setPadding(0, dp(6), 0, dp(4))
            },
        )
        listOf(30, 45, 90, 180).forEach { s ->
            afterChips[s] = chip(context.getString(R.string.transport_seconds, s)) {
                TransportPrefs.setAutoTorAfterSec(context, s)
                render()
            }
        }
        box.addView(chipRow(afterChips.values.toList()))
        torInfo.textSize = 13f
        torInfo.setTextColor(secondary)
        torInfo.setPadding(0, dp(8), 0, 0)
        box.addView(torInfo)
        box.addView(
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_tor_restart), accent) {
                TorRunner.stop()
                main.postDelayed({ TransportManager.poke(context) }, 800)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        return card
    }

    private fun wifiCard(): View {
        val (card, box) = card()
        heading(box, R.string.transport_sec_wifi)
        hint(box, R.string.transport_wifi_hint)
        val buttons = listOf(
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_wifi_create), accent) {
                withPerms(TransportPermissions.wifiDirect()) { WifiDirect.createGroup(context) }
            },
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_wifi_find), accent) {
                withPerms(TransportPermissions.wifiDirect()) { WifiDirect.discover(context) }
            },
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_wifi_stop), 0xFFEF4444.toInt()) { WifiDirect.stop() },
        )
        box.addView(com.stoyko.smessenger.ui.Btn.row(context, buttons))
        wifiStatus.textSize = 13f
        wifiStatus.setTextColor(secondary)
        wifiStatus.setPadding(0, dp(8), 0, dp(4))
        box.addView(wifiStatus)
        wifiNet.textSize = 14f
        wifiNet.setTextColor(textColor)
        wifiNet.setTextIsSelectable(true)
        box.addView(wifiNet)
        wifiQr.visibility = View.GONE
        box.addView(wifiQr, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        wifiPeers.orientation = LinearLayout.VERTICAL
        box.addView(wifiPeers)
        return card
    }

    private fun btCard(): View {
        val (card, box) = card()
        heading(box, R.string.transport_sec_bt)
        btSwitch.apply {
            setText(R.string.transport_bt_switch)
            textSize = 15f
            setTextColor(textColor)
            isChecked = TransportPrefs.bluetooth(context)
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, c -> setBluetooth(c) }
        }
        box.addView(btSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hint(box, R.string.transport_bt_hint)
        val buttons = listOf(
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_bt_find), accent) {
                withPerms(TransportPermissions.bluetooth()) {
                    if (ensureBtOn()) {
                        BtLink.start(context)
                        BtLink.scan(context)
                    }
                }
            },
            com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.transport_bt_visible), accent) {
                withPerms(TransportPermissions.bluetooth()) {
                    runCatching { context.startActivity(BtLink.discoverableIntent()) }
                }
            },
        )
        box.addView(com.stoyko.smessenger.ui.Btn.row(context, buttons))
        btStatus.textSize = 13f
        btStatus.setTextColor(secondary)
        btStatus.setPadding(0, dp(8), 0, dp(4))
        box.addView(btStatus)
        btNearby.orientation = LinearLayout.VERTICAL
        box.addView(btNearby)
        return card
    }

    private fun securityCard(): View {
        val (card, box) = card()
        heading(box, R.string.transport_sec_security)
        hint(box, R.string.transport_security_text)
        return card
    }

    // ---------- Действия ----------

    private fun pickMode(m: TransportMode) {
        if (m == TransportMode.OFFLINE && TransportPrefs.bluetooth(context) && !TransportPermissions.hasBluetooth(context)) {
            TransportPermissions.ensure(context, TransportPermissions.bluetooth())
        }
        TransportManager.setMode(context, m)
        render()
    }

    private fun pickBridges(m: BridgeMode) {
        TransportPrefs.setBridges(context, m)
        TorRunner.reconfigure(context)
        render()
    }

    private fun setBluetooth(on: Boolean) {
        if (!on) {
            TransportPrefs.setBluetooth(context, false)
            BtLink.stop()
            TransportManager.poke(context)
            render()
            return
        }
        if (!BtLink.available) {
            Toast.makeText(context, R.string.transport_bt_unavailable, Toast.LENGTH_SHORT).show()
            btSwitch.isChecked = false
            return
        }
        withPerms(TransportPermissions.bluetooth(), onDenied = { btSwitch.isChecked = false }) {
            TransportPrefs.setBluetooth(context, true)
            ensureBtOn()
            TransportManager.poke(context)
            render()
        }
    }

    /** Bluetooth выключен в телефоне - системный запрос «Включить?». */
    private fun ensureBtOn(): Boolean {
        if (BtLink.enabled) return true
        runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        Toast.makeText(context, R.string.transport_bt_off, Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * Выполнить [action], когда разрешения есть. Если нет - системный запрос, затем ждём
     * ответа (до минуты, раз в полсекунды), пока экран открыт.
     */
    private fun withPerms(perms: Array<String>, onDenied: () -> Unit = {}, action: () -> Unit) {
        if (TransportPermissions.missing(context, perms).isEmpty()) {
            action()
            return
        }
        val activity = context as? Activity ?: com.stoyko.smessenger.ui.Kinetics.activityOf(context)
        if (activity == null) {
            Toast.makeText(context, R.string.transport_perm_needed, Toast.LENGTH_LONG).show()
            onDenied()
            return
        }
        TransportPermissions.ensure(activity, perms)
        permWatch?.let { main.removeCallbacks(it) }
        val started = System.currentTimeMillis()
        val w = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                if (TransportPermissions.missing(context, perms).isEmpty()) {
                    permWatch = null
                    action()
                } else if (System.currentTimeMillis() - started > 60_000) {
                    permWatch = null
                    onDenied()
                } else {
                    main.postDelayed(this, 500)
                }
            }
        }
        permWatch = w
        main.postDelayed(w, 500)
    }

    // ---------- Отрисовка состояния ----------

    private fun render() {
        val ch = TransportManager.channel()
        val mode = TransportPrefs.mode(context)
        heroEmoji.text = ch.emoji
        heroTitle.setText(
            when (ch) {
                Channel.TOX -> R.string.transport_ch_tox
                Channel.TOR -> R.string.transport_ch_tor
                Channel.WIFI -> R.string.transport_ch_wifi
                Channel.BLUETOOTH -> R.string.transport_ch_bt
                Channel.NONE -> R.string.transport_ch_none
            },
        )
        val st = TransportManager.toxStatus
        val detail = StringBuilder()
        detail.append(
            context.getString(
                when {
                    ch == Channel.BLUETOOTH && BtLink.sessionCount > 0 -> R.string.transport_state_bt
                    st == ConnectionStatus.UDP -> R.string.transport_state_udp
                    st == ConnectionStatus.TCP -> R.string.transport_state_tcp
                    else -> R.string.transport_state_connecting
                },
            ),
        )
        val torOn = TorRunner.state != TorRunner.State.OFF
        when (TorRunner.state) {
            TorRunner.State.STARTING, TorRunner.State.CONNECTING ->
                detail.append('\n').append(context.getString(R.string.transport_tor_boot, TorRunner.bootstrap))
            TorRunner.State.READY -> detail.append('\n').append(context.getString(R.string.transport_tor_ready))
            TorRunner.State.FAILED -> detail.append('\n').append(context.getString(R.string.transport_tor_failed, TorRunner.error ?: "?"))
            TorRunner.State.OFF -> if (mode == TransportMode.AUTO && st == ConnectionStatus.None) {
                detail.append('\n').append(context.getString(R.string.transport_auto_waiting, TransportPrefs.autoTorAfterSec(context)))
            }
        }
        if (TorRunner.error?.startsWith("clock:") == true) detail.append('\n').append(context.getString(R.string.transport_clock_skew))
        heroDetail.text = detail
        torBar.visibility = if (torOn && TorRunner.state != TorRunner.State.READY && TorRunner.state != TorRunner.State.FAILED) View.VISIBLE else View.GONE
        torBar.progress = TorRunner.bootstrap
        pulse.color = when (ch) {
            Channel.TOX -> 0xFF22C55E.toInt()
            Channel.TOR -> 0xFF8B5CF6.toInt()
            Channel.WIFI -> 0xFF0EA5E9.toInt()
            Channel.BLUETOOTH -> 0xFF3B82F6.toInt()
            Channel.NONE -> 0xFF94A3B8.toInt()
        }
        // Пульс - только пока идёт подключение (и не в режиме экономии): меньше работы для слабых телефонов.
        if (!TransportManager.connected && !com.stoyko.smessenger.ui.EnergySaver.on) pulse.start() else pulse.stop()

        modeTiles.forEach { (m, tile) -> tile.background = tileBg(m == mode) }
        val br = TransportPrefs.bridges(context)
        bridgeChips.forEach { (m, c) -> styleChip(c, m == br) }
        val after = TransportPrefs.autoTorAfterSec(context)
        afterChips.forEach { (s, c) -> styleChip(c, s == after) }
        customBox.visibility = if (br == BridgeMode.CUSTOM) View.VISIBLE else View.GONE
        torInfo.text = if (TorRunner.bridgesInfo.isNotEmpty()) context.getString(R.string.transport_tor_bridges_on, TorRunner.bridgesInfo) else ""
        torInfo.visibility = if (torInfo.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        renderWifi()
        renderBt()
    }

    private fun renderWifi() {
        wifiStatus.text = when (WifiDirect.state) {
            WifiDirect.State.CREATING -> context.getString(R.string.transport_wifi_creating)
            WifiDirect.State.SEARCHING -> context.getString(R.string.transport_wifi_searching)
            WifiDirect.State.CONNECTING -> context.getString(R.string.transport_wifi_connecting)
            WifiDirect.State.FAILED -> if (WifiDirect.error == android.net.wifi.p2p.WifiP2pManager.P2P_UNSUPPORTED) {
                context.getString(R.string.transport_wifi_unsupported)
            } else {
                context.getString(R.string.transport_wifi_failed, WifiDirect.error)
            }
            else -> if (WifiDirect.linked) context.getString(R.string.transport_wifi_linked) else ""
        }
        wifiStatus.visibility = if (wifiStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val ssid = WifiDirect.ssid
        val pass = WifiDirect.passphrase
        if (WifiDirect.state == WifiDirect.State.OWNER && ssid != null && pass != null) {
            wifiNet.visibility = View.VISIBLE
            wifiNet.text = context.getString(R.string.transport_wifi_net, ssid, pass) + "\n" + context.getString(R.string.transport_wifi_qr_hint)
            val q = WifiDirect.qrText()
            if (q != null && q != qrFor) {
                qrFor = q
                wifiQr.setImageBitmap(runCatching { qr(q, dp(180)) }.getOrNull())
            }
            wifiQr.visibility = View.VISIBLE
        } else {
            wifiNet.visibility = View.GONE
            wifiQr.visibility = View.GONE
        }
        wifiPeers.removeAllViews()
        val peers = WifiDirect.peers
        if (WifiDirect.state == WifiDirect.State.SEARCHING && peers.isEmpty()) {
            wifiPeers.addView(smallText(context.getString(R.string.transport_wifi_peers_empty)))
        }
        peers.take(10).forEach { d ->
            wifiPeers.addView(
                listRow("📱", d.deviceName?.ifEmpty { d.deviceAddress } ?: d.deviceAddress, null) {
                    withPerms(TransportPermissions.wifiDirect()) { WifiDirect.connect(context, d) }
                },
            )
        }
    }

    private fun renderBt() {
        if (btSwitch.isChecked != TransportPrefs.bluetooth(context)) btSwitch.isChecked = TransportPrefs.bluetooth(context)
        btStatus.text = when {
            !BtLink.available -> context.getString(R.string.transport_bt_unavailable)
            TransportPrefs.bluetooth(context) && !BtLink.enabled -> context.getString(R.string.transport_bt_off)
            BtLink.scanning -> context.getString(R.string.transport_bt_scanning) + "\n" +
                context.getString(R.string.transport_bt_status, BtKeys.peers().size, BtLink.sessionCount)
            else -> context.getString(R.string.transport_bt_status, BtKeys.peers().size, BtLink.sessionCount)
        }
        btNearby.removeAllViews()
        BtLink.nearby.take(12).forEach { n ->
            btNearby.addView(listRow(Channel.BLUETOOTH.emoji, n.name, if (n.friend != null) context.getString(R.string.transport_bt_friend) else null, null))
        }
    }

    // ---------- Мелкие элементы ----------

    private fun tileBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (selected) {
            setColor((accent and 0x00FFFFFF) or 0x22000000)
            setStroke(dp(2), accent)
        } else {
            setColor(0x0F888888)
            setStroke(dp(1), 0x22888888)
        }
    }

    private fun styleChip(c: TextView, selected: Boolean) {
        c.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) accent else 0x14888888)
        }
        c.setTextColor(if (selected) Color.WHITE else textColor)
    }

    private fun smallText(s: String) = TextView(context).apply {
        text = s
        textSize = 13f
        setTextColor(secondary)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun listRow(icon: String, title: String, badge: String?, click: (() -> Unit)?): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            if (click != null) {
                isClickable = true
                setOnClickListener { click() }
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x225B4BDB), null, GradientDrawable().apply { setColor(Color.WHITE) })
            }
        }
        r.addView(TextView(context).apply { text = icon; textSize = 18f })
        r.addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (badge != null) {
            r.addView(
                TextView(context).apply {
                    text = badge
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(0xFF22C55E.toInt())
                    }
                },
            )
        }
        return r
    }

    /** QR без перевода в верхний регистр (пароль сети чувствителен к регистру). */
    private fun qr(text: String, size: Int): Bitmap {
        val q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val border = 2
        val n = q.size + border * 2
        val scale = (size / n).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK }
        for (y in 0 until q.size) for (x in 0 until q.size) {
            if (q.getModule(x, y)) {
                val l = ((x + border) * scale).toFloat()
                val t = ((y + border) * scale).toFloat()
                c.drawRect(l, t, l + scale, t + scale, p)
            }
        }
        return bmp
    }
}

/**
 * Расходящиеся кольца вокруг значка канала, пока идёт подключение. Время - по часам аниматора
 * (одинаково на 60 и 120 Гц), рисование без выделения памяти, остановка при закрытии экрана.
 */
class PulseView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var color: Int = 0xFF22C55E.toInt()
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var phase = 0f
    private var anim: ValueAnimator? = null

    fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        anim?.cancel()
        anim = null
        phase = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        val base = r * 0.62f
        if (anim != null) {
            for (i in 0 until 2) {
                val p = (phase + i * 0.5f) % 1f
                paint.color = color
                paint.alpha = ((1f - p) * 90).toInt()
                canvas.drawCircle(cx, cy, base + (r - base) * p, paint)
            }
        }
        paint.color = color
        paint.alpha = 46
        canvas.drawCircle(cx, cy, base, paint)
    }
}
