package app.hearth.tv.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.hearth.tv.ha.Entity
import app.hearth.tv.hearth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** What is on screen on top of the home screen. */
sealed interface Overlay {
    data object None : Overlay
    data class Control(val id: String) : Overlay
    data class Camera(val id: String) : Overlay
    data object Settings : Overlay
    data object Setup : Overlay
    data object Apps : Overlay
    data class AppActions(val pkg: String) : Overlay
}

class HomeActivity : ComponentActivity() {
    var overlay by mutableStateOf<Overlay>(Overlay.None)
    /** Bumped when Home is pressed while already home, so the screen scrolls back and refocuses. */
    var homeTicks by mutableStateOf(0)
    var toast by mutableStateOf("")
    /** App being moved with the arrows, or null. */
    var moving by mutableStateOf<String?>(null)
    /** True while the all-apps view is showing instead of the home view. */
    var allApps by mutableStateOf(false)
    /** Bumped by Back on the home view: jump straight to the favourites bar. */
    var toFavourites by mutableStateOf(0)
    /** True while a favourite is selected (Back then has nothing to do). */
    var onFavourites = false
    /** Bumped when wallpaper settings change, so the background restarts with them. */
    var wallpaperVersion by mutableStateOf(0)
    /** Bumped when tiles-per-row settings change. */
    var layoutVersion by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The connection lives in the Application, so it survives the activity being recreated.
        if (hearth.ha.status.value is app.hearth.tv.ha.HaStatus.NotSetUp) hearth.ha.restart()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // A launcher never closes on Back: it just steps out of whatever panel is open.
            override fun handleOnBackPressed() {
                when {
                    overlay == Overlay.Setup || overlay == Overlay.Apps -> overlay = Overlay.Settings
                    overlay != Overlay.None -> overlay = Overlay.None
                    moving != null -> moving = null
                    allApps -> allApps = false
                    // Anywhere else on the home view (Home Assistant tiles, header): straight to the favourites.
                    !onFavourites -> toFavourites++
                }
            }
        })
        setContent {
            androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = Outfit)) { HomeScreen(this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        overlay = Overlay.None
        homeTicks++
    }

    override fun onResume() {
        super.onResume()
        hearth.apps.refresh()
        if (hearth.ha.status.value is app.hearth.tv.ha.HaStatus.Offline) hearth.ha.restart()
    }

    // ------------------------------------------------------------------ actions
    fun press(e: Entity) {
        val ha = hearth.ha
        when (e.domain) {
            "light", "switch", "input_boolean", "fan" -> ha.call("homeassistant", "toggle", e.id)
            "cover" -> ha.call("cover", "toggle", e.id)
            "climate" -> overlay = Overlay.Control(e.id)
            "scene" -> { ha.call("scene", "turn_on", e.id); say("${e.name} on") }
            "script" -> { ha.call("script", "turn_on", e.id); say("Running ${e.name}") }
            "button" -> { ha.call("button", "press", e.id); say("Pressed ${e.name}") }
            "media_player" -> ha.call("media_player", "media_play_pause", e.id)
            "vacuum" -> ha.call("vacuum", if (e.state == "cleaning") "return_to_base" else "start", e.id)
            "camera" -> overlay = Overlay.Camera(e.id)
            "lock" -> say("Locks are view-only on the TV")
            else -> Unit
        }
    }

    fun call(domain: String, service: String, id: String, data: JSONObject = JSONObject()) = hearth.ha.call(domain, service, id, data)

    fun say(text: String) { toast = text }

    /** One camera snapshot through Home Assistant's camera proxy. */
    suspend fun snapshot(entityId: String, width: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(hearth.ha.cameraUrl(entityId) + "?width=$width")
                .header("Authorization", hearth.ha.authHeader()).build()
            hearth.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                BitmapFactory.decodeStream(r.body!!.byteStream())?.asImageBitmap()
            }
        }.getOrNull()
    }
}
