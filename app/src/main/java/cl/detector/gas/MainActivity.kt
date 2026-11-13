package cl.detector.gas

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF174F8D),
                    background = Color(0xFFF2F6FB),
                    surface = Color.White,
                    onSurface = Color(0xFF142B43),
                    onBackground = Color(0xFF142B43),
                    onSurfaceVariant = Color(0xFF41586E),
                    outline = Color(0xFF7C90A6),
                    error = Color(0xFFA91827)
                )
            ) {
                DetectorApp(activity = this)
            }
        }
    }
}

@Composable
private fun DetectorApp(activity: ComponentActivity, model: DetectorViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != Page.LOGIN) {
        if (!state.busy || state.page == Page.HISTORY) {
            when (state.page) {
                Page.HISTORY -> model.backToMonitor()
                Page.MONITOR -> model.connectionPage()
                Page.CONNECTION -> model.signOut()
                else -> model.authPage(Page.LOGIN)
            }
        }
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding()) {
            when (state.page) {
                Page.LOGIN, Page.REGISTER, Page.RECOVER, Page.EMAIL_SENT -> AuthScreen(
                    state = state,
                    firebaseReady = model.firebaseReady,
                    onSubmit = model::submit,
                    onPage = model::authPage,
                    onGoogle = { model.google(activity) },
                    onDemo = model::startDemo
                )
                Page.CONNECTION -> ConnectionScreen(state, model::connect, model::signOut)
                Page.MONITOR -> {
                    LifecycleResumeEffect(model) {
                        model.setMonitorVisible(true)
                        onPauseOrDispose { model.setMonitorVisible(false) }
                    }
                    AlertSound(state.reading?.status == DetectorStatus.ALERT)
                    MonitorScreen(
                        state, { model.refresh() }, model::connectionPage,
                        model::showHistory, model::signOut, model::chooseDemoStatus
                    )
                }
                Page.HISTORY -> HistoryScreen(state, model::backToMonitor)
            }
        }
    }
}

@Composable
private fun AlertSound(active: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 75)
            } catch (_: RuntimeException) {
                return@repeatOnLifecycle
            }
            try {
                while (true) {
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350)
                    delay(2_500)
                }
            } finally {
                tone.release()
            }
        }
    }
}
