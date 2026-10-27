package com.jacobeast.gear360revive.osc

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the Open Spherical Camera (OSC) API, as spoken by the
 * Samsung Gear 360 (2016, SM-C200) in "Google Street View" mode.
 *
 * The camera creates its own Wi-Fi hotspot with no internet behind it; this
 * client talks plain HTTP to the hotspot gateway (192.168.107.1 on the units
 * seen in the wild, but configurable).
 *
 * OSC API level 1 notes that matter here:
 *  - Most commands need a session: camera.startSession returns a sessionId
 *    that must be passed back inside the parameters of later commands.
 *  - Commands return an id; long-running ones (takePicture) are polled via
 *    /osc/commands/status until state == "done".
 *  - Samsung's implementation is only *semi* spec-compliant, so every entry
 *    point tolerates missing fields and this client falls back across the
 *    known listFiles/listImages variants instead of crashing.
 */
class OscClient(@Volatile var baseUrl: String) {

    data class CameraInfo(
        val manufacturer: String,
        val model: String,
        val serialNumber: String,
        val firmwareVersion: String,
        val apiEndpoints: List<String>
    )

    data class CameraState(
        val batteryLevel: Double?,
        val captureStatus: String?,
        val remainingSpaceBytes: Long?,
        val raw: JSONObject
    )

    data class CameraFile(
        val name: String,
        val fileUrl: String,
        val sizeBytes: Long?,
        val dateTime: String?
    )

    class OscException(message: String) : Exception(message)

    private var sessionId: String? = null

    // ------------------------------------------------------------------ HTTP

