package app.hearth.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.hearth.tv.R
import app.hearth.tv.ha.Entity
import org.json.JSONObject
import kotlin.math.roundToInt

/** Outfit (SIL Open Font License 1.1), the same typeface as the Clean Your TV launcher. Variable font. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Outfit = androidx.compose.ui.text.font.FontFamily(
    listOf(300, 400, 500, 600).map { w ->
        androidx.compose.ui.text.font.Font(
            R.font.outfit,
            weight = androidx.compose.ui.text.font.FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    }
)

object Palette {
    val bg = Color(0xFF1B2230)
    val card = Color(0xFF1C1F24)
    val cardFocused = Color(0xFF2A2E35)
    val text = Color(0xFFEEF0F2)
    val dim = Color(0xFF9AA1AA)
    val accent = Color(0xFFF2A541)
    val ok = Color(0xFF7BD88F)
    val bad = Color(0xFFFF8A80)
}

/** Remembers the last focused item of a layer, so focus can go back to it when a panel closes. */
class FocusMemory {
    var last: FocusRequester? = null
    /** The first focusable item composed in the layer: where focus goes when nothing was focused yet. */
    var first: FocusRequester? = null
    val target get() = last ?: first
}

/** False for the home screen while a panel is open on top, so the remote can't wander behind the panel. */
val LocalLayerActive = compositionLocalOf { true }
val LocalFocusMemory = compositionLocalOf { FocusMemory() }

/**
 * Remote-friendly click: OK/Enter fires [onClick] on release; holding it fires [onLongClick] once.
 * Also reports focus so the caller can draw the focused look.
 */
fun Modifier.tvClick(
    onFocus: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onKey: ((Key) -> Boolean)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    var long by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    val active = LocalLayerActive.current
    val activeNow = androidx.compose.runtime.rememberUpdatedState(active)
    val memory = LocalFocusMemory.current
    val requester = remember { FocusRequester() }
    androidx.compose.runtime.SideEffect { if (memory.first == null) memory.first = requester }
    this
        .focusRequester(requester)
        .focusProperties { canFocus = active }
        // Items behind an open panel ignore focus completely, so nothing behind the panel reacts to it.
        .onFocusChanged {
            val real = it.isFocused && activeNow.value
            if (real) memory.last = requester
            onFocus(real)
        }
        .onPreviewKeyEvent { ev ->
            val k = ev.key
            if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                when (ev.type) {
                    KeyEventType.KeyDown -> {
                        if (ev.nativeKeyEvent.repeatCount == 0) { down = true; long = false }
                        else if (!long && onLongClick != null && down) { long = true; onLongClick() }
                    }
                    KeyEventType.KeyUp -> {
                        if (down && !long) onClick()
                        down = false
                    }
                }
                true
            } else if (ev.type == KeyEventType.KeyDown && onKey != null) onKey(k) else false
        }
        .focusable()
}

const val FOCUS_SCALE = 1.04f

/**
 * One highlight frame for a whole layer that glides from item to item, instead of each item
 * drawing its own outline. Items report where they are; the layer draws the frame (see [FocusRingLayer]).
 */
class FocusRing {
    var target by mutableStateOf<Rect?>(null)
    /** Which item the ring is on (changes on each move; [target] also changes while that item scrolls). */
    var owner by mutableStateOf<Any?>(null)
    var radius by mutableStateOf(16f)
    /** True while the screen slides between views: the ring waits and reappears on the new item. */
    var sliding by mutableStateOf(false)
}

val LocalFocusRing = compositionLocalOf<FocusRing?> { null }

/** Grows a little when focused. In a layer with a [FocusRing] the ring glides to it; otherwise it gets its own outline. */
fun Modifier.focusLook(focused: Boolean, radius: Int = 14): Modifier = composed {
    val scale by animateFloatAsState(if (focused) FOCUS_SCALE else 1f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "focus")
    val ring = LocalFocusRing.current
    val bounds = remember { arrayOfNulls<Rect>(1) }
    if (ring != null) {
        LaunchedEffect(focused) { if (focused) { ring.radius = radius.toFloat(); ring.owner = bounds; bounds[0]?.let { ring.target = it } } }
    }
    this
        .then(
            if (ring == null) Modifier else Modifier.onGloballyPositioned { c ->
                val r = Rect(c.positionInRoot(), c.size.toSize())
                bounds[0] = r
                if (focused) ring.target = r      // follows the item while lists scroll or views slide
            }
        )
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (ring != null) Modifier
            else Modifier.border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(radius.dp))
        )
}

