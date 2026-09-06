package com.enderthor.kpower.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import com.enderthor.kpower.screens.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

// ── Settings store, with corruption recovery ─────────────────────────────────────────────────────
// A kill mid-write (the OS killing the extension process at ride end) leaves settings.preferences_pb
// unparseable, and DataStore then rethrows the parse failure on EVERY read — the app crash-loops on
// each launch. Those bytes are unrecoverable, so the store resets to empty; a mirror of the durable
// settings in a SECOND store refills it on the next open, so the rider doesn't lose their profiles,
// meters and toggles.
//
// Only durable settings are mirrored. The churn keys below are rewritten every 45-60 s during a ride
// (they are caches, not settings): mirroring them would put the backup under the same kill-mid-write
// fire as the original, for data nobody misses.
private val CHURN_KEYS = setOf("lastKnownPosition", "meterScreenActiveAt", "current", "stats")
internal const val CONFIG_DATA_KEY = "configdata"

// Set by the corruption handler, i.e. written into the very snapshot that replaces the unparseable
// file, so a reset is OBSERVABLE. It has to be: `loadPreferencesFlow` cannot tell "configdata key
// absent because the store was wiped" from "fresh install" — both render as one default bike — and
// the editor auto-saves on dispose, so merely opening that default bike stamps a real configdata
// into the wiped store. Without this marker the mirror would then treat those defaults as the
// rider's settings and clear the only copy that still had their bikes. Cleared only once the backup
// has actually been READ (successfully, even if it turns out empty); a failed read keeps it, because
// "we don't know what was there" must not be confused with "there was nothing".
internal val SETTINGS_RESET_AT = longPreferencesKey("settingsWasResetAt")

/**
 * How long a reset marker has authority. It is a TIMESTAMP, not a flag, because the flag version had
 * two failure modes that compounded: while it was set the mirror refused to write, and it was only
 * cleared once the backup could be read — so a backup that stayed unreadable (cold boot, busy eMMC)
 * blocked the mirror for the whole session AND, on the launch it finally read, overwrote everything
 * the rider had re-entered meanwhile with the pre-corruption copy. Past this age the rider has had
 * launches to reconfigure and their current settings are the truth, so the marker loses its authority:
 * the mirror unblocks and the restore falls back to filling in only the keys the primary lacks.
 */
private const val RESET_MARKER_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** True while a corruption reset is recent enough that the primary must not be trusted. */
internal fun Map<Preferences.Key<*>, Any>.resetPending(nowMs: Long = System.currentTimeMillis()): Boolean {
    val at = this[SETTINGS_RESET_AT] as? Long ?: return false
    return nowMs - at < RESET_MARKER_MAX_AGE_MS
}

internal fun Preferences.durable() = asMap().filterKeys { it.name !in CHURN_KEYS }

@Suppress("UNCHECKED_CAST")
internal fun MutablePreferences.putAllUnchecked(entries: Map<Preferences.Key<*>, Any>) =
    entries.forEach { (k, v) -> this[k as Preferences.Key<Any>] = v }

private val Context.settingsBackup: DataStore<Preferences> by preferencesDataStore(
    name = "settings_backup",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(SETTINGS_RESET_AT to System.currentTimeMillis()) },
    produceMigrations = { ctx -> listOf(RestoreSettingsBackup(ctx.settingsBackup)) }
)

/** Refills a wiped store from the backup on open. It also repairs an incomplete primary that acquired
 *  unrelated defaults after a failed backup read, identified by the [SETTINGS_WAS_RESET] marker. */
internal class RestoreSettingsBackup(
    private val backup: DataStore<Preferences>,
    private val maxReadAttempts: Int = 2,
    private val retryDelayMs: Long = 1_000,
) : DataMigration<Preferences> {
    private var pendingRestore: Map<Preferences.Key<*>, Any> = emptyMap()
    private var wasReset = false
    private var staleMarker = false
    private var backupWasRead = false

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val durable = currentData.durable()
        wasReset = durable.resetPending()
        staleMarker = durable[SETTINGS_RESET_AT] != null && !wasReset
        // Steady state: profiles present, no marker at all — trusted, don't even read the backup.
        if (!wasReset && !staleMarker && currentData.asMap().keys.any { it.name == CONFIG_DATA_KEY })
            return false
        val read = readDurableBackup(backup, maxReadAttempts, retryDelayMs)
        backupWasRead = read != null
        pendingRestore = read ?: emptyMap()
        // Run migrate() to clear the marker as soon as the backup is readable, even when it turns out
        // empty — that is a definite "there was nothing to restore", which unblocks the mirror. An
        // expired marker also runs, purely to drop it.
        return staleMarker || (wasReset && backupWasRead) || (pendingRestore.isNotEmpty() && (
            durable.isEmpty() || pendingRestore.keys.any { it.name == CONFIG_DATA_KEY }
        ))
    }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences()
            .apply {
                if (wasReset) {
                    // A RECENT post-corruption rebuild: anything the primary picked up since the reset
                    // is a default or a stray edit made while the rider's real setup was missing, so the
                    // backup wins outright. Bounded by RESET_MARKER_MAX_AGE_MS — past that the rider has
                    // had time to genuinely reconfigure and we must not clobber that.
                    putAllUnchecked(pendingRestore)
                } else {
                    val currentNames = currentData.asMap().keys.mapTo(mutableSetOf()) { it.name }
                    putAllUnchecked(pendingRestore.filterKeys { it.name !in currentNames })
                }
                if (backupWasRead || staleMarker) remove(SETTINGS_RESET_AT)
            }

    override suspend fun cleanUp() {
        pendingRestore = emptyMap(); wasReset = false; staleMarker = false; backupWasRead = false
    }
}