    private fun openConnection(url: URL, method: String): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        return conn
    }

    private suspend fun getJson(path: String): JSONObject {
        val conn = openConnection(URL(baseUrl + path), "GET")
        return readJson(conn)
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject {
        val conn = openConnection(URL(baseUrl + path), "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw OscException("HTTP $code from camera: ${text.take(300)}")
        }
        if (text.isBlank()) throw OscException("Empty response from camera")
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw OscException("Camera returned non-JSON: ${text.take(300)}")
        }
    }

    // -------------------------------------------------------------- Commands

    private suspend fun execute(name: String, params: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("name", name).put("parameters", params)
        val resp = postJson("/osc/commands/execute", body)
        resp.optJSONObject("error")?.let {
            throw OscException("$name failed: ${it.optString("code")} ${it.optString("message")}")
        }
        return resp
    }

    private suspend fun executeWithSession(name: String, params: JSONObject = JSONObject()): JSONObject {
        ensureSession()
        val sid = sessionId
        if (sid != null && !params.has("sessionId")) params.put("sessionId", sid)
        return try {
            execute(name, params)
        } catch (e: OscException) {
            // Session may have expired (camera closes idle sessions). Retry once fresh.
            if (e.message?.contains("invalidSession", ignoreCase = true) == true ||
                e.message?.contains("session", ignoreCase = true) == true
            ) {
                sessionId = null
                ensureSession()
                sessionId?.let { params.put("sessionId", it) }
                execute(name, params)
            } else throw e
        }
    }

    suspend fun ensureSession() {
        if (sessionId != null) return
        val resp = execute("camera.startSession")
        sessionId = resp.optJSONObject("results")?.optString("sessionId")?.takeIf { it.isNotBlank() }
        // Some firmwares accept commands with no session at all; null is fine.
    }

    suspend fun closeSession() {
        val sid = sessionId ?: return
        runCatching { execute("camera.closeSession", JSONObject().put("sessionId", sid)) }
        sessionId = null
    }

    private suspend fun commandStatus(id: String): JSONObject =
        postJson("/osc/commands/status", JSONObject().put("id", id))

    /** Poll a command id until done; returns the final response JSON. */
    private suspend fun awaitCommand(firstResponse: JSONObject, timeoutMs: Long = 60_000): JSONObject {
        var resp = firstResponse
        val id = resp.optString("id")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (resp.optString("state") == "inProgress" && id.isNotBlank()) {
            if (System.currentTimeMillis() > deadline) {
                throw OscException("Camera command timed out (id=$id)")
            }
            delay(500)
            resp = commandStatus(id)
            resp.optJSONObject("error")?.let {
                throw OscException("Command $id failed: ${it.optString("code")} ${it.optString("message")}")
            }
        }
        if (resp.optString("state") == "error") {
            val err = resp.optJSONObject("error")
            throw OscException("Command failed: ${err?.optString("code")} ${err?.optString("message")}")
        }
        return resp
    }

    // ------------------------------------------------------------------ API

    suspend fun info(): CameraInfo {
        val j = getJson("/osc/info")
        val endpoints = mutableListOf<String>()
        j.optJSONArray("api")?.let { arr ->
            for (i in 0 until arr.length()) endpoints += arr.optString(i)
        }
        return CameraInfo(
            manufacturer = j.optString("manufacturer"),
            model = j.optString("model"),
            serialNumber = j.optString("serialNumber"),
            firmwareVersion = j.optString("firmwareVersion"),
            apiEndpoints = endpoints
        )
    }

    suspend fun state(): CameraState {
        val j = postJson("/osc/state", JSONObject())
        val s = j.optJSONObject("state") ?: JSONObject()
        val battery = when {
            s.has("batteryLevel") -> s.optDouble("batteryLevel")
            s.has("_batteryLevel") -> s.optDouble("_batteryLevel")
            else -> null
        }
        val remaining = when {
            s.has("_remainingSpace") -> s.optLong("_remainingSpace")
            s.has("remainingSpace") -> s.optLong("remainingSpace")
            else -> null
        }
        return CameraState(
            batteryLevel = battery,
            captureStatus = s.optString("_captureStatus").ifBlank { s.optString("captureStatus").ifBlank { null } },
            remainingSpaceBytes = remaining,
            raw = s
        )
    }

    /** Take a photo; returns the fileUri of the finished image on the camera. */
    suspend fun takePicture(): String? {
        val resp = executeWithSession("camera.takePicture")
        val done = awaitCommand(resp)
        return done.optJSONObject("results")?.optString("fileUri")?.ifBlank { null }
    }

    suspend fun getOptions(names: List<String>): JSONObject {
        val params = JSONObject().put("optionNames", JSONArray(names))
        val resp = executeWithSession("camera.getOptions", params)
        return resp.optJSONObject("results")?.optJSONObject("options") ?: JSONObject()
    }

    suspend fun setOptions(options: JSONObject) {
        val params = JSONObject().put("options", options)
        val resp = executeWithSession("camera.setOptions", params)
        awaitCommand(resp, timeoutMs = 15_000)
    }

    /**
     * List files on the camera. Samsung's OSC v1 firmware predates the final
     * listFiles shape, so try the known variants in order and return whatever
     * the camera understands. Throws with the last error if none work.
     */
    suspend fun listFiles(): List<CameraFile> {
        var lastError: Exception? = null

        // Variant A: OSC API v2 style camera.listFiles
        try {
            val params = JSONObject()
                .put("fileType", "all")
                .put("entryCount", 200)
                .put("maxThumbSize", 0)
            val resp = executeWithSession("camera.listFiles", params)
            parseFileEntries(resp)?.let { return it }
        } catch (e: Exception) { lastError = e }

        // Variant B: older OSC v1 camera.listImages
        try {
            val params = JSONObject()
                .put("entryCount", 200)
                .put("includeThumb", false)
            val resp = executeWithSession("camera.listImages", params)
            parseFileEntries(resp)?.let { return it }
        } catch (e: Exception) { lastError = e }

        // Variant C: camera._listFiles (vendor-prefixed on some firmwares)
        try {
            val params = JSONObject()
                .put("fileType", "all")
                .put("entryCount", 200)
            val resp = executeWithSession("camera._listFiles", params)
            parseFileEntries(resp)?.let { return it }
        } catch (e: Exception) { lastError = e }

        throw OscException("Could not list files on camera. Last error: ${lastError?.message}")
    }

    private fun parseFileEntries(resp: JSONObject): List<CameraFile>? {
        val results = resp.optJSONObject("results") ?: return null
        val entries = results.optJSONArray("entries")
            ?: results.optJSONArray("fileList")
            ?: return null
        val out = mutableListOf<CameraFile>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val url = e.optString("fileUrl").ifBlank { e.optString("fileUri") }
            val name = e.optString("name").ifBlank { url.substringAfterLast('/') }
            if (name.isBlank()) continue
            out += CameraFile(
                name = name,
                fileUrl = url,
                sizeBytes = if (e.has("size")) e.optLong("size") else null,
                dateTime = e.optString("dateTime").ifBlank { null }
            )
        }
        return out
    }

    /** Resolve a file reference from the camera into an absolute URL. */
    fun resolveFileUrl(ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("/") -> baseUrl + ref
        else -> "$baseUrl/$ref"
    }

    /** Download a camera file to [dest], reporting 0..1 progress when the size is known. */
    suspend fun downloadFile(ref: String, dest: File, onProgress: (Float?) -> Unit) {
        val conn = openConnection(URL(resolveFileUrl(ref)), "GET")
        val code = conn.responseCode
        if (code !in 200..299) throw OscException("Download failed: HTTP $code")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    onProgress(if (total > 0) read.toFloat() / total else null)
                }
            }
        }
    }

    /** Fetch raw bytes (used for the last-shot preview still). */
    suspend fun fetchBytes(ref: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray {
        val conn = openConnection(URL(resolveFileUrl(ref)), "GET")
        if (conn.responseCode !in 200..299) throw OscException("Fetch failed: HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes().take(maxBytes).toByteArray() }
    }
}
