package com.playtranslate

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Local WebSocket server bound to IPv4 loopback on port 6677 that broadcasts
 * OCR text to every connected client. The bundled texthooker page (served by
 * [TexthookerHttpServer] also on IPv4 loopback) connects here and renders
 * incoming frames as a stream.
 *
 * IPv4-explicit (not [java.net.InetAddress.getLoopbackAddress]) so this
 * matches the browser's `127.0.0.1` resolution path. On some Android JVMs
 * `getLoopbackAddress()` returns the IPv6 `::1`, leaving an IPv4 dial silently
 * unanswered.
 *
 * Loopback-only, so this is never reachable from the LAN.
 */
class WebsocketManager : WebSocketServer(InetSocketAddress("127.0.0.1", PORT)) {

    private val clients = CopyOnWriteArraySet<WebSocket>()

    @Volatile private var started = false

    init {
        isReuseAddr = true
        // Disable the periodic WS keepalive ping. Java-WebSocket defaults to
        // 60 s; for a loopback listener the connection is either alive or
        // closed by the OS — there is no flaky network in between, and a
        // wakeup every minute is pure battery cost when the server is idle.
        connectionLostTimeout = 0
    }

    override fun onStart() {
        Log.i(TAG, "Server listening on ${address.address.hostAddress}:$PORT")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients += conn
        Log.i(TAG, "Client connected: ${conn.remoteSocketAddress} (clients=${clients.size})")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients -= conn
        Log.i(TAG, "Client disconnected: ${conn.remoteSocketAddress} code=$code reason=$reason (clients=${clients.size})")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "ON_MESSAGE (ignored): $message")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Server error on conn=${conn?.remoteSocketAddress}", ex)
        // A null conn means a fatal during bind/accept — the library will not
        // accept further connections. Reset our flag so the next connect()
        // attempt can build a fresh instance via reconnect().
        if (conn == null) started = false
    }

    fun connect() {
        if (started) {
            Log.d(TAG, "Connect skipped: already started")
            return
        }
        Log.i(TAG, "Starting server on loopback:$PORT")
        try {
            start()
            started = true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "start() refused (server is one-shot — recreate needed)", e)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    fun disconnect() {
        if (!started) return
        Log.i(TAG, "Stopping server")
        started = false
        clients.clear()
        runCatching { stop(0) }.onFailure { Log.w(TAG, "stop() failed", it) }
    }

    fun send(text: String) {
        val snapshot = clients.toList()
        if (snapshot.isEmpty()) return
        Log.i(TAG, "Broadcasting to ${snapshot.size} client(s): \"$text\"")
        for (client in snapshot) {
            runCatching { client.send(text) }.onFailure {
                Log.w(TAG, "send to ${client.remoteSocketAddress} failed", it)
            }
        }
    }

    /** One WS frame per OCR section. Renji's `socket.ts` treats each frame as
     *  a new line entry, so per-group framing is what surfaces the section
     *  breaks in the texthooker UI (a single frame containing `\n\n` lands
     *  inside one entry whose CSS collapses whitespace). Falls back to
     *  [OcrManager.OcrResult.fullText] when groupTexts is empty. */
    fun sendOcr(result: OcrManager.OcrResult) {
        val groups = result.groupTexts.filter { it.isNotBlank() }
        if (groups.isEmpty()) {
            send(result.fullText)
            return
        }
        for (g in groups) send(g)
    }

    companion object {
        private const val TAG = "WebsocketManager"
        private const val PORT = 28211
        val instance by lazy { WebsocketManager() }
    }
}
