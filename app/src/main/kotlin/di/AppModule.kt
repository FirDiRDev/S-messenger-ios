// SPDX-FileCopyrightText: 2021-2022 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.di

import android.content.Context
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.stoyko.smessenger.tox.BootstrapNodeRegistryImpl
import com.stoyko.smessenger.domain.tox.AndroidSaveManager
import com.stoyko.smessenger.domain.tox.BootstrapNodeRegistry
import com.stoyko.smessenger.domain.tox.SaveManager

@Module
class AppModule {
    @Provides
    fun provideBootstrapNodeRegistry(nodeRegistry: BootstrapNodeRegistryImpl): BootstrapNodeRegistry = nodeRegistry

    @Provides
    // Ошибка в фоновой задаче записывается в отчёт, а не роняет всё приложение.
    fun provideCoroutineScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + com.stoyko.smessenger.domain.Diagnostics.coroutineHandler("фоновая задача"))

    @Provides
    fun provideSaveManager(ctx: Context): SaveManager = AndroidSaveManager(ctx)
}
