package com.jacobeast.gear360revive.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------- Connect tab

@Composable
fun ConnectScreen(state: AppUiState, vm: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Connect to your Gear 360", style = MaterialTheme.typography.headlineSmall)
        Text(
            "1. On the camera, turn on Wi-Fi / “Connect to Street View” mode.\n" +
                "2. Join the Gear 360 Wi-Fi network in Android Settings. " +
                "Android will warn it has no internet — choose to stay connected.\n" +
                "3. Come back here and connect. The camera is your Wi-Fi gateway; " +
                "192.168.107.1 is the address seen on 2016 units."
        )
        OutlinedTextField(
            value = state.host,
            onValueChange = vm::onHostChange,
            label = { Text("Camera host / IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect, enabled = !state.connecting) {
                Text(if (state.connected) "Reconnect / test" else "Connect / test")
            }
            OutlinedButton(onClick = vm::bindToWifi) { Text("Pin traffic to Wi-Fi") }
        }
        if (state.connecting) CircularProgressIndicator()
        state.bindMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(state.statusMessage)
        state.cameraInfo?.let { info ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Camera", style = MaterialTheme.typography.titleMedium)
                    Text("Manufacturer: ${info.manufacturer}")
                    Text("Model: ${info.model}")
                    Text("Firmware: ${info.firmwareVersion}")
                    if (info.apiEndpoints.isNotEmpty()) {
                        Text("OSC endpoints: ${info.apiEndpoints.joinToString(", ")}")
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- Remote tab

@Composable
fun RemoteScreen(state: AppUiState, vm: AppViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusBar(state, onRefresh = vm::refreshState)
        // "Preview": OSC has no live viewfinder (Samsung kept that protocol
        // private), so we show the last captured photo instead.
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center
        ) {
            val bytes = state.lastShotBytes
            if (bytes != null) {
                val bitmap = remember(bytes) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Last captured photo (dual-fisheye, unstitched)",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else Text("Last shot could not be decoded for preview.")
            } else {
                Text(
                    "No live viewfinder — the original app's preview used Samsung's " +
                        "private protocol. Your last shot will appear here instead.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        state.countdown?.let {
            Text("Self-timer: $it", style = MaterialTheme.typography.headlineMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimerSelector(state.selfTimerSeconds, vm::onSelfTimerChange)
            Button(
                onClick = vm::shoot,
                enabled = state.connected && !state.shooting,
                shape = CircleShape,
                modifier = Modifier.size(88.dp)
            ) {
                if (state.shooting) CircularProgressIndicator(modifier = Modifier.size(32.dp))
                else Text("SHOOT")
            }
            OutlinedButton(onClick = { showSettings = true }, enabled = state.connected) {
                Text("Settings")
            }
        }
        Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
        state.lastShotUri?.let { Text("Last shot on camera: $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (showSettings) {
        SettingsSheet(state, vm, onDismiss = { showSettings = false })
    }
}

@Composable
fun StatusBar(state: AppUiState, onRefresh: () -> Unit) {
    val cs = state.cameraState
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (state.connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleMedium
                )
                val batt = cs?.batteryLevel
                Text("Battery: ${if (batt != null) "${(batt * 100).toInt()}%" else "—"}")
                val rem = cs?.remainingSpaceBytes
                Text("Free space: ${if (rem != null) "%.1f GB".format(rem / 1e9) else "—"}")
                cs?.captureStatus?.let { Text("Status: $it") }
            }
            OutlinedButton(onClick = onRefresh, enabled = state.connected) { Text("Refresh") }
        }
    }
}

@Composable
fun TimerSelector(selected: Int, onSelect: (Int) -> Unit) {
    OptionDropdown(
        label = "Timer",
        current = if (selected == 0) "Off" else "${selected}s",
        choices = listOf("Off", "2s", "5s", "10s"),
        onChoice = { onSelect(it.removeSuffix("s").toIntOrNull() ?: 0) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: AppUiState, vm: AppViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Camera settings (OSC options)", style = MaterialTheme.typography.titleLarge)
            if (state.optionsBusy) CircularProgressIndicator()
            if (state.options.isEmpty() && !state.optionsBusy) {
                Text("No options loaded. Connect first; some firmwares expose very few.")
            }
            val editable = listOf("whiteBalance", "iso", "exposureProgram", "exposureProgramMode", "captureMode")
            for (name in editable) {
                val current = state.options[name] ?: continue
                val choices = state.optionsSupport[name]?.takeIf { it.isNotEmpty() }
                    ?: defaultChoices(name)
                OptionDropdown(
                    label = name,
                    current = current,
                    choices = choices,
                    onChoice = { vm.setOption(name, it) }
                )
            }
            for ((name, value) in state.options) {
                if (name !in editable) Text("$name: $value", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::loadOptions) { Text("Reload options") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun defaultChoices(name: String): List<String> = when (name) {
    "whiteBalance" -> listOf("auto", "incandescent", "fluorescent", "daylight", "cloudy-daylight", "twilight", "shade")
    "iso" -> listOf("auto", "100", "200", "400", "800", "1600", "3200")
    "exposureProgram", "exposureProgramMode" -> listOf("auto", "manual", "normal", "aperture-priority", "shutter-priority", "iso-priority")
    "captureMode" -> listOf("image", "_video", "_videoNormal", "_loopVideo")
    else -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionDropdown(label: String, current: String, choices: List<String>, onChoice: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = { expanded = false; onChoice(choice) }
                )
            }
        }
    }
}

// ------------------------------------------------------------ Timelapse tab

@Composable
fun TimelapseScreen(state: AppUiState, vm: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Timelapse / intervalometer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Repeatedly triggers the shutter over OSC. The camera stitches " +
                "nothing itself — download the frames from the Gallery tab and " +
                "stitch/assemble on a computer (see tools/stitch.py)."
        )
        var intervalText by remember(state.tlIntervalSeconds) {
            mutableStateOf(state.tlIntervalSeconds.toString())
        }
        OutlinedTextField(
            value = intervalText,
            onValueChange = { v ->
                intervalText = v
                v.toIntOrNull()?.let(vm::onTimelapseIntervalChange)
            },
            label = { Text("Interval (seconds, min 3)") },
            singleLine = true
        )
        Text("Shots taken: ${state.tlShotsTaken}")
        Text("Status: ${state.tlStatus}")
        if (state.tlRunning) {
            Button(onClick = vm::stopTimelapse) { Text("Stop timelapse") }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Button(onClick = vm::startTimelapse, enabled = state.connected) { Text("Start timelapse") }
        }
        if (!state.connected) Text("Connect to the camera first (Connect tab).")
    }
}

// -------------------------------------------------------------- Gallery tab

@Composable
fun GalleryScreen(state: AppUiState, vm: AppViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("On the camera", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = vm::refreshGallery, enabled = state.connected && !state.galleryBusy) {
                Text("Refresh")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!state.connected) Text("Connect to the camera first (Connect tab).")
        if (state.galleryBusy) CircularProgressIndicator()
        state.galleryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.gallery, key = { it.file.name }) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.file.name, style = MaterialTheme.typography.titleSmall)
                        val size = item.file.sizeBytes
                        Text(
                            buildString {
                                if (size != null) append("%.1f MB".format(size / 1e6))
                                item.file.dateTime?.let { append("  •  ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        when {
                            item.downloading -> {
                                val p = item.progress
                                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            item.saved -> Text("Saved to Pictures/Gear360Revive ✓")
                            item.error != null -> Text("Error: ${item.error}", color = MaterialTheme.colorScheme.error)
                            else -> Button(onClick = { vm.download(item) }) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}
