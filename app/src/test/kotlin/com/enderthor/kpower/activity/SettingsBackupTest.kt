package com.enderthor.kpower.activity

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The corruption-recovery path: durable settings survive a wiped store, ride churn does not leak
 *  into the backup. Real DataStores on temp files — no Android framework involved. */
class SettingsBackupTest {
    private val tmp = kotlin.io.path.createTempDirectory("kpower-prefs").toFile()

    private val profiles = stringPreferencesKey(CONFIG_DATA_KEY)   // production name, not a copy
    private val meters = stringPreferencesKey("antPowerMeters")
    private val comparison = booleanPreferencesKey("comparisonMode")
    private val position = stringPreferencesKey("lastKnownPosition")    // churn
    private val meterScreen = longPreferencesKey("meterScreenActiveAt") // churn

    private fun store(
        name: String,
        scope: CoroutineScope,
        migrations: List<DataMigration<Preferences>> = emptyList(),
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) {
            tmp.resolve(name).apply { mkdirs() }.resolve("$name.preferences_pb")
        }

    /** The mirror runs on Dispatchers.Default while the test body is on runBlocking's own event loop,
     *  so yield() does not hand it the CPU. Poll instead of guessing a yield count. */
    private suspend fun awaitBackup(backup: DataStore<Preferences>, expected: String?) =
        withTimeout(5_000) {
            while (backup.data.first()[profiles] != expected) kotlinx.coroutines.delay(10)
            true
        }

    @Test
    fun `mirrors settings, restores them after a wipe, and ignores ride churn`() = runBlocking {
        val scope = CoroutineScope(Job())
        val settings = store("settings", scope)
        val backup = store("backup", scope)

        val mirror = scope.launch { mirrorSettings(settings, backup) }

        settings.edit {
            it[profiles] = "[{bike:mtb}]"
            it[meters] = "[12345]"
            it[comparison] = true
            it[position] = "41.4,2.2"
            it[meterScreen] = 1_700_000_000_000L
        }
        withTimeout(5_000) { while (backup.data.first()[profiles] == null) yield() }

        val mirrored = backup.data.first()
        assertEquals("[{bike:mtb}]", mirrored[profiles])
        assertEquals("[12345]", mirrored[meters])
        assertEquals(true, mirrored[comparison])
        assertNull("ride churn must not be mirrored", mirrored[position])
        assertNull("ride churn must not be mirrored", mirrored[meterScreen])
        mirror.cancel()

        // What ReplaceFileCorruptionHandler leaves behind: an empty store. Opening it runs the
        // migration, which must refill it from the backup.
        val restored = store("wiped", scope, listOf(RestoreSettingsBackup(backup))).data.first()
        assertEquals("[{bike:mtb}]", restored[profiles])
        assertEquals("[12345]", restored[meters])
        assertEquals(true, restored[comparison])

        // A store that already holds settings is left alone (no restore on every open).
        val live = store("live", scope, listOf(RestoreSettingsBackup(backup)))
        live.edit { it[profiles] = "[{bike:road}]" }
        assertEquals("[{bike:road}]", live.data.first()[profiles])

        scope.cancel()
    }

