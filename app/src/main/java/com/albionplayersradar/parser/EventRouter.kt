package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EventRouter {
    private const val TAG = "EventRouter"

    private var playerListener: PlayerListener? = null
    private var localPlayerId: Long = -1

    interface PlayerListener {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float)
        fun onPlayerHealthChanged(id: Long, current: Int, max: Int)
        fun onPlayerMountChanged(id: Long, isMounted: Boolean)
        fun onMapChanged(zoneId: String)
        fun onLocalPlayerMoved(posX: Float, posY: Float)
    }

    fun setPlayerListener(listener: PlayerListener?) {
        playerListener = listener
    }

    fun onUdpPacketReceived(data: ByteArray) {
        PhotonPacketParser.parse(data) { type, params ->
            try {
                when (type) {
                    "event" -> handleEvent(params)
                    "request" -> handleRequest(params)
                    "response" -> handleResponse(params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "dispatch error: ${e.message}")
            }
        }
    }

    private fun handleEvent(params: Map<Byte, Any>) {
        val code = (params[252.toByte()] as? Number)?.toInt() ?: return

        when (code) {
            // ── Event 1: Leave ─────────────────────────────────────────────────
            1 -> {
                val id = params.getLong(0) ?: return
                playerListener?.onPlayerLeft(id)
            }

            // ── Event 3: Move (injected by PostProcess in parser) ───────────────
            3 -> {
                val id = params.getLong(0) ?: return
                if (id == localPlayerId) return
                val posX = params.getFloat(4) ?: return
                val posY = params.getFloat(5) ?: return
                playerListener?.onPlayerMoved(id, posX, posY)
            }

            // ── Event 6: HealthUpdate ───────────────────────────────────────────
            6 -> {
                val id = params.getLong(0) ?: return
                val currentHP = params.getInt(3) ?: return
                val maxHP = params.getInt(13) ?: currentHP
                if (maxHP > 0) {
                    playerListener?.onPlayerHealthChanged(id, currentHP, maxHP)
                }
            }

            // ── Event 29: NewCharacter ──────────────────────────────────────────
            29 -> {
                val id = params.getLong(0) ?: return
                if (id == localPlayerId) return
                val name = params[1.toByte()] as? String ?: return
                val guild = params[8.toByte()] as? String ?: ""
                val faction = params.getInt(53) ?: 0
                // Position from params[7] (float32[2] array) per spec
                val posX: Float
                val posY: Float
                val loc = params[7.toByte()]
                when (loc) {
                    is List<*> -> {
                        posX = (loc.getOrNull(0) as? Number)?.toFloat() ?: 0f
                        posY = (loc.getOrNull(1) as? Number)?.toFloat() ?: 0f
                    }
                    is ByteArray -> {
                        if (loc.size >= 8) {
                            val buf = ByteBuffer.wrap(loc).order(ByteOrder.LITTLE_ENDIAN)
                            posX = buf.float
                            posY = buf.float
                        } else { posX = 0f; posY = 0f }
                    }
                    else -> { posX = 0f; posY = 0f }
                }
                playerListener?.onPlayerJoined(id, name, guild, posX, posY, faction)
            }

            // ── Event 91: RegenerationHealthChanged ────────────────────────────
            91 -> {
                val id = params.getLong(0) ?: return
                val cur = params.getInt(2) ?: return
                val max = params.getInt(3) ?: cur
                playerListener?.onPlayerHealthChanged(id, cur, max)
            }

            // ── Event 209: Mounted ──────────────────────────────────────────────
            209 -> {
                val id = params.getLong(0) ?: return
                val mounted = params[11.toByte()] == true ||
                        params[10.toByte()]?.toString() == "-1"
                playerListener?.onPlayerMountChanged(id, mounted)
            }

            // ── Event 359: ChangeFlaggingFinished — faction change ──────────────
            // (handled via player rejoining with new faction in practice)
        }
    }

    private fun handleRequest(params: Map<Byte, Any>) {
        val opCode = (params[253.toByte()] as? Number)?.toInt() ?: return
        // ops 21/22 = Move request — extract local player position
        if (opCode == 21 || opCode == 22) {
            val posData = params[1.toByte()]
            when (posData) {
                is List<*> -> {
                    if (posData.size >= 2) {
                        val x = (posData[0] as? Number)?.toFloat() ?: return
                        val y = (posData[1] as? Number)?.toFloat() ?: return
                        playerListener?.onLocalPlayerMoved(x, y)
                    }
                }
                is ByteArray -> {
                    if (posData.size >= 8) {
                        val buf = ByteBuffer.wrap(posData).order(ByteOrder.LITTLE_ENDIAN)
                        playerListener?.onLocalPlayerMoved(buf.float, buf.float)
                    }
                }
            }
        }
    }

    private fun handleResponse(params: Map<Byte, Any>) {
        val opCode = (params[253.toByte()] as? Number)?.toInt() ?: return
        when (opCode) {
            // JoinMap response
            2 -> {
                val mapId = params[8.toByte()] as? String
                if (!mapId.isNullOrEmpty()) {
                    playerListener?.onMapChanged(mapId)
                }
                // Local player spawn position
                val posData = params[9.toByte()]
                when (posData) {
                    is List<*> -> {
                        if (posData.size >= 2) {
                            val x = (posData[0] as? Number)?.toFloat() ?: return
                            val y = (posData[1] as? Number)?.toFloat() ?: return
                            playerListener?.onLocalPlayerMoved(x, y)
                        }
                    }
                    is ByteArray -> {
                        if (posData.size >= 8) {
                            val buf = ByteBuffer.wrap(posData).order(ByteOrder.LITTLE_ENDIAN)
                            playerListener?.onLocalPlayerMoved(buf.float, buf.float)
                        }
                    }
                }
            }
            // Zone change
            35, 41 -> {
                val zone = params[0.toByte()] as? String ?: return
                playerListener?.onMapChanged(zone)
            }
        }
    }

    // ── Extension helpers ───────────────────────────────────────────────────────
    private fun Map<Byte, Any>.getLong(key: Int): Long? =
        (this[key.toByte()] as? Number)?.toLong()

    private fun Map<Byte, Any>.getInt(key: Int): Int? =
        (this[key.toByte()] as? Number)?.toInt()

    private fun Map<Byte, Any>.getFloat(key: Int): Float? =
        (this[key.toByte()] as? Number)?.toFloat()
}
