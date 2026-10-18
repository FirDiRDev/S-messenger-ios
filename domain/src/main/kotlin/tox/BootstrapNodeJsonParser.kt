// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import android.util.Log
import javax.inject.Inject
import com.stoyko.smessenger.core.vo.PublicKey
import org.json.JSONObject

private const val TAG = "BootstrapNodeJsonParser"

// Разбирает JSON в формате https://nodes.tox.chat/json.
// Поля status_udp / status_tcp / tcp_ports необязательны, поэтому свой узел можно описать минимально:
// {"nodes":[{"ipv4":"my.server.ru","port":443,"tcp_ports":[443],"public_key":"<64 hex>"}]}
class BootstrapNodeJsonParser @Inject constructor() {
    fun parse(jsonString: String): List<BootstrapNode> = try {
        val nodes = mutableListOf<BootstrapNode>()

        val json = JSONObject(jsonString)
        val jsonNodes = json.getJSONArray("nodes")
        for (i in 0 until jsonNodes.length()) {
            try {
                val jsonNode = jsonNodes.getJSONObject(i)
                val udpOk = jsonNode.optBoolean("status_udp", true)
                val tcpOk = jsonNode.optBoolean("status_tcp", true)
                if (!udpOk && !tcpOk) continue

                val address = pickAddress(jsonNode) ?: continue
                val port = jsonNode.getInt("port")

                val tcpPorts = mutableListOf<Int>()
                if (tcpOk) {
                    jsonNode.optJSONArray("tcp_ports")?.let { arr ->
                        for (j in 0 until arr.length()) tcpPorts.add(arr.getInt(j))
                    }
                    if (tcpPorts.isEmpty() && !jsonNode.has("tcp_ports")) tcpPorts.add(port)
                }

                nodes.add(
                    BootstrapNode(
                        address = address,
                        port = port,
                        publicKey = PublicKey(jsonNode.getString("public_key").uppercase()),
                        tcpPorts = tcpPorts.distinct().sortedBy { if (it == 443) 0 else 1 },
                        udp = udpOk,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping bad node #$i: $e")
            }
        }

        nodes
    } catch (e: Exception) {
        Log.e(TAG, e.toString())
        listOf()
    }

    private fun pickAddress(node: JSONObject): String? {
        val v4 = node.optString("ipv4", "-")
        if (v4.isNotBlank() && v4 != "-" && !v4.equals("NONE", ignoreCase = true)) return v4
        val v6 = node.optString("ipv6", "-")
        if (v6.isNotBlank() && v6 != "-" && !v6.equals("NONE", ignoreCase = true)) return v6
        return null
    }
}
