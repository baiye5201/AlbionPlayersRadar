package com.albionplayersradar.parser

import android.util.Log

object PhotonPacketParser {

    private const val TAG = "PhotonParser"
    private const val PHOTON_HEADER = 12
    private const val CMD_HEADER = 12

    // Photon command types
    private const val CMD_DISCONNECT = 4
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_SEND_FRAGMENT = 8

    // Photon message types
    private const val MSG_EVENT = 4
    private const val MSG_REQUEST = 2
    private const val MSG_RESPONSE = 3

    // Fragment state
    private val pendingFragments = mutableMapOf<Int, FragmentState>()

    data class FragmentState(
        val totalLen: Int,
        val received: MutableList<ByteArray> = mutableListOf()
    )

    fun parsePacket(payload: ByteArray, onEvent: (Int, Map<Byte, Any?>) -> Unit) {
        if (payload.size < PHOTON_HEADER) return

        val flags = payload[2].toInt() and 0xFF
        if (flags == 1) {
            Log.w(TAG, "Encrypted packet — cannot parse")
            return
        }

        val cmdCount = payload[3].toInt() and 0xFF
        var offset = PHOTON_HEADER

        for (i in 0 until cmdCount) {
            if (offset + CMD_HEADER > payload.size) break

            val cmdType = payload[offset].toInt() and 0xFF
            val cmdLen = (
                ((payload[offset + 8].toInt() and 0xFF) shl 24) or
                ((payload[offset + 9].toInt() and 0xFF) shl 16) or
                ((payload[offset + 10].toInt() and 0xFF) shl 8) or
                (payload[offset + 11].toInt() and 0xFF)
            )

            val dataOffset = offset + CMD_HEADER
            val dataLen = cmdLen - CMD_HEADER

            if (dataOffset + dataLen > payload.size) break

            when (cmdType) {
                CMD_DISCONNECT -> { /* skip */ }
                CMD_SEND_UNRELIABLE -> {
                    // Skip sequence number (4 bytes), then read msgType
                    val msgOffset = dataOffset + 4
                    if (msgOffset < payload.size) {
                        val msgType = payload[msgOffset].toInt() and 0xFF
                        when (msgType) {
                            MSG_EVENT -> {
                                val eventData = payload.copyOfRange(msgOffset + 1, msgOffset + dataLen)
                                parseEventData(eventData, onEvent)
                            }
                            MSG_REQUEST, MSG_RESPONSE -> {
                                // Request/response not used for player tracking
                            }
                        }
                    }
                }
                CMD_SEND_FRAGMENT -> {
                    val fragData = payload.copyOfRange(dataOffset, dataOffset + dataLen)
                    val assembled = parseFragment(fragData)
                    if (assembled != null) {
                        parseEventData(assembled, onEvent)
                    }
                }
            }

            offset += cmdLen
        }
    }

    private fun parseEventData(data: ByteArray, onEvent: (Int, Map<Byte, Any?>) -> Unit) {
        if (data.size < 3) return

        val dispatchByte = data[0].toInt() and 0xFF
        val realCode = (
            ((data[1].toInt() and 0xFF)) or
            ((data[2].toInt() and 0xFF) shl 8)
        )

        val params = PhotonDeserializer.deserialize(data.copyOfRange(3, data.size))
        EventRouter.onPhotonEvent(realCode, params)
    }

    private fun parseFragment(data: ByteArray): ByteArray? {
        if (data.size < 20) return null

        val seqNum = (
            ((data[0].toInt() and 0xFF) shl 8) or
            (data[1].toInt() and 0xFF)
        )
        val fragCount = data[2].toInt() and 0xFF
        val fragNum = data[3].toInt() and 0xFF
        val totalLen = (
            ((data[4].toInt() and 0xFF) shl 24) or
            ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or
            (data[7].toInt() and 0xFF)
        )

        val state = pendingFragments.getOrPut(seqNum) {
            FragmentState(totalLen)
        }
        state.received.add(data.copyOfRange(20, data.size))

        if (state.received.size == fragCount) {
            pendingFragments.remove(seqNum)
            val assembled = ByteArray(totalLen)
            var pos = 0
            for (frag in state.received) {
                System.arraycopy(frag, 0, assembled, pos, frag.size)
                pos += frag.size
            }
            return assembled
        }
        return null
    }
}
