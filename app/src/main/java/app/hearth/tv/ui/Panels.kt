package app.hearth.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hearth.tv.ha.Entity
import app.hearth.tv.ha.HaStatus
import app.hearth.tv.hearth
import app.hearth.tv.setup.SetupServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import org.json.JSONObject

// ------------------------------------------------------------------ building blocks

/** A layer above the home screen with its own focus; its first item gets focus when it opens. */
@Composable
private fun Layer(scrim: Color = Color(0xCC000000), content: @Composable BoxScope.() -> Unit) {
    val memory = remember { FocusMemory() }
    LaunchedEffect(Unit) { delay(30); memory.target?.let { runCatching { it.requestFocus() } } }
    CompositionLocalProvider(LocalLayerActive provides true, LocalFocusMemory provides memory) {
        Box(Modifier.fillMaxSize().background(scrim), content = content)
    }
}

@Composable
private fun PanelButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(56.dp)
            .focusLook(focused, radius = 12)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Palette.accent else Color.White.copy(alpha = 0.08f))
            .tvClick(onFocus = { focused = it }, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) Color(0xFF1B1204) else Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SettingRow(title: String, value: String = "", onClick: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (onClick != null) Modifier.tvClick(onFocus = { focused = it }, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (onClick != null) Palette.text else Palette.dim, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = if (focused) Palette.accent else Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoxScope.Drawer(title: String, scroll: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp)
            .background(Color(0xFF16191D))
            .then(if (scroll) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)
            .padding(horizontal = 20.dp, vertical = 32.dp),
    ) {
        Text(title, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 18.dp, bottom = 18.dp))
        content()
    }
}

// ------------------------------------------------------------------ entity control

