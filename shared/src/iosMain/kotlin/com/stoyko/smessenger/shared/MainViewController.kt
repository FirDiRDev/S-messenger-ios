// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.stoyko.smessenger.shared.ui.App
import platform.UIKit.UIViewController

/** Swift: `MainViewControllerKt.MainViewController()` (см. iosApp/Sources/ContentView.swift). */
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        // Ключ CADisableMinimumFrameDurationOnPhone есть в Info.plist (120 Гц на ProMotion);
        // строгую проверку отключаем, чтобы забытый ключ не ронял приложение.
        enforceStrictPlistSanityCheck = false
    }
) { App() }
