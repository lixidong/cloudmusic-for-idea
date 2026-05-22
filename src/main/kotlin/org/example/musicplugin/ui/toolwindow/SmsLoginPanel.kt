package org.example.musicplugin.ui.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.example.musicplugin.MusicBundle
import org.example.musicplugin.auth.NeteaseAuthService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.Timer

internal class SmsLoginPanel : JBPanel<JBPanel<*>>() {

    private val phoneField = JBTextField().apply { preferredSize = Dimension(JBUI.scale(220), preferredSize.height) }
    private val codeField = JBTextField().apply { preferredSize = Dimension(JBUI.scale(140), preferredSize.height) }
    private val getCodeButton = JButton(MusicBundle.message("login.sms.getCode"))
    private val submitButton = JButton(MusicBundle.message("login.sms.submit"))
    private val errorLabel = JBLabel(" ").apply { foreground = Color(0xCC4136) }
    private var countdownTimer: Timer? = null
    private var remaining = 0

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(20)

        val codeRow = JBPanel<JBPanel<*>>(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
            add(codeField)
            add(getCodeButton)
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(MusicBundle.message("login.sms.phone"), phoneField)
            .addLabeledComponent(MusicBundle.message("login.sms.code"), codeRow)
            .addComponent(submitButton)
            .addComponent(errorLabel)
            .panel

        add(form, BorderLayout.NORTH)
        getCodeButton.addActionListener { requestCode() }
        submitButton.addActionListener { submit() }
    }

    private fun requestCode() {
        val phone = phoneField.text.trim()
        if (phone.isEmpty()) {
            errorLabel.text = "请输入手机号"
            return
        }
        getCodeButton.isEnabled = false
        errorLabel.text = " "
        NeteaseAuthService.getInstance().sendSmsCodeAsync(phone) { result ->
            result.onSuccess { startCountdown() }
            result.onFailure {
                getCodeButton.isEnabled = true
                errorLabel.text = MusicBundle.message("error.smsFailed", it.message ?: "")
            }
        }
    }

    private fun startCountdown() {
        countdownTimer?.stop()
        remaining = 60
        getCodeButton.text = MusicBundle.message("login.sms.countdown", remaining)
        countdownTimer = Timer(1000) {
            remaining -= 1
            if (remaining <= 0) {
                getCodeButton.text = MusicBundle.message("login.sms.getCode")
                getCodeButton.isEnabled = true
                countdownTimer?.stop()
                countdownTimer = null
            } else {
                getCodeButton.text = MusicBundle.message("login.sms.countdown", remaining)
            }
        }.also { it.start() }
    }

    private fun submit() {
        val phone = phoneField.text.trim()
        val code = codeField.text.trim()
        if (phone.isEmpty() || code.isEmpty()) {
            errorLabel.text = "请输入手机号和验证码"
            return
        }
        submitButton.isEnabled = false
        errorLabel.text = " "
        NeteaseAuthService.getInstance().loginBySmsAsync(phone, code) { result ->
            submitButton.isEnabled = true
            result.onFailure { errorLabel.text = MusicBundle.message("error.passwordFailed", it.message ?: "") }
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        countdownTimer?.stop()
        countdownTimer = null
    }
}
