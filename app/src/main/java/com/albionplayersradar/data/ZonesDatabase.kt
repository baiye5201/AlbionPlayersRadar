package com.albionplayersradar.data

object ZonesDatabase {
    data class ZoneInfo(
        val name: String,
        val pvpType: String,
        val tier: Int
    )

    val zones: Map<String, ZoneInfo> = mapOf(
        "THETFORD" to ZoneInfo("Thetford", "safe", 4),
        "LYMHURST" to ZoneInfo("Lymhurst", "safe", 3),
        "FORTSTERLING" to ZoneInfo("Fort Sterling", "safe", 2),
        "MARTLOCK" to ZoneInfo("Martlock", "safe", 5),
        "BRIDGEPWATCH" to ZoneInfo("Bridgewatch", "safe", 6),
        "CARLEON" to ZoneInfo("Carleon", "black", 8),
        "MERLYN" to ZoneInfo("Merlyn", "safe", 4),
        "MARCH" to ZoneInfo("March", "safe", 3),
        "BLACKROCK" to ZoneInfo("Blackrock", "red", 6),
        "CAERLEON" to ZoneInfo("Caerleon Roads", "black", 8),
        "THESTONE-1" to ZoneInfo("The Stone", "safe", 0),
        "THESTONE-2" to ZoneInfo("The Stone", "safe", 0),
        "BRITTAIN" to ZoneInfo("Brittain", "safe", 0)
    )

    fun getZone(id: String): ZoneInfo? = zones[id]

    fun getPvpType(id: String): String = getZone(id)?.pvpType ?: "safe"

    fun isDangerous(id: String): Boolean = getPvpType(id) in listOf("black", "red")
}
