package net.tamasoft.lifelog.core

import org.json.JSONObject

/**
 * Calls and SMS, as the receiver's phone store accepts them: start, length,
 * direction and WHO (the contact's name, or the number when it is not saved).
 * An SMS adds `party` (person or service) and its length in characters; its
 * words travel only from a PERSON. A service (a bank, a courier, a login code:
 * a sender that is a name or a number of fewer than seven digits) never sends
 * its words off the phone.
 */
object Phone {
    private val LETTER = Regex("[A-Za-z]")

    fun callDir(type: Int): String = when (type) {
        1 -> "in"
        2 -> "out"
        3 -> "missed"
        4 -> "voicemail"
        5 -> "rejected"
        6 -> "blocked"
        else -> "unknown"
    }

    /** Null for a draft, which is not a message and is not sent. */
    fun smsDir(type: Int): String? = when (type) {
        1 -> "in"
        2 -> "out"
        3 -> null
        4 -> "queued"
        5 -> "failed"
        6 -> "queued"
        else -> "unknown"
    }

    fun party(address: String?): String {
        val a = address ?: ""
        return if (LETTER.containsMatchIn(a) || a.count { it.isDigit() } < 7) "service" else "person"
    }

    fun who(name: String?, number: String?): String =
        if (!name.isNullOrBlank() && name != "UNKNOWN_CALLER") name else (number ?: "")

    fun call(epochMs: Long, durationS: Long, type: Int, name: String?, number: String?,
             zone: java.time.ZoneId): JSONObject = JSONObject()
        .put("src", "call")
        .put("ts", Times.localSeconds(epochMs, zone))
        .put("dur", durationS)
        .put("dir", callDir(type))
        .put("who", who(name, number))

    /** Null for a draft. */
    fun sms(epochMs: Long, type: Int, address: String?, name: String?, body: String?,
            zone: java.time.ZoneId): JSONObject? {
        val dir = smsDir(type) ?: return null
        val party = party(address)
        val text = body ?: ""
        val o = JSONObject()
            .put("src", "sms")
            .put("ts", Times.localSeconds(epochMs, zone))
            .put("dir", dir)
            .put("who", who(name, address))
            .put("party", party)
            .put("len", text.codePointCount(0, text.length))
        if (party == "person") o.put("text", text)
        return o
    }
}
