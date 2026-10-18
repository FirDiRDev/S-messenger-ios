// SPDX-FileCopyrightText: 2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

interface BootstrapNodeRegistry {
    fun get(n: Int): List<BootstrapNode>
    fun reset()
}
