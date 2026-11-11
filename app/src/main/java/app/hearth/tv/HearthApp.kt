package app.hearth.tv

import android.app.Application
import android.content.Context
import app.hearth.tv.apps.AppRepository
import app.hearth.tv.ha.HaClient
import app.hearth.tv.setup.SetupServer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class HearthApp : Application() {
    lateinit var prefs: Prefs
    lateinit var http: OkHttpClient
    lateinit var ha: HaClient
    lateinit var apps: AppRepository
    lateinit var setup: SetupServer
    lateinit var network: NetworkMonitor
    lateinit var wallpapers: Wallpapers

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
        ha = HaClient(http, prefs)
        apps = AppRepository(this, prefs)
        setup = SetupServer(this).also { it.start() }
        network = NetworkMonitor(this)
        wallpapers = Wallpapers(this, http, prefs)
    }
}

val Context.hearth get() = applicationContext as HearthApp

/** Everything Hearth remembers. The token stays on the device (allowBackup=false). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hearth", Context.MODE_PRIVATE)

    var haUrl: String
        get() = sp.getString("ha_url", "").orEmpty()
        set(v) = sp.edit().putString("ha_url", v.trim().trimEnd('/')).apply()
    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v.trim()).apply()
    /** Entity ids shown on the home screen, in order. */
    var entities: List<String>
        get() = list("entities")
        set(v) = putList("entities", v)
    /** Optional short names for tiles, entity id -> name. */
    var names: Map<String, String>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("names", "{}")!!)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = sp.edit().putString("names", org.json.JSONObject(v).toString()).apply()
    /** Weather entity for the header ("" = first weather.* found). */
    var weather: String
        get() = sp.getString("weather", "").orEmpty()
        set(v) = sp.edit().putString("weather", v).apply()
    /** Package names in the order the user arranged them; new apps go at the end. */
    var appOrder: List<String>
        get() = list("app_order")
        set(v) = putList("app_order", v)
    /** Apps in the bar along the bottom, in order; null until first set. */
    var favourites: List<String>?
        get() = if (sp.contains("favourites")) list("favourites") else null
        set(v) = putList("favourites", v.orEmpty())
    var hiddenApps: Set<String>
        get() = list("hidden_apps").toSet()
        set(v) = putList("hidden_apps", v.toList())

    /** The code the phone setup page asks for. Made once, so a saved link keeps working. */
    val setupCode: String
        get() = sp.getString("setup_code", null) ?: kotlin.random.Random.nextInt(100_000, 1_000_000).toString()
            .also { sp.edit().putString("setup_code", it).apply() }

    /** One of [app.hearth.tv.ui.WALLPAPERS]: built-in pictures, "bing" (photo of the day slideshow) or "solid". */
    var wallpaper: String
        get() = sp.getString("wallpaper", "sky")!!
        set(v) = sp.edit().putString("wallpaper", v).apply()
    /** Minutes between pictures in the slideshow. */
    var wallpaperMinutes: Int
        get() = sp.getInt("wallpaper_minutes", 15)
        set(v) = sp.edit().putInt("wallpaper_minutes", v).apply()
    /** Home Assistant tiles per row on the home screen. */
    var haColumns: Int
        get() = sp.getInt("ha_columns", 5)
        set(v) = sp.edit().putInt("ha_columns", v).apply()
    /** How many apps the favourites bar holds. */
    var favouriteCount: Int
        get() = sp.getInt("favourite_count", 4)
        set(v) = sp.edit().putInt("favourite_count", v).apply()
    /** Rows of Home Assistant tiles shown at once (0 = as many as fit); the rest page in with MORE. */
    var haRows: Int
        get() = sp.getInt("ha_rows", 0)
        set(v) = sp.edit().putInt("ha_rows", v).apply()
    /** Apps per row in the all-apps view. */
    var appColumns: Int
        get() = sp.getInt("app_columns", 4)
        set(v) = sp.edit().putInt("app_columns", v).apply()
    /** Which of [app.hearth.tv.ui.SOLID_COLOURS] for the "solid" wallpaper. */
    var solidColour: Int
        get() = sp.getInt("solid_colour", 0)
        set(v) = sp.edit().putInt("solid_colour", v).apply()
    var wallpaperIndex: Int
        get() = sp.getInt("wallpaper_index", 0)
        set(v) = sp.edit().putInt("wallpaper_index", v).apply()
    var wallpaperCache: String
        get() = sp.getString("wallpaper_cache", "[]")!!
        set(v) = sp.edit().putString("wallpaper_cache", v).apply()

    val configured get() = haUrl.isNotEmpty() && token.isNotEmpty()

    private fun list(key: String) = sp.getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }
    private fun putList(key: String, v: List<String>) = sp.edit().putString(key, v.joinToString("\n")).apply()
}
