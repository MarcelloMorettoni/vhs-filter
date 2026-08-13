package com.retro.vhs.ui

import android.content.Context
import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.retro.vhs.data.OutputQuality
import com.retro.vhs.vhs.VhsPreset
import kotlinx.coroutines.delay

data class SettingsUiState(
    val osd: Boolean,
    val eraDate: Boolean,
    val vhsAudio: Boolean,
    val recordAudio: Boolean,
    val letterbox: Boolean,
    val dropouts: Boolean,
    val quality: OutputQuality,
    val rotationOffset: Int = 0
)

data class ProcessingUiState(val progress: Float, val label: String)

@Composable
fun CameraScreen(
    presets: List<VhsPreset>,
    selectedIndex: Int,
    onSelectPreset: (Int) -> Unit,
    recording: Boolean,
    elapsedSec: Int,
    onToggleRecord: () -> Unit,
    onSwitchCamera: () -> Unit,
    onImport: () -> Unit,
    settings: SettingsUiState,
    onSettingsChange: (SettingsUiState) -> Unit,
    processing: ProcessingUiState?,
    onCancelProcessing: () -> Unit,
    status: String?,
    onStatusShown: () -> Unit,
    diagnostics: String,
    onSaveDebugReport: () -> Unit,
    onSupport: () -> Unit,
    onRotatePicture: () -> Unit,
    previewFactory: (Context) -> View
) {
    var showSettings by remember { mutableStateOf(false) }
    val preset = presets.getOrElse(selectedIndex) { presets.first() }
    val listState = rememberLazyListState()

    var showBlurb by remember { mutableStateOf(true) }

    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex.coerceAtLeast(0))
        showBlurb = true
        delay(4000)
        showBlurb = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(factory = previewFactory, modifier = Modifier.fillMaxSize())

        // ---- the tape library lives in the left pillarbox bar ----
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(TAPE_RAIL_WIDTH)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.4f))
                    )
                )
                .padding(vertical = 10.dp)
        ) {
            Text(
                "TAPE LIBRARY",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 12.dp)
            )
            Text(
                "${presets.size} FORMATS",
                style = MaterialTheme.typography.labelSmall,
                color = TapeAmber.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
            )
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(presets) { index, item ->
                    PresetCard(
                        preset = item,
                        selected = index == selectedIndex,
                        onClick = { onSelectPreset(index) }
                    )
                }
            }
        }

        // ---- transport state, top right ----
        Row(
            Modifier.align(Alignment.TopEnd).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recording) {
                val transition = rememberInfiniteTransition(label = "rec")
                val alpha by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.1f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "blink"
                )
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(TapeRed.copy(alpha = alpha))
                )
                Text(
                    "  REC  ${timecode(elapsedSec)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            } else {
                Text(
                    "STANDBY",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }

        // ---- transport controls, right hand side ----
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // These stay laid out while recording so the record button never moves
            // under the user's thumb mid-take.
            RecordButton(recording = recording, onClick = onToggleRecord)
            ChassisButton(
                Icons.Filled.ScreenRotation,
                if (settings.rotationOffset == 0) "rotate" else "rot ${settings.rotationOffset}°",
                true,
                onRotatePicture
            )
            ChassisButton(Icons.Filled.Cameraswitch, "flip", !recording, onSwitchCamera)
            ChassisButton(Icons.Filled.VideoLibrary, "tape in", !recording, onImport)
            ChassisButton(Icons.Filled.Settings, "setup", !recording) { showSettings = true }
        }

        // ---- sleeve notes for the tape just loaded; fades out on its own ----
        if (showBlurb) {
            Text(
                preset.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TAPE_RAIL_WIDTH + 10.dp, end = 106.dp, bottom = 10.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // ---- messages ----
        if (status != null) {
            LaunchedEffect(status) {
                delay(3200)
                onStatusShown()
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TapeAmber)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        if (processing != null) {
            ProcessingOverlay(processing, onCancelProcessing)
        }

        if (showSettings) {
            SettingsDialog(
                settings = settings,
                onChange = onSettingsChange,
                diagnostics = diagnostics,
                onSaveDebugReport = onSaveDebugReport,
                onSupport = onSupport,
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(2.dp, Color.White.copy(alpha = 0.65f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(if (recording) 24.dp else 48.dp)
                .clip(if (recording) RoundedCornerShape(3.dp) else CircleShape)
                .background(TapeRed)
        )
    }
}

@Composable
private fun ChassisButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val fade = if (enabled) 1f else 0.28f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Chassis.copy(alpha = 0.82f * fade))
                .border(1.dp, Color.White.copy(alpha = 0.22f * fade), RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = fade),
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f * fade)
        )
    }
}

