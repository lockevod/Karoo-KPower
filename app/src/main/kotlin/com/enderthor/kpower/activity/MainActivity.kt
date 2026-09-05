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
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import com.enderthor.kpower.screens.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
private const val CONFIG_DATA_KEY = "configdata"

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
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { ctx -> listOf(RestoreSettingsBackup(ctx.settingsBackup)) }
)

/** Refills a wiped store from the backup on open. It also repairs an incomplete primary that acquired
 *  unrelated defaults after a failed backup read, identified by a backup profile list absent from the
 *  primary. Existing primary keys win when those missing settings are merged back. */
internal class RestoreSettingsBackup(
    private val backup: DataStore<Preferences>,
    private val maxReadAttempts: Int = 3,
    private val retryDelayMs: Long = 1_000,
) : DataMigration<Preferences> {
    private var pendingRestore: Map<Preferences.Key<*>, Any> = emptyMap()

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        if (currentData.asMap().keys.any { it.name == CONFIG_DATA_KEY }) return false
        pendingRestore = readDurableBackup(backup, maxReadAttempts, retryDelayMs) ?: emptyMap()
        return pendingRestore.isNotEmpty() && (
            currentData.durable().isEmpty() || pendingRestore.keys.any { it.name == CONFIG_DATA_KEY }
        )
    }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences()
            .apply {
                val currentNames = currentData.asMap().keys.mapTo(mutableSetOf()) { it.name }
                putAllUnchecked(pendingRestore.filterKeys { it.name !in currentNames })
            }

    override suspend fun cleanUp() { pendingRestore = emptyMap() }
}

private val mirrorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private val mirrorStarted = AtomicBoolean(false)

/** Starts (once per process) the collector that keeps the backup in step with the durable settings.
 *  Both the activity and the extension service call it because either can be the only one alive, so
 *  later calls are no-ops — two collectors would just duplicate every write. It runs on a
 *  process-owned scope, NOT the caller's: on the activity's lifecycleScope a screen rotation would
 *  cancel the only mirror and the service's call would already have been a no-op.
 *  distinctUntilChanged means a ride's churn-key writes cost nothing here. */
fun Context.mirrorSettingsToBackup() {
    if (!mirrorStarted.compareAndSet(false, true)) return
    val app = applicationContext
    mirrorScope.launch { mirrorSettings(app.dataStore, app.settingsBackup) }
}

internal suspend fun mirrorSettings(source: DataStore<Preferences>, backup: DataStore<Preferences>) {
    while (true) {
        try {
            source.data
                .map { it.durable() }
                .distinctUntilChanged()
                .collect { durable ->
                    // Empty means fresh install or a primary-store corruption reset. A deliberate
                    // "no bikes" save still carries configdata="[]", so it is non-empty here.
                    if (durable.isNotEmpty()) mirrorSnapshot(backup, durable)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Primary settings read failed; preserving backup and retrying")
        }
        delay(1_000)
    }
}

internal suspend fun readDurableBackup(
    backup: DataStore<Preferences>,
    maxAttempts: Int = 3,
    retryDelayMs: Long = 1_000,
): Map<Preferences.Key<*>, Any>? {
    repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
        try {
            return backup.data.first().durable()
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
