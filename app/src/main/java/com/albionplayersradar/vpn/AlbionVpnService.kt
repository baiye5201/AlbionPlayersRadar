package com.albionplayersradar.vpn

import android.app.Notification
import android.app.PendingIntent
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
import java.nio.ByteBuffer

class AlbionVpnService : VpnService(), EventRouter.PlayerListener {

    private val binder = LocalBinder()
    @Volatile private var running = false
    private var tunnelFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.setPlayerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startVpnTunnel()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainApplication.CHANNEL_ID)
            .setContentTitle("Albion Players Radar")
            .setContentText("VPN active — monitoring port 5056")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startVpnTunnel() {
        if (running) return
        try {
            val builder = Builder()
            builder.setSession("AlbionPlayersRadar")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication("com.albionplayersradar")
                .setMtu(1500)

            tunnelFd = builder.establish() ?: run {
                Log.e(TAG, "establish() returned null")
                stopSelf()
                return
            }

            running = true

            // Read loop — intercept outgoing packets from TUN
            Thread({
                val buf = ByteArray(4096)
                val fis = FileInputStream(tunnelFd!!.fileDescriptor)
                val fos = FileOutputStream(tunnelFd!!.fileDescriptor)
                while (running) {
                    try {
                        val len = fis.read(buf)
                        if (len > 0) {
                            val pkt = buf.copyOf(len)
                            handlePacket(pkt, fos)
                        }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "TUN read: ${e.message}")
                        break
                    }
                }
            }, "tun-read").start()

        } catch (e: Exception) {
            Log.e(TAG, "startVpnTunnel failed: ${e.message}")
            stopSelf()
        }
    }

    private fun handlePacket(data: ByteArray, fos: FileOutputStream) {
        if (data.size < 20) {
            fos.write(data)
            return
        }
        val ihl = (data[0].toInt() and 0x0F) * 4
        val proto = data[9].toInt() and 0xFF

        if (proto == 17 /* UDP */ && data.size > ihl + 8) {
            val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
            val srcPort = ((data[ihl].toInt() and 0xFF) shl 8) or (data[ihl + 1].toInt() and 0xFF)
            val payloadOff = ihl + 8
            val payloadLen = data.size - payloadOff

            if ((dstPort == ALBION_PORT || srcPort == ALBION_PORT) && payloadLen >= 12) {
                val payload = data.copyOfRange(payloadOff, payloadOff + payloadLen)
                // Feed to parser (non-blocking)
                try {
                    EventRouter.onUdpPacketReceived(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "parser: ${e.message}")
                }
            }

            // Forward packet via protected socket
            try {
                if (udpSocket == null || udpSocket!!.isClosed) {
                    udpSocket = DatagramSocket()
                    protect(udpSocket!!)
                }
                val dstIp = InetAddress.getByAddress(
                    byteArrayOf(data[16], data[17], data[18], data[19])
                )
                udpSocket!!.send(DatagramPacket(data.copyOfRange(ihl + 8, data.size), payloadLen, dstIp, dstPort))
            } catch (e: Exception) {
                Log.e(TAG, "forward: ${e.message}")
                fos.write(data) // fallback: write back to TUN
            }
        } else {
            // Non-UDP or non-Albion — pass through
            fos.write(data)
        }
    }

    private fun stopTunnel() {
        running = false
        try { tunnelFd?.close() } catch (e: Exception) { Log.e(TAG, "close tun: ${e.message}") }
        try { udpSocket?.close() } catch (e: Exception) { Log.e(TAG, "close udp: ${e.message}") }
        tunnelFd = null
        udpSocket = null
    }

    override fun onRevoke() {
        super.onRevoke()
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        EventRouter.setPlayerListener(null)
    }