/** Draws the gliding highlight for [ring] over the layer's content. */
@Composable
fun FocusRingLayer(ring: FocusRing, visible: Boolean) {
    val progress = remember { Animatable(1f) }
    // Plain holders, not state: they are written while composing and only read when drawing.
    val from = remember { arrayOf(Rect.Zero) }
    val drawnRef = remember { arrayOf(Rect.Zero) }
    val alpha by animateFloatAsState(if (visible && !ring.sliding && ring.target != null) 1f else 0f, tween(if (ring.sliding) 60 else 140), label = "ring")
    // Each move: start from wherever the ring is drawn and ease onto the new item.
    LaunchedEffect(ring.owner) {
        if (drawnRef[0] == Rect.Zero || ring.sliding) { progress.snapTo(1f); return@LaunchedEffect }
        from[0] = drawnRef[0]
        progress.snapTo(0f)
        progress.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(ring.sliding) { if (ring.sliding) progress.snapTo(1f) }
    val t = ring.target
    if (t != null) {
        // Grow the frame with the item, so it hugs the scaled-up tile.
        val g = (FOCUS_SCALE - 1f) / 2f
        val goal = Rect(t.left - t.width * g, t.top - t.height * g, t.right + t.width * g, t.bottom + t.height * g)
        drawnRef[0] = if (progress.value >= 1f || from[0] == Rect.Zero) goal else lerp(from[0], goal, progress.value)
    }
    val drawn = drawnRef[0]
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        if (alpha <= 0f) return@Canvas
        val r = drawn
        val corner = CornerRadius(with(density) { (ring.radius * FOCUS_SCALE).dp.toPx() })
        val w = with(density) { 2.dp.toPx() }
        // a faint halo hugging the frame (stays inside the gap between tiles), then the crisp frame
        drawRoundRect(Color.White.copy(alpha = 0.16f * alpha), r.topLeft - Offset(w, w), r.size.copy(r.width + w * 2, r.height + w * 2),
            CornerRadius(corner.x + w), style = Stroke(w * 1.5f))
        drawRoundRect(Color.White.copy(alpha = alpha), r.topLeft, r.size, corner, style = Stroke(w))
    }
}

// ------------------------------------------------------------------ how each kind of entity looks and acts

fun Entity.icon(): Int = when (domain) {
    "light" -> R.drawable.ic_bulb
    "switch", "input_boolean" -> R.drawable.ic_power
    "fan" -> R.drawable.ic_fan
    "cover" -> R.drawable.ic_blinds
    "climate" -> R.drawable.ic_thermo
    "camera" -> R.drawable.ic_camera
    "scene", "script" -> R.drawable.ic_star
    "button" -> R.drawable.ic_play
    "lock" -> R.drawable.ic_lock
    "vacuum" -> R.drawable.ic_vacuum
    "media_player" -> R.drawable.ic_speaker
    "sensor" -> when (attrs.optString("device_class")) {
        "temperature" -> R.drawable.ic_thermo
        "humidity", "moisture" -> R.drawable.ic_drop
        else -> R.drawable.ic_sensor
    }
    else -> R.drawable.ic_sensor
}

/** Lit up (amber icon) when the thing is on, open, heating, playing... */
val Entity.lit get() = when (domain) {
    "climate" -> attrs.optString("hvac_action") == "heating" || attrs.optString("hvac_action") == "cooling"
    "sensor", "camera", "scene", "script", "button", "weather" -> false
    else -> isOn
}

private fun num(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)
private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
private fun cap(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

fun Entity.stateText(): String {
    if (state == "unavailable") return "Unavailable"
    return when (domain) {
        "light" -> if (isOn) attrs.dbl("brightness")?.let { "On · ${(it / 2.55).roundToInt()}%" } ?: "On" else "Off"
        "switch", "input_boolean", "fan" -> if (isOn) "On" else "Off"
        "cover" -> attrs.dbl("current_position")?.let { p -> if (p in 1.0..99.0) "${num(p)}% open" else cap(state) } ?: cap(state)
        "climate" -> {
            val now = attrs.dbl("current_temperature")?.let { "${num(it)}°" }
            val target = attrs.dbl("temperature")?.let { "${num(it)}°" }
            when {
                state == "off" -> listOfNotNull("Off", now).joinToString(" · ")
                now != null && target != null -> "$now → $target"
                else -> now ?: target ?: cap(state)
            }
        }
        "sensor" -> {
            val unit = attrs.optString("unit_of_measurement")
            val v = state.toDoubleOrNull()
            if (v != null) "${num(v)}${if (unit.startsWith("°") || unit == "%") unit else if (unit.isNotEmpty()) " $unit" else ""}" else cap(state)
        }
        "binary_sensor" -> when (attrs.optString("device_class")) {
            "door", "window", "opening", "garage_door" -> if (isOn) "Open" else "Closed"
            "motion", "occupancy", "presence" -> if (isOn) "Detected" else "Clear"
            else -> if (isOn) "On" else "Off"
        }
        "media_player" -> if (state == "playing") attrs.optString("media_title").ifEmpty { "Playing" } else cap(state)
        "scene", "script" -> "Run"
        "button" -> "Press"
        "camera" -> "Live view"
        "lock" -> cap(state)
        "vacuum" -> cap(state)
        else -> cap(state)
    }
}

/** True when pressing OK does something more than toggling, so it opens the control panel instead. */
val Entity.hasPanel get() = domain == "climate"

/** True when holding OK opens a panel with finer control. */
val Entity.hasLongPanel get() = domain in setOf("light", "cover", "climate", "media_player", "fan")

fun weatherIcon(condition: String, night: Boolean): Int = when (condition) {
    "sunny" -> R.drawable.wx_clear_day
    "clear-night" -> R.drawable.wx_clear_night
    "partlycloudy" -> if (night) R.drawable.wx_partly_night else R.drawable.wx_partly_day
    "cloudy", "windy", "windy-variant", "exceptional" -> R.drawable.wx_cloudy
    "fog" -> R.drawable.wx_fog
    "rainy", "pouring", "hail" -> R.drawable.wx_rain
    "snowy", "snowy-rainy" -> R.drawable.wx_snow
    "lightning", "lightning-rainy" -> R.drawable.wx_thunder
    else -> R.drawable.wx_cloudy
}

fun weatherWords(condition: String) = when (condition) {
    "partlycloudy" -> "Partly cloudy"
    "clear-night" -> "Clear"
    "lightning-rainy" -> "Thunderstorms"
    "snowy-rainy" -> "Sleet"
    "windy-variant" -> "Windy"
    else -> cap(condition.replace('-', ' '))
}
