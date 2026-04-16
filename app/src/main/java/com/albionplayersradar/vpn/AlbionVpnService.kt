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
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private var running = false
    private var readerThread: Thread? = null
    private var proxySocket: DatagramSocket? = null
    private var tunFd: FileDescriptor? = null
    private var eventRouter: EventRouter? = null

    private val PHOTON_PORT = 5056
    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        eventRouter = EventRouter()
        eventRouter?.setPlayerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("Starting..."))
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "albion_vpn")
            .setContentTitle("Albion Players Radar")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun startVpn() {
        if (running) return
        running = true

        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            return
        }

        val builder = Builder()
        builder.setMtu(2048)
        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("8.8.4.4")
        builder.addAllowedApplication("com.albiononline")

        try {
            val vpnInterface = builder.establish()
            if (vpnInterface != null) {
                tunFd = vpnInterface
                proxySocket = DatagramSocket(PHOTON_PORT)
                proxySocket?.reuseAddress = true
                readerThread = Thread { readLoop() }
                readerThread?.start()
                updateNotification("Scanning for players...")
            }
        } catch (e: Exception) {
            Log.e("AlbionVpn", "VPN start failed", e)
        }
    }

    fun stopVpn() {
        running = false
        try {
            readerThread?.interrupt()
            readerThread = null
            proxySocket?.close()
            proxySocket = null
            tunFd = null
        } catch (e: Exception) {
            Log.e("AlbionVpn", "Stop failed", e)
        }
        updateNotification("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun readLoop() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running && proxySocket != null) {
            try {
                proxySocket?.receive(packet)
                val data = packet.data.copyOf(packet.length)
                eventRouter?.onUdpPacketReceived(data)
            } catch (e: Exception) {
                if (running) {
                    Log.e("AlbionVpn", "Read error", e)
                }
            }
        }
    }

    fun setPlayerListener(listener: EventRouter.PlayerListener) {
        eventRouter?.setPlayerListener(listener)
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int) {
        Log.d("AlbionVpn", "Player: $name [$guild] at ($posX, $posY)")
    }

    override fun onPlayerLeft(id: Long) {}

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {}

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
