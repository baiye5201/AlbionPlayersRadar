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
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private const val TAG = "AlbionVpnService"
    private const val NOTIFY_ID = 1001
    private const val SERVER_IP = "5.45.187.219"
    private const val SERVER_PORT = 5056
    private const val LOCAL_IP = "10.0.0.2"
    private const val MTU = 2048

    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private var running = false
    private var udpSocket: DatagramSocket? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.setPlayerListener(this)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId): Int {
        startForeground(NOTIFY_ID, makeNotification("Starting..."))
        startVpn()
        return START_STICKY
    }

    private fun makeNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, MainApplication.CHANNEL_ID)
            .setContentTitle("Albion Players Radar")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (running) return
        running = true

        val prepared = VpnService.prepare(this)
        if (prepared != null) {
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
                .setMtu(MTU)

            try {
                builder.addAllowedApplication("com.albiononline")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot restrict to Albion app", e)
            }

            vpnFd = builder.establish() ?: run {
                Log.e(TAG, "VPN establish returned null")
                stopSelf(); return
            }

            udpSocket = DatagramSocket()
            udpSocket?.connect(InetAddress.getByName(SERVER_IP), SERVER_PORT)
            protect(udpSocket!!)

            Thread { readLoop() }.start()
            Thread { writeLoop() }.start()

            Log.d(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
            stopSelf()
        }
    }

    private fun readLoop() {
        val fd = vpnFd ?: return
        val inp = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)

        while (running) {
            try {
                val n = inp.read(buf)
                if (n > 0) handleOutgoing(buf, n)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "read error", e)
                break
            }
        }
    }

    private fun handleOutgoing(buf: ByteArray, len: Int) {
        if (len < 20) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20) return
        if (buf[9].toInt() and 0xFF != 17) return

        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 12) return

        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)

        EventRouter.onUdpPacketReceived(payload)

        try {
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            udpSocket?.send(pkt)
        } catch (e: Exception) {}
    }

    private fun writeLoop() {
        val sock = udpSocket ?: return
        val buf = ByteArray(MTU)

        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                if (pkt.length > 0) {
                    vpnFd?.let { fd ->
                        FileOutputStream(fd.fileDescriptor).use { it.write(pkt.data, 0, pkt.length) }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "write error", e)
            }
        }
    }

    fun stopRun() {
        running = false
        try { vpnFd?.close(); udpSocket?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        EventRouter.setPlayerListener(null)
        super.onDestroy()
    }

    override fun onPlayerJoined(player: com.albionplayersradar.data.Player) {
        Log.d(TAG, "JOIN: ${player.name}")
        MainActivity.broadcastPlayerJoined(player)
    }

    override fun onPlayerMoved(player: com.albionplayersradar.data.Player) {
        MainActivity.broadcastPlayerMove(player.id, player.posX, player.posY)
    }

    override fun onPlayerLeft(id: Long) {
        MainActivity.broadcastPlayerLeave(id)
    }

    override fun onPlayerHealthChanged(id: Long, health: Float, maxHealth: Float) {
        MainActivity.broadcastHealth(id, health, maxHealth)
    }

    override fun onLocalPlayerMoved(posX: Float, posY: Float) {
        MainActivity.broadcastLocalMove(posX, posY)
    }

    override fun onMapChanged(zoneId: String) {
        Log.d(TAG, "ZONE: $zoneId")
        MainActivity.broadcastZone(zoneId)
    }

    override fun onFactionChanged(id: Long, faction: Int) {}
    override fun onMountChanged(id: Long, isMounted: Boolean) {}
}
