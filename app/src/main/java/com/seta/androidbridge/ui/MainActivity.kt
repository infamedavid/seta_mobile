package com.seta.androidbridge.ui

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.seta.androidbridge.app.SetaMobileApplication
import com.seta.androidbridge.ui.theme.SetaTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as SetaMobileApplication).container)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.initialize()
            } else {
                viewModel.onCameraPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUi()

        permissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            val state by viewModel.uiState.collectAsState()
            val isoMin = state.isoMin
            val isoMax = state.isoMax
            val exposureTimeMin = state.exposureTimeMin
            val exposureTimeMax = state.exposureTimeMax
            val whiteBalanceTemperatureMin = state.whiteBalanceTemperatureMin
            val whiteBalanceTemperatureMax = state.whiteBalanceTemperatureMax

            val container = (application as SetaMobileApplication).container

            var serverMenuOpen by remember { mutableStateOf(false) }
            var cameraMenuOpen by remember { mutableStateOf(false) }
            var serverActionPending by remember { mutableStateOf(false) }
            var capturePending by remember { mutableStateOf(false) }
            var previewProfilePendingId by remember { mutableStateOf<String?>(null) }
            var focusDistanceDraft by remember { mutableFloatStateOf(state.currentFocusDistance ?: 0f) }
            var isoDraft by remember { mutableFloatStateOf(state.currentIso?.toFloat() ?: state.isoMin ?: 100f) }
            var exposureTimeDraft by remember {
                mutableFloatStateOf(state.currentExposureTime ?: state.exposureTimeMin ?: 0.02f)
            }
            var whiteBalanceTemperatureDraft by remember {
                mutableFloatStateOf(
                    state.currentWhiteBalanceTemperature?.toFloat()
                        ?: state.whiteBalanceTemperatureMin
                        ?: 5600f,
                )
            }

            val activeButtonColors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF9F9F9),
                contentColor = Color(0xFF4D4D4D),
                disabledContainerColor = Color(0xFFDADDDE),
                disabledContentColor = Color(0xFFBEC0C0),
            )

            val outlinedButtonColors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFFE5E7E7),
                contentColor = Color(0xFF4D4D4D),
                disabledContainerColor = Color(0xFFDADDDE),
                disabledContentColor = Color(0xFFBEC0C0),
            )

            val panelColors = CardDefaults.cardColors(
                containerColor = Color(0xFFE5E7E7),
                contentColor = Color(0xFF4D4D4D),
            )

            val buttonBorder = BorderStroke(1.dp, Color(0xFF4D4D4D))

            LaunchedEffect(state.serverRunning, state.lastError) {
                serverActionPending = false
            }

            LaunchedEffect(state.lastCaptureId, state.lastError) {
                capturePending = false
            }

            LaunchedEffect(state.previewProfileId, state.lastError) {
                previewProfilePendingId = null
            }

            LaunchedEffect(state.currentFocusDistance) {
                focusDistanceDraft = state.currentFocusDistance ?: 0f
            }

            LaunchedEffect(state.currentIso, state.isoMin) {
                isoDraft = state.currentIso?.toFloat() ?: state.isoMin ?: 100f
            }

            LaunchedEffect(state.currentExposureTime, state.exposureTimeMin) {
                exposureTimeDraft = state.currentExposureTime ?: state.exposureTimeMin ?: 0.02f
            }

            LaunchedEffect(state.currentWhiteBalanceTemperature, state.whiteBalanceTemperatureMin) {
                whiteBalanceTemperatureDraft =
                    state.currentWhiteBalanceTemperature?.toFloat()
                        ?: state.whiteBalanceTemperatureMin
                                ?: 5600f
            }

            SetaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).also { pv ->
                                    container.cameraEngine.attachPreviewView(pv, this@MainActivity)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        Text(
                            text = if (state.serverRunning) "Server running" else "Server stopped",
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    serverMenuOpen = !serverMenuOpen
                                    if (serverMenuOpen) cameraMenuOpen = false
                                },
                                containerColor = Color(0xFFE5E7E7),
                                contentColor = Color(0xFF4D4D4D),
                            ) {
                                Text("Srv")
                            }

                            FloatingActionButton(
                                onClick = {
                                    cameraMenuOpen = !cameraMenuOpen
                                    if (cameraMenuOpen) serverMenuOpen = false
                                },
                                containerColor = Color(0xFFE5E7E7),
                                contentColor = Color(0xFF4D4D4D),
                            ) {
                                Text("Cam")
                            }
                        }

                        if (serverMenuOpen) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .widthIn(min = 280.dp, max = 360.dp),
                                colors = panelColors,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = if (state.serverRunning) "Server running" else "Server stopped",
                                        style = MaterialTheme.typography.titleMedium,
                                    )

                                    Text("IP: ${state.ipAddress ?: "unknown"}")
                                    Text("Port: ${state.port}")
                                    Text("URL: ${state.baseUrl ?: "-"}")
                                    Text("Camera open: ${state.cameraOpen}")
                                    Text("Active lens: ${state.activeLensId ?: "-"}")

                                    state.lastCaptureId?.takeIf { it.isNotBlank() }?.let {
                                        Text("Last capture: $it")
                                    }

                                    state.lastError?.let { error ->
                                        Text(
                                            text = "Error: $error",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            serverActionPending = true
                                            if (state.serverRunning) {
                                                viewModel.onStopServerClicked()
                                            } else {
                                                viewModel.onStartServerClicked()
                                            }
                                        },
                                        enabled = !serverActionPending,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = activeButtonColors,
                                    ) {
                                        Text(
                                            when {
                                                serverActionPending && state.serverRunning -> "Stopping..."
                                                serverActionPending && !state.serverRunning -> "Starting..."
                                                state.serverRunning -> "Stop Server"
                                                else -> "Start Server"
                                            },
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            capturePending = true
                                            viewModel.onCaptureClicked()
                                        },
                                        enabled = !capturePending,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = outlinedButtonColors,
                                        border = buttonBorder,
                                    ) {
                                        Text(if (capturePending) "Capturing..." else "Capture (debug)")
                                    }

                                    Text(
                                        text = "Preview profile",
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    state.availablePreviewProfiles.forEach { profile ->
                                        val isSelected = profile.id == state.previewProfileId
                                        val isPending = previewProfilePendingId == profile.id

                                        if (isSelected) {
                                            Button(
                                                onClick = { },
                                                enabled = false,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = activeButtonColors,
                                            ) {
                                                Text(
                                                    if (isPending) {
                                                        "${profile.label} (applying...)"
                                                    } else {
                                                        "${profile.label} (active)"
                                                    },
                                                )
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    previewProfilePendingId = profile.id
                                                    viewModel.onPreviewProfileSelected(profile.id)
                                                },
                                                enabled = previewProfilePendingId == null,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = outlinedButtonColors,
                                                border = buttonBorder,
                                            ) {
                                                Text(
                                                    if (isPending) {
                                                        "${profile.label} (applying...)"
                                                    } else {
                                                        profile.label
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = "Higher quality increases latency and CPU/network usage.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )

                                    OutlinedButton(
                                        onClick = { serverMenuOpen = false },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = outlinedButtonColors,
                                        border = buttonBorder,
                                    ) {
                                        Text("Close")
                                    }
                                }
                            }
                        }

                        if (cameraMenuOpen) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .widthIn(min = 300.dp, max = 380.dp),
                                colors = panelColors,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = "Camera Setup",
                                        style = MaterialTheme.typography.titleMedium,
                                    )

                                    if (state.availableLensIds.isNotEmpty()) {
                                        Text("Lens", style = MaterialTheme.typography.titleSmall)

                                        state.availableLensIds.forEach { lensId ->
                                            val isActive = lensId == state.activeLensId
                                            if (isActive) {
                                                Button(
                                                    onClick = { },
                                                    enabled = false,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = activeButtonColors,
                                                ) {
                                                    Text("$lensId (active)")
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { viewModel.onLensSelected(lensId) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = outlinedButtonColors,
                                                    border = buttonBorder,
                                                ) {
                                                    Text(lensId)
                                                }
                                            }
                                        }
                                    }

                                    if (state.availableCameraSettings.containsKey("focus_mode")) {
                                        Text("Focus mode", style = MaterialTheme.typography.titleSmall)

                                        state.availableFocusModes.forEach { mode ->
                                            val isActive = mode == state.currentFocusMode
                                            if (isActive) {
                                                Button(
                                                    onClick = { },
                                                    enabled = false,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = activeButtonColors,
                                                ) {
                                                    Text("$mode (active)")
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { viewModel.onFocusModeSelected(mode) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = outlinedButtonColors,
                                                    border = buttonBorder,
                                                ) {
                                                    Text(mode)
                                                }
                                            }
                                        }
                                    }

                                    if (state.availableCameraSettings.containsKey("focus_distance")) {
                                        Text("Focus distance", style = MaterialTheme.typography.titleSmall)
                                        Text(String.format(Locale.US, "%.2f", focusDistanceDraft))

                                        Slider(
                                            value = focusDistanceDraft,
                                            onValueChange = { focusDistanceDraft = it },
                                            valueRange = 0f..1f,
                                            onValueChangeFinished = {
                                                viewModel.onFocusDistanceChanged(focusDistanceDraft)
                                            },
                                        )
                                    }

                                    state.currentAeLock?.let { aeLock ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("AE lock")
                                            Switch(
                                                checked = aeLock,
                                                onCheckedChange = { viewModel.onAeLockChanged(it) },
                                            )
                                        }
                                    }

                                    state.currentAwbLock?.let { awbLock ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("AWB lock")
                                            Switch(
                                                checked = awbLock,
                                                onCheckedChange = { viewModel.onAwbLockChanged(it) },
                                            )
                                        }
                                    }

                                    if (state.availableCameraSettings.containsKey("white_balance_mode")) {
                                        Text("White balance mode", style = MaterialTheme.typography.titleSmall)

                                        state.availableWhiteBalanceModes.forEach { mode ->
                                            val isActive = mode == state.currentWhiteBalanceMode
                                            if (isActive) {
                                                Button(
                                                    onClick = { },
                                                    enabled = false,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = activeButtonColors,
                                                ) {
                                                    Text("$mode (active)")
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { viewModel.onWhiteBalanceModeSelected(mode) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = outlinedButtonColors,
                                                    border = buttonBorder,
                                                ) {
                                                    Text(mode)
                                                }
                                            }
                                        }
                                    }

                                    if (
                                        state.availableCameraSettings.containsKey("iso") &&
                                        isoMin != null &&
                                        isoMax != null &&
                                        isoMax > isoMin
                                    ) {
                                        Text("ISO", style = MaterialTheme.typography.titleSmall)
                                        Text(isoDraft.toInt().toString())

                                        Slider(
                                            value = isoDraft.coerceIn(isoMin, isoMax),
                                            onValueChange = {
                                                isoDraft = it.coerceIn(isoMin, isoMax)
                                            },
                                            valueRange = isoMin..isoMax,
                                            onValueChangeFinished = {
                                                viewModel.onIsoChanged(isoDraft)
                                            },
                                        )
                                    }

                                    if (
                                        state.availableCameraSettings.containsKey("exposure_time") &&
                                        exposureTimeMin != null &&
                                        exposureTimeMax != null &&
                                        exposureTimeMax > exposureTimeMin
                                    ) {
                                        Text("Exposure time", style = MaterialTheme.typography.titleSmall)
                                        Text(String.format(Locale.US, "%.6f s", exposureTimeDraft))

                                        Slider(
                                            value = exposureTimeDraft.coerceIn(exposureTimeMin, exposureTimeMax),
                                            onValueChange = {
                                                exposureTimeDraft = it.coerceIn(exposureTimeMin, exposureTimeMax)
                                            },
                                            valueRange = exposureTimeMin..exposureTimeMax,
                                            onValueChangeFinished = {
                                                viewModel.onExposureTimeChanged(exposureTimeDraft)
                                            },
                                        )
                                    }

                                    if (
                                        state.availableCameraSettings.containsKey("white_balance_temperature") &&
                                        whiteBalanceTemperatureMin != null &&
                                        whiteBalanceTemperatureMax != null &&
                                        whiteBalanceTemperatureMax > whiteBalanceTemperatureMin
                                    ) {
                                        Text("White balance temperature", style = MaterialTheme.typography.titleSmall)
                                        Text("${whiteBalanceTemperatureDraft.toInt()} K")

                                        Slider(
                                            value = whiteBalanceTemperatureDraft.coerceIn(
                                                whiteBalanceTemperatureMin,
                                                whiteBalanceTemperatureMax,
                                            ),
                                            onValueChange = {
                                                whiteBalanceTemperatureDraft = it.coerceIn(
                                                    whiteBalanceTemperatureMin,
                                                    whiteBalanceTemperatureMax,
                                                )
                                            },
                                            valueRange = whiteBalanceTemperatureMin..whiteBalanceTemperatureMax,
                                            onValueChangeFinished = {
                                                viewModel.onWhiteBalanceTemperatureChanged(
                                                    whiteBalanceTemperatureDraft,
                                                )
                                            },
                                        )
                                    }

                                    state.lastError?.let { error ->
                                        Text(
                                            text = "Error: $error",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { cameraMenuOpen = false },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = outlinedButtonColors,
                                        border = buttonBorder,
                                    ) {
                                        Text("Close")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as SetaMobileApplication).container.cameraEngine.detachPreviewView()
    }
}
