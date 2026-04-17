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
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private val binder = LocalBinder()
    private var running = false
    private var tunnelFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.setPlayerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        startVpn()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "radar_channel")
            .setContentTitle("Albion Players Radar")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (running) return
        try {
            val prepared = VpnService.prepare(this)
            if (prepared != null) return

            val b = VpnService.Builder()
            b.setSession("AlbionPlayersRadar")
            b.addAddress("10.0.0.2", 32)
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer("8.8.8.8")
            b.setMtu(1500)

            val tunnel = b.establish()
            if (tunnel == null) { stopSelf(); return }

            tunnelFd = tunnel
            running = true

            Thread {
                val buf = ByteArray(4096)
                val fis = FileInputStream(tunnelFd!!.fileDescriptor)
                while (running) {
                    try {
                        val len = fis.read(buf)
                        if (len > 0) handleOutgoing(buf.copyOf(len))
                    } catch (e: Exception) {
                        if (running) Log.e("VPN", "read: ${e.message}")
                    }
                }
            }.start()

            Thread {
                val buf = ByteArray(4096)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        udpSocket?.receive(pkt)
                        if (pkt.length > 0) {
                            val resp = buildIpPacket(pkt.data, pkt.length)
                            fis.write(resp)
                        }
                    } catch (e: Exception) {
                        if (running) Log.e("VPN", "write: ${e.message}")
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e("AlbionVPN", "start failed: ${e.message}")
            stopSelf()
        }
    }

    private fun handleOutgoing(data: ByteArray) {
        if (data.size < 20) return
        val ihl = (data[0].toInt() and 0x0F) * 4
        val proto = data[9].toInt() and 0xFF
        if (proto != 17) return
        val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = data.size - payloadOff
        if (payloadLen < 8) return

        val payload = data.copyOfRange(payloadOff, payloadOff + payloadLen)
        EventRouter.onUdpPacketReceived(payload)

        try {
            if (udpSocket == null) {
                udpSocket = DatagramSocket()
                protect(udpSocket!!)
            }
            val dstIp = InetAddress.getByAddress(byteArrayOf(data[16], data[17], data[18], data[19]))
            udpSocket!!.send(DatagramPacket(payload, payload.size, dstIp, dstPort))
        } catch (e: Exception) {
            Log.e("VPN", "proxy: ${e.message}")
        }
    }

    private fun buildIpPacket(data: ByteArray, len: Int): ByteArray {
        val srcIp = byteArrayOf(5, 45.toByte(), (187).toByte(), (219).toByte())
        val dstIp = byteArrayOf(10, 0, 0, 2)
        val srcPort = byteArrayOf((SERVER_PORT shr 8).toByte(), (SERVER_PORT and 0xFF).toByte())
        val dstPort = byteArrayOf(data[2], data[3])
        val totalLen = 20 + len

        val ip = ByteArray(20 + len)
        ip[0] = 0x45.toByte()
        ip[1] = 0.toByte()
        ip[2] = (totalLen shr 8).toByte()
        ip[3] = (totalLen and 0xFF).toByte()
        ip[4] = 0; ip[5] = 0
        ip[6] = 0x40.toByte()
        ip[7] = 0.toByte()
        ip[8] = 64; ip[9] = 17
        ip[10] = 0; ip[11] = 0
        System.arraycopy(dstIp, 0, ip, 12, 4)
        System.arraycopy(srcIp, 0, ip, 16, 4)
        System.arraycopy(dstPort, 0, ip, 20, 2)
        System.arraycopy(srcPort, 0, ip, 22, 2)
        System.arraycopy(data, 0, ip, 24, len.coerceAtMost(data.size))
        return ip
    }

    fun stopRun() {
        running = false
        try {
            tunnelFd?.close()
            udpSocket?.close()
        } catch (e: Exception) { Log.e("VPN", "stop: ${e.message}") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRun()
    }

    companion object {
        var onUpdate: ((String) -> Unit)? = null
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
        Log.d("VPN", "JOIN $name [$guild] f=$faction")
        onUpdate?.invoke("JOIN:$id|$name|$guild|$faction|$posX|$posY")
    }

    override fun onPlayerLeft(id: Long) {
        Log.d("VPN", "LEFT $id")
        onUpdate?.invoke("LEAVE:$id")
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        onUpdate?.invoke("MOVE:$id|$posX|$posY")
    }

    override fun onPlayerMountChanged(id: Long, isMounted: Boolean) {}

    override fun onMapChanged(zoneId: String) {
        onUpdate?.invoke("ZONE:$zoneId")
    }

    override fun onLocalPlayerMoved(posX: Float, posY: Float) {
        onUpdate?.invoke("LOCAL:$posX|$posY")
    }
}
