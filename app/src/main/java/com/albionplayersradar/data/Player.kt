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
    var isMounted: Boolean = false,
    var isHostile: Boolean = false,
    var isPassive: Boolean = false,
    var isFactionPlayer: Boolean = false,
    var threatLevel: ThreatLevel = ThreatLevel.PASSIVE,
    val detectedAt: Long = System.currentTimeMillis()
) {
    val healthPercent: Float
        get() = if (maxHealth > 0) currentHealth.toFloat() / maxHealth else 0f

    fun updateThreat() {
        isHostile = faction == 255
        isPassive = faction == 0
        isFactionPlayer = faction in 1..6
        threatLevel = when {
            isHostile -> ThreatLevel.HOSTILE
            isFactionPlayer -> ThreatLevel.FACTION
            else -> ThreatLevel.PASSIVE
        }
    }
}

enum class ThreatLevel { PASSIVE, FACTION, HOSTILE }
