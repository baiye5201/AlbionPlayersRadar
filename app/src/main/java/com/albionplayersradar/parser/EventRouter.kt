package com.albionplayersradar.parser

import android.util.Log

object EventRouter {
    interface Callback {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float, posZ: Float)
        fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float)
    }
    private var cb: Callback? = null
    fun setPlayerListener(c: Callback) { cb = c }

    private var localPlayerId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f

    fun setLocalPlayer(id: Long, x: Float, y: Float) {
        localPlayerId = id; localX = x; localY = y
    }

    fun onUdpPacketReceived(data: ByteArray) {
        PhotonPacketParser.parse(data) { type, params ->
            try {
                when (type) {
                    "event" -> {
                        val code = (params[252.toByte()] as? Number)?.toInt() ?: return@parse
                        when (code) {
                            29 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                if (id == localPlayerId) return@parse
                                val name = params[1.toByte()] as? String ?: return@parse
                                val guild = params[8.toByte()] as? String ?: ""
                                val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
                                val loc = params[7.toByte()] as? List<*> ?: return@parse
                                val posX = (loc[0] as? Number)?.toFloat() ?: 0f
                                val posY = (loc[1] as? Number)?.toFloat() ?: 0f
                                cb?.onPlayerJoined(id, name, guild, posX, posY, 0f, faction)
                            }
                            3 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                val posX = (params[4.toByte()] as? Number)?.toFloat() ?: 0f
                                val posY = (params[5.toByte()] as? Number)?.toFloat() ?: 0f
                                cb?.onPlayerMoved(id, posX, posY, 0f)
                            }
                            6 -> {
                                val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                                val cur = (params[2.toByte()] as? Number)?.toFloat() ?: 0f
                                val max = (params[3.toByte()] as? Number)?.toFloat() ?: 1f
                                cb?.onPlayerHealthChanged(id, cur, max)
                            }
                        }
                    }
                    "response" -> {
                        val code = (params[253.toByte()] as? Number)?.toInt() ?: return@parse
                        if (code == 2) {
                            val id = (params[0.toByte()] as? Number)?.toLong() ?: return@parse
                            val posArray = params[9.toByte()] as? ByteArray
                            if (posArray != null && posArray.size >= 8) {
                                val posX = java.nio.ByteBuffer.wrap(posArray).float
                                val posY = java.nio.ByteBuffer.wrap(posArray).float
                                setLocalPlayer(id, posX, posY)
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e("EventRouter", "error", e) }
        }
    }
}
