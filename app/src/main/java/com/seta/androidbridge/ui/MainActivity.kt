package com.seta.androidbridge.ui

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.seta.androidbridge.app.SetaMobileApplication
import com.seta.androidbridge.ui.theme.SetaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "seta_mobile_prefs"
        private const val PREF_ROTATION_LOCK_ENABLED = "rotation_lock_enabled"
    }

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

        val initialRotationLockEnabled = isRotationLockEnabled()
        applyRotationLock(initialRotationLockEnabled)

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
            var rotationLockEnabled by remember { mutableStateOf(initialRotationLockEnabled) }
            var serverActionPending by remember { mutableStateOf(false) }
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
                disabledContentColor = Color(0xFF4D4D4D),
            )

            val outlinedButtonColors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFFE5E7E7),
                contentColor = Color(0xFF4D4D4D),
                disabledContainerColor = Color(0xFFDADDDE),
                disabledContentColor = Color(0xFF4D4D4D),
            )

            val panelColors = CardDefaults.cardColors(
                containerColor = Color(0xFFE5E7E7),
                contentColor = Color(0xFF4D4D4D),
            )

            val buttonBorder = BorderStroke(1.dp, Color(0xFF4D4D4D))

            LaunchedEffect(state.serverRunning, state.lastError) {
                serverActionPending = false
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

                        OverlayPreviewStack(
                            layers = state.activeOverlayLayers,
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

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 28.dp, end = 28.dp, bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.66f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OverlayDepthButton(
                                    text = "Single",
                                    selected = state.overlayStackDepth == OverlayStackDepth.SINGLE,
                                    onClick = { viewModel.onOverlayStackDepthSelected(OverlayStackDepth.SINGLE) },
                                    modifier = Modifier.weight(1f),
                                )
                                OverlayDepthButton(
                                    text = "Double",
                                    selected = state.overlayStackDepth == OverlayStackDepth.DOUBLE,
                                    onClick = { viewModel.onOverlayStackDepthSelected(OverlayStackDepth.DOUBLE) },
                                    modifier = Modifier.weight(1f),
                                )
                                OverlayDepthButton(
                                    text = "Triple",
                                    selected = state.overlayStackDepth == OverlayStackDepth.TRIPLE,
                                    onClick = { viewModel.onOverlayStackDepthSelected(OverlayStackDepth.TRIPLE) },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Slider(
                                value = state.overlayOpacity,
                                onValueChange = { viewModel.onOverlayOpacityChanged(it) },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(0.72f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF4D4D4D).copy(alpha = 0.78f),
                                    activeTrackColor = Color(0xFF4D4D4D).copy(alpha = 0.58f),
                                    inactiveTrackColor = Color(0xFF4D4D4D).copy(alpha = 0.18f),
                                ),
                            )
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (state.serverRunning) "Server running" else "Server stopped",
                                            style = MaterialTheme.typography.titleMedium,
                                        )

                                        OutlinedButton(
                                            onClick = { serverMenuOpen = false },
                                            modifier = Modifier.heightIn(min = 32.dp),
                                            colors = outlinedButtonColors,
                                            border = buttonBorder,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Close")
                                        }
                                    }

                                    Text("IP: ${state.ipAddress ?: "unknown"}")
                                    Text("Port: ${state.port}")

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

                                    Text(
                                        text = "Preview profile",
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    state.availablePreviewProfiles.forEach { profile ->
                                        val isSelected = profile.id == state.previewProfileId
                                        val isPending = previewProfilePendingId == profile.id
                                        val label = displayPreviewProfileLabel(profile.label)

                                        if (isSelected) {
                                            Button(
                                                onClick = { },
                                                enabled = false,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = activeButtonColors,
                                            ) {
                                                Text(
                                                    if (isPending) {
                                                        "$label (applying...)"
                                                    } else {
                                                        label
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
                                                        "$label (applying...)"
                                                    } else {
                                                        label
                                                    },
                                                )
                                            }
                                        }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Camera Setup",
                                            style = MaterialTheme.typography.titleMedium,
                                        )

                                        OutlinedButton(
                                            onClick = { cameraMenuOpen = false },
                                            modifier = Modifier.heightIn(min = 32.dp),
                                            colors = outlinedButtonColors,
                                            border = buttonBorder,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Close")
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Lock screen rotation")
                                        Switch(
                                            checked = rotationLockEnabled,
                                            onCheckedChange = { enabled ->
                                                rotationLockEnabled = enabled
                                                setRotationLockEnabled(enabled)
                                                applyRotationLock(enabled)
                                            },
                                        )
                                    }

                                    Text(
                                        text = "Onion skin",
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    Text(
                                        text = "Overlay history: ${state.overlayHistoryCount}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )

                                    Text("Mode", style = MaterialTheme.typography.bodyMedium)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        val autoSelected = state.overlayMode == OverlayMode.AUTO
                                        val manualSelected = state.overlayMode == OverlayMode.MANUAL

                                        if (autoSelected) {
                                            Button(
                                                onClick = { },
                                                enabled = false,
                                                modifier = Modifier.weight(1f),
                                                colors = activeButtonColors,
                                            ) {
                                                Text("Auto")
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { viewModel.onOverlayModeSelected(OverlayMode.AUTO) },
                                                modifier = Modifier.weight(1f),
                                                colors = outlinedButtonColors,
                                                border = buttonBorder,
                                            ) {
                                                Text("Auto")
                                            }
                                        }

                                        if (manualSelected) {
                                            Button(
                                                onClick = { },
                                                enabled = false,
                                                modifier = Modifier.weight(1f),
                                                colors = activeButtonColors,
                                            ) {
                                                Text("Manual")
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { viewModel.onOverlayModeSelected(OverlayMode.MANUAL) },
                                                modifier = Modifier.weight(1f),
                                                colors = outlinedButtonColors,
                                                border = buttonBorder,
                                            ) {
                                                Text("Manual")
                                            }
                                        }
                                    }

                                    if (state.overlayMode == OverlayMode.MANUAL) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            OutlinedButton(
                                                onClick = { viewModel.onSelectNewerOverlayClicked() },
                                                enabled = state.canSelectNewerOverlay,
                                                modifier = Modifier.weight(1f),
                                                colors = outlinedButtonColors,
                                                border = buttonBorder,
                                            ) {
                                                Text("Newer")
                                            }

                                            OutlinedButton(
                                                onClick = { viewModel.onSelectOlderOverlayClicked() },
                                                enabled = state.canSelectOlderOverlay,
                                                modifier = Modifier.weight(1f),
                                                colors = outlinedButtonColors,
                                                border = buttonBorder,
                                            ) {
                                                Text("Older")
                                            }
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.onPurgeOverlayHistoryClicked() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = outlinedButtonColors,
                                        border = buttonBorder,
                                    ) {
                                        Text("Purge history")
                                    }

                                    if (state.availableLensIds.isNotEmpty()) {
                                        CompactOptionsLine(
                                            title = "Lens",
                                            options = state.availableLensIds,
                                            selectedOption = state.activeLensId,
                                            onOptionSelected = viewModel::onLensSelected,
                                        )
                                    }

                                    if (state.availableCameraSettings.containsKey("focus_mode")) {
                                        CompactOptionsLine(
                                            title = "Focus mode",
                                            options = state.availableFocusModes,
                                            selectedOption = state.currentFocusMode,
                                            onOptionSelected = viewModel::onFocusModeSelected,
                                        )
                                    }

                                    if (state.availableCameraSettings.containsKey("focus_distance")) {
                                        Text(
                                            text = "Focus: ${String.format(Locale.US, "%.2f", focusDistanceDraft)}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )

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
                                        CompactOptionsGrid(
                                            title = "WB mode",
                                            options = state.availableWhiteBalanceModes,
                                            selectedOption = state.currentWhiteBalanceMode,
                                            onOptionSelected = viewModel::onWhiteBalanceModeSelected,
                                            labelFor = ::shortWhiteBalanceModeLabel,
                                            itemsPerRow = 4,
                                        )
                                    }

                                    if (
                                        state.availableCameraSettings.containsKey("iso") &&
                                        isoMin != null &&
                                        isoMax != null &&
                                        isoMax > isoMin
                                    ) {
                                        Text(
                                            text = "ISO: ${isoDraft.toInt()}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )

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
                                        Text(
                                            text = "Exposure: ${String.format(Locale.US, "%.6f s", exposureTimeDraft)}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )

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
                                        Text(
                                            text = "WB Temp: ${whiteBalanceTemperatureDraft.toInt()} K",
                                            style = MaterialTheme.typography.titleSmall,
                                        )

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
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isRotationLockEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_ROTATION_LOCK_ENABLED, false)
    }

    private fun setRotationLockEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_ROTATION_LOCK_ENABLED, enabled).apply()
    }

    private fun applyRotationLock(enabled: Boolean) {
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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


@Composable
private fun OverlayPreviewStack(
    layers: List<OverlayRenderLayer>,
    modifier: Modifier = Modifier,
) {
    if (layers.isEmpty()) return

    Box(modifier = modifier) {
        layers.forEach { layer ->
            OverlayPreviewImage(
                filePath = layer.filePath,
                alpha = layer.alpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OverlayPreviewImage(
    filePath: String?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val overlayBitmap by produceState<ImageBitmap?>(initialValue = null, filePath) {
        value = if (filePath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadOverlayBitmap(filePath)
            }
        }
    }

    val bitmap = overlayBitmap ?: return
    if (alpha <= 0f) return

    Image(
        bitmap = bitmap,
        contentDescription = "Onion skin overlay",
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alpha = alpha,
    )
}

@Composable
private fun OverlayDepthButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (selected) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFFE5E7E7).copy(alpha = 0.42f),
            contentColor = Color(0xFF4D4D4D),
        )
    } else {
        ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFF4D4D4D).copy(alpha = 0.72f),
        )
    }

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 34.dp),
        colors = colors,
        border = BorderStroke(1.dp, Color(0xFF4D4D4D).copy(alpha = if (selected) 0.55f else 0.24f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}



@Composable
private fun CompactOptionsLine(
    title: String,
    options: List<String>,
    selectedOption: String?,
    onOptionSelected: (String) -> Unit,
    labelFor: (String) -> String = { it },
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                CompactMenuButton(
                    text = labelFor(option),
                    selected = option == selectedOption,
                    onClick = { onOptionSelected(option) },
                )
            }
        }
    }
}