@Composable
private fun PresetCard(preset: VhsPreset, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) TapeAmber else Color.White.copy(alpha = 0.16f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) ChassisLight else Chassis.copy(alpha = 0.8f))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (selected) TapeRed else Color.White.copy(alpha = 0.25f))
            )
            Text(
                "  ${preset.name.uppercase()}",
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "${preset.year} · ${preset.tapeSpeed}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) TapeAmber else Color.White.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun ProcessingOverlay(state: ProcessingUiState, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(340.dp)
        ) {
            Text(
                "DUBBING TO TAPE",
                style = MaterialTheme.typography.titleLarge,
                color = TapeAmber
            )
            Text(
                state.label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
            )
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = TapeRed,
                trackColor = ChassisLight
            )
            Text(
                "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp)
            )
            TextButton(onClick = onCancel) {
                Text("STOP", style = MaterialTheme.typography.titleMedium, color = TapeRed)
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: SettingsUiState,
    onChange: (SettingsUiState) -> Unit,
    diagnostics: String,
    onSaveDebugReport: () -> Unit,
    onSupport: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        // A landscape phone is short: the menu has to scroll or the lower rows fall
        // off the bottom of the screen.
        Column(
            Modifier
                .width(430.dp)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(6.dp))
                .background(Chassis)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text("SETUP MENU", style = MaterialTheme.typography.titleLarge, color = TapeAmber)
            Text(
                "PRESS SET TO CHANGE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ToggleRow("DATE / TIME STAMP", settings.osd) { onChange(settings.copy(osd = it)) }
            ToggleRow("USE PERIOD DATE", settings.eraDate) { onChange(settings.copy(eraDate = it)) }
            ToggleRow("TAPE AUDIO", settings.vhsAudio) { onChange(settings.copy(vhsAudio = it)) }
            ToggleRow("RECORD SOUND", settings.recordAudio) {
                onChange(settings.copy(recordAudio = it))
            }
            ToggleRow("LETTERBOX IMPORTS", settings.letterbox) {
                onChange(settings.copy(letterbox = it))
            }
            ToggleRow("TAPE DROPOUTS", settings.dropouts) {
                onChange(settings.copy(dropouts = it))
            }

            Text(
                "PICTURE ROTATION",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "AUTO", 90 to "+90°", 180 to "+180°", 270 to "+270°")
                    .forEach { (degrees, label) ->
                        val selected = settings.rotationOffset == degrees
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (selected) TapeAmber else ChassisLight)
                                .clickable { onChange(settings.copy(rotationOffset = degrees)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
            }

            Text(
                diagnostics,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.38f),
                modifier = Modifier.padding(top = 6.dp)
            )

            Text(
                "OUTPUT",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputQuality.entries.forEach { quality ->
                    val selected = settings.quality == quality
                    Text(
                        quality.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (selected) TapeAmber else ChassisLight)
                            .clickable { onChange(settings.copy(quality = quality)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Text(
                "SERVICE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
            )
            Text(
                "SAVE DEBUG REPORT",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(TapeCyan)
                    .clickable(onClick = onSaveDebugReport)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Text(
                "writes Download/VHS-88/ and opens the share sheet",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.38f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                "VHS-88 · MARCELLO MORETTONI",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "github.com/MarcelloMorettoni/vhs-filter",
                style = MaterialTheme.typography.bodySmall,
                color = TapeCyan.copy(alpha = 0.75f)
            )
            Text(
                "LIKE THIS APP?",
                style = MaterialTheme.typography.labelSmall,
                color = TapeAmber,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "BUY ME A COFFEE  \u2615",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TapeAmber)
                    .clickable(onClick = onSupport)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("EXIT", style = MaterialTheme.typography.titleMedium, color = TapeAmber)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            modifier = Modifier.scale(0.85f),
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = TapeAmber,
                uncheckedTrackColor = ChassisLight
            )
        )
    }
}

private val TAPE_RAIL_WIDTH = 136.dp

private fun timecode(seconds: Int): String =
    "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
