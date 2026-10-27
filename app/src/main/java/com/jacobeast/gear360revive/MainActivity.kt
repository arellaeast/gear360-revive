package com.jacobeast.gear360revive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jacobeast.gear360revive.ui.AppViewModel
import com.jacobeast.gear360revive.ui.ConnectScreen
import com.jacobeast.gear360revive.ui.GalleryScreen
import com.jacobeast.gear360revive.ui.RemoteScreen
import com.jacobeast.gear360revive.ui.TimelapseScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Gear360App()
            }
        }
    }
}

@Composable
fun Gear360App(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Connect" to Icons.Filled.Wifi,
        "Remote" to Icons.Filled.CameraAlt,
        "Timelapse" to Icons.Filled.Timer,
        "Gallery" to Icons.Filled.PhotoLibrary
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> ConnectScreen(state, vm)
                1 -> RemoteScreen(state, vm)
                2 -> TimelapseScreen(state, vm)
                else -> GalleryScreen(state, vm)
            }
        }
    }
}
