package app.hearth.tv.fire

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.hearth.tv.ui.HomeActivity

/**
 * Fire OS will not let another app be the home screen: Amazon's launcher keeps Home, and
 * `cmd package set-home-activity` prints Success but changes nothing. This accessibility service
 * watches only Amazon's launcher and brings Hearth back whenever Amazon's home screen appears
 * (after Home, after Back out of an app, and at boot). Amazon's launcher stays installed and enabled.
 *
 * It reads nothing but window changes from com.amazon.tv.launcher (see res/xml/fire_home_service.xml)
 * and does nothing on a TV without Amazon's launcher, so it is harmless on the SHIELD.
 *
 * Android holds back an activity start from a background app for about 5 s after Home is pressed.
 * To get round that, it first presses Hearth's own tile in Amazon's apps row (the launcher itself is
 * allowed to start apps straight away). If Hearth isn't pinned there, it starts Hearth directly,
 * which Android then runs once that 5 s hold is over.
 */
class FireHomeService : AccessibilityService() {

    private var lastRedirect = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != AMAZON_LAUNCHER) return
        // Only Amazon's home itself; its search, Live and detail pages are left alone.
        val cls = event.className?.toString().orEmpty()
        if (!cls.contains("HomeActivity")) return
        val now = SystemClock.elapsedRealtime()
        if (now < passUntil || now - lastRedirect < DEBOUNCE_MS) return
        lastRedirect = now
        if (!pressHearthTile()) startHearth()
    }

    /** Clicks the "Hearth" tile in Amazon's apps row, if there is one. */
    private fun pressHearthTile(): Boolean {
        val root = rootInActiveWindow ?: return false
        val label = applicationInfo.loadLabel(packageManager).toString()
        val nodes = root.findAccessibilityNodeInfosByText(label)
        for (n in nodes) {
            val text = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!text.equals(label, ignoreCase = true)) continue
            var target: AccessibilityNodeInfo? = n
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun startHearth() {
        runCatching {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val DEBOUNCE_MS = 1500L
        @Volatile private var passUntil = 0L

        /** Lets Amazon's home stay on screen for a moment, e.g. when Fire OS routes Settings through it. */
        fun passThrough(ms: Long = 4000L) {
            passUntil = SystemClock.elapsedRealtime() + ms
        }
    }
}
