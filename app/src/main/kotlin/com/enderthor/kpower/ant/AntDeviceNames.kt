package com.enderthor.kpower.ant

/**
 * ANT+/FIT device naming tables (the same data the Karoo uses internally via the Garmin FIT SDK).
 * Source: the FIT Profile manufacturer / garmin_product types — values transcribed from the public
 * FIT profile (cross-checked against muktihari/fit, BSD-3-Clause; the underlying IDs are Garmin's
 * standard FIT profile data). We only carry the entries that can appear as an ANT+ BIKE POWER
 * device (type 11) — power meters, trainers, e-bike systems, bike computers — and fall back to
 * "ANT #<id>" for anything not listed. The model name needs the vendor's product table, which the
 * FIT SDK provides only for Garmin (garmin_product); other brands resolve to the manufacturer name.
 */
object AntManufacturers {
    private val names = mapOf(
        1 to "Garmin", 6 to "SRM", 7 to "Quarq", 8 to "iBike", 9 to "Saris",
        13 to "Dynastream", 15 to "Dynastream", 16 to "Timex", 17 to "MetriGear",
        23 to "Suunto", 28 to "Peaksware", 30 to "LeMond Fitness", 32 to "Wahoo",
        40 to "Concept2", 41 to "Shimano", 42 to "One Giant Leap", 44 to "Brim Brothers",
        45 to "Xplova", 47 to "bf1systems", 48 to "Pioneer", 51 to "4iiii", 60 to "Rotor",
        62 to "ID Bike", 63 to "Specialized", 66 to "North Pole Engineering", 67 to "Bkool",
        68 to "CatEye", 69 to "Stages", 70 to "SIGMA Sport", 71 to "TomTom", 72 to "Peripedal",
        73 to "Wattbike", 76 to "Moxy", 81 to "Bontrager", 84 to "Magura", 86 to "Elite",
        89 to "Tacx", 93 to "Inside Ride", 95 to "Stryd", 96 to "ICG", 99 to "Look",
        100 to "Campagnolo", 102 to "Praxisworks", 103 to "Limits", 107 to "Magene",
        108 to "Giant", 109 to "Tigrasport", 110 to "Salutron", 111 to "Technogym",
        112 to "Bryton", 115 to "iGPSport", 116 to "Thinkrider", 119 to "Orangetheory",
        120 to "Inpeak", 121 to "Kinetic", 123 to "Polar", 124 to "SeeSense", 126 to "iQsquare",
        127 to "Leomo", 129 to "Coros", 131 to "Chileaf", 132 to "Cycplus", 133 to "Gravaa",
        134 to "Sigeyi", 135 to "Coospo", 136 to "Geoid", 137 to "Bosch", 138 to "Kyto",
        139 to "Kinetic Sports", 141 to "TQ Systems", 143 to "Keiser", 145 to "Porsche eBike",
        149 to "Laisi", 150 to "Myzone", 152 to "Bafang", 258 to "Lezyne", 260 to "Zwift",
        261 to "Watteam", 262 to "Recon", 263 to "Favero", 267 to "Bryton", 268 to "SRAM",
        277 to "Falco eMotors", 278 to "Minoura", 279 to "Cycliq", 281 to "TrainerRoad",
        282 to "The Sufferfest", 283 to "FSA", 285 to "Feedback Sports", 287 to "VDO",
        288 to "Magneticdays", 289 to "Hammerhead", 290 to "Kinetic by Kurt", 293 to "JetBlack",
        294 to "Coros", 296 to "Velosense", 299 to "Mahle eBikemotion", 300 to "Nurvv",
        304 to "Yamaha", 306 to "Gravaa", 307 to "Onelap", 310 to "Decathlon", 311 to "Syncros",
        313 to "Cannondale", 315 to "RGT Cycling", 318 to "Fazua", 325 to "AeroSensor",
        327 to "Magicshine", 333 to "Tektro", 340 to "Peloton", 255 to "Development",
    )
    fun name(id: Int): String = names[id] ?: "ANT #$id"
}

/** Garmin power-meter product codes (FIT garmin_product) -> model name. 0x50's model number field
 *  carries this code for Garmin devices, so a Rally reports 3578. Only power products are listed. */
object GarminProducts {
    private val names = mapOf(
        1380 to "Vector SS", 1381 to "Vector CP", 2079 to "Vector S",
        2161 to "Vector 2", 2162 to "Vector 2S", 2787 to "Vector 3",
        3578 to "Rally 200", 4525 to "Rally X10",
    )
    fun name(modelNumber: Int): String? = names[modelNumber]
}

/** Best human name from a 0x50 page: "Brand Model" when the model is known (e.g. "Garmin Rally
 *  200"), otherwise just the brand ("Wahoo"). Showing the brand too — not only the bare model —
 *  is what riders expect under a Brand/Device label. */
fun antDeviceDisplayName(manufacturerId: Int, modelNumber: Int): String {
    val brand = AntManufacturers.name(manufacturerId)
    val model = if (manufacturerId == 1) GarminProducts.name(modelNumber) else null
    return if (model != null) "$brand $model" else brand
}
