package com.albionplayersradar.parser

import android.util.Log

object PhotonPacketParser {

    // Photon packet header constants
    private const val PHOTON_HEADER_SIZE = 12
    private const val COMMAND_HEADER_SIZE = 12

    // Photon message types
    private const val MSG_REQUEST = 2
    private const val MSG_RESPONSE = 3
    private const val MSG_EVENT = 4

    // Photon command types
    private const val CMD_DISCONNECT = 4
    private const val CMD_SEND_RELIABLE = 6
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_SEND_FRAGMENT = 8

    private const val TAG = "PhotonPacketParser"

    fun parsePacket(data: ByteArray, callback: PacketCallback) {
        if (data.size < PHOTON_HEADER_SIZE) return

        try {
            var offset = 0

            // Photon header
            offset += 2  // peerId
            val flags = data[offset++].toInt() and 0xFF
            if (flags == 1) return  // encrypted

            val commandCount = data[offset++].toInt() and 0xFF
            offset += 8  // timestamp + challenge

            // Process commands
            for (i in 0 until commandCount) {
                if (offset + COMMAND_HEADER_SIZE > data.size) break

                val cmdType = data[offset++].toInt()
                offset += 3  // channelId + commandFlags + reserved
                val cmdLen = intFromBigEndian(data, offset)
                offset += 4
                offset += 4  // sequenceNumber

                val payloadLen = cmdLen - COMMAND_HEADER_SIZE
                if (payloadLen <= 0 || offset + payloadLen > data.size) {
                    offset += payloadLen.coerceAtLeast(0)
                    continue
                }

                when (cmdType) {
                    CMD_DISCONNECT -> offset += payloadLen

                    CMD_SEND_UNRELIABLE -> {
                        offset += 4  // skip unreliable header
                        dispatchPayload(data, offset, payloadLen - 4, callback)
                        offset += payloadLen - 4
                    }

                    CMD_SEND_RELIABLE, CMD_SEND_FRAGMENT -> {
                        offset++  // signalByte
                        val msgType = data[offset++].toInt() and 0xFF
                        val actualPayloadLen = payloadLen - 2

                        if (offset + actualPayloadLen > data.size) {
                            offset += actualPayloadLen
                            continue
                        }

                        val payload = data.copyOfRange(offset, offset + actualPayloadLen)
                        offset += actualPayloadLen

                        when (msgType) {
                            MSG_EVENT -> {
                                PhotonDeserializer.parseEventData(payload)?.let {
                                    callback.onEvent(it.eventCode, it.params)
                                }
                            }
                            MSG_REQUEST -> {
                                PhotonDeserializer.parseEventData(payload)?.let {
                                    callback.onRequest(it.eventCode, it.params)
                                }
                            }
                            MSG_RESPONSE -> {
                                PhotonDeserializer.parseEventData(payload)?.let {
                                    callback.onResponse(it.eventCode, it.params)
                                }
                            }
                        }
                    }

                    else -> offset += payloadLen
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun dispatchPayload(data: ByteArray, offset: Int, len: Int, callback: PacketCallback) {
        if (len <= 0 || offset + len > data.size) return
    }

    private fun intFromBigEndian(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
               ((data[offset + 1].toInt() and 0xFF) shl 16) or
               ((data[offset + 2].toInt() and 0xFF) shl 8) or
               (data[offset + 3].toInt() and 0xFF)
    }

    interface PacketCallback {
        fun onEvent(eventCode: Int, params: Map<Int, Any?>)
        fun onRequest(opCode: Int, params: Map<Int, Any?>)
        fun onResponse(opCode: Int, params: Map<Int, Any?>)
    }
}
