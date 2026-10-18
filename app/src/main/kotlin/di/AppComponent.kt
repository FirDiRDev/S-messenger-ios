// SPDX-FileCopyrightText: 2019-2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.di

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton
import com.stoyko.smessenger.ActionReceiver
import com.stoyko.smessenger.BootReceiver
import com.stoyko.smessenger.MainActivity
import com.stoyko.smessenger.ToxService

@Singleton
@Component(
    modules = [
        AndroidModule::class,
        AppModule::class,
        DatabaseModule::class,
        DaoModule::class,
        ViewModelModule::class,
    ],
)
interface AppComponent {
    @Component.Factory
    interface Factory {
        fun create(@BindsInstance appContext: Context): AppComponent
    }

    fun inject(activity: MainActivity)
    fun inject(service: ToxService)
    fun inject(receiver: BootReceiver)
    fun inject(receiver: ActionReceiver)

    /** Список узлов сети (для ссылок «добавить узел»). */
    fun nodeRegistry(): com.stoyko.smessenger.domain.tox.BootstrapNodeRegistry

    /** Перезапуск Tox (например, при включении/выключении встроенного VPN). */
    fun toxStarter(): com.stoyko.smessenger.tox.ToxStarter

    /** Сообщения (подарок, отправленный из профиля, появляется и в чате). */
    fun messageRepository(): com.stoyko.smessenger.core.repository.MessageRepository

    /** Для общего поиска: файлы, группы и каналы, контакты. */
    fun fileTransferRepository(): com.stoyko.smessenger.core.repository.FileTransferRepository
    fun groupRepository(): com.stoyko.smessenger.core.repository.GroupRepository
    fun contactRepository(): com.stoyko.smessenger.core.repository.ContactRepository

    /** Ядро сети - для онлайн-игр (ходы идут невидимыми пакетами). */
    fun tox(): com.stoyko.smessenger.domain.tox.Tox

    /** Отправка сообщений в чат (перевод монет карточкой - ждёт друга, если он не в сети). */
    fun chatManager(): com.stoyko.smessenger.domain.feature.ChatManager

    /** Карточка профиля - монеты и рекорды друзьям сразу после изменения. */
    fun profileCard(): com.stoyko.smessenger.profile.ProfileCard
}
