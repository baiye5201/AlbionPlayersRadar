package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

object EventRouter {
    private const val TAG = "EventRouter"

    interface PlayerListener {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float, posZ: Float)
        fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float)
        fun onLocalPlayerJoined(id: Long, posX: Float, posY: Float, posZ: Float)
        fun onZoneChanged(zoneId: String)
    }

    private var listener: PlayerListener? = null
    private var localPlayerId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f
    private var currentZone: String = ""

    fun setPlayerListener(c: PlayerListener) { listener = c }

    fun onUdpPacketReceived(data: ByteArray) {
        PhotonPacketParser.parsePacket(data) { type, params ->
            try {
                when (type) {
                    "event" -> handleEvent(params)
                    "response" -> handleResponse(params)
                    "request" -> handleRequest(params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "routing error", e)
            }
        }
    }

    private fun handleEvent(params: Map<Byte, Any>) {
        val code = (params[252.toByte()] as? Number)?.toInt() ?: return

        when (code) {
            1 -> { // Leave
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                if (id != localPlayerId) {
                    listener?.onPlayerLeft(id)
                }
            }
            29 -> { // NewCharacter / PlayerJoined
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val name = params[1.toByte()] as? String ?: return
                val guild = params[8.toByte()] as? String ?: ""
                val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0

                // Position from param 7 (list of floats)
                val locList = params[7.toByte()] as? List<*>
                val posX = (locList?.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val posY = (locList?.getOrNull(1) as? Number)?.toFloat() ?: 0f
                val posZ = (locList?.getOrNull(2) as? Number)?.toFloat() ?: 0f

                if (id == localPlayerId) {
                    localX = posX; localY = posY
                    listener?.onLocalPlayerJoined(id, posX, posY, posZ)
                } else {
                    listener?.onPlayerJoined(id, name, guild, posX, posY, posZ, faction)
                }
            }
            3 -> { // Move
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                if (id == localPlayerId) return

                val posX = (params[4.toByte()] as? Number)?.toFloat() ?: 0f
                val posY = (params[5.toByte()] as? Number)?.toFloat() ?: 0f
                listener?.onPlayerMoved(id, posX, posY, 0f)
            }
            6 -> { // HealthUpdate
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val cur = (params[2.toByte()] as? Number)?.toFloat() ?: 0f
                val max = (params[3.toByte()] as? Number)?.toFloat() ?: 1f
                listener?.onPlayerHealthChanged(id, cur, max)
            }
            91 -> { // RegenHealth
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val cur = (params[2.toByte()] as? Number)?.toFloat() ?: 0f
                val max = (params[3.toByte()] as? Number)?.toFloat() ?: 1f
                listener?.onPlayerHealthChanged(id, cur, max)
            }
            209 -> { // MountDismount — tracked in Player data class, not routed separately
            }
            359 -> { // FlagChange — tracked in Player data class, not routed separately
            }
            252 -> { // Internal event code passthrough, ignore
            }
        }
    }

    private fun handleResponse(params: Map<Byte, Any>) {
        val code = (params[253.toByte()] as? Number)?.toInt() ?: return

        when (code) {
            2 -> { // JoinMap response — our local player info
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                localPlayerId = id

                // Position from param 9 (byte array with LE floats or list)
                val posData = params[9.toByte()]
                var posX = 0f
                var posY = 0f

                when (posData) {
                    is ByteArray -> {
                        if (posData.size >= 8) {
                            val bb = ByteBuffer.wrap(posData).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            posX = bb.float
                            posY = bb.float
                        }
                    }
                    is List<*> -> {
                        posX = (posData.getOrNull(0) as? Number)?.toFloat() ?: 0f
                        posY = (posData.getOrNull(1) as? Number)?.toFloat() ?: 0f
                    }
                }

                localX = posX; localY = posY

                // Zone from param 8
                val zoneId = (params[8.toByte()] as? String) ?: ""
                if (zoneId.isNotEmpty() && zoneId != currentZone) {
                    currentZone = zoneId
                    listener?.onZoneChanged(zoneId)
                }

                listener?.onLocalPlayerJoined(id, posX, posY, 0f)
            }
            35, 41 -> { // Cluster switch
                val zoneId = (params[0.toByte()] as? String) ?: return
                if (zoneId != currentZone) {
                    currentZone = zoneId
                    listener?.onZoneChanged(zoneId)
                }
            }
        }
    }

    private fun handleRequest(params: Map<Byte, Any>) {
        // Generally we don't act on outbound requests
    }
}
