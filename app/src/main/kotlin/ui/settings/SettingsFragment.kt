// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import java.lang.NumberFormatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.databinding.FragmentSettingsBinding
import com.stoyko.smessenger.settings.BootstrapNodeSource
import com.stoyko.smessenger.settings.FtAutoAccept
import com.stoyko.smessenger.ui.BaseFragment
import com.stoyko.smessenger.vmFactory
import com.stoyko.smessenger.domain.tox.ProxyType

private fun Spinner.onItemSelectedListener(callback: (Int) -> Unit) {
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {}
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            callback(position)
        }
    }
}

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {
    private val vm: SettingsViewModel by viewModels { vmFactory }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val blockBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(requireContext(), getString(R.string.warn_proxy_broken), Toast.LENGTH_LONG).show()
        }
    }

    private val applySettingsCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Из раздела - назад к списку разделов.
            if (sections?.back() == true) return
            vm.commit()
        }
    }

    private var sections: SettingsSections? = null

    private val importNodesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        scope.launch {
            if (uri != null && vm.validateNodeJson(uri)) {
                if (vm.importNodeJson(uri)) {
                    vm.setBootstrapNodeSource(BootstrapNodeSource.UserProvided)
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.settingBootstrapNodes.setSelection(BootstrapNodeSource.BuiltIn.ordinal)

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.warn_node_json_import_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        requireActivity().onBackPressedDispatcher.addCallback(this, applySettingsCallback)
        requireActivity().onBackPressedDispatcher.addCallback(this, blockBackCallback)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(top = insets.top)
            v.updatePadding(left = insets.left, right = insets.right)
            version.updatePadding(bottom = insets.bottom)
            compat
        }

        toolbar.apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener {
                WindowInsetsControllerCompat(requireActivity().window, view)
                    .hide(WindowInsetsCompat.Type.ime())
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        // Разделы (карточка профиля и список разделов).
        sections = SettingsSections(
            requireContext(),
            this,
            vm,
            viewLifecycleOwner,
            openProfile = {
                runCatching { findNavController().navigate(R.id.userProfileFragment) }
            },
            showAbout = { showAboutDialog() },
            openEditor = {
                runCatching {
                    findNavController().navigate(R.id.userProfileFragment, android.os.Bundle().apply { putBoolean("edit", true) })
                }
            },
        ).also { it.setup() }

        // Карточки настроек плавно выезжают снизу одна за другой.
        if (savedInstanceState == null) {
            val shift = 24 * resources.displayMetrics.density
            for (i in 0 until settingsContent.childCount) {
                val child = settingsContent.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                child.alpha = 0f
                child.translationY = shift
                child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L * i).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
            }
        }

        theme.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_theme_options,
            android.R.layout.simple_spinner_item,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        theme.setSelection(vm.getTheme())

        theme.onItemSelectedListener {
            vm.setTheme(it)
        }

        settingRunAtStartup.isChecked = vm.getRunAtStartup()
        settingRunAtStartup.setOnCheckedChangeListener { _, isChecked -> vm.setRunAtStartup(isChecked) }

        settingAutoAwayEnabled.isChecked = vm.getAutoAwayEnabled()
        settingAutoAwayEnabled.setOnCheckedChangeListener { _, isChecked -> vm.setAutoAwayEnabled(isChecked) }

        settingAutoAwaySeconds.setText(vm.getAutoAwaySeconds().toString())
        settingAutoAwaySeconds.doAfterTextChanged {
            val str = it?.toString() ?: ""
            val seconds = try {
                str.toLong()
            } catch (_: NumberFormatException) {
                settingAutoAwaySeconds.error = getString(R.string.bad_positive_number)
                return@doAfterTextChanged
            }

            vm.setAutoAwaySeconds(seconds)
        }

        settingFtAutoAccept.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_ft_auto_accept_options,
            android.R.layout.simple_spinner_item,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        settingFtAutoAccept.setSelection(vm.getFtAutoAccept().ordinal)

        settingFtAutoAccept.onItemSelectedListener {
            vm.setFtAutoAccept(FtAutoAccept.entries[it])
        }

        settingConfirmQuitting.isChecked = vm.getConfirmQuitting()
        settingConfirmQuitting.setOnCheckedChangeListener { _, isChecked -> vm.setConfirmQuitting(isChecked) }

        settingConfirmCalling.isChecked = vm.getConfirmCalling()
        settingConfirmCalling.setOnCheckedChangeListener { _, isChecked -> vm.setConfirmCalling(isChecked) }

        if (vm.getProxyType() != ProxyType.None) {
            vm.setUdpEnabled(false)
        }

        settingsUdpEnabled.isChecked = vm.getUdpEnabled()
        settingsUdpEnabled.isEnabled = vm.getProxyType() == ProxyType.None
        settingsUdpEnabled.setOnCheckedChangeListener { _, isChecked -> vm.setUdpEnabled(isChecked) }

        proxyType.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_proxy_type_options,
            android.R.layout.simple_spinner_item,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        proxyType.setSelection(vm.getProxyType().ordinal)

        proxyType.onItemSelectedListener {
            val selected = ProxyType.entries[it]
            vm.setProxyType(selected)

            // Disable UDP if a proxy is selected to ensure all traffic goes through the proxy.
            settingsUdpEnabled.isEnabled = selected == ProxyType.None
            settingsUdpEnabled.isChecked = settingsUdpEnabled.isChecked && selected == ProxyType.None
            vm.setUdpEnabled(settingsUdpEnabled.isChecked)
        }

        proxyAddress.setText(vm.getProxyAddress())
        proxyAddress.doAfterTextChanged { vm.setProxyAddress(it?.toString() ?: "") }

        proxyPort.setText(vm.getProxyPort().toString())
        proxyPort.doAfterTextChanged {
            val str = it?.toString() ?: ""
            val port = try {
                Integer.parseInt(str)
            } catch (_: NumberFormatException) {
                proxyPort.error = getString(R.string.bad_port)
                return@doAfterTextChanged
            }

            if (port < 1 || port > 65535) {
                proxyPort.error = getString(R.string.bad_port)
                return@doAfterTextChanged
            }

            vm.setProxyPort(port)
        }

        vm.proxyStatus.observe(viewLifecycleOwner) { status: ProxyStatus ->
            proxyStatus.text = when (status) {
                ProxyStatus.Good -> ""
                ProxyStatus.BadPort -> getString(R.string.bad_port)
                ProxyStatus.BadHost -> getString(R.string.bad_host)
                ProxyStatus.BadType -> getString(R.string.bad_type)
                ProxyStatus.NotFound -> getString(R.string.proxy_not_found)
            }
            blockBackCallback.isEnabled = proxyStatus.text.isNotEmpty()
        }
        vm.checkProxy()

        vm.committed.observe(viewLifecycleOwner) { committed ->
            if (committed) {
                findNavController().popBackStack()
            }
        }

        fun onPasswordEdit() {
            passwordCurrent.error = if (vm.isCurrentPassword(passwordCurrent.text.toString())) {
                null
            } else {
                getString(R.string.incorrect_password)
            }

            passwordNewConfirm.error = if (passwordNew.text.toString() == passwordNewConfirm.text.toString()) {
                null
            } else {
                getString(R.string.passwords_must_match)
            }

            passwordConfirm.isEnabled = passwordCurrent.error == null && passwordNewConfirm.error == null
        }
        onPasswordEdit()

        passwordCurrent.doAfterTextChanged { onPasswordEdit() }
        passwordNew.doAfterTextChanged { onPasswordEdit() }
        passwordNewConfirm.doAfterTextChanged { onPasswordEdit() }
        passwordConfirm.setOnClickListener {
            val pw = passwordNewConfirm.text.toString()
            val doSet = {
                passwordConfirm.isEnabled = false
                vm.setPassword(pw)
                Toast.makeText(requireContext(), getString(R.string.password_updated), Toast.LENGTH_LONG).show()
            }
            if (pw.isEmpty()) doSet() else androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.no_recovery_title)
                .setMessage(R.string.no_recovery_warning)
                .setPositiveButton(R.string.no_recovery_ok) { _, _ -> doSet() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        if (vm.nospamAvailable()) {
            @Suppress("SetTextI18n") // This should be displayed the way Tox likes it.
            nospam.setText("%08X".format(vm.getNospam()))
            nospam.doAfterTextChanged {
                saveNospam.isEnabled =
                    nospam.text.length == 8 &&
                    nospam.text.toString().toUInt(16).toInt() != vm.getNospam()
            }
            saveNospam.isEnabled = false
            saveNospam.setOnClickListener {
                vm.setNospam(nospam.text.toString().toUInt(16).toInt())
                saveNospam.isEnabled = false
                Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_LONG).show()
            }
        } else {
            nospam.isEnabled = false
            saveNospam.isEnabled = false
            nospamExtraText.text = getString(R.string.pref_disabled_tox_error)
        }

        settingBootstrapNodes.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_bootstrap_node_options,
            android.R.layout.simple_spinner_item,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        settingBootstrapNodes.setSelection(vm.getBootstrapNodeSource().ordinal)

        settingBootstrapNodes.onItemSelectedListener {
            val source = BootstrapNodeSource.entries[it]

            // Hack to avoid triggering the document chooser again if the user has set it to UserProvided.
            if (source == vm.getBootstrapNodeSource()) return@onItemSelectedListener

            vm.setBootstrapNodeSource(source)
            // «Только мои узлы», а своих ещё нет - сразу открываем список, чтобы добавить.
            if (source == BootstrapNodeSource.UserProvided && com.stoyko.smessenger.settings.Nodes.list(requireContext()).isEmpty()) {
                com.stoyko.smessenger.settings.Nodes.show(requireContext()) { vm.reloadNodes() }
            }
        }

        // Свои узлы и инструкция, как их создать.
        settingMyNodes.setOnClickListener {
            com.stoyko.smessenger.settings.Nodes.show(requireContext()) { vm.reloadNodes() }
        }
        settingNodeGuide.setOnClickListener { com.stoyko.smessenger.settings.Nodes.showGuide(requireContext()) }
        settingHideIp.isChecked = vm.appSettings.hideIp
        settingHideIp.setOnCheckedChangeListener { _, c ->
            vm.setHideIp(c)
            com.stoyko.smessenger.ui.RestartPrompt.network(requireContext())
        }

        // Чаты, звонки, оформление.
        val st = vm.appSettings
        settingReadReceipts.isChecked = st.sendReadReceipts
        settingReadReceipts.setOnCheckedChangeListener { _, c -> st.sendReadReceipts = c }
        settingCallEarpiece.isChecked = st.callEarpiece
        settingCallEarpiece.setOnCheckedChangeListener { _, c -> vm.setCallEarpiece(c) }
        settingGlass.isChecked = st.glassEffect
        settingGlass.setOnCheckedChangeListener { _, c ->
            st.glassEffect = c
            com.stoyko.smessenger.ui.GlassFrameLayout.blurEnabled = c
        }
        settingErrorLog.setOnClickListener { showErrorLog() }

        setupPrivacy()

        // Список прокси.
        settingProxyList.setOnClickListener {
            com.stoyko.smessenger.settings.Proxies.show(requireContext(), vm.appSettings) {
                if (view == null) return@show
                proxyType.setSelection(vm.getProxyType().ordinal)
                proxyAddress.setText(vm.getProxyAddress())
                proxyPort.setText(vm.getProxyPort().toString())
                settingsUdpEnabled.isChecked = vm.getUdpEnabled()
                vm.checkProxy()
            }
        }

        settingStableConnection.isChecked = vm.getStableConnection()
        settingStableConnection.setOnCheckedChangeListener { _, isChecked ->
            vm.setStableConnection(isChecked)
            com.stoyko.smessenger.ui.RestartPrompt.network(requireContext())
        }
        settingBattery.setOnClickListener { com.stoyko.smessenger.requestBatteryExemption(requireActivity()) }

        settingDisableScreenshots.isChecked = vm.getDisableScreenshots()
        settingDisableScreenshots.setOnCheckedChangeListener { _, isChecked ->
            vm.setDisableScreenshots(isChecked)
            if (isChecked) {
                requireActivity().window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        version.text = getString(R.string.version_display, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
            "\n" + getString(R.string.copyright_notice)
        version.setOnClickListener { showAboutDialog() }
    }

    private fun showAboutDialog() = com.stoyko.smessenger.ui.AboutScreen.show(requireContext())

    override fun onDestroyView() {
        sections?.release()
        sections = null
        super.onDestroyView()
    }

    /** Журнал ошибок: посмотреть, поделиться (для разработчика) или очистить. */
    private fun showErrorLog() = ErrorLogScreen.show(requireContext())

    private val lockTimeouts = intArrayOf(0, 60, 300, 3600)

    private fun lockTimeoutLabels() = arrayOf(
        getString(R.string.lock_immediately),
        getString(R.string.lock_1min),
        getString(R.string.lock_5min),
        getString(R.string.lock_1hour),
    )

    /** Раздел «Конфиденциальность»: PIN-блокировка, уведомления, «печатает…», клавиатура. */
    private fun setupPrivacy() = binding.run {
        val st = vm.appSettings
        fun refresh() {
            val on = st.appLockEnabled
            settingAppLock.setOnCheckedChangeListener(null)
            settingAppLock.isChecked = on
            settingAppLock.setOnCheckedChangeListener { _, checked -> onAppLockToggled(checked) }
            settingAppLockTimeout.visibility = if (on) View.VISIBLE else View.GONE
            settingChangePin.visibility = if (on) View.VISIBLE else View.GONE
            val idx = lockTimeouts.indexOf(st.appLockTimeoutSeconds).coerceAtLeast(0)
            settingAppLockTimeout.text = getString(R.string.pref_app_lock_timeout) + ": " + lockTimeoutLabels()[idx]
        }
        privacyRefresh = { refresh() }
        refresh()

        settingAppLockTimeout.setOnClickListener {
            val current = lockTimeouts.indexOf(st.appLockTimeoutSeconds).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_app_lock_timeout)
                .setSingleChoiceItems(lockTimeoutLabels(), current) { d, which ->
                    st.appLockTimeoutSeconds = lockTimeouts[which]
                    d.dismiss()
                    refresh()
                }
                .show()
        }
        settingChangePin.setOnClickListener {
            com.stoyko.smessenger.ui.AppLock.askCurrentPin(requireContext(), st, onOk = {
                com.stoyko.smessenger.ui.AppLock.askNewPin(requireContext(), onSet = { h ->
                    st.appLockPinHash = h
                    Toast.makeText(requireContext(), R.string.pin_changed, Toast.LENGTH_SHORT).show()
                })
            })
        }

        settingHideNotificationText.isChecked = st.hideNotificationText
        settingHideNotificationText.setOnCheckedChangeListener { _, c -> st.hideNotificationText = c }
        settingSendTyping.isChecked = st.sendTyping
        settingSendTyping.setOnCheckedChangeListener { _, c -> st.sendTyping = c }
        settingIncognitoKeyboard.isChecked = st.incognitoKeyboard
        settingIncognitoKeyboard.setOnCheckedChangeListener { _, c -> st.incognitoKeyboard = c }
    }

    private var privacyRefresh: (() -> Unit)? = null

    private fun onAppLockToggled(checked: Boolean) {
        val st = vm.appSettings
        if (checked && !st.appLockEnabled) {
            com.stoyko.smessenger.ui.AppLock.askNewPin(
                requireContext(),
                onSet = { h ->
                    st.appLockPinHash = h
                    Toast.makeText(requireContext(), R.string.pin_changed, Toast.LENGTH_SHORT).show()
                    privacyRefresh?.invoke()
                },
                onCancel = { privacyRefresh?.invoke() },
            )
        } else if (!checked && st.appLockEnabled) {
            com.stoyko.smessenger.ui.AppLock.askCurrentPin(
                requireContext(),
                st,
                onOk = {
                    st.appLockPinHash = ""
                    privacyRefresh?.invoke()
                },
                onCancel = { privacyRefresh?.invoke() },
            )
        }
    }
}
