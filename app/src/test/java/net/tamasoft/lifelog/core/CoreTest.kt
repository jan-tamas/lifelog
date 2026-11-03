package net.tamasoft.lifelog.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WebTest {
    @Test fun aSiteIsTheHostAlone() {
        assertEquals("example.com", Web.site("https://www.example.com/reset?token=secret"))
        assertEquals("example.com", Web.site("EXAMPLE.com:8080/x"))
        assertEquals("seznam.cz", Web.site("m.seznam.cz"))
        assertEquals("youtube.com", Web.site("youtu.be/abc"))
        assertEquals("news.ycombinator.com", Web.site("news.ycombinator.com/item?id=1"))
    }

    @Test fun halfTypedSearchIsNoSite() {
        assertNull(Web.site("weather prague"))
        assertNull(Web.site("localhost"))
        assertNull(Web.site(""))
        assertNull(Web.site(null))
    }

    @Test fun aYoutubePageKeepsOnlyItsVideoId() {
        assertEquals("dQw4w9WgXcQ", Web.videoId("m.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("abc_DEF-1", Web.videoId("https://youtu.be/abc_DEF-1?si=x"))
        assertEquals("short1", Web.videoId("youtube.com/shorts/short1"))
        assertNull(Web.videoId("example.com/watch?v=nope"))
    }
}

class PhoneTest {
    private val prague = ZoneId.of("Europe/Prague")

    @Test fun aServiceIsANameOrAShortNumber() {
        assertEquals("service", Phone.party("mBank"))
        assertEquals("service", Phone.party("7210"))
        assertEquals("person", Phone.party("+420 777 123 456"))
    }

    @Test fun onlyAPersonsWordsTravel() {
        val p = Phone.sms(1_762_000_000_000, 1, "+420777123456", "Alice", "Hi 👋", prague)!!
        assertEquals("Hi 👋", p.getString("text"))
        assertEquals(4, p.getInt("len"))           // characters, not UTF-16 units
        assertEquals("Alice", p.getString("who"))
        val s = Phone.sms(1_762_000_000_000, 1, "mBank", null, "Your code is 1234", prague)!!
        assertFalse(s.has("text"))
        assertEquals("mBank", s.getString("who"))
        assertNull(Phone.sms(1_762_000_000_000, 3, "+420777123456", null, "draft", prague))
    }

    @Test fun anUnsavedNumberIsWho() {
        val c = Phone.call(1_762_000_000_000, 35, 3, null, "+420777123456", prague)
        assertEquals("missed", c.getString("dir"))
        assertEquals("+420777123456", c.getString("who"))
        assertTrue(c.getString("ts").endsWith("+01:00"))
    }
}

class TimesTest {
    @Test fun spellings() {
        assertEquals("2026-11-02T23:00:53.000Z", Times.utcMillis(Instant.parse("2026-11-02T23:00:53Z").toEpochMilli()))
        assertEquals("2026-07-01T12:00:00+02:00",
            Times.localSeconds(Instant.parse("2026-07-01T10:00:00Z").toEpochMilli(), ZoneId.of("Europe/Prague")))
        assertEquals("2026-01-01T10:00:00+00:00",
            Times.localSeconds(Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(), ZoneId.of("UTC")))
        assertEquals(3.247, Times.seconds(3247), 1e-9)
    }
}

class UsageTest {
    private val z = ZoneId.of("Europe/Prague")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun aSessionRunsFromResumeToPause() {
        val s = Usage.sessions(listOf(
            UsageEvent(ms("2026-11-03T08:00:00Z"), Usage.ACTIVITY_RESUMED, "a"),
            UsageEvent(ms("2026-11-03T08:01:00Z"), Usage.ACTIVITY_PAUSED, "a"),
            UsageEvent(ms("2026-11-03T08:01:00.400Z"), Usage.ACTIVITY_RESUMED, "a"),   // same app: one session
            UsageEvent(ms("2026-11-03T08:02:00Z"), Usage.ACTIVITY_PAUSED, "a"),
            UsageEvent(ms("2026-11-03T08:02:00.100Z"), Usage.ACTIVITY_RESUMED, "b"),
            UsageEvent(ms("2026-11-03T08:03:00Z"), Usage.SCREEN_NON_INTERACTIVE, ""),
            UsageEvent(ms("2026-11-03T08:10:00Z"), Usage.ACTIVITY_RESUMED, "c"),
        ))
        assertEquals(3, s.size)
        assertEquals(Session("a", ms("2026-11-03T08:00:00Z"), ms("2026-11-03T08:02:00Z")), s[0])
        assertEquals(Session("b", ms("2026-11-03T08:02:00.100Z"), ms("2026-11-03T08:03:00Z")), s[1])
        assertNull(s[2].end)                        // still in front: not sent yet
    }

    @Test fun aSessionAcrossMidnightIsSplit() {
        val start = ms("2026-11-02T22:50:00Z")      // 23:50 in Prague
        val end = ms("2026-11-02T23:20:00Z")        // 00:20
        val sessions = listOf(Session("a", start, end))
        val d1 = Usage.dayEvents("2026-11-02", z, sessions, { "A" }, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val d2 = Usage.dayEvents("2026-11-03", z, sessions, { "A" }, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(600.0, d1.single().getDouble("dur"), 1e-9)
        assertEquals(1200.0, d2.single().getDouble("dur"), 1e-9)
        assertEquals("2026-11-02T23:00:00.000Z", d2.single().getString("ts"))
    }

    @Test fun aPageEndsWhenItsBrowserLeavesTheFront() {
        val chrome = "com.android.chrome"
        val sessions = listOf(Session(chrome, ms("2026-11-03T08:00:00Z"), ms("2026-11-03T08:05:00Z")))
        val visits = listOf(
            Visit(ms("2026-11-03T08:00:10Z"), chrome, "example.com", "Example", null),
            Visit(ms("2026-11-03T08:00:40Z"), chrome, "example.com", "Example", null),   // same page again
            Visit(ms("2026-11-03T08:02:00Z"), chrome, "seznam.cz", "Seznam", null),
        )
        val r = Usage.resolveVisits(visits, sessions, ms("2026-11-03T09:00:00Z"))
        assertEquals(2, r.size)
        assertEquals(ms("2026-11-03T08:02:00Z"), r[0].third)
        assertEquals(ms("2026-11-03T08:05:00Z"), r[1].third)
    }

    @Test fun noDayBeforeTheFirstIsSent() {
        val built = mapOf("2026-11-01" to "a", "2026-11-02" to "b", "2026-11-03" to "c", "2026-11-04" to "d")
        val sent = mapOf("2026-11-02" to Usage.md5("b"))
        assertEquals(listOf("2026-11-03", "2026-11-04"), Usage.daysToSend("2026-11-04", "2026-11-03", built, sent))
        val sentAll = sent + ("2026-11-01" to Usage.md5("a"))
        assertEquals(listOf("2026-11-03", "2026-11-04"), Usage.daysToSend("2026-11-04", "2026-11-01", built, sentAll))
        assertEquals(listOf("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04"),
            Usage.daysToSend("2026-11-04", "2026-11-01", built, emptyMap()))
    }
}

class PairingTest {
    private val cfg = PairConfig("https://vault.example.ts.net:8443", "Europe/Prague",
        mapOf("health" to "h", "location" to "l", "usage" to "u", "photos" to "p"), "AB",
        "owntracks/user/phone", listOf(Region("Home", 50.0, 14.4, 100, 1)), setOf("com.google.android.youtube"))

    @Test fun aLinkRoundTrips() {
        val code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(cfg.toJson().toByteArray())
        val back = PairConfig.parse(PairConfig.SCHEME_PREFIX + code)
        assertEquals(cfg, back)
        assertEquals("vault.example.ts.net", back.host)
        assertEquals(cfg, PairConfig.parse(cfg.toJson()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainHttpIsRefused() {
        PairConfig.parse(cfg.toJson().replace("https://", "http://"))
    }
}

class FileQueueTest {
    @Test fun oldestFirstAndRefusedAside() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val q = FileQueue(dir)
        val a = q.add("1"); q.add("2")
        assertEquals(listOf("1", "2"), q.pending().map { it.readText() })
        q.refuse(a)
        assertEquals(1, q.size()); assertEquals(1, q.refusedCount())
        q.done(q.pending().first())
        assertEquals(0, q.size())
    }
}

class OwnTracksTest {
    @Test fun aFixInsideARegionSaysSo() {
        val f = Fix(1_762_000_000_000, 50.0001, 14.4001, 12.4f, 230.0, 3f, 2f, 90f)
        val o = OwnTracks.location(f, "AB", "owntracks/user/phone", 80, 1, "w",
            listOf(Region("Home", 50.0, 14.4, 100, 1)), "abcd1234")
        assertEquals("Home", o.getJSONArray("inregions").getString(0))
        assertEquals(7, o.getInt("vel"))
        assertEquals(1_762_000_000L, o.getLong("tst"))
        val t = OwnTracks.transition(f, "AB", "owntracks/user/phone", Region("Home", 50.0, 14.4, 100, 1), false, "x")
        assertEquals("leave", t.getString("event"))
        assertEquals("owntracks/user/phone/event", t.getString("topic"))
    }
}

/**
 * Writes one example of everything this app sends, for the vault's contract
 * test (honza-vault tests/test_lifelog_contract.py), which feeds them through
 * the receiver's own parsers. Run, then copy build/contract/ into the vault's
 * tests/fixtures/lifelog/.
 */
class ContractFixtures {
    private val z = ZoneId.of("Europe/Prague")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun write() {
        val out = java.io.File(System.getProperty("contract.out") ?: "build/contract").apply { mkdirs() }
        val chrome = "com.android.chrome"
        val sessions = listOf(Session(chrome, ms("2026-11-03T08:00:00Z"), ms("2026-11-03T08:05:00Z")))
        val visits = Usage.resolveVisits(listOf(Visit(ms("2026-11-03T08:00:10Z"), chrome, "example.com",
            "Example Domain", null), Visit(ms("2026-11-03T08:01:10Z"), chrome, "youtube.com", "A video",
            "dQw4w9WgXcQ")), sessions, ms("2026-11-03T09:00:00Z"))
        val media = listOf(MediaSegment(ms("2026-11-03T08:01:20Z"), ms("2026-11-03T08:03:00Z"),
            "com.google.android.youtube", "A video", "A channel", "playing"))
        val calls = listOf(ms("2026-11-03T09:00:00Z") to Phone.call(ms("2026-11-03T09:00:00Z"), 42, 2, "Alice", "+420777123456", z))
        val sms = listOfNotNull(
            Phone.sms(ms("2026-11-03T09:10:00Z"), 1, "+420777123456", "Alice", "See you at six", z)?.let { ms("2026-11-03T09:10:00Z") to it },
            Phone.sms(ms("2026-11-03T09:11:00Z"), 1, "mBank", null, "Code 1234", z)?.let { ms("2026-11-03T09:11:00Z") to it })
        val events = Usage.dayEvents("2026-11-03", z, sessions, { "Chrome" }, listOf(ms("2026-11-03T07:59:58Z")),
            visits, media, calls, sms)
        java.io.File(out, "usage.ndjson").writeText(Usage.snapshot("2026-11-03", events) + "\n")

        val f = Fix(ms("2026-11-03T08:00:00Z"), 50.0001, 14.4001, 12f, 230.0, 3f, 0f, null)
        val home = Region("Home", 50.0, 14.4, 100, 1_790_000_000)
        java.io.File(out, "location.json").writeText(
            OwnTracks.location(f, "AB", "owntracks/user/phone", 81, 1, "w", listOf(home), "0a1b2c3d").toString())
        java.io.File(out, "transition.json").writeText(
            OwnTracks.transition(f, "AB", "owntracks/user/phone", home, true, "4e5f6a7b").toString())

        val m = Meta("com.fitbit.FitbitMobile", 2, id = "rec-1", lastModified = Instant.parse("2026-11-03T08:00:00Z"),
            manufacturer = "Google", model = "Pixel Watch 3", deviceType = 1)
        val t0 = Instant.parse("2026-11-03T07:00:00Z")
        val t1 = Instant.parse("2026-11-03T07:01:00Z")
        val recs = mapOf(
            "steps" to listOf(HealthJson.interval("count", 120, t0, t1, m)),
            "distance" to listOf(HealthJson.interval("meters", 95.5, t0, t1, m)),
            "total_calories" to listOf(HealthJson.interval("calories", 1.6, t0, t1, m)),
            "heart_rate" to listOf(HealthJson.sample("bpm", 72, t0, m)),
            "resting_heart_rate" to listOf(HealthJson.sample("bpm", 58, t0, m)),
            "respiratory_rate" to listOf(HealthJson.sample("rate", 14.5, t0, m)),
            "heart_rate_variability" to listOf(HealthJson.sample("rmssd_millis", 41.2, t0, m)),
            "sleep" to listOf(HealthJson.sleep(Instant.parse("2026-11-02T22:00:00Z"), Instant.parse("2026-11-03T05:30:00Z"),
                listOf(HealthJson.Stage(4, Instant.parse("2026-11-02T22:00:00Z"), Instant.parse("2026-11-03T01:00:00Z")),
                    HealthJson.Stage(5, Instant.parse("2026-11-03T01:00:00Z"), Instant.parse("2026-11-03T05:30:00Z"))), m)),
            "exercise" to listOf(HealthJson.exercise(79, "Walk", t0, Instant.parse("2026-11-03T07:30:00Z"), 2400.0, 3100, m)),
            "skin_temperature" to listOf(HealthJson.skinTemperature(t0, -0.3, 33.1, 0, m)),
            "mindfulness" to listOf(HealthJson.mindfulness("meditation", "Meditation", null, t0,
                Instant.parse("2026-11-03T08:30:00Z"), m.copy(origin = "net.tamasoft.meditationbell"))),
        )
        java.io.File(out, "health.json").writeText(HealthJson.payload(recs, "lifelog 0.1", Instant.parse("2026-11-03T09:00:00Z")))
        assertTrue(JSONObject(java.io.File(out, "health.json").readText()).has("mindfulness"))
    }
}
