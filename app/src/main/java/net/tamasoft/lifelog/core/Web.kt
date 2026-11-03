package net.tamasoft.lifelog.core

import java.util.Locale

/**
 * The privacy boundary for web pages, computed on the phone before anything is
 * kept: the SITE (the host, without www./m./mobile.) and never the path or the
 * query, which can carry far more than a person meant to give (a reset link
 * carries its token in it). A YouTube page keeps only its video id. Mirrors the
 * rules of the Termux script this app replaces and the receiver's own check,
 * which refuses anything else.
 */
object Web {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val AFTER_HOST = Regex("[/?#:].*$", RegexOption.DOT_MATCHES_ALL)
    private val PREFIX = Regex("^(www[0-9]*|m|mobile)\\.")
    private val HOST = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
    private val YOUTUBE = Regex(
        "^([A-Za-z][A-Za-z0-9+.-]*://)?([A-Za-z0-9_-]+\\.)*(youtube\\.com|youtu\\.be)[/?#]",
        RegexOption.IGNORE_CASE)
    private val V_PARAM = Regex("[?&]v=([A-Za-z0-9_-]+)")
    private val V_PATH = Regex("(youtu\\.be|/shorts)/([A-Za-z0-9_-]+)")

    /** The site of an address-bar text, or null when it is not a real host
     *  (an address bar can hold half-typed search text). */
    fun site(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var s = url.trim().replace(SCHEME, "").replace(AFTER_HOST, "").lowercase(Locale.ROOT)
        s = s.replace(PREFIX, "")
        if (s == "youtu.be") s = "youtube.com"
        return if (HOST.matches(s)) s else null
    }

    /** A YouTube video id, or null. */
    fun videoId(url: String?): String? {
        if (url.isNullOrBlank() || !YOUTUBE.containsMatchIn(url.trim())) return null
        val u = url.trim()
        V_PARAM.find(u)?.let { return it.groupValues[1] }
        V_PATH.find(u)?.let { return it.groupValues[2] }
        return null
    }
}
