package com.albionplayersradar.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.albionplayersradar.MainApplication
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class AlbionVpnService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null
    private var thread: Thread? = null
    private var proxyThread: Thread? = null

    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056
    private val LOCAL_IP = "10.0.0.2"
    private val NOTIFY_ID = 1001

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d("AlbionVPN", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, makeNotification())
        startVpn()
        return START_STICKY
    }

    private fun makeNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainApplication.CHANNEL_ID)
            .setContentTitle("Albion Players Radar")
            .setContentText("Radar Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (running) return
        running = true

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            val builder = VpnService.Builder()
                .setSession("AlbionPlayersRadar")
                .addAddress(LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .addAllowedApplication("com.albiononline")

            vpnFd = builder.establish()
            if (vpnFd == null) { stopSelf(); return }

            udpSocket = DatagramSocket()
            protect(udpSocket!!)
            udpSocket!!.connect(InetAddress.getByName(SERVER_IP), SERVER_PORT)

            thread = Thread { readLoop() }
            proxyThread = Thread { writeLoop() }
            thread!!.start()
            proxyThread!!.start()

            Log.d("AlbionVPN", "VPN started")

        } catch (e: Exception) {
            Log.e("AlbionVPN", "startVpn failed: ${e.message}")
            stopSelf()
        }
    }

    private val parser = PhotonPacketParser()
    private val players = mutableMapOf<Long, PlayerState>()
    private var localPosX = 0f
    private var localPosY = 0f
    private var localPlayerId: Long = -1
    private var currentZone = ""

    data class PlayerState(
        var name: String = "",
        var guildName: String? = null,
        var allianceName: String? = null,
        var faction: Int = 0,
        var posX: Float = 0f,
        var posY: Float = 0f,
        var health: Float = 0f,
        var maxHealth: Float = 0f,
        var isMounted: Boolean = false,
        var lastSeen: Long = System.currentTimeMillis()
    )

    private var onUpdate: ((String) -> Unit)? = null

    fun setPlayerUpdateListener(cb: (String) -> Unit) { onUpdate = cb }
    private fun notifyUpdate(msg: String) { onUpdate?.invoke(msg) }

    private fun readLoop() {
        val fd = vpnFd ?: return
        val inp = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(2048)
        while (running) {
            try {
                val n = inp.read(buf)
                if (n > 0) handleOutgoing(buf, n)
            } catch (e: Exception) {
                if (running) Log.e("AlbionVPN", "readLoop: ${e.message}")
                break
            }
        }
    }

    private fun handleOutgoing(buf: ByteArray, len: Int) {
        if (len < 20) return
        val version = (buf[0].toInt() and 0xFF) shr 4
        if (version != 4) return
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 12) return

        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)

        parser.parsePacket(payload) { type, params ->
            handlePhotonEvent(type, params)
        }

        try {
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            udpSocket?.send(pkt)
        } catch (e: Exception) {}
    }

    private fun writeLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpSocket?.receive(pkt)
                if (pkt.length > 0) {
                    vpnFd?.let { fd ->
                        FileInputStream(fd.fileDescriptor).write(buf, 0, pkt.length)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun handlePhotonEvent(type: String, params: Map<Byte, Any>) {
        when (type) {
            "event" -> handleEvent(params)
            "response" -> handleResponse(params)
        }
    }

    private fun handleEvent(params: Map<Byte, Any>) {
        val code = (params[252.toByte()] as? Number)?.toInt() ?: return
        when (code) {
            1 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                if (players.remove(id) != null) {
                    notifyUpdate("LEAVE:$id")
                    MainActivity.broadcastPlayerLeave(id)
                }
            }
            29 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                if (id == localPlayerId) return
                val name = params[1.toByte()] as? String ?: return
                val guild = params[8.toByte()] as? String
                val alliance = params[51.toByte()] as? String
                val faction = (params[53.toByte()] as? Number)?.toInt() ?: 0
                players[id] = PlayerState(name = name, guildName = guild, allianceName = alliance, faction = faction)
                notifyUpdate("SPAWN:$id|$name|${guild ?: ""}|${alliance ?: ""}|$faction")
                MainActivity.broadcastPlayerJoined(id, name, guild ?: "", 0f, 0f, faction)
            }
            3 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val raw = params[1.toByte()] as? ByteArray ?: return
                if (raw.size < 17) return
                val px = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                val py = ByteBuffer.wrap(raw, 4, raw.size - 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                players[id]?.let { p ->
                    p.posX = px; p.posY = py; p.lastSeen = System.currentTimeMillis()
                    notifyUpdate("MOVE:$id|$px|$py")
                    MainActivity.broadcastPlayerMove(id, px, py)
                }
            }
            6 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val hp = (params[2.toByte()] as? Number)?.toFloat() ?: return
                val maxHp = (params[3.toByte()] as? Number)?.toFloat() ?: return
                players[id]?.let { p -> p.health = hp; p.maxHealth = maxHp }
                notifyUpdate("HEALTH:$id|$hp|$maxHp")
            }
            91 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val hp = (params[2.toByte()] as? Number)?.toFloat() ?: return
                val maxHp = (params[3.toByte()] as? Number)?.toFloat() ?: return
                players[id]?.let { p -> p.health = hp; p.maxHealth = maxHp }
            }
            209 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val mounted = params[11.toByte()] == true || params[11.toByte()] == "true"
                players[id]?.isMounted = mounted
            }
            359 -> {
                val id = (params[0.toByte()] as? Number)?.toLong() ?: return
                val faction = (params[1.toByte()] as? Number)?.toInt() ?: return
                players[id]?.let { p ->
                    val old = p.faction; p.faction = faction
                    if (old != faction) MainActivity.broadcastFactionChanged(id, faction)
                }
            }
        }
    }

    private fun handleResponse(params: Map<Byte, Any>) {
        val code = (params[253.toByte()] as? Number)?.toInt() ?: return
        if (code == 2) {
            val id = (params[0.toByte()] as? Number)?.toLong() ?: return
            val posData = params[9.toByte()] as? ByteArray
            if (posData != null && posData.size >= 8) {
                val buf = ByteBuffer.wrap(posData).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                localPosX = buf.float; localPosY = buf.float
                localPlayerId = id
                notifyUpdate("LOCAL:$localPosX|$localPosY")
                MainActivity.broadcastLocalPlayer(id, localPosX, localPosY)
            } else {
                val posList = params[9.toByte()] as? List<*>
                if (posList != null && posList.size >= 2) {
                    localPosX = (posList[0] as? Number)?.toFloat() ?: 0f
                    localPosY = (posList[1] as? Number)?.toFloat() ?: 0f
                    localPlayerId = id
                    notifyUpdate("LOCAL:$localPosX|$localPosY")
                    MainActivity.broadcastLocalPlayer(id, localPosX, localPosY)
                }
            }
            val zoneId = params[8.toByte()] as? String
            if (zoneId != null && zoneId.isNotEmpty() && zoneId != currentZone) {
                currentZone = zoneId; players.clear()
                notifyUpdate("ZONE:$zoneId")
                MainActivity.broadcastZone(zoneId)
            }
        }
    }

    fun getAllPlayers() = players.values.toList()
    fun getLocalPosition() = localPosX to localPosY
    fun getCurrentZone() = currentZone
    fun getLocalPlayerId() = localPlayerId
    fun getCurrentZonePvpType() = com.albionplayersradar.data.ZonesDatabase.getPvpType(currentZone)

    override fun onDestroy() {
        running = false
        try { thread?.interrupt() } catch (_: Exception) {}
        try { proxyThread?.interrupt() } catch (_: Exception) {}
        try { vpnFd?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
