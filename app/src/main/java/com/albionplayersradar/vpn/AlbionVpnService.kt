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
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

class AlbionVpnService : Service(), PhotonPacketParser.PlayerListener {

    private val binder = LocalBinder()
    private var socket: DatagramSocket? = null
    private var running = false
    private var readerThread: Thread? = null
    private var playerListener: PhotonPacketParser.PlayerListener? = null
    private val parser = PhotonPacketParser()

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onCreate() {
        super.onCreate()
        parser.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        running = true
        startCapture()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        running = false
        readerThread?.interrupt()
        socket?.close()
        socket = null
        super.onDestroy()
    }

    fun stopVpn() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setPlayerListener(listener: PhotonPacketParser.PlayerListener?) {
        playerListener = listener
    }

    private fun startCapture() {
        readerThread = Thread {
            try {
                val vpnInterface = VpnService.Builder()
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)
                    .establish()

                socket = DatagramSocket(5056)
                socket?.soTimeout = 1000

                val buffer = ByteArray(2048)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buffer, buffer.size)
                        socket?.receive(pkt)
                        val data = pkt.data.copyOf(pkt.length)
                        parseAndForward(data)
                    } catch (e: Exception) {
                        if (running) continue
                    }
                }
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
            }
        }.apply { start() }
    }

    private fun parseAndForward(data: ByteArray) {
        try {
            val result = PhotonDeserializer.parsePacket(data)
            for (event in result.events) {
                parser.handleEvent(event)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parse error: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "radar_channel")
            .setContentTitle("Albion Radar")
            .setContentText("Tracking players in background")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // PhotonPacketParser.PlayerListener
    override fun onPlayerFound(player: PhotonPacketParser.PlayerInfo) {
        playerListener?.onPlayerFound(player)
    }

    override fun onPlayerLeft(playerId: Int) {
        playerListener?.onPlayerLeft(playerId)
    }

    override fun onPlayerMoved(player: PhotonPacketParser.PlayerInfo) {
        playerListener?.onPlayerMoved(player)
    }

    override fun onPlayerHealthChanged(playerId: Int, health: Float, maxHealth: Float) {
        playerListener?.onPlayerHealthChanged(playerId, health, maxHealth)
    }

    companion object {
        private const val TAG = "AlbionVpnService"
        private const val NOTIF_ID = 1001
    }
}
