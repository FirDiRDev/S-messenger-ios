// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.call

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Входящий звонок на весь экран (поверх блокировки): имя, пульсирующий аватар,
 * кнопки «Принять» и «Отклонить». Закрывается сам, если звонящий положил трубку.
 */
class IncomingCallScreen(
    private val activity: AppCompatActivity,
    val publicKey: String,
    private val name: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit,
) {
    private var dialog: Dialog? = null
    val isShowing get() = dialog?.isShowing == true

    fun show() {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF2A1F6B.toInt(), 0xFF0B1026.toInt()),
            )
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(80), dp(24), dp(24))
        }
        val avatar = TextView(activity).apply {
            text = name.trim().take(1).uppercase().ifEmpty { "?" }
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5B4BDB.toInt())
            }
        }
        val avatarBox = FrameLayout(activity)
        val halo = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x335B4BDB)
            }
        }
        avatarBox.addView(halo, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        avatarBox.addView(avatar, FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER))
        column.addView(avatarBox, LinearLayout.LayoutParams(dp(170), dp(170)))
        column.addView(
            TextView(activity).apply {
                text = name
                textSize = 28f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(6))
            },
        )
        column.addView(
            TextView(activity).apply {
                setText(R.string.incoming_call)
                textSize = 16f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER
            },
        )
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(64))
        }
        fun round(icon: Int, color: Int, label: Int, click: () -> Unit): LinearLayout {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            box.addView(
                ImageButton(activity).apply {
                    setImageResource(icon)
                    setColorFilter(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                    setOnClickListener {
                        isEnabled = false
                        click()
                    }
                },
                LinearLayout.LayoutParams(dp(72), dp(72)),
            )
            box.addView(
                TextView(activity).apply {
                    setText(label)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(0, dp(8), 0, 0)
                },
            )
            return box
        }
        buttons.addView(
            round(R.drawable.ic_call_end, 0xFFE5484D.toInt(), R.string.reject) {
                dismiss()
                onReject()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val accept = round(R.drawable.ic_call, 0xFF22C55E.toInt(), R.string.accept) {
            dismiss()
            onAccept()
        }
        buttons.addView(accept, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.fitSystemBars()

        // Пульс аватара и «покачивание» кнопки «Принять».
        fun pulse() {
            halo.scaleX = 0.8f
            halo.scaleY = 0.8f
            halo.alpha = 1f
            halo.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setDuration(1300).withEndAction {
                if (dialog?.isShowing == true) pulse()
            }.start()
        }
        fun wiggle() {
            accept.animate().translationY(-dp(8).toFloat()).setDuration(350).withEndAction {
                accept.animate().translationY(0f).setDuration(350).withEndAction {
                    if (dialog?.isShowing == true) accept.postDelayed({ wiggle() }, 600)
                }.start()
            }.start()
        }

        dlg.setContentView(root)
        dlg.setCancelable(false)
        dlg.window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            it.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        dialog = dlg
        dlg.show()
        pulse()
        wiggle()
    }

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }
}
