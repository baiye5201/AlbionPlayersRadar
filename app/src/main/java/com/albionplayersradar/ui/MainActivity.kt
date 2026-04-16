package com.albionplayersradar.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity(), PhotonPacketParser.PlayerListener {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false
    private var radarView: RadarView? = null
    private var vpnButton: Button? = null
    private var clearButton: Button? = null
    private var statusText: TextView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AlbionVpnService.LocalBinder
            vpnService = binder?.getService()
            vpnBound = true
            vpnService?.setPlayerListener(this@MainActivity)
            updateVpnButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        vpnButton = findViewById(R.id.btn_toggle)
        clearButton = findViewById(R.id.btn_clear)
        radarView = findViewById(R.id.radar_view)

        vpnButton?.setOnClickListener { onToggleVpn() }
        clearButton?.setOnClickListener { radarView?.clearPlayers() }

        updateVpnButton()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AlbionVpnService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (vpnBound) {
            vpnService?.setPlayerListener(null)
            unbindService(serviceConnection)
            vpnBound = false
        }
    }

    private fun onToggleVpn() {
        if (vpnService != null && vpnService?.isRunning == true) {
            stopVpn()
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        statusText?.text = "Starting VPN..."
        vpnButton?.isEnabled = false

        val intent = Intent(this, AlbionVpnService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopVpn() {
        if (vpnBound) {
            vpnService?.stopVpn()
            vpnService?.setPlayerListener(null)
            unbindService(serviceConnection)
            vpnBound = false
        }
        vpnService = null
        updateVpnButton()
        statusText?.text = "VPN Stopped"
    }

    private fun updateVpnButton() {
        val running = vpnService?.isRunning == true
        vpnButton?.text = if (running) "Disconnect" else "Connect VPN"
        vpnButton?.isEnabled = true
        statusText?.text = if (running) "Tracking Players" else "Tap Connect to start"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Albion Radar",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows radar status"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // PhotonPacketParser.PlayerListener
    override fun onPlayerFound(player: PhotonPacketParser.PlayerInfo) {
        runOnUiThread {
            radarView?.addPlayer(player)
            updateCount()
        }
    }

    override fun onPlayerLeft(playerId: Int) {
        runOnUiThread {
            radarView?.removePlayer(playerId)
            updateCount()
        }
    }

    override fun onPlayerMoved(player: PhotonPacketParser.PlayerInfo) {
        runOnUiThread {
            radarView?.updatePlayer(player)
        }
    }

    override fun onPlayerHealthChanged(playerId: Int, health: Float, maxHealth: Float) {
        runOnUiThread {
            radarView?.updatePlayerHealth(playerId, health, maxHealth)
        }
    }

    private fun updateCount() {
        val count = radarView?.getPlayerCount() ?: 0
        statusText?.text = "Players: $count"
    }

    companion object {
        private const val CHANNEL_ID = "albion_radar_channel"
    }
}

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val players = mutableMapOf<Int, PhotonPacketParser.PlayerInfo>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var centerX = 0f
    private var centerY = 0f
    private var scale = 50f
    private var localX = 0f
    private var localY = 0f

    private val hostileColor = Color.parseColor("#FF4444")
    private val alliedColor = Color.parseColor("#44FF44")
    private val neutralColor = Color.parseColor("#FFFF44")
    private val localColor = Color.parseColor("#4488FF")

    init {
        localPaint.color = localColor
        localPaint.style = Paint.Style.STROKE
        localPaint.strokeWidth = 3f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw range rings
        paint.color = Color.parseColor("#333333")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f

        for (r in listOf(50f, 100f, 150f)) {
            canvas.drawCircle(centerX, centerY, r * scale / 50f, paint)
        }

        // Draw local player
        canvas.drawCircle(centerX, centerY, 8f, localPaint)

        // Draw players
        for (player in players.values) {
            val dx = (player.posX - localX) * scale / 50f
            val dy = (player.posY - localY) * scale / 50f

            if (dx * dx + dy * dy > 4000000f) continue

            val sx = centerX + dx
            val sy = centerY - dy

            val color = when {
                player.faction == 255 -> hostileColor
                player.faction in 1..6 -> neutralColor
                else -> alliedColor
            }

            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(sx, sy, 6f, paint)

            paint.color = Color.WHITE
            paint.textSize = 10f
            paint.style = Paint.Style.FILL
            canvas.drawText(player.name.take(8), sx + 10f, sy - 5f, paint)

            if (player.guild != null) {
                paint.textSize = 8f
                paint.color = Color.LTGRAY
                canvas.drawText(player.guild.take(8), sx + 10f, sy + 8f, paint)
            }
        }
    }

    fun addPlayer(player: PhotonPacketParser.PlayerInfo) {
        players[player.id] = player
        if (players.size == 1) {
            localX = player.posX
            localY = player.posY
        }
        invalidate()
    }

    fun removePlayer(id: Int) {
        players.remove(id)
        invalidate()
    }

    fun updatePlayer(player: PhotonPacketParser.PlayerInfo) {
        players[player.id] = player
        if (player.equipment != null) {
            localX = player.posX
            localY = player.posY
        }
        invalidate()
    }

    fun updatePlayerHealth(id: Int, health: Float, maxHealth: Float) {
        players[id]?.let { p ->
            players[id] = p.copy(health = health, maxHealth = maxHealth)
            invalidate()
        }
    }

    fun clearPlayers() {
        players.clear()
        invalidate()
    }

    fun getPlayerCount() = players.size
}
