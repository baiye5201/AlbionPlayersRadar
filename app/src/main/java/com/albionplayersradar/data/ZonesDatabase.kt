package com.albionplayersradar.data

object ZonesDatabase {

    private val zones: Map<String, ZoneInfo> = mapOf(
        // Royal Cities
        "THETFORD" to ZoneInfo("Thetford", "safe", 4),
        "LYMHURST" to ZoneInfo("Lymhurst", "safe", 3),
        "FORTSTERLING" to ZoneInfo("Fort Sterling", "safe", 2),
        "MARTLOCK" to ZoneInfo("Martlock", "safe", 5),
        "BRIDGEPWATCH" to ZoneInfo("Bridgewatch", "safe", 6),
        "CARLEON" to ZoneInfo("Carleon", "black", 8),

        // Black Zone clusters (3000-3059)
        "3000-1" to ZoneInfo("BZ Outpost", "black", 8),
        "3001-1" to ZoneInfo("BZ Deep", "black", 8),
        "3002-1" to ZoneInfo("BZ Deep", "black", 8),
        "3003-1" to ZoneInfo("BZ Outpost", "black", 8),
        "3004-1" to ZoneInfo("BZ Deep", "black", 8),
        "3005-1" to ZoneInfo("BZ Deep", "black", 8),
        "3006-1" to ZoneInfo("BZ Outpost", "black", 8),
        "3007-1" to ZoneInfo("BZ Deep", "black", 8),
        "3008-1" to ZoneInfo("BZ Deep", "black", 8),
        "3009-1" to ZoneInfo("BZ Outpost", "black", 8),
        "3010-1" to ZoneInfo("BZ Deep", "black", 8),

        // Red Zones (2000-2059)
        "2000-1" to ZoneInfo("RZ Cluster", "red", 6),
        "2001-1" to ZoneInfo("RZ Deep", "red", 7),
        "2002-1" to ZoneInfo("RZ Deep", "red", 7),
        "2003-1" to ZoneInfo("RZ Cluster", "red", 6),
        "2004-1" to ZoneInfo("RZ Deep", "red", 7),

        // Yellow Zones (1000-1059)
        "1000-1" to ZoneInfo("YZ Cluster", "yellow", 4),
        "1001-1" to ZoneInfo("YZ Deep", "yellow", 5),
        "1002-1" to ZoneInfo("YZ Cluster", "yellow", 4),

        // Roads of Avalon
        "ROAD" to ZoneInfo("Roads of Avalon", "red", 7),
        "CAERLEON" to ZoneInfo("Caerleon Roads", "black", 8),
    )

    fun getZone(zoneId: String): ZoneInfo? {
        zones[zoneId]?.let { return it }
        val baseId = zoneId.split("-").firstOrNull()
        if (baseId != null && baseId != zoneId) {
            zones["$baseId-1"]?.let { return it }
        }
        return null
    }

    fun getPvpType(zoneId: String): String = getZone(zoneId)?.pvpType ?: "safe"
    fun isBlackZone(zoneId: String): Boolean = getPvpType(zoneId) == "black"
    fun isRedZone(zoneId: String): Boolean = getPvpType(zoneId) == "red"
    fun isYellowZone(zoneId: String): Boolean = getPvpType(zoneId) == "yellow"
    fun isSafeZone(zoneId: String): Boolean = getPvpType(zoneId) == "safe"
    fun isDangerous(zoneId: String): Boolean {
        val pvp = getPvpType(zoneId)
        return pvp == "black" || pvp == "red"
    }
}
