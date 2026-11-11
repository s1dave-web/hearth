package app.hearth.tv.ha

import android.util.Log
import app.hearth.tv.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** One Home Assistant entity as the launcher sees it. */
data class Entity(val id: String, val state: String, val attrs: JSONObject) {
    val domain get() = id.substringBefore('.')
    val name: String get() = attrs.optString("friendly_name").ifEmpty { id.substringAfter('.').replace('_', ' ') }
    val isOn get() = state in ON_STATES
    val available get() = state != "unavailable" && state != "unknown"

    companion object {
        val ON_STATES = setOf("on", "open", "opening", "playing", "heat", "cool", "heat_cool", "auto", "dry", "fan_only", "cleaning", "unlocked")
    }
}

sealed interface HaStatus {
    data object NotSetUp : HaStatus
    data object Connecting : HaStatus
    data object Connected : HaStatus
    data object BadToken : HaStatus
    data class Offline(val reason: String) : HaStatus
}

/**
 * Keeps a live WebSocket to Home Assistant and mirrors the chosen entities into [entities].
 * Uses subscribe_entities, so only the entities on the home screen are streamed.
 */
class HaClient(private val http: OkHttpClient, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val entities: StateFlow<Map<String, Entity>> = _entities
    private val _status = MutableStateFlow<HaStatus>(HaStatus.NotSetUp)
    val status: StateFlow<HaStatus> = _status

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private val ids = AtomicInteger(1)
    @Volatile private var generation = 0

    /** (Re)connects with the current settings. Safe to call any time. */
    fun restart() {
        loop?.cancel()
        socket?.close(1000, null)
        socket = null
        _entities.value = emptyMap()
        if (!prefs.configured) {
            _status.value = HaStatus.NotSetUp
            return
        }
        val gen = ++generation
        loop = scope.launch {
            var backoff = 2_000L
            while (gen == generation) {
                val closed = kotlinx.coroutines.CompletableDeferred<Boolean>()   // true = bad token, stop retrying
                _status.value = HaStatus.Connecting
                socket = http.newWebSocket(Request.Builder().url(wsUrl(prefs.haUrl)).build(), Listener(gen, closed) { backoff = 2_000L })
                if (closed.await() || gen != generation) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun call(domain: String, service: String, entityId: String, data: JSONObject = JSONObject()) {
        val msg = JSONObject()
            .put("id", ids.getAndIncrement())
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
            .put("service_data", data)
            .put("target", JSONObject().put("entity_id", entityId))
        if (socket?.send(msg.toString()) != true) Log.w(TAG, "not connected, dropped $domain.$service")
    }

    private inner class Listener(
        private val gen: Int,
        private val closed: kotlinx.coroutines.CompletableDeferred<Boolean>,
        private val onConnected: () -> Unit,
    ) : WebSocketListener() {
        private var statesId = -1
        private var subscribeId = -1

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", prefs.token).toString())
                "auth_invalid" -> {
                    _status.value = HaStatus.BadToken
                    webSocket.close(1000, null)
                    closed.complete(true)
                }
                "auth_ok" -> {
                    onConnected()
                    if (prefs.weather.isEmpty()) {
                        statesId = ids.getAndIncrement()
                        webSocket.send(JSONObject().put("id", statesId).put("type", "get_states").toString())
                    } else subscribe(webSocket)
                }
                "result" -> if (msg.optInt("id") == statesId) {
                    // First run: pick a weather entity for the header, then subscribe.
                    val all = msg.optJSONArray("result") ?: JSONArray()
                    for (i in 0 until all.length()) {
                        val id = all.getJSONObject(i).optString("entity_id")
                        if (id.startsWith("weather.")) { prefs.weather = id; break }
                    }
                    subscribe(webSocket)
                } else if (msg.optInt("id") == subscribeId && msg.optBoolean("success")) {
                    _status.value = HaStatus.Connected
                }
                "event" -> if (msg.optInt("id") == subscribeId) apply(msg.getJSONObject("event"))
            }
        }

        private fun subscribe(webSocket: WebSocket) {
            subscribeId = ids.getAndIncrement()
            val wanted = (prefs.entities + prefs.weather).filter { it.isNotEmpty() }.distinct()
            webSocket.send(
                JSONObject().put("id", subscribeId).put("type", "subscribe_entities")
                    .put("entity_ids", JSONArray(wanted)).toString()
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) return
            _status.value = HaStatus.Offline(t.message ?: t.javaClass.simpleName)
            closed.complete(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) return
            if (_status.value != HaStatus.BadToken) _status.value = HaStatus.Offline("closed")
            closed.complete(false)
        }
    }

    /** Applies a subscribe_entities event: a = added (full), c = changed (diff), r = removed. */
    private fun apply(ev: JSONObject) {
        val map = _entities.value.toMutableMap()
        ev.optJSONObject("a")?.let { a ->
            for (id in a.keys()) {
                val e = a.getJSONObject(id)
                map[id] = Entity(id, e.optString("s"), e.optJSONObject("a") ?: JSONObject())
            }
        }
        ev.optJSONObject("c")?.let { c ->
            for (id in c.keys()) {
                val old = map[id] ?: continue
                val diff = c.getJSONObject(id)
                var state = old.state
                val attrs = JSONObject(old.attrs.toString())
                diff.optJSONObject("+")?.let { plus ->
                    if (plus.has("s")) state = plus.optString("s")
                    plus.optJSONObject("a")?.let { a -> for (k in a.keys()) attrs.put(k, a.get(k)) }
                }
                diff.optJSONObject("-")?.optJSONArray("a")?.let { gone -> for (i in 0 until gone.length()) attrs.remove(gone.getString(i)) }
                map[id] = Entity(id, state, attrs)
            }
        }
        ev.optJSONArray("r")?.let { r -> for (i in 0 until r.length()) map.remove(r.getString(i)) }
        _entities.value = map
    }

    // ------------------------------------------------------------------ REST, for setup
    /** Checks a URL + token and returns every entity, or throws with a readable message. */
    suspend fun fetchStates(url: String, token: String): List<Entity> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${url.trimEnd('/')}/api/states").header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 401 -> error("Home Assistant rejected the token")
                !r.isSuccessful -> error("Home Assistant answered ${r.code}")
            }
            val arr = JSONArray(r.body!!.string())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entity(o.getString("entity_id"), o.optString("state"), o.optJSONObject("attributes") ?: JSONObject())
            }
        }
    }

    fun cameraUrl(entityId: String) = "${prefs.haUrl}/api/camera_proxy/$entityId"
    fun authHeader() = "Bearer ${prefs.token}"

    companion object {
        private const val TAG = "HaClient"
        fun wsUrl(base: String) = base.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/websocket"
    }
}
