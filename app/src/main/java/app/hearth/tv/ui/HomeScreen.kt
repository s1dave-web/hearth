package app.hearth.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hearth.tv.NetState
import app.hearth.tv.R
import app.hearth.tv.apps.AppEntry
import app.hearth.tv.ha.Entity
import app.hearth.tv.ha.HaStatus
import app.hearth.tv.hearth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private val SIDE = 56.dp
private val GAP = 26.dp
private val APP_RADIUS = 16.dp

@Composable
fun HomeScreen(act: HomeActivity) {
    val app = act.hearth
    val entities by app.ha.entities.collectAsState()
    val status by app.ha.status.collectAsState()
    val favourites by app.apps.favourites.collectAsState()
    val others by app.apps.others.collectAsState()
    val net by app.network.state.collectAsState()
    val imported by app.setup.importedVersion.collectAsState()
    LaunchedEffect(imported) { if (imported > 0) { act.layoutVersion++; act.wallpaperVersion++ } }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Backdrop(act)
        val homeFocus = remember { FocusMemory() }
        val home = act.overlay == Overlay.None
        // Remember the focused tile when a panel opens and go back to it when the panel closes
        // (Android would otherwise hand focus to the first button on screen).
        val restore = remember { arrayOfNulls<FocusRequester>(1) }
        LaunchedEffect(home) {
            if (!home) restore[0] = homeFocus.last
            else { delay(60); (restore[0] ?: homeFocus.last)?.let { runCatching { it.requestFocus() } }; restore[0] = null }
        }
        val ring = remember { FocusRing() }
        CompositionLocalProvider(LocalLayerActive provides home, LocalFocusMemory provides homeFocus, LocalFocusRing provides ring) {
            Column(Modifier.fillMaxSize()) {
                Header(act, entities[app.prefs.weather], net)
                Body(act, entities, status, favourites, others)
            }
            FocusRingLayer(ring, visible = home)
        }
        // A control panel for an entity that has gone away (e.g. after re-setup) closes itself.
        LaunchedEffect(act.overlay, entities) {
            (act.overlay as? Overlay.Control)?.let { if (entities.isNotEmpty() && entities[it.id] == null) act.overlay = Overlay.None }
        }
        when (val o = act.overlay) {
            is Overlay.Control -> entities[o.id]?.let { ControlPanel(act, it) }
            is Overlay.Camera -> CameraView(act, o.id, entities[o.id]?.let { label(act, it) } ?: "")
            Overlay.Settings -> SettingsPanel(act, status)
            Overlay.Setup -> SetupPanel(act, status)
            Overlay.Apps -> HideAppsPanel(act)
            is Overlay.AppActions -> AppActionsPanel(act, o.pkg)
            Overlay.None -> Unit
        }
        Toast(act)
    }
}

fun label(act: HomeActivity, e: Entity) = act.hearth.prefs.names[e.id] ?: e.name

// ------------------------------------------------------------------ backdrop: night sky over mountains, drawn not loaded

