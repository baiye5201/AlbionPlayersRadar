package com.albionplayersradar.parser

import android.util.Log

object PhotonDeserializer {

    private const val TAG = "PhotonDeserializer"

    data class PhotonHeader(
        val peerId: Int,
        val flags: Int,
        val commandCount: Int,
        val timestamp: Long,
        val challenge: Long
    )

    data class Command(
        val cmdType: Int,
        val channelId: Int,
        val flags: Int,
        val length: Int,
        val sequenceNumber: Int,
        val payload: ByteArray
    )

    data class EventData(
        val code: Int,
        val params: Map<Int, Any>
    )

    data class ParseResult(
        val events: List<EventData>,
        val requests: List<OperationRequest>,
        val responses: List<OperationResponse>
    )

    data class OperationRequest(
        val opCode: Int,
        val params: Map<Int, Any>
    )

    data class OperationResponse(
        val opCode: Int,
        val returnCode: Int,
        val params: Map<Int, Any>
    )

    private const val CMD_DISCONNECT = 4
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_SEND_RELIABLE = 6
    private const val MSG_REQUEST = 2
    private const val MSG_RESPONSE = 3
    private const val MSG_EVENT = 4

    fun parsePacket(data: ByteArray): ParseResult {
        val events = mutableListOf<EventData>()
        val requests = mutableListOf<OperationRequest>()
        val responses = mutableListOf<OperationResponse>()

        try {
            if (data.size < 12) return ParseResult(events, requests, responses)

            val peerId = intFromBytes(data[0], data[1], data[2], data[3])
            val flags = data[4].toInt() and 0xFF
            val commandCount = data[5].toInt() and 0xFF
            val timestamp = intFromBytes(data[6], data[7], data[8], data[9]).toLong() and 0xFFFFFFFFL
            val challenge = intFromBytes(data[10], data[11], data[12], data[13]).toLong() and 0xFFFFFFFFL

            if (flags and 1 != 0) return ParseResult(events, requests, responses)

            var offset = 14
            repeat(commandCount) {
                if (offset + 12 > data.size) return@repeat
                val cmdType = data[offset].toInt() and 0xFF
                offset += 4
                val cmdLen = intFromBytes(data[offset], data[offset + 1], data[offset + 2], data[offset + 3])
                offset += 4
                val seqNum = intFromBytes(data[offset], data[offset + 1], data[offset + 2], data[offset + 3])
                offset += 4
                val payloadLen = cmdLen - 12
                if (payloadLen < 0 || offset + payloadLen > data.size) return@repeat

                when (cmdType) {
                    CMD_DISCONNECT -> { }
                    CMD_SEND_UNRELIABLE -> {
                        if (payloadLen > 4) {
                            val payload = data.copyOfRange(offset + 4, offset + payloadLen)
                            parseMessage(payload, events, requests, responses)
                        }
                    }
                    CMD_SEND_RELIABLE -> {
                        if (payloadLen > 2) {
                            val payload = data.copyOfRange(offset + 2, offset + payloadLen)
                            parseMessage(payload, events, requests, responses)
                        }
                    }
                }
                offset += payloadLen
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
        return ParseResult(events, requests, responses)
    }

    private fun parseMessage(payload: ByteArray, events: MutableList<EventData>, requests: MutableList<OperationRequest>, responses: MutableList<OperationResponse>) {
        if (payload.size < 2) return
        val msgType = payload[0].toInt() and 0xFF
        val body = payload.copyOfRange(1, payload.size)
        when (msgType) {
            MSG_EVENT -> {
                val ev = parseEventData(body)
                if (ev != null) events.add(ev)
            }
            MSG_REQUEST -> {
                val req = parseRequest(body)
                if (req != null) requests.add(req)
            }
            MSG_RESPONSE -> {
                val resp = parseResponse(body)
                if (resp != null) responses.add(resp)
            }
        }
    }

    fun parseEventData(data: ByteArray): EventData? {
        try {
            if (data.isEmpty()) return null
            val code = data[0].toInt() and 0xFF
            val params = mutableMapOf<Int, Any>()
            var offset = 1
            val count = readUInt8(data, offset); offset++
            repeat(count) {
                if (offset >= data.size) return@repeat
                val key = data[offset].toInt() and 0xFF; offset++
                if (offset >= data.size) return@repeat
                val (value, newOffset) = readValue(data, offset)
                params[key] = value
                offset = newOffset
            }
            return EventData(code, params)
        } catch (e: Exception) { return null }
    }

    private fun parseRequest(data: ByteArray): OperationRequest? {
        try {
            if (data.size < 1) return null
            val opCode = data[0].toInt() and 0xFF
            val params = mutableMapOf<Int, Any>()
            var offset = 1
            val count = readUInt8(data, offset); offset++
            repeat(count) {
                if (offset >= data.size) return@repeat
                val key = data[offset].toInt() and 0xFF; offset++
                if (offset >= data.size) return@repeat
                val (value, newOffset) = readValue(data, offset)
                params[key] = value
                offset = newOffset
            }
            return OperationRequest(opCode, params)
        } catch (e: Exception) { return null }
    }

    private fun parseResponse(data: ByteArray): OperationResponse? {
        try {
            if (data.size < 3) return null
            val opCode = data[0].toInt() and 0xFF
            val returnCode = intFromBytes(data[1], data[2], 0, 0)
            val params = mutableMapOf<Int, Any>()
            var offset = 3
            val count = readUInt8(data, offset); offset++
            repeat(count) {
                if (offset >= data.size) return@repeat
                val key = data[offset].toInt() and 0xFF; offset++
                if (offset >= data.size) return@repeat
                val (value, newOffset) = readValue(data, offset)
                params[key] = value
                offset = newOffset
            }
            return OperationResponse(opCode, returnCode, params)
        } catch (e: Exception) { return null }
    }

    private fun readValue(data: ByteArray, offset: Int): Pair<Any, Int> {
        if (offset >= data.size) return Pair(Unit, offset)
        val typeCode = data[offset].toInt() and 0xFF
        return when (typeCode) {
            0 -> Pair(Unit, offset + 1)
            2 -> {
                val v = if (offset + 1 < data.size) data[offset + 1].toInt() != 0 else false
                Pair(v, offset + 2)
            }
            3 -> {
                val v = if (offset + 1 < data.size) data[offset + 1].toInt() and 0xFF else 0
                Pair(v, offset + 2)
            }
            4 -> {
                val v = if (offset + 2 < data.size) intFromBytes(data[offset + 1], data[offset + 2], 0, 0).toShort() else 0.toShort()
                Pair(v, offset + 3)
            }
            5 -> {
                if (offset + 4 < data.size) {
                    val bits = intFromBytes(data[offset + 1], data[offset + 2], data[offset + 3], data[offset + 4])
                    Pair(Float.fromBits(bits), offset + 5)
                } else Pair(0f, offset + 1)
            }
            7 -> {
                val len = readUInt16(data, offset + 1)
                val end = offset + 3 + len
                if (end > data.size) Pair("", offset + 3) else {
                    val str = String(data, offset + 3, len, Charsets.UTF_8)
                    Pair(str, end)
                }
            }
            9 -> {
                val (v, end) = readCompressedInt(data, offset + 1)
                Pair(v, end)
            }
            11 -> {
                val v = if (offset + 1 < data.size) data[offset + 1].toInt() and 0xFF else 0
                Pair(v, offset + 2)
            }
            12 -> {
                val v = if (offset + 1 < data.size) -(data[offset + 1].toInt() and 0xFF) else 0
                Pair(v, offset + 2)
            }
            13 -> {
                val v = if (offset + 2 < data.size) readUInt16(data, offset + 1) else 0
                Pair(v, offset + 3)
            }
            14 -> {
                val v = if (offset + 2 < data.size) -readUInt16(data, offset + 1) else 0
                Pair(v, offset + 3)
            }
            21 -> {
                val count = readUInt16(data, offset + 1)
                val map = mutableMapOf<Any, Any>()
                var p = offset + 3
                repeat(count) {
                    if (p >= data.size) return@repeat
                    val (key, p2) = readValue(data, p)
                    if (p2 >= data.size) return@repeat
                    val (value, p3) = readValue(data, p2)
                    map[key] = value
                    p = p3
                }
                Pair(map, offset + 3 + count * 2)
            }
            27 -> Pair(false, offset + 1)
            28 -> Pair(true, offset + 1)
            29 -> Pair(0.toShort(), offset + 1)
            30 -> Pair(0, offset + 1)
            31 -> Pair(0L, offset + 1)
            67 -> {
                val len = readUInt16(data, offset + 1)
                val end = offset + 3 + len
                if (end > data.size) Pair(byteArrayOf(), offset + 3)
                else Pair(data.copyOfRange(offset + 3, end), end)
            }
            else -> {
                if (typeCode and 0x40 != 0) {
                    val elemType = typeCode and 0x3F
                    val len = readUInt16(data, offset + 1)
                    val list = mutableListOf<Any>()
                    var p = offset + 3
                    repeat(len) {
                        if (p >= data.size) return@repeat
                        val (v, np) = readPrimitive(elemType, data, p)
                        list.add(v); p = np
                    }
                    Pair(list, p)
                } else {
                    Pair(Unit, offset + 1)
                }
            }
        }
    }

    private fun readPrimitive(type: Int, data: ByteArray, offset: Int): Pair<Any, Int> {
        return when (type) {
            2 -> {
                val v = if (offset < data.size) data[offset].toInt() != 0 else false
                Pair(v, offset + 1)
            }
            3 -> {
                val v = if (offset < data.size) data[offset].toInt() and 0xFF else 0
                Pair(v, offset + 1)
            }
            4 -> {
                val v = if (offset + 1 < data.size) intFromBytes(data[offset], data[offset + 1], 0, 0).toShort() else 0.toShort()
                Pair(v, offset + 2)
            }
            5 -> {
                if (offset + 3 < data.size) {
                    val bits = intFromBytes(data[offset], data[offset + 1], data[offset + 2], data[offset + 3])
                    Pair(Float.fromBits(bits), offset + 4)
                } else Pair(0f, offset + 1)
            }
            7 -> {
                val len = if (offset < data.size) data[offset].toInt() and 0xFF else 0
                Pair(String(data, offset + 1, len, Charsets.UTF_8), offset + 1 + len)
            }
            else -> Pair(Unit, offset + 1)
        }
    }

    private fun readUInt8(data: ByteArray, offset: Int): Int {
        return if (offset < data.size) data[offset].toInt() and 0xFF else 0
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readCompressedInt(data: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos].toInt()
            result = result or ((b and 0x7F) shl shift)
            shift += 7
            pos++
            if (b and 0x80 == 0) break
            if (shift >= 35) break
        }
        val decoded = (result ushr 1) xor -(result and 1)
        return Pair(decoded, pos)
    }

    private fun intFromBytes(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int {
        return (b0.toInt() and 0xFF) or
                ((b1.toInt() and 0xFF) shl 8) or
                ((b2.toInt() and 0xFF) shl 16) or
                ((b3.toInt() and 0xFF) shl 24)
    }
}
