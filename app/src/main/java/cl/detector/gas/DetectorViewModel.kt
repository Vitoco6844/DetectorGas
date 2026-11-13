package cl.detector.gas

import android.app.Activity
import android.app.Application
import android.util.Patterns
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class Page { LOGIN, REGISTER, RECOVER, EMAIL_SENT, CONNECTION, MONITOR, HISTORY }

data class ScreenState(
    val page: Page = Page.LOGIN,
    val demo: Boolean = false,
    val address: String = "",
    val reading: Reading? = null,
    val receivedAt: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val history: List<SavedReading> = emptyList(),
    val historyWarning: String? = null
)

class DetectorViewModel(app: Application) : AndroidViewModel(app) {
    private val accounts = Accounts(app)
    private val historyStore = HistoryStore(app)
    private val ui = MutableStateFlow(ScreenState())
    val state = ui.asStateFlow()
    val firebaseReady = accounts.auth != null

    private var owner: String? = null
    private var source: WifiDetector? = null
    private var monitorJob: Job? = null
    private var historyJob: Job? = null
    private var monitorVisible = false
    private var demoStatus = DetectorStatus.NORMAL
    private var lastStored: Reading? = null
    private var lastStoredAt = 0L

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val uid = auth.currentUser?.uid
        if (!ui.value.demo && uid != owner) {
            clearDetector()
            owner = uid
            ui.value = ScreenState(page = if (uid == null) Page.LOGIN else Page.CONNECTION)
        }
    }

    init {
        accounts.auth?.addAuthStateListener(authListener)
    }

    fun authPage(page: Page) {
        ui.update { it.copy(page = page, error = null) }
    }

    fun submit(email: String, password: String, confirmation: String) {
        val page = ui.value.page
        val cleanEmail = email.trim()
        val validation = when {
            !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches() -> "Escribe un correo válido."
            page == Page.LOGIN && password.isEmpty() -> "Escribe tu contraseña."
            page == Page.REGISTER && password.length < 6 -> "Usa al menos 6 caracteres."
            page == Page.REGISTER && password != confirmation -> "Las contraseñas deben coincidir."
            else -> null
        }
        if (validation != null) {
            ui.update { it.copy(error = validation) }
            return
        }
        authenticate {
            when (page) {
                Page.LOGIN -> accounts.signIn(cleanEmail, password)
                Page.REGISTER -> accounts.register(cleanEmail, password)
                Page.RECOVER -> {
                    try {
                        accounts.recover(cleanEmail)
                    } catch (_: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                        // Mostrar el mismo resultado para correos registrados y desconocidos.
                    }
                    authPage(Page.EMAIL_SENT)
                }
                else -> Unit
            }
        }
    }

    fun google(activity: Activity) = authenticate { accounts.google(activity) }

    private fun authenticate(action: suspend () -> Unit) {
        if (ui.value.busy) return
        if (!firebaseReady) {
            ui.update { it.copy(error = "Configura Firebase en Android Studio para usar cuentas.") }
            return
        }
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: GetCredentialCancellationException) {
                // La persona cerró el selector de cuentas.
            } catch (error: Exception) {
                val message = when (error) {
                    is FirebaseNetworkException -> "Comprueba tu conexión a Internet e inténtalo otra vez."
                    is FirebaseAuthUserCollisionException -> "Este correo ya tiene una cuenta."
                    is FirebaseAuthInvalidCredentialsException -> "Revisa el correo y la contraseña."
                    is NoCredentialException -> "Agrega una cuenta de Google al teléfono o inicia sesión por correo."
                    is IllegalStateException -> "Revisa la configuración de Google en Firebase."
                    else -> "No se pudo completar la solicitud. Inténtalo otra vez."
                }
                ui.update { it.copy(error = message) }
            } finally {
                ui.update { it.copy(busy = false) }
            }
        }
    }

    fun startDemo() {
        if (!BuildConfig.DEBUG || ui.value.busy) return
        clearDetector()
        owner = "demo-local"
        demoStatus = DetectorStatus.NORMAL
        ui.value = ScreenState(page = Page.CONNECTION, demo = true)
    }

    fun signOut() {
        val demo = ui.value.demo
        clearDetector()
        owner = null
        ui.value = ScreenState()
        if (!demo) {
            viewModelScope.launch {
                try {
                    accounts.signOut()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: androidx.credentials.exceptions.ClearCredentialException) {
                    // Firebase ya cerró la sesión.
                }
            }
        }
    }

    fun connect(address: String, accessKey: String) {
        if (owner == null) return
        if (!ui.value.demo) {
            val app = getApplication<Application>()
            val certificate = app.resources.getIdentifier("detector_cert", "raw", app.packageName)
            if (certificate == 0) {
                ui.update { it.copy(error = "Falta configurar el certificado del detector. Sigue los pasos del README.") }
                return
            }
            try {
                val url = detectorUrl(address)
                val authorization = detectorAuthorization(accessKey)
                source = app.resources.openRawResource(certificate).use {
                    WifiDetector(url, authorization, it)
                }
            } catch (_: IllegalArgumentException) {
                ui.update { it.copy(error = "Escribe la IP privada del detector y su clave de 32 caracteres.") }
                return
            } catch (_: java.security.GeneralSecurityException) {
                ui.update { it.copy(error = "El certificado del detector no es válido. Vuelve a generarlo.") }
                return
            }
        }
        ui.update { it.copy(page = Page.MONITOR, address = address.trim(), reading = null, receivedAt = null, error = null) }
    }

    fun connectionPage() {
        clearDetector()
        ui.update {
            it.copy(page = Page.CONNECTION, reading = null, receivedAt = null, error = null, history = emptyList())
        }
    }

    fun setMonitorVisible(visible: Boolean) {
        monitorVisible = visible
        if (visible) {
            refresh(forceStore = false)
        } else {
            monitorJob?.cancel()
            ui.update { it.copy(reading = null, receivedAt = null) }
        }
    }

    fun chooseDemoStatus(status: DetectorStatus) {
        if (!ui.value.demo) return
        demoStatus = status
        refresh()
    }

    fun refresh(forceStore: Boolean = true) {
        val account = owner ?: return
        if (!monitorVisible || ui.value.page != Page.MONITOR) return
        monitorJob?.cancel()
        val demo = ui.value.demo
        val wifi = source
        monitorJob = viewModelScope.launch {
            var saveRequested = forceStore
            do {
                val reading = if (demo) DemoDetector.read(demoStatus) else readWifi(wifi)
                val time = System.currentTimeMillis()
                ui.update {
                    it.copy(reading = reading, receivedAt = time.takeIf { reading.adc != null })
                }
                if (reading.adc != null && (saveRequested ||
                        reading.status != lastStored?.status || reading.fanOrdered != lastStored?.fanOrdered ||
                        time - lastStoredAt >= 30_000L)
                ) {
                    try {
                        historyStore.save(account, time, reading)
                        lastStored = reading
                        lastStoredAt = time
                        ui.update { it.copy(historyWarning = null) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ui.update { it.copy(historyWarning = "No se pudo guardar esta lectura en el historial.") }
                    }
                }
                saveRequested = false
                if (!demo) delay(1_000)
            } while (!demo)
        }
    }

    private suspend fun readWifi(wifi: WifiDetector?): Reading = try {
        requireNotNull(wifi).read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: JsonParseException) {
        Reading(DetectorStatus.INVALID)
    } catch (_: IllegalArgumentException) {
        Reading(DetectorStatus.INVALID)
    } catch (error: retrofit2.HttpException) {
        Reading(if (error.code() == 401) DetectorStatus.ACCESS_DENIED else DetectorStatus.INVALID)
    } catch (_: javax.net.ssl.SSLException) {
        Reading(DetectorStatus.TLS_ERROR)
    } catch (_: IOException) {
        Reading(DetectorStatus.OFFLINE)
    }

    fun showHistory() {
        val account = owner ?: return
        monitorJob?.cancel()
        historyJob?.cancel()
        ui.update { it.copy(page = Page.HISTORY, busy = true, error = null, history = emptyList()) }
        historyJob = viewModelScope.launch {
            try {
                val entries = historyStore.latest(account)
                ui.update { it.copy(history = entries, busy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ui.update { it.copy(error = "No se pudo leer el historial local.", busy = false) }
            }
        }
    }

    fun backToMonitor() {
        historyJob?.cancel()
        ui.update {
            it.copy(page = Page.MONITOR, reading = null, receivedAt = null, history = emptyList(), busy = false, error = null)
        }
    }

    private fun clearDetector() {
        monitorJob?.cancel()
        historyJob?.cancel()
        source = null
        lastStored = null
        lastStoredAt = 0
    }

    override fun onCleared() {
        accounts.auth?.removeAuthStateListener(authListener)
    }
}
