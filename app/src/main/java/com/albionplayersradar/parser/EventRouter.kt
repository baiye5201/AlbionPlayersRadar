package com.albionplayersradar.parser

import com.albionplayersradar.data.Player

object EventRouter {
    interface PlayerCallback {
        fun onPlayerJoined(player: Player)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, x: Float, y: Float)
        fun onLocalPlayer(id: Long, x: Float, y: Float, zone: String)
    }

    private var cb: PlayerCallback? = null
    fun setCallback(c: PlayerCallback) { cb = c }

    private var localId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f
    private var currentZone: String = ""

    fun route(type: String, params: Map<Byte, Any>) {
        try {
            when (type) {
                "event" -> {
                    val code = (params[252.toByte()] as? Number)?.toInt() ?: return
                    when (code) {
                        29 -> handlePlayerSpawn(params)
                        3 -> handlePlayerMove(params)
                        6 -> {} // health handled by move
                    }
                }
                "response" -> {
                    val code = (params[253.toByte()] as? Number)?.toInt() ?: return
                    if (code == 2) handleJoinMap(params)
                }
            }
        } catch (e: Exception) {}
    }

    private fun handlePlayerSpawn(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        if (id == localId) return
        val name = params[1.toByte()] as? String ?: return
        val guild = params[8.toByte()] as? String
        val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
        cb?.onPlayerJoined(Player(id, name, guild, null, faction))
    }

    private fun handlePlayerMove(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        if (id == localId) return
        val posData = params[1.toByte()] as? ByteArray ?: return
        if (posData.size < 8) return
        val posX = java.nio.ByteBuffer.wrap(posData).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        val posY = java.nio.ByteBuffer.wrap(posData, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        cb?.onPlayerMoved(id, posX, posY)
    }

    private fun handleJoinMap(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val zone = params[8.toByte()] as? String ?: ""
        val posData = params[9.toByte()] as? ByteArray
        if (posData != null && posData.size >= 8) {
            localX = java.nio.ByteBuffer.wrap(posData).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            localY = java.nio.ByteBuffer.wrap(posData, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        }
        localId = id
        currentZone = zone
        cb?.onLocalPlayer(id, localX, localY, zone)
    }
}
