package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EventRouter {
    private const val TAG = "EventRouter"

    private var playerListener: PlayerListener? = null

    interface PlayerListener {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float)
        fun onPlayerMountChanged(id: Long, isMounted: Boolean)
        fun onMapChanged(zoneId: String)
        fun onLocalPlayerMoved(posX: Float, posY: Float)
    }

    fun setPlayerListener(listener: PlayerListener?) {
        playerListener = listener
    }

    private var localPlayerId: Long = -1

    // Protocol18: real event code is in params[252] (int16)
    // Two dispatch bytes: 3=Move (hot path), 1=generic
    fun onPhotonEvent(code: Int, params: Map<Byte, Any?>) {
        try {
            when (code) {
                // Event 1 — Player Left
                1 -> {
                    val id = (params[0] as? Number)?.toLong() ?: return
                    if (id == localPlayerId) localPlayerId = -1
                    playerListener?.onPlayerLeft(id)
                }
                // Event 29 — New Character (spawn)
                29 -> {
                    val id = (params[0] as? Number)?.toLong() ?: return
                    val name = params[1] as? String ?: return
                    // Protocol18: guild at index 7, alliance at index 16
                    val guildRaw = params[7]
                    val guild = when (guildRaw) {
                        is String -> guildRaw
                        is ByteArray -> String(guildRaw)
                        else -> ""
                    }
                    val allianceRaw = params[16]
                    val alliance = when (allianceRaw) {
                        is String -> allianceRaw
                        is ByteArray -> String(allianceRaw)
                        else -> ""
                    }
                    // Extract spawn position from params[8] (packed []float32: [posX, posY])
                    val posX = extractFloat32(params[8], 0)
                    val posY = extractFloat32(params[8], 1)
                    // Faction from params[5] (int8)
                    val faction = (params[5] as? Number)?.toInt() ?: 0

                    localPlayerId = id
                    playerListener?.onPlayerJoined(id, name, guild, posX, posY, faction)
                }
                // Event 3 — Move (hot path, dispatched by byte 3)
                3 -> {
                    val id = (params[0] as? Number)?.toLong() ?: return
                    // Protocol18 PostProcess: params[4]=posX, params[5]=posY (float32, injected)
                    val posX = (params[4] as? Number)?.toFloat() ?: return
                    val posY = (params[5] as? Number)?.toFloat() ?: return
                    if (id == localPlayerId) {
                        playerListener?.onLocalPlayerMoved(posX, posY)
                    } else {
                        playerListener?.onPlayerMoved(id, posX, posY)
                    }
                }
                // Event 6 / 8 — HealthUpdate
                6, 8 -> {
                    // Health data — not currently displayed
                }
                // Event 40 — New Mob
                40 -> {
                    // Mob tracking — not currently displayed
                }
                // Zone change (real code varies, routed via byte 1)
                else -> {
                    // Log.d(TAG, "Unhandled event code: $code")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onPhotonEvent error code=$code", e)
        }
    }

    // Extract float32 from packed []float32 at given index
    private fun extractFloat32(value: Any?, index: Int): Float {
        when (value) {
            is FloatArray -> return value.getOrElse(index) { 0f }
            is DoubleArray -> return value.getOrElse(index) { 0.0 }.toFloat()
            is Array<*> -> return (value.getOrElse(index) as? Number)?.toFloat() ?: 0f
            is Number -> return value.toFloat()  // single float passed directly
            else -> return 0f
        }
    }
}
