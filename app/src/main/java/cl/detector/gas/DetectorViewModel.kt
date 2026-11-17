package cl.detector.gas

import android.app.Activity
import android.app.Application
import android.util.Patterns
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
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
    val savedAccessKey: String = "",
    val loadingConnection: Boolean = false,
    val signedIn: Boolean = false,
    val reading: Reading? = null,
    val receivedAt: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val history: List<SavedReading> = emptyList(),
    val cloudCounts: CloudCounts? = null,
    val historyWarning: String? = null
)

class DetectorViewModel(app: Application) : AndroidViewModel(app) {
    private val accounts = Accounts(app)
    private val historyStore = HistoryStore(app)
    private val connectionStore = DetectorConnectionStore(app)
    private val ui = MutableStateFlow(ScreenState())
    val state = ui.asStateFlow()
    val firebaseReady = accounts.auth != null

    private var owner: String? = null
    private var source: WifiDetector? = null
    private var monitorJob: Job? = null
    private var historyJob: Job? = null
    private var configurationJob: Job? = null
    private var pendingConnection: DetectorConnection? = null
    private var monitorVisible = false
    private var demoStatus = DetectorStatus.NORMAL
    private var lastStored: Reading? = null
    private var lastStoredAt = 0L

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        val blocked = user?.email?.let(accounts::localBlockMessage)
        if (blocked != null) {
            clearDetector()
            owner = null
            ui.value = ScreenState(error = blocked)
            auth.signOut()
            return@AuthStateListener
        }
        val uid = user?.uid
        if (uid == null && owner == "demo-local") return@AuthStateListener
        if (uid != owner) {
            clearDetector()
            owner = uid
            if (uid == null) {
                ui.value = ScreenState()
            } else {
                scheduleHistorySync(getApplication())
                loadConnection(uid)
            }
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
                    is LocalLoginException -> error.message
                    is FirebaseNetworkException -> "Comprueba tu conexión a Internet e inténtalo otra vez."
                    is FirebaseTooManyRequestsException -> "Firebase recibió demasiadas solicitudes. Inténtalo más tarde."
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
        val current = ui.value
        if (current.busy || current.demo || (!current.signedIn && !BuildConfig.DEBUG)) return
        clearDetector()
        if (!current.signedIn) owner = "demo-local"
        demoStatus = DetectorStatus.NORMAL
        ui.value = ScreenState(
            page = if (current.signedIn) Page.MONITOR else Page.CONNECTION,
            demo = true,
            signedIn = current.signedIn
        )
    }

    fun leaveMonitor() {
        if (ui.value.demo && ui.value.signedIn) leaveDemo() else connectionPage()
    }

    fun leaveDemo() {
        val account = owner ?: return
        clearDetector()
        if (ui.value.signedIn) {
            loadConnection(account)
        } else {
            owner = null
            ui.value = ScreenState()
        }
    }

    fun signOut() {
        val demo = ui.value.demo && !ui.value.signedIn
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
        if (owner == null || ui.value.loadingConnection || ui.value.page != Page.CONNECTION) return
        if (!ui.value.demo) {
            val app = getApplication<Application>()
            val certificate = app.resources.getIdentifier("detector_cert", "raw", app.packageName)
            if (certificate == 0) {
                ui.update { it.copy(error = "Falta configurar el certificado del detector. Sigue los pasos del README.") }
                return
            }
            try {
                val configuration = DetectorConnection(address, accessKey)
                val url = detectorUrl(configuration.address)
                val authorization = detectorAuthorization(configuration.accessKey)
                source = app.resources.openRawResource(certificate).use {
                    WifiDetector(url, authorization, it)
                }
                pendingConnection = configuration
            } catch (_: IllegalArgumentException) {
                ui.update { it.copy(error = "Escribe la IP privada del detector y su clave de 32 caracteres.") }
                return
            } catch (_: java.security.GeneralSecurityException) {
                ui.update { it.copy(error = "El certificado del detector no es válido. Vuelve a generarlo.") }
                return
            }
        }
        ui.update {
            it.copy(page = Page.MONITOR, address = pendingConnection?.address.orEmpty(), savedAccessKey = "",
                reading = null, receivedAt = null, error = null)
        }
    }

    fun connectionPage() {
        clearDetector()
        if (ui.value.demo) {
            ui.value = ScreenState(page = Page.CONNECTION, demo = true)
        } else {
            owner?.let(::loadConnection)
        }
    }

    private fun loadConnection(uid: String) {
        ui.value = ScreenState(page = Page.CONNECTION, signedIn = true, loadingConnection = true)
        configurationJob = viewModelScope.launch {
            try {
                val saved = connectionStore.load(uid)
                ui.update { it.copy(address = saved?.address.orEmpty(), savedAccessKey = saved?.accessKey.orEmpty()) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ui.update { it.copy(error = "No se pudo recuperar la conexión guardada. Ingresa los datos otra vez.") }
            }
            ui.update { it.copy(loadingConnection = false) }
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
        val account = historyOwner() ?: return
        if (!monitorVisible || ui.value.page != Page.MONITOR) return
        monitorJob?.cancel()
        val demo = ui.value.demo
        val wifi = source
        monitorJob = viewModelScope.launch {
            var saveRequested = forceStore
            var retryDelay = 1_000L
            do {
                val reading = if (demo) DemoDetector.read(demoStatus) else readWifi(wifi)
                val time = System.currentTimeMillis()
                ui.update {
                    it.copy(reading = reading, receivedAt = time.takeIf { reading.adc != null })
                }
                if (reading.confirmsDetectorConnection()) savePendingConnection(account)
                if (reading.adc != null && (saveRequested ||
                        reading.status != lastStored?.status || reading.fanOrdered != lastStored?.fanOrdered ||
                        time - lastStoredAt >= 30_000L)
                ) {
                    try {
                        historyStore.save(account, time, reading)
                        if (!demo) scheduleHistorySync(getApplication())
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
                if (!demo) {
                    retryDelay = nextDetectorDelay(retryDelay, reading.status)
                    delay(retryDelay)
                }
            } while (!demo)
        }
    }

    private suspend fun savePendingConnection(account: String) {
        val pending = pendingConnection ?: return
        try {
            connectionStore.save(account, pending)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ui.update { it.copy(error = "El detector responde, pero no se pudo guardar su conexión.") }
        }
        pendingConnection = null
    }

    private fun historyOwner(): String? = owner?.let { uid ->
        if (ui.value.demo && ui.value.signedIn) "demo:$uid" else uid
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
        val account = historyOwner() ?: return
        monitorJob?.cancel()
        historyJob?.cancel()
        val realConnection = !ui.value.demo
        if (realConnection) scheduleHistorySync(getApplication())
        ui.update { it.copy(page = Page.HISTORY, busy = true, error = null, history = emptyList(), cloudCounts = null) }
        historyJob = viewModelScope.launch {
            try {
                val entries = historyStore.latest(account)
                val counts = if (realConnection) historyStore.cloudCounts(account) else null
                ui.update { it.copy(history = entries, cloudCounts = counts, busy = false) }
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
        configurationJob?.cancel()
        pendingConnection = null
        source = null
        lastStored = null
        lastStoredAt = 0
    }

    override fun onCleared() {
        accounts.auth?.removeAuthStateListener(authListener)
    }
}
