package net.tamasoft.lifelog

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import net.tamasoft.lifelog.core.Phone
import net.tamasoft.lifelog.core.Usage
import net.tamasoft.lifelog.core.UsageEvent
import org.json.JSONObject
import java.time.ZoneId

/** Android's own records: the usage history, the call history and the SMS store. */
object UsageSources {
    private val KINDS = setOf(Usage.ACTIVITY_RESUMED, Usage.ACTIVITY_PAUSED, Usage.ACTIVITY_STOPPED,
        Usage.SCREEN_NON_INTERACTIVE, Usage.KEYGUARD_SHOWN, Usage.KEYGUARD_HIDDEN, Usage.DEVICE_SHUTDOWN)

    fun events(c: Context, from: Long, to: Long): List<UsageEvent> {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val it = usm.queryEvents(from, to) ?: return emptyList()
        val e = UsageEvents.Event()
        val out = ArrayList<UsageEvent>()
        while (it.hasNextEvent()) {
            it.getNextEvent(e)
            if (e.eventType in KINDS) out.add(UsageEvent(e.timeStamp, e.eventType, e.packageName ?: ""))
        }
        return out
    }

    private val labels = HashMap<String, String>()

    fun label(c: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Calls in [from, to), each with the moment it began. */
    fun calls(c: Context, from: Long, to: Long, zone: ZoneId): List<Pair<Long, JSONObject>> {
        val out = ArrayList<Pair<Long, JSONObject>>()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} < ?",
            arrayOf(from.toString(), to.toString()), "${CallLog.Calls.DATE} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val date = cur.getLong(0)
                out.add(date to Phone.call(date, cur.getLong(1), cur.getInt(2), cur.getString(3), cur.getString(4), zone))
            }
        } ?: error("the call history could not be read")
        return out
    }

    /** SMS in [from, to). A name comes from the contacts when they may be read. */
    fun sms(c: Context, from: Long, to: Long, zone: ZoneId): List<Pair<Long, JSONObject>> {
        val names = HashMap<String, String?>()
        val contacts = Perms.granted(c, Manifest.permission.READ_CONTACTS)
        fun name(address: String?): String? {
            if (!contacts || address.isNullOrBlank() || Phone.party(address) == "service") return null
            return names.getOrPut(address) {
                runCatching {
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                    c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
            }
        }
        val out = ArrayList<Pair<Long, JSONObject>>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?",
            arrayOf(from.toString(), to.toString()), "${Telephony.Sms.DATE} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val date = cur.getLong(0)
                val address = cur.getString(2)
                Phone.sms(date, cur.getInt(1), address, name(address), cur.getString(3), zone)?.let { out.add(date to it) }
            }
        } ?: error("the SMS store could not be read")
        return out
    }
}
