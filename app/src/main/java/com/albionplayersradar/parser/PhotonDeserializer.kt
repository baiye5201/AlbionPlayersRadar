package com.albionplayersradar.parser

import android.util.Log

object PhotonDeserializer {

    private const val TAG = "PhotonDeserializer"

    // Photon Protocol18 type codes
    private const val TYPE_NULL = 8
    private const val TYPE_BOOL_FALSE = 27
    private const val TYPE_BOOL_TRUE = 28
    private const val TYPE_SHORT_ZERO = 29
    private const val TYPE_INT_ZERO = 30
    private const val TYPE_LONG_ZERO = 31
    private const val TYPE_FLOAT_ZERO = 32
    private const val TYPE_DOUBLE_ZERO = 33
    private const val TYPE_BYTE_ZERO = 34
    private const val TYPE_ARRAY = 0x40

    private const val PHOTON_HEADER_LEN = 12
    private const val CMD_HEADER_LEN = 12
    private const val CMD_DISCONNECT = 4
    private const val CMD_SEND_RELIABLE = 6
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_FRAGMENT = 8
    private const val MSG_REQUEST = 2
    private const val MSG_RESPONSE = 3
    private const val MSG_EVENT = 4

    data class PacketResult(
        val events: MutableList<EventData> = mutableListOf(),
        val requests: MutableList<RequestData> = mutableListOf(),
        val responses: MutableList<ResponseData> = mutableListOf()
    )

    data class EventData(val code: Int, val params: Map<Int, Any?>)
    data class RequestData(val code: Int, val params: Map<Int, Any?>)
    data class ResponseData(val code: Int, val returnCode: Int, val params: Map<Int, Any?>)

    fun parsePacket(payload: ByteArray): PacketResult {
        val result = PacketResult()
        if (payload.size < PHOTON_HEADER_LEN) return result
        var offset = 0
        offset += 2 // peerId
        val flags = payload[offset].toInt() and 0xFF; offset++
        if (payload.size < offset + 1) return result
        val cmdCount = payload[offset].toInt() and 0xFF; offset++
        offset += 8 // timestamp + challenge
        if (flags == 1) return result

        for (i in 0 until cmdCount) {
            if (offset + CMD_HEADER_LEN > payload.size) break
            offset += 4 // cmdType + channelId
            val cmdLen = readUInt(payload, offset).toInt(); offset += 4
            offset += 4 // sequence number
            val pLen = cmdLen - CMD_HEADER_LEN
            if (pLen < 0 || offset + pLen > payload.size) break
            val cmdType = payload[offset - CMD_HEADER_LEN - 4].toInt()

            when (cmdType) {
                CMD_DISCONNECT -> { offset += pLen }
                CMD_SEND_UNRELIABLE -> { if (pLen >= 4) { offset += 4; parseMessage(payload, offset, pLen - 4, result) } else offset += pLen }
                CMD_SEND_RELIABLE -> { parseMessage(payload, offset, pLen, result); offset += pLen }
                CMD_FRAGMENT -> { offset += pLen }
                else -> offset += pLen
            }
        }
        return result
    }

    private fun parseMessage(data: ByteArray, offset: Int, len: Int, result: PacketResult) {
        if (len < 2 || offset >= data.size) return
        val sig = data[offset]; offset++
        val msgType = data[offset]; offset++
        val msgData = data.copyOfRange(offset, minOf(offset + len - 2, data.size))
        val buf = msgData.inputStream()

        when (msgType.toInt()) {
            MSG_REQUEST -> {
                val opCode = buf.read()
                val params = readParams(buf)
                result.requests.add(RequestData(opCode, params))
            }
            MSG_RESPONSE, 7 -> {
                val opCode = buf.read()
                val retCode = if (buf.available() >= 2) readUShortFromBuf(buf).toInt() else 0
                val params = readParams(buf)
                result.responses.add(ResponseData(opCode, retCode, params))
            }
            MSG_EVENT -> {
                val code = buf.read()
                val params = readParams(buf)
                result.events.add(EventData(code, params))
            }
        }
    }

    private fun readParams(buf: java.io.ByteArrayInputStream): Map<Int, Any?> {
        val params = mutableMapOf<Int, Any?>()
        val count = readVarInt(buf).toInt()
        repeat(count) {
            val key = buf.read()
            val value = readValue(buf)
            params[key] = value
        }
        return params
    }

