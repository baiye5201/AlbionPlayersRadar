package com.albionplayersradar.data

object ZonesDatabase {
    private val zones = mapOf(
        "THESTONE-1" to ZoneInfo("The Stone", "safe", 0),
        "THESTONE-2" to ZoneInfo("The Stone", "safe", 0),
        "CAERLEON" to ZoneInfo("Caerleon", "safe", 0),
        "BRITTAIN" to ZoneInfo("Brittain", "safe", 0),
        "MERLYN" to ZoneInfo("Merlyn", "safe", 0),
        "MARCH" to ZoneInfo("March", "safe", 0),
        "BLACKROCK" to ZoneInfo("Blackrock", "red", 0),
    )

    fun getZone(id: String): ZoneInfo? = zones[id]

    fun getPvpType(id: String): String = zones[id]?.pvpType ?: "safe"

    fun isDangerous(id: String): Boolean {
        val pvp = getPvpType(id)
        return pvp == "black" || pvp == "red"
    }
}
