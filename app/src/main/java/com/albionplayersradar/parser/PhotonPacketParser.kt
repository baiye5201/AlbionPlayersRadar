package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

object PhotonPacketParser {
    private const val TAG = "PhotonPacketParser"
    private const val PACKET_HEADER_SIZE = 12
    private const val CMD_HEADER_SIZE = 12

    private const val CMD_DISCONNECT = 4
    private const val CMD_SEND_RELIABLE = 6
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_SEND_FRAGMENT = 8

    private const val MSG_REQUEST = 2
    private const val MSG_RESPONSE = 3
    private const val MSG_EVENT = 4

    fun parse(data: ByteArray, callback: (type: String, params: Map<Byte, Any>) -> Unit) {
        if (data.size < PACKET_HEADER_SIZE) return
        try {
            val buf = ByteBuffer.wrap(data)
            buf.position(PACKET_HEADER_SIZE)

            while (buf.hasRemaining()) {
                if (buf.remaining() < 4) break
                val cmdType = buf.get().toInt()
                buf.get()
                buf.get()
                buf.get()
                val cmdLen = buf.int
                buf.int

                val payloadLen = cmdLen - CMD_HEADER_SIZE
                if (payloadLen <= 0 || payloadLen > buf.remaining()) break

                when (cmdType) {
                    CMD_DISCONNECT -> { buf.position(buf.position() + payloadLen) }
                    CMD_SEND_RELIABLE -> {
                        buf.get()
                        parseReliable(buf, payloadLen - 1, callback)
                    }
                    CMD_SEND_UNRELIABLE -> {
                        buf.int
                        parseReliable(buf, payloadLen - 4, callback)
                    }
                    CMD_SEND_FRAGMENT -> {
                        parseFragment(buf, payloadLen, callback)
                    }
                    else -> { buf.position(buf.position() + payloadLen) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed", e)
        }
    }

    private val fragmentMap = mutableMapOf<Int, FragmentState>()
    private data class FragmentState(val totalLen: Int, val payload: ByteArray, var written: Int = 0, val seen: MutableSet<Int> = mutableSetOf())

    private fun parseFragment(buf: ByteBuffer, len: Int, callback: (type: String, params: Map<Byte, Any>) -> Unit) {
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
            val fb = ByteBuffer.wrap(state.payload)
            parseReliable(fb, state.payload.size, callback)
        }
    }

    private fun parseReliable(buf: ByteBuffer, len: Int, callback: (type: String, params: Map<Byte, Any>) -> Unit) {
        if (len < 1) return
        buf.get()
        val msgType = buf.get().toInt()

        when (msgType) {
            MSG_EVENT -> {
                val eventCode = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                val count = readUVariant(buf)
                repeat(count) {
                    val key = buf.get()
                    val typeCode = buf.get()
                    params[key] = readValue(buf, typeCode)!!
                }
                params[252.toByte()] = eventCode
                callback("event", params)
            }
            MSG_REQUEST -> {
                val opCode = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                val count = readUVariant(buf)
                repeat(count) {
                    val key = buf.get()
                    val typeCode = buf.get()
                    params[key] = readValue(buf, typeCode)!!
                }
                params[253.toByte()] = opCode
                callback("request", params)
            }
            MSG_RESPONSE -> {
                val opCode = buf.get().toInt() and 0xFF
                buf.short
                val params = mutableMapOf<Byte, Any>()
                val count = readUVariant(buf)
                repeat(count) {
                    val key = buf.get()
                    val typeCode = buf.get()
                    params[key] = readValue(buf, typeCode)!!
                }
                params[253.toByte()] = opCode
                callback("response", params)
            }
        }
    }

    private fun readUVariant(buf: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
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
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
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
                } else {
                    null
                }
            }
        }
    }
}
