package lib.editors.base.engine

import android.util.Log
import io.socket.client.IO
import io.socket.client.Manager
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
 *
 * The connection reconnects as the web editors' does, with sdkjs' settings; sdkjs then
 * authenticates again and catches up.
 */
class CoEditingConnection(
    /** The configuration's documentServerUrl. */
    serverUrl: String,
    private val documentKey: String,
    private val downloadDir: File,
    /** The engine's images directory (EditorDocument.imagesDirectory), for the document's pictures. */
    private val imagesDir: File,
    private val onEvent: (Event) -> Unit
) {

    /**
     * A socket event with its arguments (JSON array), and a file to pass to the engine before it;
     * or a picture downloaded into the images directory ([image], for EditorDocument.coImageLoaded).
     */
    class Event(val name: String, val args: String, val file: Pair<String, String>? = null, val image: String? = null)

    private val server = URI(serverUrl.trimEnd('/') + "/")
    private var socket: Socket? = null
    private var downloads = 0
    // sdkjs' tokens; the socket reads them again when it reconnects
    private val auth = mutableMapOf<String, String>()
    private val reconnection = mutableMapOf("attempts" to 15L, "delay" to 500L, "delayMax" to 10_000L)
    // Pictures are downloaded one after the other, apart from the socket's events
    private val pictures = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Carries out what sdkjs sent (EditorDocument.coTakeOutbox). */
    fun handle(outbox: String) {
        val messages = JSONArray(outbox)
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            when (message.optString("type")) {
                "connect" -> if (socket == null) connect(message.optJSONObject("options") ?: JSONObject())
                "emit" -> socket?.emit(message.optString("name"), message.opt("data"))
                "disconnect" -> disconnect()
                "auth" -> setAuth(message.optJSONObject("auth"))
                "reconnection" -> setReconnection(message.optString("name"), message.optLong("value"))
                "fetch" -> fetch(message.optString("url"), message.optString("name"))
            }
        }
    }

    // sdkjs' socket.io options: the session tokens to authenticate, the query
    private fun connect(options: JSONObject) {
        setAuth(options.optJSONObject("auth"))
        val query = options.optJSONObject("query")
        val socketOptions = IO.Options.builder()
            .setPath(server.path.trimEnd('/') + "/doc/$documentKey/c")
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionAttempts(reconnection.getValue("attempts").toInt())
            .setReconnectionDelay(reconnection.getValue("delay"))
            .setReconnectionDelayMax(reconnection.getValue("delayMax"))
            .setRandomizationFactor(0.5)
            .setAuth(auth)
            .setQuery(query?.keys()?.asSequence()?.joinToString("&") { "$it=${java.net.URLEncoder.encode(query.optString(it), "UTF-8")}" })
            .build()
        val created = IO.socket(URI(server.scheme, server.authority, null, null, null), socketOptions)
        created.on(Socket.EVENT_CONNECT) { onEvent(Event("connect", "[]")) }
        created.on(Socket.EVENT_DISCONNECT) { args -> onEvent(Event("disconnect", JSONArray().put(args.getOrNull(0)?.toString() ?: "").toString())) }
        created.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.getOrNull(0)
            Log.w(TAG, "connect error: $error")
            // The server's refusals come with data (sdkjs ends the session); others are retried
            val data = (error as? JSONObject)?.optJSONObject("data")
            val json = JSONObject().put("message", (error as? JSONObject)?.optString("message") ?: error?.toString() ?: "")
            if (data != null) json.put("data", data)
            onEvent(Event("connect_error", JSONArray().put(json).toString()))
        }
        created.io().on(Manager.EVENT_RECONNECT_FAILED) { onEvent(Event("reconnect_failed", "[]")) }
        created.on("message") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            onEvent(Event("message", JSONArray().put(data).toString(), download(data)))
        }
        socket = created
        created.connect()
    }

    private fun setAuth(json: JSONObject?) {
        for (key in listOf("token", "session")) {
            val value = json?.optString(key)?.takeIf { it.isNotEmpty() && it != "null" }
            if (value != null) auth[key] = value else auth.remove(key)
        }
    }

    private fun setReconnection(name: String, value: Long) {
        if (name !in reconnection) return
        reconnection[name] = value
        val manager = socket?.io() ?: return
        when (name) {
            "attempts" -> manager.reconnectionAttempts(value.toInt())
            "delay" -> manager.reconnectionDelay(value)
            "delayMax" -> manager.reconnectionDelayMax(value)
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    /** Ends the connection for good (the editor closes). */
    fun close() {
        disconnect()
        pictures.shutdownNow()
    }

    // The document as the server converted it (documentOpen), before the message that opens it
    private fun download(data: JSONObject): Pair<String, String>? {
        if (data.optString("type") != "documentOpen") return null
        val url = data.optJSONObject("data")?.optJSONObject("data")?.optString("Editor.bin")?.takeIf { it.isNotEmpty() } ?: return null
        val file = File(downloadDir, "Editor-${++downloads}.bin")
        return runCatching {
            downloadDir.mkdirs()
            download(url, file)
            url to file.absolutePath
        }.onFailure { Log.e(TAG, "can't download the document", it) }.getOrNull()
    }

    // A picture of the document; one the engine has (from the copy it opened, or uploaded) stays
    private fun fetch(url: String, name: String) {
        if (url.isEmpty() || name.isEmpty() || name.contains('/') || name.contains("..")) return
        val file = File(imagesDir, name)
        if (file.exists()) return
        runCatching {
            pictures.execute {
                runCatching {
                    val partial = File(imagesDir, "$name.partial")
                    download(url, partial)
                    check(partial.renameTo(file))
                    onEvent(Event("image", "[]", image = name))
                }.onFailure { Log.w(TAG, "can't download the picture $name", it) }
            }
        }
    }

    /**
     * Uploads a picture to insert (on an IO thread): the server stores it with the document and
     * answers with its name there and its URL ({"media/name.png": url}, for
     * EditorDocument.coAddUrls), null if that failed. [documentId] and [token] from
     * EditorDocument.coUploadInfo.
     */
    fun upload(file: File, contentType: String, documentId: String, token: String): JSONObject? = runCatching {
        val connection = URL(server.resolve("upload/" + java.net.URLEncoder.encode(documentId, "UTF-8")).toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.e(TAG, "can't upload the picture", it) }.getOrNull()

    private fun download(url: String, file: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "CoEditing"
        const val TIMEOUT_MS = 30_000
    }
}
