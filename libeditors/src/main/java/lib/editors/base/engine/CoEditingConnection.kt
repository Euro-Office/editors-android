package lib.editors.base.engine

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The network side of co-editing through a Document Server: the socket.io connection sdkjs in the
 * engine talks through (EditorDocument.coTakeOutbox, coReceive), and downloads of the files the
 * server converted, which sdkjs would load over the web. The server's events come to [onEvent]
 * in their order, on the connection's thread; [Event.file] was downloaded for the event first.
 */
class CoEditingConnection(
    /** The configuration's documentServerUrl. */
    serverUrl: String,
    private val documentKey: String,
    private val downloadDir: File,
    private val onEvent: (Event) -> Unit
) {

    /** A socket event with its arguments (JSON array), and a file to pass to the engine before it. */
    class Event(val name: String, val args: String, val file: Pair<String, String>? = null)

    private val server = URI(serverUrl)
    private var socket: Socket? = null
    private var downloads = 0

    /** Carries out what sdkjs sent (EditorDocument.coTakeOutbox). */
    fun handle(outbox: String) {
        val messages = JSONArray(outbox)
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            when (message.optString("type")) {
                "connect" -> if (socket == null) connect(message.optJSONObject("options") ?: JSONObject())
                "emit" -> socket?.emit(message.optString("name"), message.opt("data"))
                "disconnect" -> disconnect()
            }
        }
    }

    // sdkjs' socket.io options: the session tokens to authenticate, the query
    private fun connect(options: JSONObject) {
        val auth = options.optJSONObject("auth")
        val query = options.optJSONObject("query")
        val socketOptions = IO.Options.builder()
            .setPath(server.path.trimEnd('/') + "/doc/$documentKey/c")
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(false)
            .setAuth(listOf("token", "session").mapNotNull { key -> auth?.optString(key)?.takeIf { it.isNotEmpty() }?.let { key to it } }.toMap())
            .setQuery(query?.keys()?.asSequence()?.joinToString("&") { "$it=${java.net.URLEncoder.encode(query.optString(it), "UTF-8")}" })
            .build()
        val created = IO.socket(URI(server.scheme, server.authority, null, null, null), socketOptions)
        created.on(Socket.EVENT_CONNECT) { onEvent(Event("connect", "[]")) }
        created.on(Socket.EVENT_DISCONNECT) { args -> onEvent(Event("disconnect", JSONArray().put(args.getOrNull(0)?.toString() ?: "").toString())) }
        created.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.getOrNull(0)
            Log.w(TAG, "connect error: $error")
            onEvent(Event("connect_error", JSONArray().put(JSONObject().put("message", error?.toString() ?: "")).toString()))
        }
        created.on("message") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            onEvent(Event("message", JSONArray().put(data).toString(), download(data)))
        }
        socket = created
        created.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    // The document as the server converted it (documentOpen), before the message that opens it
    private fun download(data: JSONObject): Pair<String, String>? {
        if (data.optString("type") != "documentOpen") return null
        val url = data.optJSONObject("data")?.optJSONObject("data")?.optString("Editor.bin")?.takeIf { it.isNotEmpty() } ?: return null
        val file = File(downloadDir, "Editor-${++downloads}.bin")
        return runCatching {
            downloadDir.mkdirs()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            url to file.absolutePath
        }.onFailure { Log.e(TAG, "can't download the document", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "CoEditing"
        const val TIMEOUT_MS = 30_000
    }
}
