package cl.detector.gas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val actionShape = RoundedCornerShape(10.dp)
private val sectionSpacing = Arrangement.spacedBy(12.dp)
private val pagePadding = PaddingValues(horizontal = 22.dp, vertical = 24.dp)

@Composable
private fun Screen(
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxSize()
                .verticalScroll(scroll).padding(pagePadding),
            verticalArrangement = sectionSpacing,
            content = content
        )
    }
}

@Composable
private fun Heading(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Detector de gas", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        description?.let { Detail(it) }
    }
}

@Composable
private fun Detail(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Action(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
    if (primary) {
        Button(onClick, modifier, enabled = enabled, shape = actionShape) { Text(label) }
    } else {
        OutlinedButton(onClick, modifier, enabled = enabled, shape = actionShape) { Text(label) }
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun AuthScreen(
    state: ScreenState,
    firebaseReady: Boolean,
    onSubmit: (String, String, String) -> Unit,
    onPage: (Page) -> Unit,
    onGoogle: () -> Unit,
    onDemo: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember(state.page) { mutableStateOf("") }
    var confirmation by remember(state.page) { mutableStateOf("") }
    val page = state.page
    Screen {
        when (page) {
            Page.REGISTER -> Heading("Crear una cuenta", "Usa tu correo para acceder al detector.")
            Page.RECOVER -> Heading("Recuperar contraseña", "Te enviaremos un enlace para elegir una contraseña nueva.")
            Page.EMAIL_SENT -> Heading("Revisa tu correo", "Si el correo corresponde a una cuenta, recibirás un enlace para cambiar tu contraseña.")
            else -> Heading("Bienvenido", "Consulta el estado de tu detector desde el teléfono.")
        }

        if (page == Page.EMAIL_SENT) {
            Detail("Revisa también la carpeta de correo no deseado.")
            Action("Volver al inicio", primary = true) { onPage(Page.LOGIN) }
        } else {
            val enabled = !state.busy
            OutlinedTextField(
                value = email, onValueChange = { email = it }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), label = { Text("Correo electrónico") },
                placeholder = { Text("persona@ejemplo.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = actionShape
            )
            if (page != Page.RECOVER) {
                PasswordField("Contraseña", password, enabled) { password = it }
            }
            if (page == Page.REGISTER) {
                PasswordField("Repetir contraseña", confirmation, enabled) { confirmation = it }
            }
            val label = when (page) {
                Page.REGISTER -> "Crear cuenta"
                Page.RECOVER -> "Enviar enlace"
                else -> "Iniciar sesión"
            }
            ErrorText(state.error)
            Action(if (state.busy) "Procesando…" else label, primary = true, enabled = enabled && firebaseReady) {
                onSubmit(email, password, confirmation)
            }
            if (page == Page.LOGIN) {
                Action("Continuar con Google", enabled = enabled && firebaseReady, onClick = onGoogle)
                Action("Crear una cuenta", enabled = enabled) { onPage(Page.REGISTER) }
                Action("Olvidé mi contraseña", enabled = enabled) { onPage(Page.RECOVER) }
            } else {
                Action("Volver al inicio", enabled = enabled) { onPage(Page.LOGIN) }
            }
            if (!firebaseReady) Detail("Configura Firebase para iniciar sesión con una cuenta.")
            if (BuildConfig.DEBUG && page == Page.LOGIN) {
                HorizontalDivider()
                Action("Probar demostración", enabled = enabled, onClick = onDemo)
            }
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = actionShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation()
    )
}

@Composable
internal fun ConnectionScreen(state: ScreenState, onConnect: (String, String) -> Unit, onExit: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(state.address) }
    var accessKey by remember { mutableStateOf("") }
    Screen {
        Heading(
            "Conecta tu detector",
            if (state.demo) "Explora el monitoreo con lecturas de ejemplo."
            else "Conecta el teléfono a la misma red Wi-Fi del detector."
        )
        if (!state.demo) {
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Dirección del detector") },
                placeholder = { Text("192.168.1.50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true, shape = actionShape
            )
            Detail("Busca la dirección que muestra el ESP32 al conectarse al Wi-Fi.")
            PasswordField("Clave de acceso del detector", accessKey, true) { accessKey = it }
            Detail("Usa la clave generada al configurar HTTPS.")
        }
        ErrorText(state.error)
        Action(if (state.demo) "Conectar simulador" else "Conectar", primary = true) { onConnect(address, accessKey) }
        Action(if (state.demo) "Salir de la demostración" else "Cerrar sesión", onClick = onExit)
    }
}

private data class StatusText(val title: String, val message: String, val background: Long, val ink: Long)

private fun statusText(status: DetectorStatus?): StatusText = when (status) {
    DetectorStatus.NORMAL -> StatusText("Sin alerta detectada", "El detector no informa una alerta en este momento.", 0xFFE4F3EB, 0xFF155A41)
    DetectorStatus.ALERT -> StatusText("Alerta del detector", "Se superó el umbral configurado en el equipo.", 0xFFFFE9EA, 0xFFA91827)
    DetectorStatus.TEST -> StatusText("Prueba en curso", "El Arduino está ejecutando una prueba local.", 0xFFE7EFFC, 0xFF174F8D)
    DetectorStatus.WARMING -> StatusText("Preparando detector", "Espera a que termine el periodo inicial para interpretar la lectura.", 0xFFE7EFFC, 0xFF174F8D)
    DetectorStatus.NO_DATA -> StatusText("Sin datos recientes", "El ESP32 responde. Revisa la alimentación y el enlace con el Arduino.", 0xFFFFF1D0, 0xFF684504)
    DetectorStatus.OFFLINE -> StatusText("Detector sin conexión", "Revisa el Wi-Fi del teléfono, la dirección y la alimentación del detector.", 0xFFFFF1D0, 0xFF684504)
    DetectorStatus.INVALID -> StatusText("Respuesta no válida", "Comprueba la dirección y el formato de /estado en el ESP32.", 0xFFFFF1D0, 0xFF684504)
    DetectorStatus.TLS_ERROR -> StatusText("No se pudo verificar el detector", "Revisa que la IP coincida con la del certificado y que hayas instalado la app actualizada.", 0xFFFFF1D0, 0xFF684504)
    DetectorStatus.ACCESS_DENIED -> StatusText("Acceso rechazado", "Vuelve a configurar la conexión y revisa la clave del detector.", 0xFFFFF1D0, 0xFF684504)
    null -> StatusText("Consultando detector", "Esperando la respuesta del detector.", 0xFFE7EFFC, 0xFF174F8D)
}

private fun fanText(ordered: Boolean?): String = when (ordered) {
    true -> "Encender"
    false -> "Apagar"
    null -> "Sin información"
}

@Composable
internal fun MonitorScreen(
    state: ScreenState,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onExit: () -> Unit,
    onDemoStatus: (DetectorStatus) -> Unit
) {
    val reading = state.reading
    val status = statusText(reading?.status)
    var showDemo by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Screen(scroll) {
        Heading("Estado del detector", if (state.demo) "Demostración · Datos de ejemplo" else state.address)
        Surface(
            color = Color(status.background), contentColor = Color(status.ink),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.title, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
                reading?.adc?.let {
                    Text("$it ADC", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("Escala del sensor de 0 a 1023", style = MaterialTheme.typography.bodyMedium)
                } ?: Text("Sin lectura actual", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text("Orden del ventilador · ${fanText(reading?.fanOrdered)}", fontWeight = FontWeight.SemiBold)
        state.receivedAt?.let { Detail("Última lectura · ${timeLabel(it)}") }
        ErrorText(state.historyWarning)
        Action(
            if (reading?.adc == null && reading != null) "Reintentar" else "Actualizar lectura",
            primary = true, onClick = onRefresh
        )
        Action("Consultar historial", onClick = onHistory)
        Action("Configurar conexión", onClick = onSettings)
        Action(if (state.demo) "Salir de la demostración" else "Cerrar sesión", onClick = onExit)
        if (state.demo) {
            HorizontalDivider()
            Action(if (showDemo) "Ocultar estados de prueba" else "Explorar estados de prueba") { showDemo = !showDemo }
            if (showDemo) {
                listOf(DetectorStatus.NORMAL, DetectorStatus.ALERT, DetectorStatus.TEST, DetectorStatus.NO_DATA,
                    DetectorStatus.OFFLINE, DetectorStatus.WARMING).forEach {
                    Action(statusText(it).title) {
                        onDemoStatus(it)
                        showDemo = false
                        scope.launch { scroll.animateScrollTo(0) }
                    }
                }
            }
        }
        Detail("Prototipo educativo. No reemplaza un detector certificado.")
    }
}

@Composable
internal fun HistoryScreen(state: ScreenState, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 520.dp).fillMaxSize(),
            contentPadding = pagePadding, verticalArrangement = sectionSpacing
        ) {
            item {
                Heading("Historial de lecturas", if (state.demo) "Demostración · Datos de ejemplo" else "Últimas 200 lecturas guardadas en este teléfono.")
            }
            item { Action("Volver al monitoreo", primary = true, onClick = onBack) }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.error != null) item { ErrorText(state.error) }
            if (!state.busy && state.error == null && state.history.isEmpty()) {
                item { Detail("Todavía no hay lecturas guardadas. Vuelve al monitoreo y actualiza la lectura.") }
            }
            items(state.history, key = { it.id }) { entry ->
                val status = statusText(entry.reading.status)
                Surface(color = MaterialTheme.colorScheme.surface, shape = actionShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Detail(timeLabel(entry.receivedAt))
                        Text("${entry.reading.adc} ADC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(status.title, color = Color(status.ink), fontWeight = FontWeight.SemiBold)
                        Detail("Orden del ventilador · ${fanText(entry.reading.fanOrdered)}")
                    }
                }
            }
        }
    }
}

private fun timeLabel(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