@Composable
fun ControlPanel(act: HomeActivity, e: Entity) {
    Layer {
        Column(
            Modifier.align(Alignment.Center).width(600.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B1E23)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(e.icon()), null, tint = if (e.lit) Palette.accent else Palette.dim, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(label(act, e), color = Palette.text, fontSize = 22.sp)
            }
            Spacer(Modifier.height(14.dp))
            val (big, small) = bigValue(e)
            Text(big, color = if (e.lit) Palette.accent else Palette.text, fontSize = 54.sp, fontWeight = FontWeight.Light)
            if (small.isNotEmpty()) Text(small, color = Palette.dim, fontSize = 16.sp)
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((text, action) in controls(act, e)) PanelButton(text, Modifier.width(160.dp), onClick = action)
            }
            if (e.domain == "light") ColourRow(act, e)
            Spacer(Modifier.height(18.dp))
            Text("Back to close", color = Palette.dim.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

private fun num(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

private fun bigValue(e: Entity): Pair<String, String> = when (e.domain) {
    "light" -> if (e.isOn) "${Math.round(e.attrs.optDouble("brightness", 255.0) / 2.55)}%" to "On" else "Off" to ""
    "climate" -> {
        val now = e.attrs.optDouble("current_temperature")
        val target = e.attrs.optDouble("temperature")
        val action = e.attrs.optString("hvac_action").replace('_', ' ').replaceFirstChar { it.uppercase() }
        (if (now.isNaN()) "–" else "${num(now)}°") to listOfNotNull(
            if (e.state == "off") "Off" else if (!target.isNaN()) "Set to ${num(target)}°" else null,
            action.takeIf { it.isNotEmpty() && e.state != "off" },
        ).joinToString(" · ")
    }
    else -> e.stateText() to ""
}

private fun controls(act: HomeActivity, e: Entity): List<Pair<String, () -> Unit>> {
    val id = e.id
    return when (e.domain) {
        "light" -> listOf(
            "Dimmer" to { act.call("light", "turn_on", id, JSONObject().put("brightness_step_pct", -10)) },
            (if (e.isOn) "Turn off" else "Turn on") to { act.call("light", "toggle", id) },
            "Brighter" to { act.call("light", "turn_on", id, JSONObject().put("brightness_step_pct", 10)) },
        )
        "climate" -> {
            val step = e.attrs.optDouble("target_temp_step").takeIf { !it.isNaN() } ?: 0.5
            val target = e.attrs.optDouble("temperature")
            val modes = e.attrs.optJSONArray("hvac_modes")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val onMode = listOf("heat", "auto", "heat_cool", "cool").firstOrNull { it in modes } ?: modes.firstOrNull { it != "off" } ?: "heat"
            fun set(t: Double) = act.call("climate", "set_temperature", id, JSONObject().put("temperature", t))
            listOf(
                "− ${num(step)}°" to { if (!target.isNaN()) set(target - step) },
                (if (e.state == "off") "Turn on" else "Turn off") to {
                    act.call("climate", "set_hvac_mode", id, JSONObject().put("hvac_mode", if (e.state == "off") onMode else "off"))
                },
                "+ ${num(step)}°" to { if (!target.isNaN()) set(target + step) },
            )
        }
        "cover" -> listOf(
            "Open" to { act.call("cover", "open_cover", id) },
            "Stop" to { act.call("cover", "stop_cover", id) },
            "Close" to { act.call("cover", "close_cover", id) },
        )
        "fan" -> listOf(
            "Slower" to { act.call("fan", "decrease_speed", id) },
            (if (e.isOn) "Turn off" else "Turn on") to { act.call("fan", "toggle", id) },
            "Faster" to { act.call("fan", "increase_speed", id) },
        )
        "media_player" -> listOf(
            "Volume −" to { act.call("media_player", "volume_down", id) },
            (if (e.state == "playing") "Pause" else "Play") to { act.call("media_player", "media_play_pause", id) },
            "Volume +" to { act.call("media_player", "volume_up", id) },
        )
        else -> listOf("Toggle" to { act.call("homeassistant", "toggle", id) })
    }
}

// ------------------------------------------------------------------ camera

@Composable
fun CameraView(act: HomeActivity, id: String, name: String) {
    val image by produceState<ImageBitmap?>(null, id) {
        while (true) { act.snapshot(id, 1920)?.let { value = it }; delay(1_000) }
    }
    Layer(scrim = Color.Black) {
        image?.let { Image(it, name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: Text("Loading camera…", color = Palette.dim, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        Text(name, color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.BottomStart).padding(32.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0x88000000)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

// ------------------------------------------------------------------ settings

private fun minutesWords(m: Int) = when {
    m >= 1440 -> "Once a day"
    m >= 60 -> "${m / 60} hour"
    else -> "$m minutes"
}

private fun statusWords(s: HaStatus) = when (s) {
    HaStatus.NotSetUp -> "Not set up"
    HaStatus.Connecting -> "Connecting…"
    HaStatus.Connected -> "Connected"
    HaStatus.BadToken -> "Token rejected"
    is HaStatus.Offline -> "Offline"
}

@Composable
fun SettingsPanel(act: HomeActivity, status: HaStatus) {
    val app = act.hearth
    act.wallpaperVersion   // read so the rows refresh when a wallpaper setting changes
    Layer(scrim = Color(0x99000000)) {
        Drawer("Settings") {
            act.layoutVersion
            SettingRow("Home Assistant", statusWords(status)) { act.overlay = Overlay.Setup }
            SettingRow("Edit tiles", "From your phone") { act.overlay = Overlay.Setup }
            SettingRow("Tiles per row", "${app.prefs.haColumns}") {
                app.prefs.haColumns = if (app.prefs.haColumns >= 7) 3 else app.prefs.haColumns + 1
                act.layoutVersion++
            }
            SettingRow("Favourites in the bar", "${app.prefs.favouriteCount}") {
                app.prefs.favouriteCount = if (app.prefs.favouriteCount >= 6) 3 else app.prefs.favouriteCount + 1
                app.apps.relayout()
                act.layoutVersion++
            }
            SettingRow("Apps per row", "${app.prefs.appColumns}") {
                app.prefs.appColumns = if (app.prefs.appColumns >= 6) 3 else app.prefs.appColumns + 1
                act.layoutVersion++
            }
            SettingRow("Show or hide apps") { act.overlay = Overlay.Apps }
            val current = WALLPAPERS.indexOfFirst { it.first == app.prefs.wallpaper }.coerceAtLeast(0)
            SettingRow("Wallpaper", WALLPAPERS[current].second) {
                app.prefs.wallpaper = WALLPAPERS[(current + 1) % WALLPAPERS.size].first
                act.wallpaperVersion++
            }
            if (app.prefs.wallpaper == "solid") SettingRow("Colour", SOLID_COLOURS[app.prefs.solidColour % SOLID_COLOURS.size].first) {
                app.prefs.solidColour = (app.prefs.solidColour + 1) % SOLID_COLOURS.size
                act.wallpaperVersion++
            }
            if (app.prefs.wallpaper == "bing") SettingRow("Change picture every", minutesWords(app.prefs.wallpaperMinutes)) {
                val steps = listOf(5, 15, 30, 60, 1440)
                app.prefs.wallpaperMinutes = steps[(steps.indexOf(app.prefs.wallpaperMinutes) + 1) % steps.size]
                act.wallpaperVersion++
            }
            SettingRow("Android settings") { app.apps.openAndroidSettings() }
            SettingRow("Reconnect to Home Assistant") { app.ha.restart(); act.say("Reconnecting…") }
            Spacer(Modifier.height(24.dp))
            SettingRow("Hearth", act.packageManager.getPackageInfo(act.packageName, 0).versionName.orEmpty(), onClick = null)
        }
    }
}


@Composable
fun SetupPanel(act: HomeActivity, status: HaStatus) {
    val server = act.hearth.setup
    val event by server.events.collectAsState()
    val address = remember { server.address }
    val qr = remember(address) { address?.let { qrCode(it) } }
    Layer(scrim = Color(0xEE0E1013)) {
        Row(Modifier.align(Alignment.Center).width(820.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(250.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                qr?.let { Image(it, "QR code for the setup page", modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text("Set up on your phone", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Scan the code, or open this address on a phone or computer on the same Wi-Fi:",
                    color = Palette.dim, fontSize = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(address ?: "The TV isn't on a network", color = Palette.accent, fontSize = 20.sp)
                Spacer(Modifier.height(20.dp))
                Text("You'll need a long-lived access token from your Home Assistant profile page.", color = Palette.dim, fontSize = 14.sp)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (status == HaStatus.Connected) Palette.ok else Palette.dim))
                    Spacer(Modifier.width(10.dp))
                    Text(event.ifEmpty { "Home Assistant: ${statusWords(status).lowercase()}" }, color = Palette.text, fontSize = 16.sp)
                }
                Spacer(Modifier.height(24.dp))
                PanelButton("Done", Modifier.width(160.dp)) { act.overlay = Overlay.None }
            }
        }
    }
}

private fun qrCode(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return Bitmap.createScaledBitmap(bmp, m.width * 8, m.height * 8, false).asImageBitmap()
}

@Composable
fun HideAppsPanel(act: HomeActivity) {
    val all by act.hearth.apps.all.collectAsState()
    var hidden by remember { mutableStateOf(act.hearth.prefs.hiddenApps) }
    Layer(scrim = Color(0x99000000)) {
        Drawer("Show or hide apps", scroll = false) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(all, key = { it.pkg }) { a ->
                    val isHidden = a.pkg in hidden
                    SettingRow(a.label, if (isHidden) "Hidden" else "Shown") {
                        act.hearth.apps.setHidden(a.pkg, !isHidden)
                        hidden = act.hearth.prefs.hiddenApps
                    }
                }
            }
        }
    }
}

@Composable
fun AppActionsPanel(act: HomeActivity, pkg: String) {
    val apps = act.hearth.apps
    val app = apps.all.collectAsState().value.firstOrNull { it.pkg == pkg } ?: return
    val fav = apps.isFavourite(pkg)
    Layer {
        Column(
            Modifier.align(Alignment.Center).width(460.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B1E23)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(app.label, color = Palette.text, fontSize = 24.sp)
            Spacer(Modifier.height(20.dp))
            PanelButton(if (fav) "Remove from favourites" else "Add to favourites", Modifier.fillMaxWidth()) {
                if (apps.toggleFavourite(pkg)) {
                    act.say(if (fav) "Removed ${app.label} from favourites" else "Added ${app.label} to favourites")
                } else act.say("The favourites bar is full. Remove one first, or allow more in Settings.")
                act.overlay = Overlay.None
            }
            Spacer(Modifier.height(12.dp))
            PanelButton("Move", Modifier.fillMaxWidth()) {
                act.moving = pkg
                act.overlay = Overlay.None
                act.say("Use the arrows to move ${app.label}, then press OK")
            }
            Spacer(Modifier.height(12.dp))
            PanelButton("Hide", Modifier.fillMaxWidth()) {
                apps.setHidden(pkg, true)
                act.say("${app.label} hidden. Show it again from Settings.")
                act.overlay = Overlay.None
            }
        }
    }
}

// ------------------------------------------------------------------ light colours

private val COLOUR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

/** Preset colours for lights that can do colour (and warm/cool white for tunable ones). */
@Composable
private fun ColourRow(act: HomeActivity, e: Entity) {
    val modes = e.attrs.optJSONArray("supported_color_modes")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
    val colour = modes.any { it in COLOUR_MODES }
    val temp = "color_temp" in modes
    if (!colour && !temp) return
    val swatches = buildList {
        if (temp) {
            add(Triple("Warm white", Color(0xFFFFC27A), JSONObject().put("color_temp_kelvin", 2700)))
            add(Triple("Cool white", Color(0xFFE8F0FF), JSONObject().put("color_temp_kelvin", 5500)))
        } else if (colour) {
            add(Triple("White", Color.White, JSONObject().put("rgb_color", org.json.JSONArray(listOf(255, 255, 255)))))
        }
        if (colour) {
            for ((name, rgb) in listOf(
                "Red" to 0xFF3B30, "Orange" to 0xFF8C1A, "Yellow" to 0xFFD60A, "Green" to 0x34C759,
                "Cyan" to 0x32D2E6, "Blue" to 0x2F6BFF, "Purple" to 0x9B5CFF, "Pink" to 0xFF4FA3,
            )) {
                val r = rgb shr 16 and 0xFF; val g = rgb shr 8 and 0xFF; val b = rgb and 0xFF
                add(Triple(name, Color(0xFF000000 or rgb.toLong()), JSONObject().put("rgb_color", org.json.JSONArray(listOf(r, g, b)))))
            }
        }
    }
    // The light's current colour, to ring the matching swatch.
    val current = e.attrs.optJSONArray("rgb_color")?.let { a -> Color(a.optInt(0), a.optInt(1), a.optInt(2)) }
    var picked by remember(e.id) { mutableStateOf("") }
    Spacer(Modifier.height(22.dp))
    Text(if (picked.isEmpty()) "Colour" else picked, color = Palette.dim, fontSize = 14.sp)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((name, swatch, data) in swatches) {
            var focused by remember { mutableStateOf(false) }
            val isCurrent = e.isOn && current != null && name !in setOf("Warm white", "Cool white") && near(current, swatch)
            Box(
                Modifier
                    .size(40.dp)
                    .focusLook(focused, radius = 20)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(if (isCurrent) 3.dp else 1.dp, if (isCurrent) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                    .tvClick(onFocus = { focused = it; if (it) picked = name }) {
                        act.call("light", "turn_on", e.id, JSONObject(data.toString()))
                    },
            )
        }
    }
}

private fun near(a: Color, b: Color): Boolean {
    val d = kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
    return d < 0.35f
}
