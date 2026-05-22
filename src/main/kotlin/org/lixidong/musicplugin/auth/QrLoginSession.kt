package org.lixidong.musicplugin.auth

import com.intellij.openapi.application.ApplicationManager
import org.lixidong.musicplugin.api.NeteaseApiClient
import org.lixidong.musicplugin.api.NeteaseEndpoints
import org.lixidong.musicplugin.api.model.UserProfile
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean

internal class QrLoginSession(
    private val api: NeteaseApiClient,
    private val onState: (State) -> Unit
) {
    sealed class State {
        data class Ready(val qrImage: BufferedImage, val unikey: String) : State()
        object Waiting : State()
        object Scanned : State()
        data class Success(val profile: UserProfile?) : State()
        object Expired : State()
        data class Error(val message: String) : State()
    }

    private val cancelled = AtomicBoolean(false)
    @Volatile private var pollThread: Thread? = null

    fun start() {
        Thread({
            try {
                val unikey = api.fetchQrUnikey()
                val img = QrCodeRenderer.render(NeteaseEndpoints.QR_LOGIN_PAGE_PREFIX + unikey)
                emit(State.Ready(img, unikey))
                pollLoop(unikey)
            } catch (e: Throwable) {
                if (!cancelled.get()) emit(State.Error(e.message ?: "error"))
            }
        }, "NeteaseMusic-QrInit").apply { isDaemon = true }.start()
    }

    private fun pollLoop(unikey: String) {
        val t = Thread({
            var lastCode = -1
            while (!cancelled.get()) {
                try {
                    val resp = api.checkQrLogin(unikey)
                    when (resp.code) {
                        800 -> {
                            emit(State.Expired)
                            return@Thread
                        }
                        801 -> if (lastCode != 801) emit(State.Waiting)
                        802 -> if (lastCode != 802) emit(State.Scanned)
                        803 -> {
                            val profile = try { api.fetchUserInfo() } catch (_: Throwable) { null }
                            emit(State.Success(profile))
                            return@Thread
                        }
                        else -> {
                            emit(State.Error("网易云返回 ${resp.code}：${resp.message ?: "未知错误"}"))
                            return@Thread
                        }
                    }
                    lastCode = resp.code
                } catch (e: Throwable) {
                    if (!cancelled.get()) emit(State.Error(e.message ?: "error"))
                    return@Thread
                }
                try { Thread.sleep(2500) } catch (_: InterruptedException) { return@Thread }
            }
        }, "NeteaseMusic-QrPoll").apply { isDaemon = true }
        pollThread = t
        t.start()
    }

    private fun emit(state: State) {
        if (cancelled.get()) return
        ApplicationManager.getApplication().invokeLater { onState(state) }
    }

    fun cancel() {
        cancelled.set(true)
        pollThread?.interrupt()
        pollThread = null
    }
}
