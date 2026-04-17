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
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import com.albionplayersradar.data.Player
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerCallback {

    private var running = false
    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null
    private var onUpdate: ((String) -> Unit)? = null
    private val parser = PhotonPacketParser()
    private val players = mutableMapOf<Long, Player>()

    companion object {
        private const val TAG = "AlbionVPN"
        private const val NOTIFY_ID = 1
        private const val SERVER_IP = "5.45.187.219"
        private const val SERVER_PORT = 5056
        private const val LOCAL_IP = "10.0.0.2"
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@AlbionVpnService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?) = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.setCallback(this)
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
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    fun setOnUpdateListener(cb: (String) -> Unit) { onUpdate = cb }

    private fun startVpn() {
        if (running) return
        running = true
        try {
            VpnService.prepare(this)?.let { return }
            val builder = VpnService.Builder()
            builder.setSession("AlbionPlayersRadar")
                .addAddress(LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .addAllowedApplication("com.albiononline")

            vpnFd = builder.establish()
            if (vpnFd == null) { stopSelf(); return }

            udpSocket = DatagramSocket()
            protect(udpSocket!!)

            Thread { readLoop() }.start()
            Thread { writeLoop() }.start()
            Log.d(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed: ${e.message}")
            stopSelf()
        }
    }

    private fun readLoop() {
        val fd = vpnFd ?: return
        val inp = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(4096)
        while (running) {
            try {
                val n = inp.read(buf)
                if (n > 0) handleOutgoing(buf, n)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "read: ${e.message}")
                break
            }
        }
    }

    private fun handleOutgoing(buf: ByteArray, len: Int) {
        if (len < 20) return
        if (buf[9].toInt() and 0xFF != 17) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (len < ihl + 8) return
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 1) return
        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)

        parser.parse(payload) { type, params ->
            EventRouter.route(type, params)
        }

        try {
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            udpSocket?.send(pkt)
        } catch (e: Exception) {}
    }

    private fun writeLoop() {
        val buf = ByteArray(4096)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpSocket?.receive(pkt)
                if (pkt.length > 0 && vpnFd != null) {
                    vpnFd?.let { fd ->
                        FileInputStream(fd.fileDescriptor).use { }
                        val out = java.io.FileOutputStream(fd.fileDescriptor)
                        out.write(pkt.data, 0, pkt.length)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "write: ${e.message}")
            }
        }
    }

    override fun onPlayerJoined(player: Player) {
        players[player.id] = player
        onUpdate?.invoke("PLAYER:${player.id}|${player.name}|${player.guildName ?: ""}|${player.faction}")
    }

    override fun onPlayerLeft(id: Long) {
        players.remove(id)
        onUpdate?.invoke("LEFT:$id")
    }

    override fun onPlayerMoved(id: Long, x: Float, y: Float) {
        players[id]?.let { p ->
            p.posX = x
            p.posY = y
            onUpdate?.invoke("MOVE:$id|$x|$y")
        }
    }

    override fun onLocalPlayer(id: Long, x: Float, y: Float, zone: String) {
        onUpdate?.invoke("ZONE:$zone")
    }

    fun stopRun() {
        running = false
        try {
            vpnFd?.close()
            udpSocket?.close()
        } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun getPlayerCount() = players.size
    fun getPlayer(id: Long) = players[id]

    override fun onDestroy() {
        super.onDestroy()
        stopRun()
    }
}
