package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

object PhotonPacketParser {
    private const val TAG = "PhotonPacketParser"
    private const val PACKET_HEADER_SIZE = 12
    private const val CMD_HEADER_SIZE = 12

    private const val CMD_DISCONNECT: Byte = 4
    private const val CMD_SEND_RELIABLE: Byte = 6
    private const val CMD_SEND_UNRELIABLE: Byte = 7
    private const val CMD_SEND_FRAGMENT: Byte = 8

    private const val MSG_REQUEST: Byte = 2
    private const val MSG_RESPONSE: Byte = 3
    private const val MSG_EVENT: Byte = 4

    interface PacketListener {
        fun onPlayerSpawned(id: Long, name: String, guild: String, alliance: String, faction: Int, posX: Float, posY: Float)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float)
        fun onPlayerLeft(id: Long)
        fun onHealthChanged(id: Long, health: Float, maxHealth: Float)
        fun onFactionChanged(id: Long, faction: Int)
        fun onMountChanged(id: Long, isMounted: Boolean)
        fun onLocalMoved(posX: Float, posY: Float)
        fun onZoneChanged(zoneId: String)
    }

    var listener: PacketListener? = null
    private val fragmentMap = mutableMapOf<Int, FragmentState>()
    private data class FragmentState(val totalLen: Int, val payload: ByteArray, var written: Int = 0, val seen: MutableSet<Int> = mutableSetOf())

    private var localId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f
    private var currentZone: String = ""

    fun parsePacket(data: ByteArray) {
        if (data.size < PACKET_HEADER_SIZE) return
        try {
            val buf = ByteBuffer.wrap(data)
            buf.position(PACKET_HEADER_SIZE)
            while (buf.hasRemaining()) {
                if (buf.remaining() < 4) break
                val cmdType = buf.get()
                buf.get(); buf.get(); buf.get()
                val cmdLen = buf.int
                buf.int
                val payloadLen = cmdLen - CMD_HEADER_SIZE
                if (payloadLen <= 0 || payloadLen > buf.remaining()) break
                when (cmdType.toInt()) {
                    CMD_DISCONNECT.toInt() -> { buf.position(buf.position() + payloadLen) }
                    CMD_SEND_RELIABLE -> { buf.get(); parseReliable(buf, payloadLen - 1) }
                    CMD_SEND_UNRELIABLE -> { buf.int; parseReliable(buf, payloadLen - 4) }
                    CMD_SEND_FRAGMENT -> { parseFragment(buf, payloadLen) }
                    else -> { buf.position(buf.position() + payloadLen) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "parsePacket failed", e) }
    }

    private fun parseFragment(buf: ByteBuffer, len: Int) {
        if (len < 20) return
        val key = buf.int
        buf.int
        val totalLen = buf.int
        val fragOff = buf.int
        val fragLen = len - 20
        val state = fragmentMap.getOrPut(key) { FragmentState(totalLen, ByteArray(totalLen)) }
        if (fragOff >= 0 && fragOff + fragLen <= totalLen && !state.seen.contains(fragOff)) {
            val arr = ByteArray(fragLen)
            buf.get(arr)
            System.arraycopy(arr, 0, state.payload, fragOff, fragLen)
            state.written += fragLen
            state.seen.add(fragOff)
        }
        if (state.written >= state.totalLen) {
            fragmentMap.remove(key)
            parseReliable(ByteBuffer.wrap(state.payload), state.payload.size)
        }
    }

    private fun parseReliable(buf: ByteBuffer, len: Int) {
        if (len < 1) return
        buf.get()
        val msgType = buf.get()
        when (msgType.toInt()) {
            MSG_EVENT.toInt() -> {
                val eventCode = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                val count = readUVariant(buf)
                repeat(count) {
                    val key = buf.get()
                    val typeCode = buf.get()
                    params[key] = readValue(buf, typeCode)!!
                }
                val realCode = (params[252.toByte()] as? Number)?.toInt() ?: eventCode
                handleEvent(realCode, params)
            }
            MSG_REQUEST.toInt() -> {
                val opCode = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                val count = readUVariant(buf)
                repeat(count) {
                    val key = buf.get()
                    val typeCode = buf.get()
                    params[key] = readValue(buf, typeCode)!!
                }
                val realCode = (params[253.toByte()] as? Number)?.toInt() ?: opCode
                if (realCode == 2 || realCode == 21 || realCode == 22) handleJoinOrMove(params)
            }
            MSG_RESPONSE.toInt() -> {
                val opCode = buf.get().toInt() and 0xFF
                buf.short
                val params = mutableMapOf<Byte, Any>()
                val count = readUVariant(buf)
                repeat(count) {
                    val key = buf.get()
                    val typeCode = buf.get()
                    params[key] = readValue(buf, typeCode)!!
                }
                val realCode = (params[253.toByte()] as? Number)?.toInt() ?: opCode
                if (realCode == 2) handleJoinOrMove(params)
                if (realCode == 35 || realCode == 41) handleClusterChange(params)
            }
        }
    }

    private fun handleEvent(code: Int, params: Map<Byte, Any>) {
        when (code) {
            1 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                listener?.onPlayerLeft(id)
            }
            29 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                if (id == localId) return
                val name = params[1.toByte()] as? String ?: return
                val guild = params[8.toByte()] as? String ?: ""
                val alliance = params[51.toByte()] as? String ?: ""
                val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
                val loc = params[7.toByte()] as? List<*> ?: return
                val px = (loc.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val py = (loc.getOrNull(1) as? Number)?.toFloat() ?: 0f
                listener?.onPlayerSpawned(id, name, guild, alliance, faction, px, py)
            }
            3 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val raw = params[1.toByte()] as? ByteArray ?: return
                if (raw.size < 17) return
                val px = ByteBuffer.wrap(raw, 9, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                val py = ByteBuffer.wrap(raw, 13, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                listener?.onPlayerMoved(id, px, py)
            }
            6, 91 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val hp = (params[2.toByte()] as? Number)?.toFloat() ?: return
                val maxHp = (params[3.toByte()] as? Number)?.toFloat() ?: return
                listener?.onHealthChanged(id, hp, maxHp)
            }
            209 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val mounted = params[11.toByte()] == true
                listener?.onMountChanged(id, mounted)
            }
            359 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val faction = (params[1.toByte()] as? Number)?.toInt() ?: return
                listener?.onFactionChanged(id, faction)
            }
        }
    }

    private fun handleJoinOrMove(params: Map<Byte, Any>) {
        val id = (params[0.toByte()] as? Number)?.toLong() ?: return
        val zoneId = params[8.toByte()] as? String ?: ""
        val posData = params[9.toByte()]
        var px = 0f; var py = 0f
        when (posData) {
            is ByteArray -> {
                if (posData.size >= 8) {
                    px = ByteBuffer.wrap(posData).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                    py = ByteBuffer.wrap(posData, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                }
            }
            is List<*> -> {
                px = (posData.getOrNull(0) as? Number)?.toFloat() ?: 0f
                py = (posData.getOrNull(1) as? Number)?.toFloat() ?: 0f
            }
        }
        if (zoneId != currentZone && zoneId.isNotEmpty()) {
            currentZone = zoneId
            listener?.onZoneChanged(zoneId)
        }
        if (id == localId) {
            localX = px; localY = py
            listener?.onLocalMoved(px, py)
        }
    }

    private fun handleClusterChange(params: Map<Byte, Any>) {
        val zoneId = (params[0.toByte()] as? String) ?: return
        if (zoneId != currentZone) {
            currentZone = zoneId
            listener?.onZoneChanged(zoneId)
        }
    }

    private fun readUVariant(buf: ByteBuffer): Int {
        var result = 0; var shift = 0
        while (true) {
            val b = (buf.get().toInt() and 0xFF)
            result = result or ((b and 0x7F) shl shift)
            shift += 7
            if (b and 0x80 == 0 || shift >= 35) break
        }
        return result
    }

    private fun readZigZag32(buf: ByteBuffer): Int {
        val v = readUVariant(buf).toLong()
        return ((v shr 1) xor -(v and 1)).toInt()
    }

    private fun readZigZag64(buf: ByteBuffer): Long {
        var result = 0L; var shift = 0
        while (true) {
            val b = (buf.get().toInt() and 0xFF)
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0 || shift >= 70) break
        }
        return (result shr 1) xor -(result and 1)
    }

    private fun readString(buf: ByteBuffer): String {
        val len = readUVariant(buf)
        if (len <= 0 || len > buf.remaining()) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readHashtable(buf: ByteBuffer): Map<Any, Any> {
        val count = readUVariant(buf)
        val map = mutableMapOf<Any, Any>()
        for (i in 0 until count) {
            val keyType = buf.get()
            val key = readValue(buf, keyType)
            val valType = buf.get()
            val value = readValue(buf, valType)
            map[key!!] = value!!
        }
        return map
    }

    private fun readObjectArray(buf: ByteBuffer): List<Any?> {
        val count = readUVariant(buf)
        val list = mutableListOf<Any?>()
        for (i in 0 until count) {
            val typeCode = buf.get()
            list.add(readValue(buf, typeCode))
        }
        return list
    }

    private fun readTypedArray(buf: ByteBuffer, elemType: Byte): List<Any?> {
        val count = readUVariant(buf)
        val list = mutableListOf<Any?>()
        for (i in 0 until count) {
            list.add(readValue(buf, elemType))
        }
        return list
    }

    private fun readCustom(buf: ByteBuffer): ByteArray {
        val len = readUVariant(buf)
        if (len <= 0 || len > buf.remaining()) return ByteArray(0)
        val bytes = ByteArray(len)
        buf.get(bytes)
        return bytes
    }

    private fun readValue(buf: ByteBuffer, typeCode: Byte): Any? {
        return when (typeCode.toInt()) {
            0, 8 -> null
            2 -> buf.get().toInt() != 0
            3 -> buf.get()
            4 -> buf.short
            5 -> buf.float
            6 -> buf.double
            7 -> readString(buf)
            9 -> readZigZag32(buf)
            10 -> readZigZag64(buf)
            11 -> buf.get().toInt() and 0xFF
            12 -> -(buf.get().toInt() and 0xFF)
            13 -> buf.short.toInt() and 0xFFFF
            14 -> -(buf.short.toInt() and 0xFFFF)
            15 -> buf.get().toLong() and 0xFF
            16 -> -(buf.get().toLong() and 0xFF)
            17 -> buf.short.toLong() and 0xFFFF
            18 -> -(buf.short.toLong() and 0xFFFF)
            21 -> readHashtable(buf)
            23 -> readObjectArray(buf)
            27 -> false
            28 -> true
            29 -> 0.toShort()
            30 -> 0
            31 -> 0L
            32 -> 0f
            33 -> 0.0
            34 -> 0.toByte()
            else -> {
                if (typeCode.toInt() and 0x40 != 0) {
                    readTypedArray(buf, (typeCode.toInt() and 0x3F).toByte())
                } else if (typeCode.toInt() and 0x80 != 0) {
                    readCustom(buf)
                } else null
            }
        }
    }
}
