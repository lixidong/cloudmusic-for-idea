package org.example.musicplugin.auth

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.musicplugin.api.NeteaseApiClient
import org.example.musicplugin.api.model.UserProfile

@Service(Service.Level.APP)
internal class NeteaseAuthService(private val cs: CoroutineScope) : Disposable {

    private val log = Logger.getInstance(NeteaseAuthService::class.java)
    private val api = NeteaseApiClient.getInstance()
    private val passwordHandler = PasswordLoginHandler(api)
    private val smsHandler = SmsLoginHandler(api)

    @Volatile private var qrSession: QrLoginSession? = null
    @Volatile var currentProfile: UserProfile? = null
        private set

    init {
        restoreCookies()
    }

    fun isLoggedIn(): Boolean = api.isLoggedIn()

    private fun restoreCookies() {
        val cookies = CookieStore.load()
        if (cookies.isEmpty()) return
        api.setCookies(cookies)
        cs.launch(Dispatchers.IO) {
            try {
                val profile = api.fetchUserInfo()
                currentProfile = profile
                ApplicationManager.getApplication().invokeLater {
                    publishLoginChanged(profile != null)
                }
            } catch (e: Throwable) {
                log.info("restoreCookies failed", e)
            }
        }
    }

    fun startQrLogin(listener: (QrLoginSession.State) -> Unit): QrLoginSession {
        qrSession?.cancel()
        val session = QrLoginSession(api) { state ->
            listener(state)
            if (state is QrLoginSession.State.Success) {
                currentProfile = state.profile
                cs.launch(Dispatchers.IO) { CookieStore.save(api.snapshotCookies()) }
                publishLoginChanged(true)
            }
        }
        qrSession = session
        session.start()
        return session
    }

    fun cancelQrLogin() {
        qrSession?.cancel()
        qrSession = null
    }

    fun loginByPasswordAsync(phone: String, password: String, callback: (Result<UserProfile>) -> Unit) {
        cs.launch(Dispatchers.IO) {
            val r = runCatching { passwordHandler.login(phone, password) }
            r.onSuccess { onLoginSuccess(it) }
            ApplicationManager.getApplication().invokeLater { callback(r) }
        }
    }

    fun sendSmsCodeAsync(phone: String, callback: (Result<Unit>) -> Unit) {
        cs.launch(Dispatchers.IO) {
            val r = runCatching { smsHandler.sendCode(phone) }
            ApplicationManager.getApplication().invokeLater { callback(r) }
        }
    }

    fun loginBySmsAsync(phone: String, code: String, callback: (Result<UserProfile>) -> Unit) {
        cs.launch(Dispatchers.IO) {
            val r = runCatching { smsHandler.login(phone, code) }
            r.onSuccess { onLoginSuccess(it) }
            ApplicationManager.getApplication().invokeLater { callback(r) }
        }
    }

    private fun onLoginSuccess(profile: UserProfile) {
        currentProfile = profile
        cs.launch(Dispatchers.IO) { CookieStore.save(api.snapshotCookies()) }
        ApplicationManager.getApplication().invokeLater { publishLoginChanged(true) }
    }

    fun logout() {
        qrSession?.cancel()
        qrSession = null
        api.clearCookies()
        CookieStore.clear()
        currentProfile = null
        publishLoginChanged(false)
    }

    private fun publishLoginChanged(loggedIn: Boolean) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(LoginTopics.LOGIN_STATE_CHANGED)
            .onLoginStateChanged(loggedIn, currentProfile)
        if (loggedIn) {
            try {
                org.example.musicplugin.service.MusicPlayerService.getInstance().refreshLikedSongs()
            } catch (_: Throwable) {
                // Player service may not be initialised yet; no-op.
            }
        }
    }

    override fun dispose() {
        qrSession?.cancel()
        qrSession = null
    }

    companion object {
        fun getInstance(): NeteaseAuthService = service()
    }
}
