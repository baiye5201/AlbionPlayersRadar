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
    var eventRouter: EventRouter? = null

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
        eventRouter!!.setPlayerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "radar_channel")
            .setContentTitle("Albion Players Radar")
            .setContentText("VPN is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        running = true

        try {
            val vpnBuilder = VpnService.Builder()
            vpnBuilder.setSession("AlbionPlayersRadar")
            vpnBuilder.addAddress("10.0.0.2", 32)
            vpnBuilder.addRoute("0.0.0.0", 0)
            vpnBuilder.addDnsServer("8.8.8.8")

            val tunnel = vpnBuilder.establish()

            proxySocket = DatagramSocket()
            proxySocket!!.connect(InetAddress.getByName(SERVER_IP), SERVER_PORT)

            readerThread = Thread {
                val buffer = ByteArray(2048)
                while (running) {
                    try {
                        if (tunnel != null) {
                            val len = tunnel.read(buffer, 0, buffer.size)
                            if (len > 0) {
                                eventRouter?.onUdpPacketReceived(buffer.copyOf(len))
                                val packet = DatagramPacket(buffer.copyOf(len), len, InetAddress.getByName(SERVER_IP), SERVER_PORT)
                                proxySocket?.send(packet)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AlbionVPN", "read loop error", e)
                    }
                }
            }
            readerThread!!.start()

            Thread {
                val buffer = ByteArray(2048)
                while (running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        proxySocket?.receive(packet)
                        if (packet.length > 0) {
                            tunnel?.write(packet.data, 0, packet.length)
                        }
                    } catch (e: Exception) {
                        Log.e("AlbionVPN", "write loop error", e)
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e("AlbionVPN", "VPN start failed", e)
        }
    }

    fun stopRun() {
        running = false
        try {
            readerThread?.interrupt()
            proxySocket?.close()
        } catch (e: Exception) {
            Log.e("AlbionVPN", "stop failed", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRun()
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int) {
        Log.d("AlbionVPN", "Player joined: $name [$guild] at ($posX, $posY, $posZ)")
    }

    override fun onPlayerLeft(id: Long) {
        Log.d("AlbionVPN", "Player left: $id")
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float, posZ: Float) {}

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {}
}