@Composable
private fun Backdrop(act: HomeActivity) {
    val prefs = act.hearth.prefs
    act.wallpaperVersion   // read so a settings change swaps the background straight away
    when (val w = prefs.wallpaper) {
        "sky" -> { NightSky(); return }
        "solid" -> {
            val c = SOLID_COLOURS[prefs.solidColour % SOLID_COLOURS.size].second
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c, c.copy(red = c.red * 0.7f, green = c.green * 0.7f, blue = c.blue * 0.7f)))))
            return
        }
        "bing" -> Unit
        else -> { BuiltIn(WALLPAPERS.firstOrNull { it.first == w }?.third ?: R.drawable.wall_aurora); return }
    }
    // Photo slideshow: crossfades to the next picture every few minutes; the night sky shows until the first one loads.
    val photo by produceState<Pair<ImageBitmap, String>?>(null, act.wallpaperVersion) {
        while (true) {
            val list = act.hearth.wallpapers.photos()
            if (list.isEmpty()) { delay(60_000); continue }
            val i = prefs.wallpaperIndex % list.size
            act.hearth.wallpapers.load(list[i])?.let { value = it.asImageBitmap() to list[i].credit }
            prefs.wallpaperIndex = i + 1
            delay(prefs.wallpaperMinutes * 60_000L)
        }
    }
    NightSky()
    Crossfade(targetState = photo, animationSpec = tween(2_000), label = "wallpaper") { p ->
        if (p != null) Box(Modifier.fillMaxSize()) {
            Image(p.first, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Keep the clock, tiles and apps readable on any picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.62f), 0.35f to Color.Black.copy(alpha = 0.38f),
                0.65f to Color.Black.copy(alpha = 0.42f), 1f to Color.Black.copy(alpha = 0.72f),
            )))
            Text(
                p.second.substringBefore(" (©").take(90), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = SIDE, bottom = 18.dp).width(360.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

/** One of the pictures that ship with Hearth, decoded off the main thread. */
@Composable
private fun BuiltIn(res: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val image by produceState<ImageBitmap?>(null, res) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeResource(ctx.resources, res)?.asImageBitmap()
        }
    }
    NightSky()
    Crossfade(targetState = image, animationSpec = tween(600), label = "builtin") { im ->
        if (im != null) Box(Modifier.fillMaxSize()) {
            Image(im, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.35f), 0.4f to Color.Black.copy(alpha = 0.10f),
                0.7f to Color.Black.copy(alpha = 0.20f), 1f to Color.Black.copy(alpha = 0.50f),
            )))
        }
    }
}

/** (setting value, name in Settings, picture) for every wallpaper choice, in the order Settings cycles them. */
val WALLPAPERS = listOf(
    Triple("sky", "Night sky", 0),
    Triple("aurora", "Aurora", R.drawable.wall_aurora),
    Triple("dunes", "Desert dunes", R.drawable.wall_dunes),
    Triple("lake", "Misty lake", R.drawable.wall_lake),
    Triple("christmas", "Christmas", R.drawable.wall_christmas),
    Triple("bing", "Bing Photo of the day", 0),
    Triple("solid", "Solid colour", 0),
)

val SOLID_COLOURS = listOf(
    "Charcoal" to Color(0xFF24272C),
    "Midnight blue" to Color(0xFF16213A),
    "Deep teal" to Color(0xFF0F3A40),
    "Forest green" to Color(0xFF1C3524),
    "Plum" to Color(0xFF3A1F3D),
    "Black" to Color(0xFF050506),
)

@Composable
private fun NightSky() {
    val stars = remember { List(140) { Triple(Random.nextFloat(), Random.nextFloat() * 0.6f, Random.nextFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2230), Color(0xFF283245), Color(0xFF384457))))
        for ((x, y, b) in stars) drawCircle(Color.White.copy(alpha = 0.10f + b * 0.35f), radius = 0.6f + b * 1.3f, center = Offset(x * size.width, y * size.height))
        fun ridge(base: Float, amp: Float, seed: Int, color: Color) {
            val r = Random(seed)
            val path = Path().apply {
                moveTo(0f, size.height)
                var x = 0f
                lineTo(0f, base * size.height)
                while (x < size.width) {
                    x += size.width * (0.012f + r.nextFloat() * 0.025f)
                    lineTo(x, (base - amp * (0.3f + r.nextFloat() * 0.7f) * (0.6f + 0.4f * kotlin.math.sin(x / size.width * 7f + seed))) * size.height)
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, color)
        }
        ridge(0.60f, 0.16f, 3, Color(0xFF34404F))
        ridge(0.70f, 0.13f, 7, Color(0xFF2A3340))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF242C39)), startY = size.height * 0.7f, endY = size.height))
    }
}

// ------------------------------------------------------------------ header: greeting, clock, date, weather + settings buttons

