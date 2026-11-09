package com.enderthor.kpower.data

import com.enderthor.kpower.extension.jsonWithUnknownKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Upgrading in place from 1.9.6 (store install over the old app): the 3.0 loader must read the exact
 * `configdata` JSON 1.9.6 wrote (same "settings" DataStore, same key). 1.9.6 used the default `Json`,
 * so fields at their default value (surface, isforcepower) may be absent, and it still carried the
 * OpenWeather fields that 3.0 dropped.
 */
class LegacyConfigUpgradeTest {

    private val v196 = """
        [{"id":0,"name":"Spark","isActive":true,"bikeMass":"14.0","rollingResistanceCoefficient":"0.0095",
          "dragCoefficient":"0.8","frontalArea":"0.9","powerLoss":"2.2","headwindconf":"0.0",
          "isOpenWeather":true,"apikey":"abc","ftp":"257","surface":"GRAVEL","isforcepower":true},
         {"id":1,"name":"Road","isActive":false,"bikeMass":"8.5","rollingResistanceCoefficient":"0.005",
          "dragCoefficient":"0.6","frontalArea":"0.4","powerLoss":"1.5","headwindconf":"0.0",
          "isOpenWeather":false,"apikey":"","ftp":"200"}]
    """.trimIndent()

    @Test
    fun `1_9_6 bikes load unchanged in advanced mode`() {
        val bikes = jsonWithUnknownKeys.decodeFromString<List<ConfigData>>(v196)

        assertEquals(2, bikes.size)
        val spark = bikes[0]
        assertEquals("Spark", spark.name)
        assertEquals("14.0", spark.bikeMass)
        assertEquals("0.0095", spark.rollingResistanceCoefficient)
        assertEquals("0.8", spark.dragCoefficient)
        assertEquals("0.9", spark.frontalArea)
        assertEquals("2.2", spark.powerLoss)
        assertEquals("257", spark.ftp)
        assertEquals(KarooSurface.GRAVEL, spark.surface)
        assertEquals(true, spark.isforcepower)
        // Advanced mode, configured FTP, no offset: the stored physics values are used as-is.
        assertFalse(spark.simpleMode)
        assertFalse(spark.useProfileFtp)
        assertEquals("0.0", spark.estPowerFactorPct)
        assertEquals("0.0", spark.estPowerOffsetW)

        // Fields omitted at their 1.9.6 default come back with the same default.
        assertEquals(KarooSurface.STANDARD, bikes[1].surface)
        assertFalse(bikes[1].isforcepower)
        assertEquals(true, bikes[0].isActive)
    }
}
