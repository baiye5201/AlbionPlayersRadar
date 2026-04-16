package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

class PhotonPacketParser {

    fun parse(data: ByteArray, callback: EventRouter.Callback) {
        try {
            if (data.size < 12) return
            val buf = ByteBuffer.wrap(data)
            buf.short
            val flags = (buf.get().toInt() and 0xFF)
            if (flags and 1 != 0) return
            val cmdCount = buf.get().toInt() and 0xFF
            buf.int
            buf.int

            for (i in 0 until cmdCount) {
                val cmdType = buf.get().toInt()
                buf.get()
                buf.get()
                buf.get()
                val cmdLen = buf.int
                buf.int

                if (cmdLen < 12 || cmdLen > 65535) continue
                val payloadLen = cmdLen - 12

                when (cmdType) {
                    7 -> {
                        buf.get()
                        val msgType = buf.get().toInt()
                        val payload = ByteArray(payloadLen - 2)
                        buf.get(payload)
                        when (msgType) {
                            2 -> deserializeRequest(payload, callback)
                            3, 7 -> deserializeResponse(payload, callback)
                            4 -> deserializeEvent(payload, callback)
                        }
                    }
                    6 -> {
                        buf.get()
                        val msgType = buf.get().toInt()
                        val payload = ByteArray(payloadLen - 2)
                        buf.get(payload)
                        when (msgType) {
                            2 -> deserializeRequest(payload, callback)
                            3, 7 -> deserializeResponse(payload, callback)
                            4 -> deserializeEvent(payload, callback)
                        }
                    }
                    else -> {
                        buf.position(buf.position() + payloadLen)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
        }
    }

    private fun deserializeEvent(data: ByteArray, callback: EventRouter.Callback) {
        if (data.isEmpty()) return
        val buf = ByteBuffer.wrap(data)
        val code = buf.get().toInt() and 0xFF
        val params = mutableMapOf<Byte, Any>()
        val count = (buf.get().toInt() and 0xFF)

        for (i in 0 until count) {
            val key = buf.get()
            val value = readValue(buf) ?: continue
            params[key] = value
        }

        callback.onEvent(code, params)
    }

    private fun deserializeRequest(data: ByteArray, callback: EventRouter.Callback) {
        if (data.isEmpty()) return
        val buf = ByteBuffer.wrap(data)
        val code = buf.get().toInt() and 0xFF
        val params = mutableMapOf<Byte, Any>()
        val count = (buf.get().toInt() and 0xFF)

        for (i in 0 until count) {
            val key = buf.get()
            val value = readValue(buf) ?: continue
            params[key] = value
        }

        callback.onRequest(code, params)
    }

    private fun deserializeResponse(data: ByteArray, callback: EventRouter.Callback) {
        if (data.size < 3) return
        val buf = ByteBuffer.wrap(data)
        val code = buf.get().toInt() and 0xFF
        buf.short
        val params = mutableMapOf<Byte, Any>()
        if (buf.hasRemaining()) {
            val count = (buf.get().toInt() and 0xFF)
            for (i in 0 until count) {
                val key = buf.get()
                val value = readValue(buf) ?: continue
                params[key] = value
            }
        }
        callback.onResponse(code, params)
    }

    private fun readValue(buf: ByteBuffer): Any? {
        if (!buf.hasRemaining()) return null
        val typeCode = (buf.get().toInt() and 0xFF)

        return when (typeCode) {
            0, 8 -> null
            2 -> buf.get().toInt() != 0
            27 -> false
            28 -> true
            3 -> buf.get().toInt() and 0xFF
            34 -> 0.toByte()
            4 -> {
                if (buf.remaining() < 2) null
                else buf.short.toInt()
            }
            29 -> 0.toShort()
            5 -> {
                if (buf.remaining() < 4) null
                else buf.float
            }
            32 -> 0f
            6 -> {
                if (buf.remaining() < 8) null
                else buf.double
            }
            33 -> 0.0
            7 -> readString(buf)
            9 -> readVarInt(buf)
            11 -> buf.get().toInt() and 0xFF
            12 -> -(buf.get().toInt() and 0xFF)
            13 -> {
                if (buf.remaining() < 2) null
                else (buf.short.toInt() and 0xFFFF)
            }
            14 -> {
                if (buf.remaining() < 2) null
                else -(buf.short.toInt() and 0xFFFF)
            }
            10 -> readVarLong(buf)
            15 -> buf.get().toLong() and 0xFF
            16 -> -(buf.get().toLong() and 0xFF)
            17 -> {
                if (buf.remaining() < 2) null
                else (buf.short.toLong() and 0xFFFF)
            }
            18 -> {
                if (buf.remaining() < 2) null
                else -(buf.short.toLong() and 0xFFFF)
            }
            30 -> 0
            31 -> 0L
            21 -> readHashtable(buf)
            67 -> readByteArray(buf)
            23 -> readObjectArray(buf)
            24 -> readOperationRequest(buf)
            25 -> readOperationResponse(buf)
            else -> null
        }
    }

    private fun readString(buf: ByteBuffer): String {
        val len = readVarInt(buf)
        if (len <= 0 || buf.remaining() < len) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readVarInt(buf: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (buf.hasRemaining()) {
            val b = (buf.get().toInt() and 0xFF)
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift >= 35) break
        }
        return (result shr 1).xor(-(result and 1))
    }

    private fun readVarLong(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (buf.hasRemaining()) {
            val b = (buf.get().toLong() and 0xFF)
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift >= 70) break
        }
        return (result shr 1).xor(-(result and 1))
    }

    private fun readHashtable(buf: ByteBuffer): Map<Any?, Any?> {
        val count = readVarInt(buf)
        val map = mutableMapOf<Any?, Any?>()
        for (i in 0 until count) {
            val key = readValue(buf) ?: continue
            val value = readValue(buf)
            map[key] = value
        }
        return map
    }

    private fun readByteArray(buf: ByteBuffer): ByteArray {
        val len = readVarInt(buf)
        if (len <= 0 || buf.remaining() < len) return ByteArray(0)
        val arr = ByteArray(len)
        buf.get(arr)
        return arr
    }

    private fun readObjectArray(buf: ByteBuffer): List<Any?> {
        val count = readVarInt(buf)
        val list = mutableListOf<Any?>()
        for (i in 0 until count) {
            list.add(readValue(buf))
        }
        return list
    }

    private fun readOperationRequest(buf: ByteBuffer): Map<Byte, Any?> {
        if (!buf.hasRemaining()) return emptyMap()
        buf.get()
        return readHashtable(buf) as Map<Byte, Any?>
    }

    private fun readOperationResponse(buf: ByteBuffer): Map<Byte, Any?> {
        if (buf.remaining() < 3) return emptyMap()
        buf.get()
        buf.short
        return readHashtable(buf) as Map<Byte, Any?>
    }

    companion object {
        const val TAG = "PhotonParser"
    }
}