@Composable
private fun Header(act: HomeActivity, weather: Entity?, net: NetState) {
    val now by produceState(Date()) {
        while (true) { value = Date(); delay(1_000L * (60 - (System.currentTimeMillis() / 1000) % 60).coerceAtLeast(1)) }
    }
    val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    Row(Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = 20.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting.uppercase(), color = Palette.dim, fontFamily = Outfit, fontSize = 13.sp, letterSpacing = 4.sp, modifier = Modifier.padding(start = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = Palette.text, fontFamily = Outfit, fontWeight = FontWeight.Light, fontSize = 54.sp, lineHeight = 60.sp)
                Spacer(Modifier.width(22.dp))
                Box(Modifier.width(1.dp).height(34.dp).background(Color.White.copy(alpha = 0.25f)))
                Spacer(Modifier.width(22.dp))
                Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).uppercase(), color = Palette.dim, fontFamily = Outfit, fontSize = 16.sp, letterSpacing = 3.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
        if (weather != null && weather.available) {
            val t = weather.attrs.optDouble("temperature")
            Pill {
                Icon(painterResource(weatherIcon(weather.state, hour < 7 || hour >= 19)), weatherWords(weather.state), tint = Palette.text, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (t.isNaN()) "" else "${Math.round(t)}°", color = Palette.text, fontFamily = Outfit, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
        }
        RoundButton(
            if (net.ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi,
            if (net.connected) "Connected" else "Not connected",
            tint = if (net.connected) Palette.ok else Palette.bad,
        ) { act.hearth.apps.openNetworkSettings() }
        Spacer(Modifier.width(12.dp))
        RoundButton(R.drawable.ic_gear, "Settings") { act.overlay = Overlay.Settings }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(
        Modifier.height(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun RoundButton(icon: Int, description: String, tint: Color = Palette.text, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(56.dp).focusLook(focused, radius = 28)
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .tvClick(onFocus = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), description, tint = tint, modifier = Modifier.size(24.dp)) }
}

// ------------------------------------------------------------------ body: Home Assistant tiles, favourites bar, then all apps

/** Turns off Compose's automatic "scroll the focused item into view", so we scroll by whole rows ourselves. */
@OptIn(ExperimentalFoundationApi::class)
private val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * The home view (tiles + favourites) and the all-apps view are both always laid out, one above the
 * other, and the screen slides between them. Because both stay focusable, the remote moves straight
 * from the favourites to the first row of apps and back, with no moment where nothing is selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Body(act: HomeActivity, entities: Map<String, Entity>, status: HaStatus, favourites: List<AppEntry>, others: List<AppEntry>) {
    val dock = remember { FocusRequester() }
    var title by remember { mutableStateOf("") }
    var inGrid by remember { mutableStateOf(false) }
    LaunchedEffect(act.homeTicks) {
        act.moving = null
        act.allApps = false
        delay(80)
        runCatching { dock.requestFocus() }
    }
    // Back from all apps: the slide is driven by allApps, focus has to follow.
    LaunchedEffect(act.allApps) { if (!act.allApps && inGrid) runCatching { dock.requestFocus() } }

    act.layoutVersion
    val cols = act.hearth.prefs.appColumns
    fun moveKeys(a: AppEntry, rows: Boolean): (Key) -> Boolean = { key ->
        if (act.moving != a.pkg) false else {
            when (key) {
                Key.DirectionLeft -> act.hearth.apps.move(a.pkg, -1)
                Key.DirectionRight -> act.hearth.apps.move(a.pkg, 1)
                Key.DirectionUp -> if (rows) act.hearth.apps.move(a.pkg, -cols)
                Key.DirectionDown -> if (rows) act.hearth.apps.move(a.pkg, cols)
                else -> Unit
            }
            key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
        }
    }
    fun click(a: AppEntry) { if (act.moving != null) act.moving = null else act.hearth.apps.launch(a) }
    fun atHome() { inGrid = false; act.allApps = false }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val h = maxHeight
        val goal = if (act.allApps) -h else 0.dp
        val shift by animateDpAsState(goal, tween(300), label = "slide")
        // Hide the selector while the screen slides, so it doesn't fly past the clock on the way.
        val ring = LocalFocusRing.current
        val sliding = kotlin.math.abs((shift - goal).value) > 0.5f
        androidx.compose.runtime.SideEffect { ring?.sliding = sliding }

        // ---- home view
        Column(Modifier.fillMaxWidth().height(h).offset(y = shift)) {
            HaArea(act, entities, status, Modifier.weight(1f)) { atHome(); title = it }
            Text(
                title, color = Palette.text, fontWeight = FontWeight.Light, fontSize = 26.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = SIDE + 8.dp, end = SIDE, top = 2.dp, bottom = 6.dp).height(34.dp),
            )
            Box(
                Modifier.padding(horizontal = SIDE).fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF1C222C).copy(alpha = 0.72f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                    .focusRequester(dock),
            ) {
                // Banner shape from the bar width, but never taller than the old fixed height (keeps room for tiles).
                val favCount = act.hearth.prefs.favouriteCount
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val barTileH = minOf((maxWidth - 28.dp - GAP * (favCount - 1)) / favCount * 9f / 16f, APP_H)
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    for (a in favourites) {
                        AppTile(
                            a, moving = act.moving == a.pkg, modifier = Modifier.weight(1f), showLabel = false, tileHeight = barTileH,
                            onFocused = { atHome(); title = a.label },
                            onMoveKey = moveKeys(a, rows = false),
                            onLongClick = { act.overlay = Overlay.AppActions(a.pkg) },
                        ) { click(a) }
                    }
                    repeat((favCount - favourites.size).coerceAtLeast(0)) {
                        Box(Modifier.weight(1f).height(barTileH).clip(RoundedCornerShape(APP_RADIUS)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(APP_RADIUS)))
                    }
                }
                }
            }
            Text(
                if (others.isEmpty()) "" else "▾  ALL APPS", color = Palette.dim, fontSize = 12.sp, letterSpacing = 3.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
            )
        }

        // ---- all-apps view, one screen below
        Column(Modifier.fillMaxWidth().height(h).offset(y = shift + h)) {
            Text(
                "▴  ALL APPS", color = Palette.dim, fontSize = 12.sp, letterSpacing = 3.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = SIDE + 10.dp)) {
                val inset = 8.dp
                val gap = 14.dp
                val tileW = (maxWidth - inset * 2 - GAP * (cols - 1)) / cols
                val rowH = tileW * 9f / 16f + 32.dp          // banner + the name under the focused one
                val fit = (((maxHeight - 24.dp - inset * 2 + gap) / (rowH + gap)).toInt()).coerceAtLeast(1)
                val rows = (others.size + cols - 1) / cols
                val shown = minOf(fit, rows).coerceAtLeast(1)
                var top by remember { mutableStateOf(0) }
                LaunchedEffect(cols, others.size) { top = 0 }
                Column {
                    PagedRows(others.size, cols, rowH, gap, GAP, shown, top, inset, Modifier.padding(top = 10.dp), arrows = { act.moving == null }) { i ->
                        val a = others[i]
                        AppTile(
                            a, moving = act.moving == a.pkg, modifier = Modifier, tileHeight = tileW * 9f / 16f,
                            onFocused = {
                                inGrid = true
                                act.allApps = true
                                top = pageFor(i / cols, top, shown)
                            },
                            onMoveKey = moveKeys(a, rows = true),
                            onLongClick = { act.overlay = Overlay.AppActions(a.pkg) },
                        ) { click(a) }
                    }
                    Text(
                        if (top + shown < rows) "▾  MORE" else "", color = Palette.dim, fontSize = 11.sp, letterSpacing = 3.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(HINT_H),
                    )
                }
            }
        }
    }
}

