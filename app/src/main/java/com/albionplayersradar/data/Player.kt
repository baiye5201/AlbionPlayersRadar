package com.albionplayersradar.data

data class Player(
    val id: Long,
    val name: String,
    val guildName: String?,
    val allianceName: String?,
    val faction: Int,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var posZ: Float = 0f,
    var currentHealth: Int = 0,
    var maxHealth: Int = 1,
    var isMounted: Boolean = false
) {
    val healthPercent: Float
        get() = if (maxHealth > 0) currentHealth.toFloat() / maxHealth else 0f

    val isHostile: Boolean
        get() = faction == 255

    val isPassive: Boolean
        get() = faction == 0

    val isFactionPlayer: Boolean
        get() = faction in 1..6

    val threatLevel: ThreatLevel
        get() = when {
            isHostile -> ThreatLevel.HOSTILE
            isFactionPlayer -> ThreatLevel.FACTION
            else -> ThreatLevel.PASSIVE
        }
}
