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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.Callback {

    private var running = false
    private var readerThread: Thread? = null
    private var proxySocket: DatagramSocket? = null

    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

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
            .setContentText("VPN running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).build()
    }

    private fun startVpn() {
        if (running) return
        running = true
        try {
            VpnService.prepare(this)?.let { return }
            val b = VpnService.Builder()
            b.setSession("AlbionPlayersRadar")
            b.addAddress("10.0.0.2", 32)
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer("8.8.8.8")
            b.setMtu(1500)
            val tunnel = b.establish() ?: return

            proxySocket = DatagramSocket()
            proxySocket!!.connect(InetAddress.getByName(SERVER_IP), SERVER_PORT)

            Thread {
                val buf = ByteArray(2048)
                while (running) {
                    try {
                        val len = tunnel.read(buf, 0, buf.size)
                        if (len > 0) {
                            EventRouter.onUdpPacketReceived(buf.copyOf(len))
                            val pkt = DatagramPacket(buf.copyOf(len), len, InetAddress.getByName(SERVER_IP), SERVER_PORT)
                            proxySocket?.send(pkt)
                        }
                    } catch (e: Exception) {
                        if (running) Log.e("VPN", "read", e)
                    }
                }
            }.start()

            Thread {
                val buf = ByteArray(2048)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        proxySocket?.receive(pkt)
                        if (pkt.length > 0) tunnel.write(pkt.data, 0, pkt.length)
                    } catch (e: Exception) {
                        if (running) Log.e("VPN", "write", e)
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e("AlbionVPN", "failed", e)
        }
    }

    fun stopRun() {
        running = false
        try {
            readerThread?.interrupt()
            proxySocket?.close()
        } catch (e: Exception) { Log.e("VPN", "stop", e) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRun()
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int) {
        Log.d("AlbionVPN", "JOIN: $name [$guild] ($posX,$posY)")
        MainActivity.broadcastPlayerEvent(id, name, guild, posX, posY, faction)
    }

    override fun onPlayerLeft(id: Long) {
        Log.d("AlbionVPN", "LEFT: $id")
        MainActivity.broadcastPlayerLeave(id)
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float, posZ: Float) {
        MainActivity.broadcastPlayerMove(id, posX, posY)
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {}
}
