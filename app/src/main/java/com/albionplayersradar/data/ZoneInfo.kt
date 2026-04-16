package com.albionplayersradar.data

data class ZoneInfo(
    val name: String,
    val pvpType: String,  // "safe" | "yellow" | "red" | "black"
    val tier: Int
)