private val mirrorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private val mirrorStarted = AtomicBoolean(false)

/** Starts (once per process) the collector that keeps the backup in step with the durable settings.
 *  Both the activity and the extension service call it because either can be the only one alive, so
 *  later calls are no-ops — two collectors would just duplicate every write. It runs on a
 *  process-owned scope, NOT the caller's: on the activity's lifecycleScope a screen rotation would
 *  cancel the only mirror and the service's call would already have been a no-op. */
fun Context.mirrorSettingsToBackup() {
    if (!mirrorStarted.compareAndSet(false, true)) return
    val app = applicationContext
    mirrorScope.launch { mirrorSettings(app.dataStore, app.settingsBackup) }
}

internal suspend fun mirrorSettings(source: DataStore<Preferences>, backup: DataStore<Preferences>) {
    // Deduped by hand rather than with distinctUntilChanged, which sits UPSTREAM of the write and so
    // would drop the retry: once mirrorSnapshot exhausted its attempts that exact snapshot could never
    // arrive again, freezing the backup silently until the rider next changed a setting. Advancing
    // only on success means the next emission (a churn write during a ride, or any edit) retries it.
    var lastMirrored: Map<Preferences.Key<*>, Any>? = null
    while (true) {
        try {
            source.data
                .map { it.durable() }
                .collect { durable ->
                    // Empty means fresh install. A deliberate "no bikes" save still carries
                    // configdata="[]", so it is non-empty here.
                    if (durable.isEmpty() || durable == lastMirrored) return@collect
                    // Restore still pending: the primary holds post-reset defaults, not the rider's
                    // settings. Mirroring now would destroy the only copy that still has their bikes.
                    // Expires, so an unreadable backup cannot freeze the mirror indefinitely — after a
                    // day the rider's current settings are the truth and deserve backing up.
                    if (durable.resetPending()) return@collect
                    if (mirrorSnapshot(backup, durable)) lastMirrored = durable
                }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable: mirrorStarted is never reset, so if this coroutine dies the process has no
            // backup for the rest of its life — and silently. The loop below retries instead.
            Timber.w(t, "Primary settings read failed; preserving backup and retrying")
        }
        delay(1_000)
    }
}

internal suspend fun readDurableBackup(
    backup: DataStore<Preferences>,
    maxAttempts: Int = 2,   // worst case 2x2s + 1s: this blocks every dataStore consumer
    retryDelayMs: Long = 1_000,
    readTimeoutMs: Long = 2_000,
): Map<Preferences.Key<*>, Any>? {
    repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
        try {
            // Bounded: this runs on the PRIMARY store's init path, so a backup read that hangs (rather
            // than throws) would block every dataStore consumer in the process — the settings screens
            // and the estimator's config load included — with no recovery short of a process kill.
            withTimeoutOrNull(readTimeoutMs) { backup.data.first().durable() }?.let { return it }
            Timber.w("Settings backup read timed out (%d/%d)", attempt + 1, maxAttempts)
            if (attempt + 1 < maxAttempts) delay(retryDelayMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Settings backup read failed (%d/%d)", attempt + 1, maxAttempts)
            if (attempt + 1 < maxAttempts) delay(retryDelayMs)
        }
    }
    return null
}

internal suspend fun mirrorSnapshot(
    backup: DataStore<Preferences>,
    durable: Map<Preferences.Key<*>, Any>,
    maxAttempts: Int = 3,
    retryDelayMs: Long = 1_000,
): Boolean {
    repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
        try {
            backup.edit { stored ->
                val storedHasProfiles = stored.asMap().keys.any { it.name == CONFIG_DATA_KEY }
                val incomingHasProfiles = durable.keys.any { it.name == CONFIG_DATA_KEY }
                // After a corruption reset, unrelated defaults can make the primary non-empty
                // before restoration succeeds. Keep the last good profiles until a later open can
                // merge them back; an explicit empty list still has the configdata key and mirrors.
                if (storedHasProfiles && !incomingHasProfiles) return@edit
                stored.clear()
                stored.putAllUnchecked(durable)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Settings backup write failed (%d/%d)", attempt + 1, maxAttempts)
            if (attempt + 1 < maxAttempts) delay(retryDelayMs)
        }
    }
    return false
}

@Composable
fun Main(
) {

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        TabLayout(
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The settings edited on these screens are mirrored from here too — the extension service
        // (the other mirror site) may not be running while the rider is in the app.
        mirrorSettingsToBackup()
        setContent { Main() }
    }
}
