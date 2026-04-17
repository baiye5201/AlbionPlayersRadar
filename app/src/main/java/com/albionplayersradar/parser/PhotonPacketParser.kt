package com.albionplayersradar.parser

import android.util.Log
import java.nio.ByteBuffer

object PhotonPacketParser {

    private const val TAG = "PhotonParser"
    private const val PACKET_HEADER = 12
    private const val CMD_HEADER = 12

    private const val CMD_DISCONNECT: Byte = 4
    private const val CMD_SEND_RELIABLE: Byte = 6
    private const val CMD_SEND_UNRELIABLE: Byte = 7
    private const val CMD_SEND_FRAGMENT: Byte = 8

    private const val MSG_REQUEST: Byte = 2
    private const val MSG_RESPONSE: Byte = 3
    private const val MSG_EVENT: Byte = 4

    private val fragmentMap = mutableMapOf<Int, FragmentState>()

    private data class FragmentState(
        val totalLen: Int,
        val payload: ByteArray,
        var written: Int = 0,
        val seen: MutableSet<Int> = mutableSetOf()
    ) {
        override fun equals(other: Any?) = other is FragmentState && other.totalLen == totalLen
        override fun hashCode() = totalLen
    }

    fun parse(data: ByteArray, cb: (String, Map<Byte, Any>) -> Unit) {
        if (data.size < PACKET_HEADER) return
        try {
            val buf = ByteBuffer.wrap(data)
            buf.position(PACKET_HEADER)

            while (buf.hasRemaining()) {
                if (buf.remaining() < 4) break
                val cmdType = buf.get()
                buf.get(); buf.get(); buf.get()
                val cmdLen = buf.int
                buf.int

                val payloadLen = cmdLen - CMD_HEADER
                if (payloadLen <= 0 || payloadLen > buf.remaining()) break

                when (cmdType.toInt()) {
                    CMD_DISCONNECT.toInt() -> buf.position(buf.position() + payloadLen)
                    CMD_SEND_RELIABLE -> {
                        buf.get()
                        parseReliable(buf, payloadLen - 1, cb)
                    }
                    CMD_SEND_UNRELIABLE -> {
                        buf.int
                        parseReliable(buf, payloadLen - 4, cb)
                    }
                    CMD_SEND_FRAGMENT -> parseFragment(buf, payloadLen, cb)
                    else -> buf.position(buf.position() + payloadLen)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed", e)
        }
    }

    private fun parseFragment(buf: ByteBuffer, len: Int, cb: (String, Map<Byte, Any>) -> Unit) {
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
            parseReliable(fb, state.payload.size, cb)
        }
    }

    private fun parseReliable(buf: ByteBuffer, len: Int, cb: (String, Map<Byte, Any>) -> Unit) {
        if (len < 1) return
        buf.get()
        val msgType = buf.get()

        when (msgType.toInt()) {
            MSG_EVENT -> {
                val code = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                repeat(readUVariant(buf)) {
                    params[buf.get()] = readValue(buf, buf.get())!!
                }
                params[252.toByte()] = code
                cb("event", params)
            }
            MSG_REQUEST -> {
                val op = buf.get().toInt() and 0xFF
                val params = mutableMapOf<Byte, Any>()
                repeat(readUVariant(buf)) {
                    params[buf.get()] = readValue(buf, buf.get())!!
                }
                params[253.toByte()] = op
                cb("request", params)
            }
            MSG_RESPONSE -> {
                val op = buf.get().toInt() and 0xFF
                buf.short
                val params = mutableMapOf<Byte, Any>()
                repeat(readUVariant(buf)) {
                    params[buf.get()] = readValue(buf, buf.get())!!
                }
                params[253.toByte()] = op
                cb("response", params)
            }
        }
    }

    private fun readUVariant(buf: ByteBuffer): Int {
        var r = 0; var s = 0
        while (true) {
            val b = (buf.get().toInt() and 0xFF)
            r = r or ((b and 0x7F) shl s)
            s += 7
            if (b and 0x80 == 0 || s >= 35) break
        }
        return r
    }

    private fun readZigZag32(buf: ByteBuffer): Int {
        val v = readUVariant(buf).toLong()
        return ((v shr 1) xor -(v and 1)).toInt()
    }

    private fun readZigZag64(buf: ByteBuffer): Long {
        var r = 0L; var s = 0
        while (true) {
            val b = (buf.get().toInt() and 0xFF)
            r = r or ((b and 0x7F).toLong() shl s)
            s += 7
            if (b and 0x80 == 0 || s >= 70) break
        }
        return (r shr 1) xor -(r and 1)
    }

    private fun readString(buf: ByteBuffer): String {
        val len = readUVariant(buf)
        if (len <= 0 || len > buf.remaining()) return ""
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    private fun readHashtable(buf: ByteBuffer): Map<Any, Any> {
        val map = mutableMapOf<Any, Any>()
        repeat(readUVariant(buf)) {
            val key = readValue(buf, buf.get())!!
            val value = readValue(buf, buf.get())!!
            map[key] = value
        }
        return map
    }

    private fun readObjectArray(buf: ByteBuffer): List<Any?> {
        val list = mutableListOf<Any?>()
        repeat(readUVariant(buf)) { list.add(readValue(buf, buf.get())) }
        return list
    }

    private fun readTypedArray(buf: ByteBuffer, elemType: Byte): List<Any?> {
        val list = mutableListOf<Any?>()
        repeat(readUVariant(buf)) { list.add(readValue(buf, elemType)) }
        return list
    }

    private fun readCustom(buf: ByteBuffer): ByteArray {
        val len = readUVariant(buf)
        if (len <= 0 || len > buf.remaining()) return ByteArray(0)
        val b = ByteArray(len)
        buf.get(b)
        return b
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
