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
import com.albionplayersradar.data.Player
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.Listener {

    private var running = false
    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056
    private val LOCAL_IP = "10.0.0.2"

    private var callback: PlayerCallback? = null

    interface PlayerCallback {
        fun onPlayerJoined(player: Player)
        fun onPlayerMoved(id: Long, posX: Float, posY: Float)
        fun onPlayerLeft(id: Long)
        fun onLocalPlayerUpdate(id: Long, posX: Float, posY: Float)
    }

    fun setCallback(cb: PlayerCallback) { callback = cb }

    inner class LocalBinder : Binder() {
        fun getService() = this@AlbionVpnService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent) = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.listener = this
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
            .setContentText("VPN running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).build()
    }

    private fun startVpn() {
        if (running) return
        val prepare = VpnService.prepare(this)
        if (prepare != null) { stopSelf(); return }
        running = true
        try {
            val b = VpnService.Builder()
            b.setSession("AlbionPlayersRadar")
            b.addAddress(LOCAL_IP, 32)
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer("8.8.8.8")
            b.setMtu(1500)
            b.addAllowedApplication("com.albiononline")
            vpnFd = b.establish() ?: return

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
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 1) return
        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)
        EventRouter.onPacket(payload)
        try {
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            udpSocket?.send(pkt)
        } catch (e: Exception) { Log.e(TAG, "send: ${e.message}") }
    }

    private fun writeLoop() {
        val buf = ByteArray(4096)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpSocket?.receive(pkt)
                if (pkt.length > 0) {
                    val resp = buildIpPacket(buf, pkt.length, pkt.address, pkt.port)
                    vpnFd?.let { vpnWrite(it, resp) }
                }
            } catch (e: Exception) { if (running) Log.e(TAG, "recv: ${e.message}") }
        }
    }

    private fun buildIpPacket(data: ByteArray, len: Int, dst: InetAddress, dstPort: Int): ByteArray {
        val total = 28 + len
        val out = ByteArray(total)
        out[0] = 0x40.toByte()
        out[1] = 0x11.toByte()
        java.nio.ByteBuffer.wrap(out, 2, 2).putShort(total.toShort())
        java.nio.ByteBuffer.wrap(out, 4, 4).putInt(0)
        out[8] = 64.toByte(); out[9] = 17
        out[10] = 0; out[11] = 0
        out[12] = 10; out[13] = 0; out[14] = 0; out[15] = 2
        dst.address.copyInto(out, 16)
        java.nio.ByteBuffer.wrap(out, 20, 2).putShort(dstPort.toShort())
        java.nio.ByteBuffer.wrap(out, 22, 2).putShort((8 + len).toShort())
        System.arraycopy(data, 0, out, 28, len)
        return out
    }

    private fun vpnWrite(fd: android.os.ParcelFileDescriptor, data: ByteArray) {
        try {
            val out = FileOutputStream(fd.fileDescriptor)
            out.write(data)
        } catch (e: Exception) { Log.e(TAG, "vpnWrite: ${e.message}") }
    }

    fun stopRun() {
        running = false
        try {
            vpnFd?.close()
            udpSocket?.close()
        } catch (e: Exception) { Log.e(TAG, "stop: ${e.message}") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRun()
    }

    override fun onNewCharacter(id: Long, name: String, guild: String?, alliance: String?, faction: Int, posX: Float, posY: Float, posZ: Float) {
        val player = Player(id, name, guild, alliance, faction, posX, posY, posZ, 0, 0, false)
        callback?.onPlayerJoined(player)
    }

    override fun onCharacterLeft(id: Long) {
        callback?.onPlayerLeft(id)
    }

    override fun onMove(id: Long, posX: Float, posY: Float) {
        callback?.onPlayerMoved(id, posX, posY)
    }

    override fun onHealthChanged(id: Long, currentHp: Float, maxHp: Float) {}
    override fun onFactionChanged(id: Long, faction: Int) {}
    override fun onMountChanged(id: Long, isMounted: Boolean) {}
    override fun onLocalPlayerPosition(id: Long, posX: Float, posY: Float) {
        callback?.onLocalPlayerUpdate(id, posX, posY)
    }

    companion object {
        private const val TAG = "AlbionVpnService"
    }
}
