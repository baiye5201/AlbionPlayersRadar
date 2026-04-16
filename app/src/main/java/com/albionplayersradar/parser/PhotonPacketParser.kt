package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

class PhotonPacketParser {

    interface PlayerListener {
        fun onPlayerJoined(id: Int, name: String, guild: String, posX: Float, posY: Float, faction: Int)
        fun onPlayerLeft(id: Int)
        fun onPlayerMoved(id: Int, posX: Float, posY: Float)
    }

    var listener: PlayerListener? = null

    fun parse(data: ByteArray, callback: EventRouter.Callback) {
        try {
            if (data.size < 12) return
            val buf = ByteBuffer.wrap(data)

            buf.short               // peerId
            val flags = buf.byte.toInt() and 0xFF
            if (flags and 1 != 0) return
            val cmdCount = buf.byte.toInt() and 0xFF
            buf.int                // timestamp
            buf.int                // challenge

            for (i in 0 until cmdCount) {
                val cmdType = buf.byte.toInt()
                buf.byte
                buf.byte
                buf.byte
                val cmdLen = buf.int
                buf.int

                if (cmdLen < 12 || cmdLen > 65535) continue
                val payloadLen = cmdLen - 12

                if (cmdType == 7) {
                    buf.byte
                    val msgType = buf.byte.toInt()
                    val payload = ByteArray(payloadLen - 2)
                    buf.get(payload)

                    when (msgType) {
                        2 -> deserializeRequest(payload, callback)
                        3, 7 -> deserializeResponse(payload, callback)
                        4 -> deserializeEvent(payload, callback)
                    }
                } else if (cmdType == 6) {
                    buf.byte
                    val msgType = buf.byte.toInt()
                    val payload = ByteArray(payloadLen - 2)
                    buf.get(payload)

                    when (msgType) {
                        2 -> deserializeRequest(payload, callback)
                        3, 7 -> deserializeResponse(payload, callback)
                        4 -> deserializeEvent(payload, callback)
                    }
                } else {
                    buf.position(buf.position() + payloadLen)
                }
            }
        } catch (e: Exception) {
            Log.e("PhotonParser", "Parse error: ${e.message}")
        }
    }

    fun parse(data: ByteArray, listener: PlayerListener) {
        this.listener = listener
        parse(data, object : EventRouter.Callback {
            override fun onEvent(code: Int, params: Map<Byte, Any>) {
                EventRouter.routeEvent(code, params, listener)
            }
            override fun onRequest(code: Int, params: Map<Byte, Any>) {}
            override fun onResponse(code: Int, params: Map<Byte, Any>) {}
        })
    }

    private fun deserializeRequest(data: ByteArray, cb: EventRouter.Callback) {
        if (data.isEmpty()) return
        val buf = ByteBuffer.wrap(data)
        val opCode = (buf.byte.toInt() and 0xFF).toByte()
        val params = deserializeParams(buf)
        cb.onRequest(opCode.toInt(), params)
    }

    private fun deserializeResponse(data: ByteArray, cb: EventRouter.Callback) {
        if (data.size < 3) return
        val buf = ByteBuffer.wrap(data)
        val opCode = (buf.byte.toInt() and 0xFF).toByte()
        buf.short
        val params = deserializeParams(buf)
        cb.onResponse(opCode.toInt(), params)
    }

    private fun deserializeEvent(data: ByteArray, cb: EventRouter.Callback) {
        if (data.isEmpty()) return
        val buf = ByteBuffer.wrap(data)
        val eventCode = (buf.byte.toInt() and 0xFF).toByte()
        val params = deserializeParams(buf)
        cb.onEvent(eventCode.toInt(), params)
    }

    private fun deserializeParams(buf: ByteBuffer): Map<Byte, Any> {
        val params = mutableMapOf<Byte, Any>()
        try {
            val count = deserializeInt(buf)
            for (i in 0 until count) {
                if (buf.remaining() < 2) break
                val key = (buf.byte.toInt() and 0xFF).toByte()
                if (buf.remaining() < 1) break
                val typeCode = buf.byte.toInt() and 0xFF
                val value: Any = when (typeCode) {
                    0 -> deserializeCustom(buf)
                    2 -> buf.byte.toInt() != 0
                    3 -> buf.byte.toInt() and 0xFF
                    4 -> deserializeShort(buf)
                    5 -> deserializeFloat(buf)
                    6 -> deserializeDouble(buf)
                    7 -> deserializeString(buf)
                    8 -> Any()
                    9 -> deserializeInt(buf)
                    10 -> deserializeLong(buf)
                    11 -> buf.byte.toInt() and 0xFF
                    12 -> -(buf.byte.toInt() and 0x7F)
                    13 -> deserializeUShort(buf).toInt()
                    14 -> -deserializeUShort(buf).toInt()
                    15 -> buf.byte.toLong()
                    16 -> -buf.byte.toLong()
                    17 -> deserializeUShort(buf).toLong()
                    18 -> -deserializeUShort(buf).toLong()
                    21 -> deserializeHashtable(buf)
                    27 -> false
                    28 -> true
                    29 -> 0.toShort()
                    30 -> 0
                    31 -> 0L
                    32 -> 0.0f
                    33 -> 0.0
                    34 -> 0.toByte()
                    67 -> deserializeByteArray(buf)
                    23 -> deserializeObjectArray(buf)
                    else -> deserializeCustom(buf)
                }
                params[key] = value
            }
        } catch (e: Exception) {}
        return params
    }