    @Test
    fun `backup write failure is bounded and does not escape`() = runBlocking {
        var attempts = 0
        val failingBackup = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                attempts++
                throw IOException("unwritable")
            }
        }

        val written = mirrorSnapshot(failingBackup, emptyMap(), maxAttempts = 3, retryDelayMs = 0)

        assertFalse(written)
        assertEquals(3, attempts)
    }

    @Test
    fun `transient backup read restores and empty primary cannot erase backup`() = runBlocking {
        var reads = 0
        var writes = 0
        val backup = object : DataStore<Preferences> {
            override val data = flow {
                if (++reads == 1) throw IOException("transient read")
                emit(mutablePreferencesOf(profiles to "[{bike:mtb}]"))
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                writes++
                return transform(mutablePreferencesOf(profiles to "[{bike:mtb}]"))
            }
        }

        val restored = readDurableBackup(backup, maxAttempts = 2, retryDelayMs = 0)
        assertEquals("[{bike:mtb}]", restored?.get(profiles))

        val scope = CoroutineScope(Job())
        val mirror = scope.launch { mirrorSettings(store("empty-source", scope), backup) }
        repeat(3) { yield() }
        mirror.cancel()
        scope.cancel()
        assertEquals(0, writes)
    }

    @Test
    fun `failed restore plus unrelated durable write preserves and later restores profiles`() = runBlocking {
        var stored: Preferences = mutablePreferencesOf(profiles to "[{bike:mtb}]")
        var failRead = true
        val backup = object : DataStore<Preferences> {
            override val data = flow {
                if (failRead) throw IOException("temporarily unreadable")
                emit(stored)
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                stored = transform(stored)
                return stored
            }
        }
        val failedRestore = RestoreSettingsBackup(backup, maxReadAttempts = 1, retryDelayMs = 0)
        assertFalse(failedRestore.shouldMigrate(emptyPreferences()))

        val incompletePrimary = mutablePreferencesOf(comparison to true)
        failRead = false
        assertTrue(mirrorSnapshot(backup, incompletePrimary.asMap(), maxAttempts = 1, retryDelayMs = 0))
        assertEquals("[{bike:mtb}]", stored[profiles])
        assertNull(stored[comparison])

        val recoveredRestore = RestoreSettingsBackup(backup, maxReadAttempts = 1, retryDelayMs = 0)
        assertTrue(recoveredRestore.shouldMigrate(incompletePrimary))
        val recovered = recoveredRestore.migrate(incompletePrimary)
        assertEquals("[{bike:mtb}]", recovered[profiles])
        assertEquals(true, recovered[comparison])
    }

    @Test
    fun `malformed preferences file is replaced with empty settings`() = runBlocking {
        val scope = CoroutineScope(Job())
        val file = tmp.resolve("corrupt/corrupt.preferences_pb")
        requireNotNull(file.parentFile).mkdirs()
        file.writeBytes(byteArrayOf(0x0A, 0x7F, 0x01))
        val recovered = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )

        assertEquals(emptyMap<Preferences.Key<*>, Any>(), recovered.data.first().asMap())
        scope.cancel()
    }

    private val resetAt = SETTINGS_RESET_AT   // production key, not a copy

    /** C1: a wiped store renders as one DEFAULT bike (loadPreferencesFlow cannot tell "key absent" from
     *  "fresh install"), and the editor auto-saves on dispose — so merely opening it stamps a real
     *  configdata into the reset store. Without the reset marker the mirror accepts those defaults and
     *  clears the only copy that still had the rider's bikes. */
    @Test
    fun `a reset store cannot overwrite the backup before its restore succeeds`() = runBlocking {
        val scope = CoroutineScope(Job())
        val settings = store("reset-settings", scope)
        val backup = store("reset-backup", scope)
        backup.edit { it[profiles] = "[{bike:road},{bike:mtb}]" }

        val mirror = scope.launch { mirrorSettings(settings, backup) }
        // The store was wiped by the corruption handler, which stamps the marker...
        settings.edit { it[resetAt] = System.currentTimeMillis() }
        // ...and the rider opens the default bike, so the editor writes it back.
        settings.edit { it[profiles] = "[{bike:default}]" }
        repeat(20) { yield() }

        assertEquals(
            "backup must still hold the rider's bikes",
            "[{bike:road},{bike:mtb}]", backup.data.first()[profiles]
        )
        mirror.cancel(); scope.cancel()
    }

    /** The marker must survive a backup we could not READ — "we don't know what was there" is not
     *  "there was nothing". Only a successful read (even of an empty backup) clears it. Asserted on the
     *  OBSERVABLE consequence — the mirror staying blocked — because asserting shouldMigrate()==false
     *  alone passed even with the `if (backupWasRead)` guard reverted to an unconditional remove. */
    @Test
    fun `an unreadable backup keeps the reset marker so the mirror stays blocked`() = runBlocking {
        val unreadable = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("backup unreadable") }
            override suspend fun updateData(t: suspend (Preferences) -> Preferences) = emptyPreferences()
        }
        val migration = RestoreSettingsBackup(unreadable, maxReadAttempts = 1, retryDelayMs = 0)
        val current = mutablePreferencesOf(resetAt to System.currentTimeMillis(),
            profiles to "[{bike:default}]")
        assertFalse("nothing to restore yet", migration.shouldMigrate(current))

        val scope = CoroutineScope(Job())
        val settings = store("unreadable-primary", scope, listOf(migration))
        val backup = store("unreadable-primary-backup", scope)
        backup.edit { it[profiles] = "[{bike:road},{bike:mtb}]" }
        val mirror = scope.launch { mirrorSettings(settings, backup) }
        settings.edit { it[resetAt] = System.currentTimeMillis(); it[profiles] = "[{bike:default}]" }
        repeat(20) { yield() }
        assertEquals("marker must still block the mirror",
            "[{bike:road},{bike:mtb}]", backup.data.first()[profiles])
        // Prove the mirror was ALIVE and it was the marker that stopped it — otherwise a lazy scheduler
        // would make the assertion above pass for the wrong reason.
        settings.edit { it.remove(resetAt) }
        assertTrue("the mirror must write once the marker is gone",
            awaitBackup(backup, "[{bike:default}]"))
        mirror.cancel(); scope.cancel()
    }

    /** …but it must EXPIRE. A backup that stays unreadable would otherwise freeze the mirror for the
     *  whole session and then, on the launch it finally reads, overwrite everything the rider re-entered
     *  meanwhile. Past the age limit their current settings are the truth. */
    @Test
    fun `an expired reset marker releases the mirror and stops the backup winning`() = runBlocking {
        val scope = CoroutineScope(Job())
        val settings = store("expired-settings", scope)
        val backup = store("expired-backup", scope)
        backup.edit { it[profiles] = "[{bike:old}]" }
        val mirror = scope.launch { mirrorSettings(settings, backup) }

        val twoDaysAgo = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
        settings.edit { it[resetAt] = twoDaysAgo; it[profiles] = "[{bike:rebuilt}]" }
        assertTrue("an expired marker must not block the mirror",
            awaitBackup(backup, "[{bike:rebuilt}]"))

        // And the restore must no longer let the backup clobber the rebuilt config.
        val stale = RestoreSettingsBackup(backup, maxReadAttempts = 1, retryDelayMs = 0)
        val current = mutablePreferencesOf(resetAt to twoDaysAgo, profiles to "[{bike:rebuilt}]")
        assertTrue("an expired marker still runs, to drop itself", stale.shouldMigrate(current))
        val migrated = stale.migrate(current)
        assertEquals("the rider's rebuilt config wins", "[{bike:rebuilt}]", migrated[profiles])
        assertNull("expired marker dropped", migrated[resetAt])
        mirror.cancel(); scope.cancel()
    }

    /** A RECENT marker still means the primary is untrusted and the backup wins outright. */
    @Test
    fun `a fresh reset marker lets the backup win and is cleared once read`() = runBlocking {
        val scope = CoroutineScope(Job())
        val good = store("fresh-marker-backup", scope)
        good.edit { it[profiles] = "[{bike:road}]"; it[meters] = "[6593]" }
        val restore = RestoreSettingsBackup(good, maxReadAttempts = 1, retryDelayMs = 0)
        val current = mutablePreferencesOf(resetAt to System.currentTimeMillis(),
            profiles to "[{bike:default}]")

        assertTrue("a readable backup must run the migration", restore.shouldMigrate(current))
        val migrated = restore.migrate(current)
        assertEquals("[{bike:road}]", migrated[profiles])
        assertEquals("[6593]", migrated[meters])
        assertNull("marker cleared once we know what the backup held", migrated[resetAt])
        scope.cancel()
    }
}
