package com.albionplayersradar.parser

import android.util.Log

object EventRouter {

    interface Callback {
        fun onEvent(code: Int, params: Map<Byte, Any>)
        fun onRequest(code: Int, params: Map<Byte, Any>)
        fun onResponse(code: Int, params: Map<Byte, Any>)
    }

    private var callback: Callback? = null
    private var playerListener: PlayerListener? = null

    interface PlayerListener {
        fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float, posZ: Float)
        fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float)
    }

    fun setPlayerListener(listener: PlayerListener?) {
        playerListener = listener
    }

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    fun onUdpPacketReceived(data: ByteArray) {
        PhotonPacketParser.parsePacket(data) { type, params ->
            when (type) {
                "event" -> {
                    val code = (params[255.toByte()] as? Number)?.toInt() ?: 0
                    when (code) {
                        1 -> handleLeave(params)
                        3 -> handleMove(params)
                        29 -> handleNewCharacter(params)
                        6, 91 -> handleHealth(params)
                    }
                    callback?.onEvent(code, params)
                }
                "request" -> {
                    val code = (params[255.toByte()] as? Number)?.toInt() ?: 0
                    callback?.onRequest(code, params)
                }
                "response" -> {
                    val code = (params[255.toByte()] as? Number)?.toInt() ?: 0
                    callback?.onResponse(code, params)
                }
                else -> {}
            }
        }
    }

    private fun handleLeave(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        playerListener?.onPlayerLeft(id)
    }

    private fun handleMove(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val posX = (params[4.toByte()] as? Number)?.toFloat() ?: 0f
        val posY = (params[5.toByte()] as? Number)?.toFloat() ?: 0f
        val posZ = (params[6.toByte()] as? Number)?.toFloat() ?: 0f
        playerListener?.onPlayerMoved(id, posX, posY, posZ)
    }

    private fun handleNewCharacter(params: Map<Byte, Any>) {
        try {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            val name = params[1.toByte()]?.toString() ?: ""
            val guild = params[8.toByte()]?.toString() ?: ""
            val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
            val posX = (params[4.toByte()] as? Number)?.toFloat() ?: 0f
            val posY = (params[5.toByte()] as? Number)?.toFloat() ?: 0f
            val posZ = (params[6.toByte()] as? Number)?.toFloat() ?: 0f
            playerListener?.onPlayerJoined(id, name, guild, posX, posY, posZ, faction)
        } catch (e: Exception) {
            Log.e("EventRouter", "handleNewCharacter failed", e)
        }
    }

    private fun handleHealth(params: Map<Byte, Any>) {
        try {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            val currentHp = (params[2.toByte()] as? Number)?.toFloat() ?: return
            val maxHp = (params[3.toByte()] as? Number)?.toFloat() ?: return
            playerListener?.onPlayerHealthChanged(id, currentHp, maxHp)
        } catch (e: Exception) {
            Log.e("EventRouter", "handleHealth failed", e)
        }
    }
}