    private fun readValue(buf: java.io.ByteArrayInputStream): Any? {
        if (buf.available() == 0) return null
        val typeCode = buf.read()
        return when (typeCode) {
            TYPE_NULL -> null
            TYPE_BOOL_FALSE -> false
            TYPE_BOOL_TRUE -> true
            2 -> buf.read() != 0
            3 -> buf.read().toByte()
            4 -> readShort(buf)
            5 -> java.lang.Float.intBitsToFloat(readUIntFromBuf(buf).toInt())
            6 -> java.lang.Double.longBitsToDouble(readULongFromBuf(buf))
            7 -> readString(buf)
            9 -> readVarInt(buf).toInt()
            11 -> buf.read().toByte().toInt()
            12 -> -buf.read().toByte().toInt()
            13 -> readUShortFromBuf(buf).toInt()
            14 -> -readUShortFromBuf(buf).toInt()
            10 -> readVarLong(buf)
            15 -> buf.read().toLong()
            16 -> -buf.read().toLong()
            17 -> readUShortFromBuf(buf).toLong()
            18 -> -readUShortFromBuf(buf).toLong()
            30 -> 0
            31 -> 0L
            32 -> 0f
            33 -> 0.0
            34 -> 0.toByte()
            67 -> readByteArray(buf)
            23 -> readObjectArray(buf)
            21 -> readHashtable(buf)
            else -> {
                if (typeCode and TYPE_ARRAY == TYPE_ARRAY) readTypedArray(buf, typeCode and 0x3F)
                else null
            }
        }
    }

    private fun readVarInt(buf: java.io.ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.read().toLong()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
            if (shift >= 35) break
        }
        return (result shr 1) xor -(result and 1)
    }

    private fun readVarLong(buf: java.io.ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.read().toLong()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
            if (shift >= 70) break
        }
        return (result shr 1) xor -(result and 1)
    }

    private fun readString(buf: java.io.ByteArrayInputStream): String {
        val len = readVarInt(buf).toInt()
        if (len <= 0 || len > 65536) return ""
        val bytes = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = buf.read(bytes, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }

    private fun readByteArray(buf: java.io.ByteArrayInputStream): ByteArray {
        val len = readVarInt(buf).toInt()
        if (len <= 0 || len > 65536) return ByteArray(0)
        val arr = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = buf.read(arr, read, len - read)
            if (r < 0) break
            read += r
        }
        return arr
    }

    private fun readObjectArray(buf: java.io.ByteArrayInputStream): List<Any?> {
        val count = readVarInt(buf).toInt()
        if (count < 0 || count > 65536) return emptyList()
        return (0 until count).map { readValue(buf) }
    }

    private fun readHashtable(buf: java.io.ByteArrayInputStream): Map<Any?, Any?> {
        val count = readVarInt(buf).toInt()
        if (count < 0 || count > 65536) return emptyMap()
        val map = mutableMapOf<Any?, Any?>()
        repeat(count) {
            val key = readValue(buf)
            val value = readValue(buf)
            if (key != null) map[key] = value
        }
        return map
    }

    private fun readTypedArray(buf: java.io.ByteArrayInputStream, elemType: Int): Any? {
        val count = readVarInt(buf).toInt()
        if (count < 0 || count > 65536) return null
        return when (elemType) {
            2 -> (0 until count).map { buf.read() != 0 }.toList()
            3 -> { val a = ByteArray(count); buf.read(a, 0, count); a.toList() }
            4 -> (0 until count).map { readShort(buf) }.toList()
            11 -> (0 until count).map { buf.read().toByte().toInt() }.toList()
            9 -> (0 until count).map { readVarInt(buf).toInt() }.toList()
            10 -> (0 until count).map { readVarLong(buf) }.toList()
            5 -> (0 until count).map { java.lang.Float.intBitsToFloat(readUIntFromBuf(buf).toInt()) }.toList()
            7 -> (0 until count).map { readString(buf) }.toList()
            else -> null
        }
    }

    private fun readShort(buf: java.io.ByteArrayInputStream): Short {
        val lo = buf.read(); val hi = buf.read()
        return ((lo and 0xFF) or ((hi and 0xFF) shl 8)).toShort()
    }

    private fun readUShortFromBuf(buf: java.io.ByteArrayInputStream): Int {
        val lo = buf.read(); val hi = buf.read()
        return (lo and 0xFF) or ((hi and 0xFF) shl 8)
    }

    private fun readUIntFromBuf(buf: java.io.ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.read().toLong()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    private fun readULongFromBuf(buf: java.io.ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.read().toLong()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    private fun readUInt(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24))
    }
}
