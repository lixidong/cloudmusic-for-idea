package org.lixidong.musicplugin.ui.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.lixidong.musicplugin.MusicBundle
import org.lixidong.musicplugin.auth.NeteaseAuthService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.JButton

internal class PasswordLoginPanel : JBPanel<JBPanel<*>>() {

    private val phoneField = JBTextField().apply { preferredSize = Dimension(JBUI.scale(220), preferredSize.height) }
    private val passwordField = JBPasswordField().apply { preferredSize = Dimension(JBUI.scale(220), preferredSize.height) }
    private val submitButton = JButton(MusicBundle.message("login.password.submit"))
    private val errorLabel = JBLabel(" ").apply { foreground = Color(0xCC4136) }

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(20)

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(MusicBundle.message("login.password.phone"), phoneField)
            .addLabeledComponent(MusicBundle.message("login.password.password"), passwordField)
            .addComponent(submitButton)
            .addComponent(errorLabel)
            .addComponent(JBLabel(MusicBundle.message("login.password.tip")).apply {
                foreground = Color.GRAY
            })
            .panel

        add(form, BorderLayout.NORTH)
        submitButton.addActionListener { submit() }
    }

    private fun submit() {
        val phone = phoneField.text.trim()
        val password = String(passwordField.password)
        if (phone.isEmpty() || password.isEmpty()) {
            errorLabel.text = "请输入手机号和密码"
            return
        }
        submitButton.isEnabled = false
        errorLabel.text = " "
        NeteaseAuthService.getInstance().loginByPasswordAsync(phone, password) { result ->
            submitButton.isEnabled = true
            result.onFailure {
                errorLabel.text = MusicBundle.message("error.passwordFailed", it.message ?: "")
            }
        }
    }
}
