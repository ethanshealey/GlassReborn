package com.glass.companion.service

import android.util.Log
import com.glass.companion.GlassProtocol
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress

/**
 * WebSocket server running on Glass for media transfer.
 *
 * iPhone connects to ws://glass-ip:8765 after learning the IP via BLE.
 * Handles: gallery listing, photo download, photo upload from Glass camera.
 */
class WifiServer(
    private val photoDir: File,
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: () -> Unit
) : WebSocketServer(InetSocketAddress(GlassProtocol.WIFI_PORT)) {

    private val clients = mutableSetOf<WebSocket>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        Log.d(TAG, "WiFi client connected: ${conn.remoteSocketAddress}")
        onClientConnected(conn.remoteSocketAddress?.address?.hostAddress ?: "?")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)
        if (clients.isEmpty()) onClientDisconnected()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("t")) {
                "LIST"     -> handleList(conn, json.optInt("offset", 0), json.optInt("count", 20))
                "DOWNLOAD" -> handleDownload(conn, json.getString("name"))
                else       -> Log.w(TAG, "Unknown WiFi message: $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi message error: $e")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WiFi server error: $ex")
    }

    override fun onStart() {
        Log.d(TAG, "WiFi server started on port ${GlassProtocol.WIFI_PORT}")
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private fun handleList(conn: WebSocket, offset: Int, count: Int) {
        val files = photoDir.listFiles { f -> f.extension.lowercase() in PHOTO_EXTS }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val page  = files.drop(offset).take(count)
        val items = org.json.JSONArray()
        page.forEach { f ->
            items.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("date", f.lastModified())
            })
        }
        conn.send(JSONObject().apply {
            put("t", "LIST_RESP")
            put("total", files.size)
            put("offset", offset)
            put("items", items)
        }.toString())
    }

    private fun handleDownload(conn: WebSocket, name: String) {
        val file = File(photoDir, name)
        if (!file.exists() || !file.canonicalPath.startsWith(photoDir.canonicalPath)) {
            conn.send(JSONObject().apply {
                put("t", "ERROR"); put("msg", "not found")
            }.toString()); return
        }
        // Send header then raw bytes
        conn.send(JSONObject().apply {
            put("t", "FILE_START")
            put("name", name)
            put("size", file.length())
        }.toString())
        conn.send(file.readBytes())
    }

    /** Broadcast new-photo event to all connected iPhone clients. */
    fun notifyNewPhoto(name: String) {
        val msg = JSONObject().apply {
            put("t", "NEW_PHOTO"); put("name", name)
        }.toString()
        clients.forEach { it.send(msg) }
    }

    companion object {
        private const val TAG = "WifiServer"
        private val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "mp4")
    }
}
