package com.albionplayersradar.data

data class ZoneInfo(
    val name: String,
    val pvpType: String,
    val tier: Int
)

object ZonesDatabase {
    private val zones = mapOf(
        "THETFORD" to ZoneInfo("Thetford", "safe", 4),
        "LYMHURST" to ZoneInfo("Lymhurst", "safe", 3),
        "FORTSTERLING" to ZoneInfo("Fort Sterling", "safe", 2),
        "MARTLOCK" to ZoneInfo("Martlock", "safe", 5),
        "BRIDGEWATCH" to ZoneInfo("Bridgewatch", "safe", 6),
        "CARLEON" to ZoneInfo("Caerleon", "black", 8),
        "CAERLEON" to ZoneInfo("Caerleon Roads", "black", 8),
        "ROAD" to ZoneInfo("Royal Roads", "red", 7),
        "1000-1" to ZoneInfo("Yellow Zone", "yellow", 4),
        "2000-1" to ZoneInfo("Red Zone", "red", 6),
        "3000-1" to ZoneInfo("Black Zone", "black", 8)
    )

    fun getZone(zoneId: String): ZoneInfo? = zones[zoneId]
    fun getPvpType(zoneId: String): String = zones[zoneId]?.pvpType ?: "safe"
    fun isDangerous(zoneId: String): Boolean {
        val pvp = getPvpType(zoneId)
        return pvp == "black" || pvp == "red"
    }
}