/**
 * The Home Assistant tiles, filling the space between the header and the favourites bar.
 * Only whole rows are shown. With more rows than fit, it pages a row at a time as the remote
 * moves, and small arrows say there is more above or below.
 */
@Composable
private fun HaArea(act: HomeActivity, entities: Map<String, Entity>, status: HaStatus, modifier: Modifier, onTitle: (String) -> Unit) {
    act.layoutVersion
    val ids = act.hearth.prefs.entities
    val cols = act.hearth.prefs.haColumns
    var top by remember { mutableStateOf(0) }
    Column(modifier.padding(horizontal = SIDE)) {
        Row(Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("HOME", color = Palette.dim, fontSize = 12.sp, letterSpacing = 3.sp)
            val line = when (status) {
                HaStatus.BadToken -> "Home Assistant rejected the token"
                is HaStatus.Offline -> "Home Assistant offline, retrying"
                HaStatus.Connecting -> "Connecting…"
                else -> ""
            }
            if (line.isNotEmpty()) Text("   $line", color = Palette.bad, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (top > 0) Text("▴  MORE", color = Palette.dim, fontSize = 11.sp, letterSpacing = 3.sp, modifier = Modifier.padding(end = 8.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val inset = 8.dp
            val gap = 10.dp
            // How many whole rows fit, leaving room for the "more" hint underneath.
            val fit = (((maxHeight - HINT_H - inset * 2 + gap) / (TILE_H + gap)).toInt()).coerceAtLeast(1)
            if (status == HaStatus.NotSetUp || ids.isEmpty()) {
                Box(Modifier.padding(inset).width(320.dp)) {
                    WideTile("Add your Home Assistant", "Set up from your phone", onFocused = { onTitle("Home Assistant") }) { act.overlay = Overlay.Setup }
                }
            } else {
                val rows = (ids.size + cols - 1) / cols
                val shown = minOf(fit, rows)
                LaunchedEffect(cols, ids.size) { top = 0 }
                Column {
                    PagedRows(ids.size, cols, TILE_H, gap, 14.dp, shown, top, inset) { i ->
                        val id = ids[i]
                        val e = entities[id]
                        val focused: () -> Unit = {
                            top = pageFor(i / cols, top, shown)
                            e?.let { onTitle(label(act, it)) }
                        }
                        when {
                            e == null -> PlaceholderTile(act.hearth.prefs.names[id] ?: id.substringAfter('.').replace('_', ' '))
                            e.domain == "camera" -> CameraTile(act, e, focused)
                            else -> EntityTile(act, e, focused)
                        }
                    }
                    Text(
                        if (top + shown < rows) "▾  MORE" else "", color = Palette.dim, fontSize = 11.sp, letterSpacing = 3.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(HINT_H),
                    )
                }
            }
        }
    }
}

private val HINT_H = 16.dp

/**
 * A grid that shows [shown] whole rows at a time inside a window and slides the rows (never a cut-off
 * tile) to keep the focused one in view. Every tile stays composed and placed, just clipped, so the
 * remote can always move to the next row; the window then glides one row.
 */
@Composable
private fun PagedRows(
    count: Int,
    cols: Int,
    rowHeight: Dp,
    gap: Dp,
    hGap: Dp,
    shown: Int,
    top: Int,
    inset: Dp,
    modifier: Modifier = Modifier,
    /** False while something else owns the arrows (moving an app). */
    arrows: () -> Boolean = { true },
    cell: @Composable (index: Int) -> Unit,
) {
    val rows = (count + cols - 1) / cols
    val requesters = remember(count) { List(count) { FocusRequester() } }
    val stride = rowHeight + gap
    val goalOffset = -(stride * top)
    val offset by animateDpAsState(goalOffset, tween(MOVE_MS, easing = FastOutSlowInEasing), label = "rows")
    // Tell the tiles how much further the window will move this slide (read when they report their position).
    val pending = remember { PendingShift() }
    pending.dy = with(LocalDensity.current) { (goalOffset - offset).toPx() }
    Box(modifier.fillMaxWidth().height(stride * shown - gap + inset * 2).clipToBounds()) {
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).offset(y = offset + inset).padding(horizontal = inset),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            CompositionLocalProvider(LocalPendingShift provides pending) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(hGap)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        Box(
                            Modifier.weight(1f).then(
                                if (i >= count) Modifier
                                else Modifier.focusRequester(requesters[i]).onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown || !arrows()) return@onPreviewKeyEvent false
                                    // Down goes to the row below even when it's shorter (lands on its last tile);
                                    // Up goes straight up. From the last/first row the remote leaves the grid as normal.
                                    when {
                                        ev.key == Key.DirectionDown && r < rows - 1 -> runCatching { requesters[minOf(i + cols, count - 1)].requestFocus() }.isSuccess
                                        ev.key == Key.DirectionUp && r > 0 -> runCatching { requesters[i - cols].requestFocus() }.isSuccess
                                        else -> false
                                    }
                                }
                            )
                        ) { if (i < count) cell(i) }
                    }
                }
            }
            }
        }
    }
}