@Composable
private fun CompactOptionsGrid(
    title: String,
    options: List<String>,
    selectedOption: String?,
    onOptionSelected: (String) -> Unit,
    labelFor: (String) -> String = { it },
    itemsPerRow: Int = 4,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )

        options.chunked(itemsPerRow).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    CompactMenuButton(
                        text = labelFor(option),
                        selected = option == selectedOption,
                        onClick = { onOptionSelected(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMenuButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFF9F9F9),
        contentColor = Color(0xFF4D4D4D),
        disabledContainerColor = Color(0xFFDADDDE),
        disabledContentColor = Color(0xFF4D4D4D),
    )

    val outlinedColors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color(0xFFE5E7E7),
        contentColor = Color(0xFF4D4D4D),
        disabledContainerColor = Color(0xFFDADDDE),
        disabledContentColor = Color(0xFF4D4D4D),
    )

    val border = BorderStroke(1.dp, Color(0xFF4D4D4D))

    if (selected) {
        Button(
            onClick = { },
            enabled = false,
            modifier = modifier.heightIn(min = 34.dp),
            colors = activeColors,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 34.dp),
            colors = outlinedColors,
            border = border,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(text)
        }
    }
}

private fun displayPreviewProfileLabel(label: String): String {
    val normalized = label
        .replace("(experimental)", "", ignoreCase = true)
        .trim()
        .lowercase(Locale.US)

    return when (normalized) {
        "muy alta" -> "Very High"
        "alta" -> "High"
        "media" -> "Medium"
        "baja" -> "Low"
        else -> normalized
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> ch.titlecase(Locale.US) }
            }
    }
}

private fun shortWhiteBalanceModeLabel(mode: String): String {
    val normalized = mode.trim().lowercase(Locale.US)

    return when {
        normalized == "auto" || normalized.contains("auto") -> "AWB"
        normalized.contains("daylight") || normalized == "day" -> "Day"
        normalized.contains("cloud") -> "Cloud"
        normalized.contains("shade") -> "Shade"
        normalized.contains("tung") || normalized.contains("incan") -> "Tung"
        normalized.contains("fluo") -> "Fluo"
        normalized.contains("flash") -> "Flash"
        normalized.contains("twilight") -> "Twil"
        normalized.contains("warm") -> "Warm"
        else -> mode
            .replace("_", " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.US) } }
            .take(8)
    }
}


private fun loadOverlayBitmap(filePath: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(filePath, bounds)

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
    var inSampleSize = 1
    while (maxDimension / inSampleSize > 2048) {
        inSampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
    }

    return BitmapFactory.decodeFile(filePath, decodeOptions)?.asImageBitmap()
}
