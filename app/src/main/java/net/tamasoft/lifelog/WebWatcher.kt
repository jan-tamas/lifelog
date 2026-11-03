package net.tamasoft.lifelog

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import net.tamasoft.lifelog.core.Visit
import net.tamasoft.lifelog.core.Web

/**
 * The browser watcher. Android delivers it events from the BROWSERS listed
 * below and from nothing else (res/xml/web_watcher.xml names the same
 * packages), and from those it reads two things: the address bar, which is cut
 * to its site here, before anything is kept, and the page title.
 *
 * It keeps a line only when the page changes or a browser comes to the front.
 * How long a page was looked at is worked out later from the usage history,
 * which says when the browser left the front; the watcher never sees other apps.
 */
class WebWatcher : AccessibilityService() {
    private val last = HashMap<String, Triple<String, String, String?>>()
    private var lastAt = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val ids = BROWSERS[pkg] ?: return
        val now = System.currentTimeMillis()
        val front = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!front && now - lastAt < 1_000) return
        lastAt = now
        val prefs = Prefs(this)
        if (!prefs.on(Module.USAGE) || !prefs.part(UsagePart.WEB)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val bar = ids.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()
        } ?: return
        if (bar.isFocused) return                      // being typed into
        val text = bar.text?.toString() ?: return
        val site = Web.site(text) ?: return
        val title = title(root)
        val video = Web.videoId(text)
        val key = Triple(site, title, video)
        if (!front && last[pkg] == key) return
        last[pkg] = key
        RecordLog(this).visit(Visit(now, pkg, site, title, video))
    }

    /** The page title: the web content's own node carries it. Bounded search. */
    private fun title(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val n = queue.removeFirst()
            seen++
            if (n.className?.toString() == "android.webkit.WebView") {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty()) return t.take(300)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return ""
    }

    override fun onInterrupt() {}

    companion object {
        /** Browser package → the id of its address bar. */
        val BROWSERS = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.chrome.dev" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.vivaldi.browser" to listOf("url_bar"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        )
    }
}