/** New first row so [row] is inside a window of [shown] rows starting at [top]. */
private fun pageFor(row: Int, top: Int, shown: Int) = when {
    row < top -> row
    row >= top + shown -> row - shown + 1
    else -> top
}


private val TILE_H = 78.dp
// Dark, slightly see-through tiles: readable over the night sky and over busy photos alike.
private val TILE_BG = Color(0xFF141922).copy(alpha = 0.68f)
private val TILE_LIT = Color(0xFF3B2C17).copy(alpha = 0.82f)
private val TILE_FOCUSED = Color(0xFF353E4C).copy(alpha = 0.92f)
private val APP_H = 92.dp

@Composable
private fun EntityTile(act: HomeActivity, e: Entity, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = e.lit
    Column(
        Modifier
            .fillMaxWidth().height(TILE_H)
            .focusLook(focused, radius = 18)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    focused -> TILE_FOCUSED
                    lit -> TILE_LIT
                    else -> TILE_BG
                }
            )
            .tvClick(
                onFocus = { focused = it; if (it) onFocused() },
                onLongClick = if (e.hasLongPanel) ({ act.overlay = Overlay.Control(e.id) }) else null,
            ) { act.press(e) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (e.available) 1f else 0.45f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(e.icon()), null, tint = if (lit) Palette.accent else Palette.text.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.weight(1f))
            Text(e.stateText(), color = if (lit) Palette.accent else Palette.dim, fontFamily = Outfit, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Text(label(act, e), color = Palette.text, fontFamily = Outfit, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}

@Composable
private fun CameraTile(act: HomeActivity, e: Entity, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, e.id) {
        while (true) { act.snapshot(e.id, 480)?.let { value = it }; delay(15_000) }
    }
    Box(
        Modifier
            .fillMaxWidth().height(TILE_H)
            .focusLook(focused, radius = 18)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .tvClick(onFocus = { focused = it; if (it) onFocused() }) { act.press(e) },
    ) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))))
        Text(label(act, e), color = Color.White, fontFamily = Outfit, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

@Composable
private fun PlaceholderTile(name: String) {
    Box(
        Modifier.fillMaxWidth().height(TILE_H).clip(RoundedCornerShape(18.dp)).background(TILE_BG).padding(14.dp).alpha(0.45f),
        contentAlignment = Alignment.BottomStart,
    ) { Text(name, color = Palette.text, fontFamily = Outfit, fontSize = 17.sp, maxLines = 2) }
}

@Composable
private fun WideTile(title: String, action: String, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth().height(TILE_H)
            .focusLook(focused, radius = 18)
            .clip(RoundedCornerShape(18.dp))
            .background(if (focused) TILE_FOCUSED else TILE_BG)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .tvClick(onFocus = { focused = it; if (it) onFocused() }, onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, color = Palette.text, fontFamily = Outfit, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        Text(action, color = Palette.accent, fontFamily = Outfit, fontSize = 14.sp)
    }
}

@Composable
private fun AppTile(
    a: AppEntry,
    moving: Boolean,
    modifier: Modifier,
    showLabel: Boolean = true,
    tileHeight: Dp? = APP_H,
    onFocused: () -> Unit,
    onMoveKey: (Key) -> Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (tileHeight != null) Modifier.height(tileHeight) else Modifier.aspectRatio(16f / 9f))
                .focusLook(focused || moving, radius = 16)
                .clip(RoundedCornerShape(APP_RADIUS))
                .background(Color(0xFF2A2F37))
                .tvClick(onFocus = { focused = it; if (it) onFocused() }, onLongClick = onLongClick, onKey = onMoveKey, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val banner = remember(a) { a.banner?.asImageBitmap() }
            val icon = remember(a) { a.icon?.asImageBitmap() }
            when {
                banner != null -> Image(banner, a.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    icon?.let { Image(it, null, modifier = Modifier.size(44.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Text(a.label, color = Palette.text, fontFamily = Outfit, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            if (moving) Box(Modifier.fillMaxSize().background(Palette.accent.copy(alpha = 0.28f)))
        }
        // Name under the focused app, like the old launcher.
        if (showLabel) Text(
            if (focused || moving) a.label else "", color = Palette.text, fontFamily = Outfit, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).height(20.dp),
        )
    }
}

@Composable
private fun Toast(act: HomeActivity) {
    val text = act.toast
    LaunchedEffect(text) { if (text.isNotEmpty()) { delay(2_500); act.toast = "" } }
    if (text.isEmpty()) return
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        Text(text, color = Palette.text, fontFamily = Outfit, fontSize = 16.sp, modifier = Modifier
            .clip(RoundedCornerShape(24.dp)).background(Color(0xEE24282E)).padding(horizontal = 22.dp, vertical = 12.dp))
    }
}
