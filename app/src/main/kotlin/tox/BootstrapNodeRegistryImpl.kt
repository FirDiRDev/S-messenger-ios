// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.tox

import android.content.Context
import android.widget.Toast
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.BootstrapNodeSource
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.domain.tox.BootstrapNode
import com.stoyko.smessenger.domain.tox.BootstrapNodeJsonParser
import com.stoyko.smessenger.domain.tox.BootstrapNodeRegistry

@Singleton
class BootstrapNodeRegistryImpl @Inject constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val parser: BootstrapNodeJsonParser,
    private val settings: Settings,
) : BootstrapNodeRegistry {
    @Volatile
    private var nodes: List<BootstrapNode> = emptyList()

    // Свои узлы («Мои узлы» в настройках) - всегда первыми.
    @Volatile
    private var mine: List<BootstrapNode> = emptyList()

    init {
        reset()
    }

    override fun reset() {
        scope.launch(Dispatchers.IO) {
            // Встроенные узлы + свежий список, который приложение само скачивает раз в сутки.
            val builtIn = parser.parse(com.stoyko.smessenger.settings.NodeHealth.mergedJson(context)).ifEmpty {
                parser.parse(context.resources.openRawResource(R.raw.nodes).use { String(it.readBytes()) })
            }
            // Файл со своими узлами (его пишет экран «Мои узлы»; старые импортированные JSON - тоже).
            val file = File(context.filesDir, "user_nodes.json")
            mine = if (file.exists()) parser.parse(file.readText()) else emptyList()

            nodes = if (settings.bootstrapNodeSource == BootstrapNodeSource.UserProvided) {
                // «Только мои узлы» - не тратим время на общие узлы, заблокированные в вашей сети.
                mine.ifEmpty { builtIn }
            } else {
                builtIn
            }

            if (nodes.isEmpty() && mine.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.error_no_nodes_loaded), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Свои узлы - первыми. Из остальных - сначала рабочие по последней проверке, от быстрых
     * к медленным (распределение нагрузки: быстрые узлы дают больше скорости); не отвечавшие -
     * в конце. Среди непроверенных - сначала с TCP-портом 443.
     */
    override fun get(n: Int): List<BootstrapNode> {
        val own = mine
        val rest = nodes.filter { node -> own.none { it.address == node.address && it.port == node.port } }
            .shuffled()
            .sortedWith(
                compareBy<BootstrapNode> { node ->
                    when (val p = com.stoyko.smessenger.settings.NodeHealth.ping(context, node.address, node.port)) {
                        null -> 5_000L - (if (443 in node.tcpPorts) 1 else 0)
                        -1L -> 100_000L
                        // Узлы с TCP-портом 443 похожи на обычный HTTPS и лучше проходят DPI -
                        // небольшой приоритет при почти равной скорости.
                        else -> p - (if (443 in node.tcpPorts) 120 else 0)
                    }
                },
            )
        return (own + rest).take(n)
    }
}
