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
import com.albionplayersradar.R
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private val binder = LocalBinder()
    private var running = false
    private var readerThread: Thread? = null
    private var tunFd: FileDescriptor? = null
    private var proxySocket: DatagramSocket? = null
    private var playerListener: EventRouter.PlayerListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onCreate() {
        super.onCreate()
        EventRouter.setPlayerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    fun startVpn() {
        if (running) return
        try {
            val builder = android.net.VpnService.Builder()
                                                
                .setMtu(2048)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")

            builder.addAllowedApplication("com.albiononline")

            tunFd = builder.establish()?.fileDescriptor
            if (tunFd == null) {
                Log.e(TAG, "VPN establish returned null")
                return
            }

            proxySocket = DatagramSocket(5056)
            proxySocket?.reuseAddress = true

            running = true
            readerThread = Thread { readLoop() }
            readerThread?.start()
            Log.i(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn error: ${e.message}")
        }
    }

    fun stopVpn() {
        running = false
        readerThread?.interrupt()
        readerThread = null
        proxySocket?.close()
        proxySocket = null
        tunFd = null
        Log.i(TAG, "VPN stopped")
    }

    fun setPlayerListener(listener: EventRouter.PlayerListener) {
        playerListener = listener
    }

    private fun readLoop() {
        val buffer = ByteArray(65535)
        val socket = proxySocket ?: return
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val pkt = DatagramPacket(buffer, buffer.size)
                socket.receive(pkt)
                val data = pkt.data.copyOf(pkt.length)
                processPacket(data)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "readLoop error: ${e.message}")
            }
        }
    }

    private fun processPacket(data: ByteArray) {
        try {
            val eventRouter = object : EventRouter.Callback {
                override fun onEvent(code: Int, params: Map<Byte, Any>) {
                    EventRouter.routeEvent(code, params)
                }
                override fun onRequest(code: Int, params: Map<Byte, Any>) {}
                override fun onResponse(code: Int, params: Map<Byte, Any>) {}
            }
            com.albionplayersradar.parser.PhotonPacketParser().parse(data, eventRouter)
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
            .setContentTitle("Albion Radar")
            .setContentText("Radar is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // EventRouter.PlayerListener
    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
        playerListener?.onPlayerJoined(id, name, guild, posX, posY, faction)
    }
    override fun onPlayerLeft(id: Long) {
        playerListener?.onPlayerLeft(id)
    }
    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        playerListener?.onPlayerMoved(id, posX, posY)
    }
    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {
        playerListener?.onPlayerHealthChanged(id, currentHp, maxHp)
    }

    companion object {
        const val TAG = "AlbionVpnService"
        const val NOTIF_ID = 1001
    }
}
