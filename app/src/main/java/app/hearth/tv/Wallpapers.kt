package app.hearth.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One background picture and who to credit for it. */
data class Photo(val url: String, val credit: String)

/**
 * Background pictures for the home screen.
 * Source: Bing's photo of the day - large, landscape, well-shot, no account or key needed, about 16 days at a time.
 * Pictures are cached on the TV so a slideshow doesn't download the same file twice.
 */
class Wallpapers(context: Context, private val http: OkHttpClient, private val prefs: Prefs) {
    private val dir = File(context.cacheDir, "wallpapers").apply { mkdirs() }
    private var cached: List<Photo> = emptyList()
    private var cachedAt = 0L

    suspend fun photos(): List<Photo> = withContext(Dispatchers.IO) {
        if (cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < 6 * 3600_000L) return@withContext cached
        val list = runCatching { (bing(0) + bing(8)).distinctBy { it.url } }
            .onFailure { Log.w(TAG, "wallpaper list failed", it) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) {
            cached = list
            cachedAt = System.currentTimeMillis()
            prefs.wallpaperCache = JSONArray(list.map { JSONObject().put("url", it.url).put("credit", it.credit) }).toString()
            list
        } else {
            // Offline: fall back to the last list we saw (the pictures themselves are cached).
            runCatching {
                val a = JSONArray(prefs.wallpaperCache)
                List(a.length()) { a.getJSONObject(it).let { o -> Photo(o.getString("url"), o.optString("credit")) } }
            }.getOrDefault(emptyList())
        }
    }

    private fun bing(from: Int): List<Photo> {
        val req = Request.Builder().url("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$from&n=8&mkt=en-GB").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val images = JSONObject(r.body!!.string()).optJSONArray("images") ?: return emptyList()
            return List(images.length()) { i ->
                val o = images.getJSONObject(i)
                Photo("https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg", o.optString("copyright"))
            }
        }
    }

    /** The picture, from the cache or downloaded, sized for a 1080p screen. */
    suspend fun load(photo: Photo): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(dir, sha(photo.url) + ".jpg")
        if (!file.exists()) {
            runCatching {
                http.newCall(Request.Builder().url(photo.url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val tmp = File(dir, file.name + ".part")
                    tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                    tmp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "download failed", it); return@withContext null }
            trim()
        }
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    /** Keeps the cache to the newest 24 pictures. */
    private fun trim() {
        dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { private const val TAG = "Wallpapers" }
}
