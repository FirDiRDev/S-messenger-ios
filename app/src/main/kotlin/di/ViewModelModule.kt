// SPDX-FileCopyrightText: 2019-2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.di

import androidx.lifecycle.ViewModel
import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.multibindings.IntoMap
import kotlin.reflect.KClass
import com.stoyko.smessenger.ui.addcontact.AddContactViewModel
import com.stoyko.smessenger.ui.call.CallViewModel
import com.stoyko.smessenger.ui.chat.ChatViewModel
import com.stoyko.smessenger.ui.contactlist.ContactListViewModel
import com.stoyko.smessenger.ui.contactprofile.ContactProfileViewModel
import com.stoyko.smessenger.ui.createprofile.CreateProfileViewModel
import com.stoyko.smessenger.ui.friendrequest.FriendRequestViewModel
import com.stoyko.smessenger.ui.groups.GroupChatViewModel
import com.stoyko.smessenger.ui.groups.GroupListViewModel
import com.stoyko.smessenger.ui.settings.SettingsViewModel
import com.stoyko.smessenger.ui.userprofile.UserProfileViewModel

@MustBeDocumented
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.RUNTIME)
@MapKey
annotation class ViewModelKey(val value: KClass<out ViewModel>)

@Suppress("unused")
@Module
abstract class ViewModelModule {
    @Binds
    @IntoMap
    @ViewModelKey(AddContactViewModel::class)
    abstract fun bindAddContactViewModel(vm: AddContactViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(CallViewModel::class)
    abstract fun bindCallViewModel(vm: CallViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(ChatViewModel::class)
    abstract fun bindChatViewModel(vm: ChatViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(ContactListViewModel::class)
    abstract fun bindContactListViewModel(vm: ContactListViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(ContactProfileViewModel::class)
    abstract fun bindContactProfileViewModel(vm: ContactProfileViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(FriendRequestViewModel::class)
    abstract fun bindFriendRequestViewModel(vm: FriendRequestViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(CreateProfileViewModel::class)
    abstract fun bindProfileViewModel(vm: CreateProfileViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(SettingsViewModel::class)
    abstract fun bindSettingsViewModel(vm: SettingsViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(GroupListViewModel::class)
    abstract fun bindGroupListViewModel(vm: GroupListViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(GroupChatViewModel::class)
    abstract fun bindGroupChatViewModel(vm: GroupChatViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(UserProfileViewModel::class)
    abstract fun bindUserProfileViewModel(vm: UserProfileViewModel): ViewModel
}