    private fun deserializeInt(buf: ByteBuffer): Int {
        val b = buf.byte.toInt() and 0xFF
        return when {
            b == 30 -> 0
            b == 11 -> buf.byte.toInt() and 0xFF
            b == 12 -> -(buf.byte.toInt() and 0x7F)
            b == 13 -> deserializeUShort(buf).toInt()
            b == 14 -> -deserializeUShort(buf).toInt()
            b == 9 -> {
                var result = 0
                var shift = 0
                while (true) {
                    val bb = (buf.byte.toInt() and 0xFF)
                    result = result or ((bb and 0x7F) shl shift)
                    if ((bb and 0x80) == 0) break
                    shift += 7
                }
                (result ushr 1) xor -(result and 1)
            }
            else -> 0
        }
    }

    private fun deserializeShort(buf: ByteBuffer): Short {
        val b = buf.byte.toInt() and 0xFF
        return when {
            b == 29 -> 0
            b == 4 -> {
                val lo = buf.byte.toInt() and 0xFF
                val hi = buf.byte.toInt() and 0xFF
                ((lo or (hi shl 8))).toShort()
            }
            else -> 0
        }
    }

    private fun deserializeUShort(buf: ByteBuffer): Int {
        val lo = buf.byte.toInt() and 0xFF
        val hi = buf.byte.toInt() and 0xFF
        return lo or (hi shl 8)
    }

    private fun deserializeFloat(buf: ByteBuffer): Float {
        return java.lang.Float.intBitsToFloat(buf.int)
    }

    private fun deserializeDouble(buf: ByteBuffer): Double {
        return java.lang.Double.longBitsToDouble(buf.long)
    }

    private fun deserializeLong(buf: ByteBuffer): Long {
        val b = buf.byte.toInt() and 0xFF
        return when {
            b == 31 -> 0L
            b == 15 -> buf.byte.toLong()
            b == 16 -> -buf.byte.toLong()
            b == 17 -> deserializeUShort(buf).toLong()
            b == 18 -> -deserializeUShort(buf).toLong()
            b == 10 -> {
                var result = 0L
                var shift = 0
                while (true) {
                    val bb = (buf.byte.toInt() and 0xFF)
                    result = result or ((bb and 0x7F).toLong() shl shift)
                    if ((bb and 0x80) == 0) break
                    shift += 7
                }
                (result ushr 1) xor -(result and 1)
            }
            else -> 0L
        }
    }

    private fun deserializeString(buf: ByteBuffer): String {
        val len = deserializeInt(buf)
        if (len <= 0 || len > 8192) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun deserializeByteArray(buf: ByteBuffer): ByteArray {
        val len = deserializeInt(buf)
        if (len <= 0 || len > 65536) return ByteArray(0)
        val arr = ByteArray(len)
        buf.get(arr)
        return arr
    }

    private fun deserializeHashtable(buf: ByteBuffer): Map<*, *> {
        val map = mutableMapOf<Any, Any?>()
        try {
            val count = deserializeInt(buf)
            for (i in 0 until count) {
                if (buf.remaining() < 2) break
                val key = deserializeInt(buf)
                val value: Any? = try { deserializeCustom(buf) } catch (_: Exception) { null }
                map[key] = value
            }
        } catch (_: Exception) {}
        return map
    }

    private fun deserializeObjectArray(buf: ByteBuffer): List<*> {
        val list = mutableListOf<Any?>()
        try {
            val count = deserializeInt(buf)
            for (i in 0 until count) {
                if (buf.remaining() < 1) break
                list.add(deserializeCustom(buf))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun deserializeCustom(buf: ByteBuffer): Any {
        try {
            val typeCode = buf.byte.toInt() and 0xFF
            when {
                typeCode < 128 -> {
                    val size = deserializeInt(buf)
                    if (size <= 0 || size > 65536) return Any()
                    val data = ByteArray(size)
                    buf.get(data)
                    return data
                }
                typeCode == 67 -> return deserializeByteArray(buf)
                typeCode == 9 -> return deserializeInt(buf)
                typeCode == 10 -> return deserializeLong(buf)
                typeCode == 5 -> return deserializeFloat(buf)
                typeCode == 7 -> return deserializeString(buf)
                typeCode == 4 -> return deserializeShort(buf)
                typeCode == 3 -> return buf.byte.toInt() and 0xFF
                typeCode == 2 -> return buf.byte.toInt() != 0
                else -> {
                    val size = deserializeInt(buf)
                    if (size <= 0) return Any()
                    val data = ByteArray(size)
                    buf.get(data)
                    return data
                }
            }
        } catch (_: Exception) {}
        return Any()
    }

    companion object {
        const val EVENT_NEW_CHARACTER = 29
        const val EVENT_MOVE = 3
        const val EVENT_LEAVE = 1
        const val EVENT_HEALTH_UPDATE = 6
        const val OP_LOGIN = 2
        const val OP_MOVE = 22
        const val OP_GET_CHARACTERS = 16
    }
}
