package app.hearth.tv.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.hearth.tv.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** An app on the home screen. [banner] is the 16:9 TV banner, or null when the app only has an icon. */
class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val banner: Bitmap?,
    val icon: Bitmap?,
)

class AppRepository(private val context: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Visible apps in the user's order. */
    val apps: StateFlow<List<AppEntry>> = _apps
    private val _all = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Every launchable app, hidden ones included (for the settings list). */
    val all: StateFlow<List<AppEntry>> = _all
    private val _favourites = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Up to [Prefs.favouriteCount] apps in the bar along the bottom. */
    val favourites: StateFlow<List<AppEntry>> = _favourites
    private val _others = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Everything visible that isn't a favourite: the grid below the bar. */
    val others: StateFlow<List<AppEntry>> = _others

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch {
            val pm = context.packageManager
            val found = LinkedHashMap<String, AppEntry>()
            // TV apps first (they have banners), then phone-style apps that also run on the TV.
            for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val ai = ri.activityInfo
                    if (ai.packageName == context.packageName || ai.packageName in found) continue
                    val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
                    found[ai.packageName] = AppEntry(
                        pkg = ai.packageName,
                        component = ComponentName(ai.packageName, ai.name),
                        label = ri.loadLabel(pm).toString(),
                        banner = banner?.toBitmap(320, 180),
                        icon = if (banner == null) runCatching { ri.loadIcon(pm).toBitmap(192, 192) }.getOrNull() else null,
                    )
                }
            }
            val order = prefs.appOrder
            val sorted = found.values.sortedWith(
                compareBy<AppEntry> { order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.banner == null }
                    .thenBy { it.label.lowercase() }
            )
            _all.value = sorted
            val hidden = prefs.hiddenApps
            publish(sorted.filter { it.pkg !in hidden })
        }
    }

    private fun publish(visible: List<AppEntry>) {
        _apps.value = visible
        // First run: the first four apps become favourites, so the bar is never empty.
        val all = prefs.favourites ?: visible.take(prefs.favouriteCount).map { it.pkg }.also { prefs.favourites = it }
        // A smaller bar just shows the first few; the rest go back to the all-apps grid (and return if the bar grows).
        val fav = all.take(prefs.favouriteCount)
        val byPkg = visible.associateBy { it.pkg }
        _favourites.value = fav.mapNotNull { byPkg[it] }
        _others.value = visible.filter { it.pkg !in fav }
    }

    fun isFavourite(pkg: String) = pkg in prefs.favourites.orEmpty().take(prefs.favouriteCount)

    /** Re-sorts favourites and the grid after the bar size changes. */
    fun relayout() = publish(_apps.value)

    /** Adds or removes a favourite. Returns false when the bar is already full. */
    fun toggleFavourite(pkg: String): Boolean {
        val fav = prefs.favourites.orEmpty().take(prefs.favouriteCount)
        prefs.favourites = when {
            pkg in fav -> fav - pkg
            fav.size >= prefs.favouriteCount -> return false
            else -> fav + pkg
        }
        publish(_apps.value)
        return true
    }

    fun launch(app: AppEntry) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: pm.getLaunchIntentForPackage(app.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.component)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Moves an app by [by] places within its own group (the favourites bar or the grid) and remembers it. */
    fun move(pkg: String, by: Int) {
        val fav = prefs.favourites.orEmpty().take(prefs.favouriteCount).toMutableList()
        if (pkg in fav) {
            val i = fav.indexOf(pkg)
            val j = i + by
            if (j !in fav.indices) return
            fav.add(j, fav.removeAt(i))
            prefs.favourites = fav
            publish(_apps.value)
            return
        }
        val list = _others.value.map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        prefs.appOrder = list + prefs.appOrder.filter { it !in list }
        val byPkg = _apps.value.associateBy { it.pkg }
        publish(_favourites.value + list.mapNotNull { byPkg[it] })
    }

    fun setHidden(pkg: String, hidden: Boolean) {
        prefs.hiddenApps = if (hidden) prefs.hiddenApps + pkg else prefs.hiddenApps - pkg
        refresh()
    }

    fun openAndroidSettings() {
        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isInstalled(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun openNetworkSettings() {
        val wifi = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(wifi) }.onFailure { openAndroidSettings() }
    }

    companion object {
    }

    private fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
        val bw = if (intrinsicWidth > 0) intrinsicWidth.coerceAtMost(w * 2) else w
        val bh = if (intrinsicHeight > 0) intrinsicHeight.coerceAtMost(h * 2) else h
        val bmp = createBitmap(bw, bh)
        setBounds(0, 0, bw, bh)
        draw(Canvas(bmp))
        return bmp
    }

}
