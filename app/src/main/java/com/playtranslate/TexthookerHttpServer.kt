package com.playtranslate

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * Local HTTP server bound to 127.0.0.1:6678 that serves the bundled Renji-XD
 * texthooker UI (vendored under assets/texthooker/) so the page's WebSocket
 * dial to ws://127.0.0.1:6677 originates from an HTTP page, sidestepping the
 * mixed-content block browsers apply to ws:// dials from https:// origins.
 */
class TexthookerHttpServer private constructor(
    private val appContext: Context,
) : NanoHTTPD("127.0.0.1", PORT) {

    override fun serve(session: IHTTPSession): Response {
        val raw = session.uri.trimStart('/').ifEmpty { "index.html" }
        if (raw.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "")
        }
        return try {
            val mime = guessMime(raw)
            val stream = appContext.assets.open("texthooker/$raw")
            newChunkedResponse(Response.Status.OK, mime, stream)
        } catch (e: IOException) {
            Log.w(TAG, "asset miss: $raw")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }
    }

    fun startIfNeeded() {
        if (isAlive) return
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HTTP server listening on http://127.0.0.1:$PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start HTTP server on port $PORT", e)
        }
    }

    fun stopIfRunning() {
        if (!isAlive) return
        stop()
        Log.i(TAG, "HTTP server stopped")
    }

    companion object {
        const val PORT = 6678
        private const val TAG = "TexthookerHttp"

        @Volatile private var instance: TexthookerHttpServer? = null

        fun get(ctx: Context): TexthookerHttpServer =
            instance ?: synchronized(this) {
                instance ?: TexthookerHttpServer(ctx.applicationContext).also { instance = it }
            }

        private fun guessMime(name: String): String = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".css")  -> "text/css; charset=utf-8"
            name.endsWith(".js")   -> "application/javascript; charset=utf-8"
            name.endsWith(".svg")  -> "image/svg+xml"
            name.endsWith(".ico")  -> "image/x-icon"
            name.endsWith(".ttf")  -> "font/ttf"
            name.endsWith(".woff") -> "font/woff"
            name.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
