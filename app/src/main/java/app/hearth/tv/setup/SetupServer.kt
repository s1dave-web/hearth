package app.hearth.tv.setup

import android.content.Context
import android.util.Log
import app.hearth.tv.hearth
import app.hearth.tv.ha.Entity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A tiny web page on the TV so the Home Assistant address, token and tiles can be set from a phone
 * instead of with the remote. It runs whenever Hearth runs, so a bookmarked page keeps working.
 * Every request must carry the 6-digit code shown in Hearth's settings; it never changes unless
 * reset, and the page never sends the token back out.
 */
class SetupServer(private val context: Context) {
    val code: String get() = context.hearth.prefs.setupCode
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val _events = MutableStateFlow("")
    /** Last thing that happened, for the TV screen ("Connected", "Saved 9 entities"...). */
    val events: StateFlow<String> = _events

    val address: String? get() = lanAddress()?.let { "http://$it:$PORT/?k=$code" }

    fun start() {
        scope.launch {
            val ss = runCatching { ServerSocket(PORT) }.getOrElse {
                Log.w(TAG, "could not listen", it); _events.value = "Could not start the setup page"; return@launch
            }
            server = ss
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                scope.launch { s.use { handle(it) } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        scope.cancel()
    }

    // ------------------------------------------------------------------ HTTP
    private fun handle(s: Socket) {
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, target) = lines[0].split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len in 1..1_000_000) String(ByteArray(len).also { java.io.DataInputStream(input).readFully(it) }) else ""
        val out = s.getOutputStream()

        if (method == "GET" && path == "/") {
            val html = context.assets.open("setup.html").bufferedReader().readText()
            return send(out, 200, "text/html; charset=utf-8", html)
        }
        if (query["k"] != code && headers["x-code"] != code) return send(out, 403, "application/json", """{"error":"Wrong code - use the address shown on the TV"}""")

        val app = context.hearth
        val reply: JSONObject = try {
            when ("$method $path") {
                "GET /api/state" -> JSONObject()
                    .put("url", app.prefs.haUrl)
                    .put("connected", app.prefs.configured)
                    .put("entities", JSONArray(app.prefs.entities))
                    .put("weather", app.prefs.weather)
                "POST /api/connect" -> {
                    val j = JSONObject(body)
                    val url = j.getString("url").trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
                    // The saved token is only reused for the same server, so a changed address can't be used to fish it out.
                    val token = j.optString("token").trim().ifEmpty {
                        if (url == app.prefs.haUrl) app.prefs.token else error("Paste a token to connect to a new address")
                    }
                    val states = runBlocking { app.ha.fetchStates(url, token) }
                    app.prefs.haUrl = url
                    app.prefs.token = token
                    _events.value = "Connected to Home Assistant"
                    app.ha.restart()
                    listing(states)
                }
                "GET /api/export" -> exportConfig()
                "POST /api/import" -> {
                    importConfig(JSONObject(body))
                    _events.value = "Settings restored"
                    JSONObject().put("ok", true)
                }
                "GET /api/entities" -> listing(runBlocking { app.ha.fetchStates(app.prefs.haUrl, app.prefs.token) })
                "POST /api/entities" -> {
                    val j = JSONObject(body)
                    val ids = j.getJSONArray("entities").let { a -> List(a.length()) { a.getString(it) } }
                    app.prefs.entities = ids
                    if (j.has("weather")) app.prefs.weather = j.getString("weather")
                    j.optJSONObject("names")?.let { n ->
                        app.prefs.names = n.keys().asSequence().associateWith { n.getString(it).trim() }.filterValues { it.isNotEmpty() }
                    }
                    app.ha.restart()
                    _events.value = "Saved ${ids.size} tiles"
                    JSONObject().put("ok", true)
                }
                else -> return send(out, 404, "application/json", """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            return send(out, 400, "application/json", JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString())
        }
        send(out, 200, "application/json", reply.toString())
    }

    /**
     * Everything that makes Hearth yours, except the Home Assistant token (so a saved file is safe to keep
     * anywhere). The address is included; on import the saved token is kept if the address matches.
     */
    private fun exportConfig(): JSONObject {
        val p = context.hearth.prefs
        return JSONObject()
            .put("hearth", 1)
            .put("haUrl", p.haUrl)
            .put("entities", JSONArray(p.entities))
            .put("names", JSONObject(p.names))
            .put("weather", p.weather)
            .put("haColumns", p.haColumns)
            .put("haRows", p.haRows)
            .put("appColumns", p.appColumns)
            .put("favouriteCount", p.favouriteCount)
            .put("favourites", JSONArray(p.favourites.orEmpty()))
            .put("appOrder", JSONArray(p.appOrder))
            .put("hiddenApps", JSONArray(p.hiddenApps.toList()))
            .put("wallpaper", p.wallpaper)
            .put("wallpaperMinutes", p.wallpaperMinutes)
            .put("solidColour", p.solidColour)
    }

    private fun importConfig(j: JSONObject) {
        require(j.optInt("hearth") == 1) { "That doesn't look like a Hearth settings file" }
        val p = context.hearth.prefs
        fun list(k: String) = j.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }
        j.optString("haUrl").takeIf { it.isNotEmpty() && it != p.haUrl }?.let {
            p.haUrl = it
            p.token = ""          // a different server needs its own token: paste it on this page
        }
        list("entities")?.let { p.entities = it }
        j.optJSONObject("names")?.let { n -> p.names = n.keys().asSequence().associateWith { n.getString(it) } }
        if (j.has("weather")) p.weather = j.getString("weather")
        if (j.has("haColumns")) p.haColumns = j.getInt("haColumns").coerceIn(3, 7)
        if (j.has("haRows")) p.haRows = j.getInt("haRows").coerceIn(0, 3)
        if (j.has("appColumns")) p.appColumns = j.getInt("appColumns").coerceIn(3, 6)
        if (j.has("favouriteCount")) p.favouriteCount = j.getInt("favouriteCount").coerceIn(3, 6)
        list("favourites")?.let { p.favourites = it }
        list("appOrder")?.let { p.appOrder = it }
        list("hiddenApps")?.let { p.hiddenApps = it.toSet() }
        if (j.has("wallpaper")) p.wallpaper = j.getString("wallpaper")
        if (j.has("wallpaperMinutes")) p.wallpaperMinutes = j.getInt("wallpaperMinutes")
        if (j.has("solidColour")) p.solidColour = j.getInt("solidColour")
        context.hearth.apps.refresh()
        context.hearth.ha.restart()
        importedVersion.value++
    }

    /** Bumped after an import so the TV screen redraws with the restored layout and wallpaper. */
    val importedVersion = MutableStateFlow(0)

    private fun listing(states: List<Entity>): JSONObject {
        val arr = JSONArray()
        states.filter { it.domain in SUPPORTED }.sortedWith(compareBy({ it.domain }, { it.name.lowercase() })).forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("state", it.state))
        }
        return JSONObject().put("all", arr).put("entities", JSONArray(context.hearth.prefs.entities)).put("weather", context.hearth.prefs.weather)
            .put("names", JSONObject(context.hearth.prefs.names))
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("ISO-8859-1").trimEnd()
        }
        return null
    }

    private fun send(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found")[status] ?: "OK"
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "SetupServer"
        const val PORT = 8686
        val SUPPORTED = setOf(
            "light", "switch", "input_boolean", "fan", "cover", "climate", "scene", "script", "button",
            "sensor", "binary_sensor", "camera", "media_player", "lock", "vacuum", "weather",
        )

        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
