package com.jacobeast.gear360revive.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jacobeast.gear360revive.osc.NetworkBinding
import com.jacobeast.gear360revive.osc.OscClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class GalleryItem(
    val file: OscClient.CameraFile,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val saved: Boolean = false,
    val error: String? = null
)

data class AppUiState(
    val host: String = "192.168.107.1",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val cameraInfo: OscClient.CameraInfo? = null,
    val cameraState: OscClient.CameraState? = null,
    val statusMessage: String = "Not connected yet.",
    val bindMessage: String? = null,
    val shooting: Boolean = false,
    val selfTimerSeconds: Int = 0,
    val countdown: Int? = null,
    val lastShotUri: String? = null,
    val lastShotBytes: ByteArray? = null,
    val options: Map<String, String> = emptyMap(),
    val optionsSupport: Map<String, List<String>> = emptyMap(),
    val optionsBusy: Boolean = false,
    val gallery: List<GalleryItem> = emptyList(),
    val galleryBusy: Boolean = false,
    val galleryError: String? = null,
    // Timelapse
    val tlIntervalSeconds: Int = 10,
    val tlRunning: Boolean = false,
    val tlShotsTaken: Int = 0,
    val tlStatus: String = "Idle"
) {
    val baseUrl: String get() = "http://${host.trim().trimEnd('/')}"
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val client = OscClient(_state.value.baseUrl)
    private var timelapseJob: Job? = null

    fun onHostChange(host: String) {
        _state.update { it.copy(host = host) }
        client.baseUrl = _state.value.baseUrl
    }

    fun bindToWifi() {
        val msg = NetworkBinding.bindToWifi(getApplication())
        _state.update { it.copy(bindMessage = msg) }
    }

    fun connect() {
        if (_state.value.connecting) return
        client.baseUrl = _state.value.baseUrl
        _state.update { it.copy(connecting = true, statusMessage = "Contacting camera…") }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { client.info() }
                val camState = withContext(Dispatchers.IO) {
                    runCatching { client.state() }.getOrNull()
                }
                _state.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        cameraInfo = info,
                        cameraState = camState ?: it.cameraState,
                        statusMessage = "Connected to ${info.model.ifBlank { "Gear 360" }}."
                    )
                }
                loadOptions()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        statusMessage = "Connection failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.state() } }
                .onSuccess { cs -> _state.update { it.copy(cameraState = cs) } }
                .onFailure { e ->
                    _state.update { it.copy(statusMessage = "State refresh failed: ${e.message}") }
                }
        }
    }

    // ------------------------------------------------------------- Shooting

    fun shoot() {
        val s = _state.value
        if (s.shooting || !s.connected) return
        _state.update { it.copy(shooting = true) }
        viewModelScope.launch {
            try {
                if (s.selfTimerSeconds > 0) {
                    for (t in s.selfTimerSeconds downTo 1) {
                        _state.update { it.copy(countdown = t) }
                        delay(1000)
                    }
                    _state.update { it.copy(countdown = null) }
                }
                val uri = withContext(Dispatchers.IO) { client.takePicture() }
                _state.update { it.copy(lastShotUri = uri, statusMessage = "Shot saved on camera: ${uri ?: "(unknown name)"}") }
                if (uri != null) loadPreview(uri)
                refreshState()
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Shutter failed: ${e.message}") }
            } finally {
                _state.update { it.copy(shooting = false, countdown = null) }
            }
        }
    }

    private suspend fun loadPreview(uri: String) {
        runCatching { withContext(Dispatchers.IO) { client.fetchBytes(uri) } }
            .onSuccess { bytes -> _state.update { it.copy(lastShotBytes = bytes) } }
    }

    // -------------------------------------------------------------- Options

    private val optionNames = listOf(
        "captureMode", "fileFormat", "exposureProgram", "exposureProgramMode",
        "iso", "shutterSpeed", "apexSsv", "whiteBalance", "gpsInfo"
    )

    fun loadOptions() {
        _state.update { it.copy(optionsBusy = true) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.getOptions(optionNames) } }
                .onSuccess { json ->
                    val values = mutableMapOf<String, String>()
                    val support = mutableMapOf<String, List<String>>()
                    for (name in optionNames) {
                        if (json.has(name)) values[name] = json.opt(name)?.toString() ?: ""
                        val supKey = name + "Support"
                        json.optJSONArray(supKey)?.let { arr ->
                            support[name] = (0 until arr.length()).map { arr.optString(it) }
                        }
                    }
                    _state.update { it.copy(options = values, optionsSupport = support, optionsBusy = false) }
                }
                .onFailure {
                    _state.update { it.copy(optionsBusy = false) }
                }
        }
    }

    fun setOption(name: String, value: String) {
        _state.update { it.copy(optionsBusy = true) }
        viewModelScope.launch {
            val parsed: Any = value.toIntOrNull() ?: value.toDoubleOrNull() ?: value
            val json = JSONObject().put(name, parsed)
            runCatching { withContext(Dispatchers.IO) { client.setOptions(json) } }
                .onSuccess {
                    _state.update {
                        it.copy(options = it.options + (name to value), optionsBusy = false,
                            statusMessage = "Set $name = $value")
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(optionsBusy = false, statusMessage = "Set $name failed: ${e.message}") }
                }
        }
    }

    fun onSelfTimerChange(seconds: Int) = _state.update { it.copy(selfTimerSeconds = seconds) }

    // -------------------------------------------------------------- Gallery

    fun refreshGallery() {
        _state.update { it.copy(galleryBusy = true, galleryError = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.listFiles() } }
                .onSuccess { files ->
                    _state.update {
                        it.copy(
                            galleryBusy = false,
                            gallery = files.sortedByDescending { f -> f.name }.map { f -> GalleryItem(f) },
                            galleryError = if (files.isEmpty()) "Camera reported no files." else null
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(galleryBusy = false, galleryError = "Listing failed: ${e.message}") }
                }
        }
    }

    fun download(item: GalleryItem) {
        _state.update { st ->
            st.copy(gallery = st.gallery.map {
                if (it.file.name == item.file.name) it.copy(downloading = true, progress = 0f, error = null) else it
            })
        }
        viewModelScope.launch {
            try {
                val cacheFile = File(getApplication<Application>().cacheDir, item.file.name)
                withContext(Dispatchers.IO) {
                    client.downloadFile(item.file.fileUrl, cacheFile) { p ->
                        _state.update { st ->
                            st.copy(gallery = st.gallery.map {
                                if (it.file.name == item.file.name) it.copy(progress = p) else it
                            })
                        }
                    }
                }
                withContext(Dispatchers.IO) { saveToPictures(cacheFile, item.file.name) }
                cacheFile.delete()
                _state.update { st ->
                    st.copy(gallery = st.gallery.map {
                        if (it.file.name == item.file.name) it.copy(downloading = false, progress = null, saved = true) else it
                    }, statusMessage = "Saved ${item.file.name} to Pictures/Gear360Revive")
                }
            } catch (e: Exception) {
                _state.update { st ->
                    st.copy(gallery = st.gallery.map {
                        if (it.file.name == item.file.name) it.copy(downloading = false, progress = null, error = e.message) else it
                    })
                }
            }
        }
    }

    /** Copy a downloaded file into the public Pictures/Gear360Revive folder. */
    private fun saveToPictures(src: File, name: String) {
        val ctx = getApplication<Application>()
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Gear360Revive"
                )
            }
            val collection = if (mime.startsWith("video"))
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = ctx.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open MediaStore output")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Gear360Revive"
            )
            dir.mkdirs()
            src.copyTo(File(dir, name), overwrite = true)
        }
    }

    // ------------------------------------------------------------ Timelapse

    fun onTimelapseIntervalChange(seconds: Int) {
        _state.update { it.copy(tlIntervalSeconds = seconds.coerceAtLeast(3)) }
    }

    fun startTimelapse() {
        val s = _state.value
        if (s.tlRunning || !s.connected) return
        _state.update { it.copy(tlRunning = true, tlShotsTaken = 0, tlStatus = "Running…") }
        timelapseJob = viewModelScope.launch {
            var shots = 0
            try {
                while (true) {
                    val uri = withContext(Dispatchers.IO) { client.takePicture() }
                    shots++
                    _state.update {
                        it.copy(tlShotsTaken = shots, tlStatus = "Shot $shots: ${uri ?: ""}", lastShotUri = uri)
                    }
                    if (uri != null) loadPreview(uri)
                    delay(_state.value.tlIntervalSeconds * 1000L)
                }
            } catch (e: Exception) {
                _state.update { it.copy(tlStatus = "Stopped: ${e.message}") }
            } finally {
                _state.update { it.copy(tlRunning = false) }
            }
        }
    }

    fun stopTimelapse() {
        timelapseJob?.cancel()
        timelapseJob = null
        _state.update { it.copy(tlRunning = false, tlStatus = "Stopped by user after ${it.tlShotsTaken} shots.") }
    }

    override fun onCleared() {
        timelapseJob?.cancel()
        super.onCleared()
    }
}
