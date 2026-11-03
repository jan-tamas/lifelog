package net.tamasoft.lifelog

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import net.tamasoft.lifelog.core.PairConfig
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var pending by mutableStateOf<PairConfig?>(null)
    private var pairError by mutableStateOf<String?>(null)
    private var tick by mutableIntStateOf(0)
    // Each module's "Send now" job while it waits or runs, by module key.
    private var sending by mutableStateOf<Map<String, WorkInfo.State>>(emptyMap())
    private var watch: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        // A job finishing writes its result to settings, which the screen does not
        // watch by itself: without this a press showed nothing until the app was reopened.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@MainActivity).getWorkInfosByTagFlow(Scheduler.NOW).collect { infos ->
                    sending = infos.filter { !it.state.isFinished }.mapNotNull { w ->
                        w.tags.firstOrNull { it.startsWith("${Scheduler.NOW}-") }?.let { it.removePrefix("${Scheduler.NOW}-") to w.state }
                    }.toMap()
                    bump()
                }
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        shown = true
        watch = Prefs(this).watchStatus { bump() }
    }

    override fun onStop() {
        shown = false
        watch?.let { Prefs(this).unwatch(it) }
        watch = null
        super.onStop()
    }

    companion object {
        /** Whether this screen is on show. Health Connect lets an app read while it is, and
         *  needs "read in the background" otherwise, so a health send says which it was. */
        @Volatile var shown = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // From the foreground, the service can start with its location type and repost its notice.
        if (Prefs(this).anyOn()) Scheduler.apply(this)
        tick++
    }

    private fun handle(i: Intent?) {
        val data = i?.data?.toString() ?: return
        if (!data.startsWith("lifelog://", ignoreCase = true)) return
        try { pending = PairConfig.parse(data); pairError = null } catch (e: Exception) { pairError = e.message }
    }

    private fun bump() { tick++ }

    // Android's answers to permission requests come back here. These live
    // outside the keyed body below, so rebuilding the body never drops one.
    private var ask: (Array<String>) -> Unit = {}
    private var askHealth: (Set<String>) -> Unit = {}
    private var healthGranted by mutableStateOf<Set<String>>(emptySet())

    @Composable
    private fun Screen() {
        val c = LocalContext.current
        val prefs = remember { Prefs(c) }
        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Scheduler.apply(c); bump()
        }
        val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { bump() }
        ask = { perms.launch(it) }
        askHealth = { health.launch(it) }
        LaunchedEffect(tick) {
            // A newer tick cancels this one; that must not read as "nothing allowed".
            healthGranted = try { Health.granted(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptySet() }
        }
        // Everything shown is read from settings and permissions, not from Compose
        // state, so the whole body is rebuilt on every tick rather than trusting
        // Compose to know what changed.
        val scroll = rememberScrollState()                  // kept across rebuilds: a tap does not jump to the top
        key(tick) {
            Column(Modifier.safeDrawingPadding().verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Lifelog", style = MaterialTheme.typography.headlineMedium)
                val cfg = prefs.config
                if (cfg == null) Unpaired() else Paired(prefs, cfg)
                pairError?.let { Text("That pairing code could not be read: $it", color = MaterialTheme.colorScheme.error) }
            }
        }
        pending?.let { PairDialog(prefs, it) }
    }

    @Composable
    private fun Unpaired() {
        var code by remember { mutableStateOf("") }
        Text("Lifelog sends what this phone knows (health, location, app use, photos) to ONE server: " +
            "your own vault's receiver. Nothing is sent until you pair it and turn a module on.")
        Text("To pair: on this phone, open the pairing page your receiver serves at /lifelog/pair and " +
            "tap “Open in Lifelog”. Or copy the code from that page and paste it here.")
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Pairing code") }, minLines = 2)
        Button({ try { pending = PairConfig.parse(code); pairError = null } catch (e: Exception) { pairError = e.message } },
            enabled = code.isNotBlank()) { Text("Pair") }
    }

    @Composable
    private fun PairDialog(prefs: Prefs, cfg: PairConfig) {
        val c = LocalContext.current
        val old = prefs.config
        val other = old != null && old.host != cfg.host
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Pair with ${cfg.host}?") },
            text = {
                Text(buildString {
                    append("This phone will send what you switch on to ${cfg.url}, and to nowhere else. ")
                    append("Pair only with a server you run yourself.")
                    if (other) append("\n\nThis REPLACES ${old!!.host}, and turns every module off.")
                    val offered = Module.entries.filter { cfg.offers(it.key) }.joinToString(", ") { it.title }
                    append("\n\nThis server takes: ${offered.ifEmpty { "nothing" }}.")
                })
            },
            confirmButton = {
                TextButton({
                    if (other) prefs.unpair()
                    prefs.config = cfg
                    pending = null
                    Scheduler.apply(c)
                    bump()
                }) { Text("Pair") }
            },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } },
        )
    }

    @Composable
    private fun Paired(prefs: Prefs, cfg: PairConfig) {
        val c = LocalContext.current
        var confirmUnpair by remember { mutableStateOf(false) }
        Text("Paired with ${cfg.host}. Each module below is off until you turn it on, and the " +
            "notification says what is sent for as long as anything is.")
        Phone(prefs)
        HealthCard(prefs, cfg)
        LocationCard(prefs, cfg)
        UsageCard(prefs, cfg)
        PhotosCard(prefs, cfg)
        OutlinedButton({ confirmUnpair = true }) { Text("Unpair") }
        Text("Lifelog ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        if (confirmUnpair) AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair from ${cfg.host}?") },
            text = { Text("Every module turns off and the tokens are forgotten. What was already sent stays on the server.") },
            confirmButton = { TextButton({ prefs.unpair(); Scheduler.apply(c); confirmUnpair = false; bump() }) { Text("Unpair") } },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text("Cancel") } },
        )
    }

    // ---------------------------------------------------------------- pieces

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun Need(label: String, ok: Boolean, action: (() -> Unit)?) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((if (ok) "✓ " else "✗ ") + label, Modifier.weight(1f),
                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            if (!ok && action != null) TextButton(action) { Text("Allow") }
        }
    }

    @Composable
    private fun Toggle(label: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(on, set, enabled = enabled)
        }
    }

    @Composable
    private fun ModuleSection(prefs: Prefs, cfg: PairConfig, m: Module, what: String,
                              onEnable: () -> Unit = {}, content: @Composable () -> Unit) {
        val c = LocalContext.current
        Section(m.title) {
            Text(what, style = MaterialTheme.typography.bodyMedium)
            if (!cfg.offers(m.key)) {
                Text("This server does not take ${m.title.lowercase()}.", style = MaterialTheme.typography.bodySmall)
                return@Section
            }
            Toggle(if (prefs.on(m)) "On" else "Off", prefs.on(m)) { v ->
                prefs.setOn(m, v)
                if (v) onEnable()
                Scheduler.apply(c)
                bump()
            }
            if (prefs.on(m)) {
                content()
                Status(prefs, m)
                when (sending[m.key]) {
                    null -> TextButton({ Scheduler.now(c, m); bump() }) { Text("Send now") }
                    WorkInfo.State.RUNNING -> Text("Sending…", style = MaterialTheme.typography.bodySmall)
                    else -> Text(if (m == Module.PHOTOS) "Waiting for Wi-Fi…" else "Waiting for a connection…",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun time(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    @Composable
    private fun Status(prefs: Prefs, m: Module) {
        prefs.noteOf(m)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val ok = prefs.lastOk(m)
        if (ok > 0) Text("Last sent ${time(ok)}: ${prefs.lastOkWhat(m) ?: ""}", style = MaterialTheme.typography.bodySmall)
        val idle = prefs.lastIdle(m)
        if (idle > ok) Text("Checked ${time(idle)}: nothing new to send", style = MaterialTheme.typography.bodySmall)
        prefs.lastErr(m)?.let {
            Text("${time(prefs.lastErrAt(m))}: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }

    private fun open(action: String, pkgUri: Boolean = false) {
        val i = Intent(action)
        if (pkgUri) i.data = Uri.parse("package:$packageName")
        runCatching { startActivity(i) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    @Composable
    private fun Phone(prefs: Prefs) {
        val c = LocalContext.current
        Section("This phone") {
            Need("Notifications (the notice of what is sent)", Perms.notifications(c),
                if (Build.VERSION.SDK_INT >= 33) ({ ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) else null)
            Need("Battery: unrestricted, so Android does not stop the sends", Perms.batteryUnrestricted(c)) {
                @Suppress("BatteryLife") open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri = true)
            }
            Need("Not paused when unused (App info → Manage app if unused: off)", Perms.neverHibernated(c)) {
                open(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkgUri = true)
            }
            Text("If Android greys out Accessibility, Notification access, SMS or Call logs for Lifelog: " +
                "open App info, tap ⋮ at the top right, and choose “Allow restricted settings”.",
                style = MaterialTheme.typography.bodySmall)
            TextButton({ open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri = true) }) { Text("Open App info") }
        }
    }

    @Composable
    private fun HealthCard(prefs: Prefs, cfg: PairConfig) {
        val c = LocalContext.current
        val granted = healthGranted
        ModuleSection(prefs, cfg, Module.HEALTH,
            "Health Connect's records (steps, heart rate, sleep, workouts, meditation and the rest), " +
                "in HC Webhook's format, to the receiver's health route.") {
            if (!Health.available(c)) {
                Text("Health Connect is not available on this phone.", color = MaterialTheme.colorScheme.error)
            } else {
                val kinds = Health.KINDS.values.map { androidx.health.connect.client.permission.HealthPermission.getReadPermission(it) }
                val n = kinds.count { it in granted }
                Need("Health Connect: $n of ${kinds.size} kinds allowed", n > 0) { askHealth(Health.PERMISSIONS) }
                Need("Reading in the background", androidx.health.connect.client.permission.HealthPermission
                    .PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted) { askHealth(Health.PERMISSIONS) }
                if (n in 1 until kinds.size) TextButton({ askHealth(Health.PERMISSIONS) }) { Text("Choose kinds") }
            }
            val hours = prefs.long(Prefs.HEALTH_HOURS, 12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Every", Modifier.padding(end = 8.dp))
                for (h in listOf(1L, 6L, 12L, 24L)) {
                    TextButton({ prefs.setLong(Prefs.HEALTH_HOURS, h); Scheduler.apply(c); bump() }) {
                        Text(if (h == hours) "[$h h]" else "$h h")
                    }
                }
            }
        }
    }

    @Composable
    private fun LocationCard(prefs: Prefs, cfg: PairConfig) {
        val c = LocalContext.current
        ModuleSection(prefs, cfg, Module.LOCATION,
            "A location reading every 15 minutes, with battery level and network, in OwnTracks' " +
                "format, and a message on entering or leaving each place below.") {
            Need("Precise location", Perms.fineLocation(c)) {
                ask(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            Need("Location all the time (choose “Allow all the time”)", Perms.backgroundLocation(c),
                if (Perms.fineLocation(c)) ({ ask(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }) else null)
            if (cfg.regions.isEmpty()) Text("No places from the server.", style = MaterialTheme.typography.bodySmall)
            else Text("Places: " + cfg.regions.joinToString(", ") { "${it.desc} (${it.rad} m)" }, style = MaterialTheme.typography.bodySmall)
            val q = Location.queue(c)
            if (q.size() > 0) Text("${q.size()} reading(s) waiting to be sent.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun UsageCard(prefs: Prefs, cfg: PairConfig) {
        val c = LocalContext.current
        ModuleSection(prefs, cfg, Module.USAGE,
            "Which apps were in front and for how long, and each unlock. The parts below are " +
                "each off until you turn them on.") {
            Need("Usage access", Perms.usageAccess(c)) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            Text("Sends today and every day of the last week the phone still remembers. The receiver keeps " +
                "these apart from another sender's, such as the Termux script, so both can run.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Part(prefs, UsagePart.WEB, "The SITE and page title of each page in a browser, never the address.") {
                Need("Browser watcher (Accessibility → Lifelog)", Perms.accessibility(c)) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            }
            Part(prefs, UsagePart.VIDEOS, "Title, channel and state of videos in: " +
                cfg.videoApps.sorted().joinToString(", ") { it.substringAfterLast('.') } + ". No music.") {
                Need("Notification access (reads no notification, only what is playing)", Perms.notificationAccess(c)) {
                    open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
            }
            Part(prefs, UsagePart.CALLS, "Ordinary phone calls: time, length, direction, and the contact's name or the number.") {
                Need("Call history", Perms.granted(c, Manifest.permission.READ_CALL_LOG)) {
                    ask(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                }
            }
            Part(prefs, UsagePart.SMS, "Text messages: time, direction, who, length. A person's words go to the " +
                "server, which keeps them apart from the record; a service's (a bank, a login code) never leave the phone.") {
                Need("Text messages", Perms.granted(c, Manifest.permission.READ_SMS)) {
                    ask(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))
                }
                Need("Contacts, for names", Perms.granted(c, Manifest.permission.READ_CONTACTS)) {
                    ask(arrayOf(Manifest.permission.READ_CONTACTS))
                }
            }
            Text("Turning a part off also withdraws it from today and yesterday, which are sent again.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun Part(prefs: Prefs, p: UsagePart, what: String, needs: @Composable () -> Unit) {
        Toggle(p.title, prefs.part(p)) { prefs.setPart(p, it); Scheduler.apply(this); bump() }
        Text(what, style = MaterialTheme.typography.bodySmall)
        if (prefs.part(p)) needs()
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun PhotosCard(prefs: Prefs, cfg: PairConfig) {
        val c = LocalContext.current
        ModuleSection(prefs, cfg, Module.PHOTOS,
            "New camera photos, the original files with their time and place, on Wi-Fi only.",
            onEnable = {
                if (prefs.long(Prefs.PHOTOS_MARK_ADDED) == 0L) prefs.setLong(Prefs.PHOTOS_MARK_ADDED, System.currentTimeMillis() / 1000)
            }) {
            Need("Photos (all of them, not selected ones)", Perms.granted(c, Perms.photoRead)) {
                ask(arrayOf(Perms.photoRead, Manifest.permission.ACCESS_MEDIA_LOCATION))
            }
            Need("Photo locations", Perms.granted(c, Manifest.permission.ACCESS_MEDIA_LOCATION)) {
                ask(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
            }
            val since = prefs.long(Prefs.PHOTOS_MARK_ADDED)
            if (since > 0) Text("Sends photos added after ${time(since * 1000)}.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
